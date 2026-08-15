package dev.togar.dynasched.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.togar.dynasched.BuildConfig
import dev.togar.dynasched.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * 外部ライブラリ不使用。Android標準の HttpURLConnection と org.json だけで
 * バックエンド(api.togar.dev)と通信する。
 * PATCH は HttpURLConnection が苦手なので使わず、GET / POST のみで完結させる。
 */
object Api {
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')

    /**
     * 単一スレッドだと、スケジューラ実行(最大90秒)の間に他の画面の通信が全部詰まる。
     * 数本のプールにして、遅い呼び出しが他をブロックしないようにする。
     */
    private val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "dynasched-api").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * トークン失効(401)を検知した時に呼ばれる。MainActivity がログイン画面への
     * 誘導を仕掛ける。メインスレッドで、1回の失効につき1度だけ呼ぶ。
     */
    @Volatile var onUnauthorized: (() -> Unit)? = null
    @Volatile private var unauthorizedNotified = false

    /** バックグラウンドで処理し、結果/失敗をメインスレッドへ返す簡易ヘルパー */
    fun <T> async(
        work: () -> T,
        onSuccess: (T) -> Unit,
        onError: (Exception) -> Unit
    ) {
        io.execute {
            try {
                val result = work()
                unauthorizedNotified = false
                mainHandler.post { onSuccess(result) }
            } catch (e: Exception) {
                if (e is ApiException && e.code == 401) {
                    // 失効は想定内なのでサーバーへは報告しない（ログが埋まるだけ）
                    if (!unauthorizedNotified) {
                        unauthorizedNotified = true
                        mainHandler.post { onUnauthorized?.invoke() }
                    }
                } else {
                    appContext?.let { reportError(it, "api", e) }
                }
                mainHandler.post { onError(e) }
            }
        }
    }

    /**
     * 例外を利用者に見せられる日本語にする。
     * 生の `failed to connect to api.togar.dev/... (port 443)` を出さないため。
     */
    fun friendlyMessage(e: Exception): String = when {
        e is ApiException && e.code == 401 -> "ログインの有効期限が切れました"
        e is ApiException && e.code == 404 -> "対象が見つかりませんでした"
        e is ApiException && e.code in 500..599 -> "サーバーが応答できない状態です"
        e is ApiException -> "サーバーエラー (${e.code})"
        e is java.net.SocketTimeoutException -> "通信がタイムアウトしました"
        e is java.net.UnknownHostException -> "ネットワークに接続されていません"
        e is java.net.ConnectException -> "サーバーに接続できません"
        e is java.io.IOException -> "通信に失敗しました"
        else -> e.message ?: "不明なエラー"
    }

    // ---- エラー報告 ----

    /** reportError用にApplication Contextを覚えておく（App.onCreateでセット） */
    @Volatile var appContext: Context? = null

    /**
     * エラーをサーバーへ送信 (POST /app/error)。失敗しても無視（報告のせいで壊さない）。
     * 呼び出し元スレッドから直接HTTPするので、必ずワーカースレッドから呼ぶこと。
     */
    fun reportError(ctx: Context, where: String, e: Throwable) {
        try {
            val json = JSONObject()
                .put("version", "${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
                .put("where", where)
                .put("message", (e.message ?: e.toString()).take(500))
                .put("stack", android.util.Log.getStackTraceString(e).take(4000))
                .put("device", "${android.os.Build.MODEL} / Android ${android.os.Build.VERSION.RELEASE}")
            post(ctx, "/app/error", json, timeoutMs = 10000)
        } catch (ignore: Exception) {
            // 報告できなくても本処理には影響させない
        }
    }

    // ---- 低レベルHTTP ----

    private fun open(ctx: Context, method: String, path: String, timeoutMs: Int = 15000): HttpURLConnection {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/json")
        Prefs.token(ctx)?.let { token ->
            if (token.isNotEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }
        }
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            return reader.readText()
        }
    }

    private fun get(ctx: Context, path: String): String {
        val conn = open(ctx, "GET", path)
        try {
            val body = readBody(conn)
            if (conn.responseCode !in 200..299) {
                throw ApiException(conn.responseCode, body.ifEmpty { "HTTP ${conn.responseCode}" })
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun post(ctx: Context, path: String, json: JSONObject?, timeoutMs: Int = 15000): String {
        val conn = open(ctx, "POST", path, timeoutMs)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w ->
                w.write(json?.toString() ?: "{}")
            }
            val body = readBody(conn)
            if (conn.responseCode !in 200..299) {
                throw ApiException(conn.responseCode, body.ifEmpty { "HTTP ${conn.responseCode}" })
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun put(ctx: Context, path: String, json: JSONObject?): String {
        val conn = open(ctx, "PUT", path)
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { w ->
                w.write(json?.toString() ?: "{}")
            }
            val body = readBody(conn)
            if (conn.responseCode !in 200..299) {
                throw ApiException(conn.responseCode, body.ifEmpty { "HTTP ${conn.responseCode}" })
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun delete(ctx: Context, path: String): String {
        val conn = open(ctx, "DELETE", path)
        try {
            val body = readBody(conn)
            if (conn.responseCode !in 200..299) {
                throw ApiException(conn.responseCode, body.ifEmpty { "HTTP ${conn.responseCode}" })
            }
            return body
        } finally {
            conn.disconnect()
        }
    }

    // ---- 各エンドポイント ----

    /** Googleログイン用のURLを取得 (GET /auth/google -> {url}) */
    fun getAuthUrl(ctx: Context): String {
        val body = get(ctx, "/auth/google")
        return JSONObject(body).optString("url", "")
    }

    /** 指定日から7日分の予定を取得 (GET /schedule?date=yyyy-MM-dd) */
    fun getSchedule(ctx: Context, dateYmd: String): List<ScheduledEvent> {
        val q = URLEncoder.encode(dateYmd, "UTF-8")
        val body = get(ctx, "/schedule?date=$q")
        val arr = JSONArray(body)
        val list = ArrayList<ScheduledEvent>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(ScheduledEvent.from(arr.getJSONObject(i)))
        }
        return list
    }

    /**
     * タスク完了 (POST /schedule/:id/complete)。
     * 教材の予定なら problems を渡すと実績として記録される。
     * -1 のままなら、サーバーがコマの長さと実測ペースから推定して入れる
     * （聞かずに済ませたいときの逃げ道。何も入らないより推定でも入るほうがいい）。
     */
    fun completeTask(ctx: Context, id: Long, problems: Int = -1) {
        val json = JSONObject()
        if (problems >= 0) json.put("problems", problems)
        post(ctx, "/schedule/$id/complete", json)
    }

    /**
     * できなかった (POST /schedule/:id/skip {reschedule:1})。
     * イベントを消し、サーバー側がバックグラウンドで現在時刻以降に再配置する。
     */
    fun skipTask(ctx: Context, id: Long) {
        post(ctx, "/schedule/$id/skip", JSONObject().put("reschedule", 1))
    }

    /** スケジューラ再実行 (POST /scheduler/run {days})。Googleカレンダー書き込みを含むため長めのタイムアウト。 */
    fun runScheduler(ctx: Context, days: Int) {
        post(ctx, "/scheduler/run", JSONObject().put("days", days), timeoutMs = 90000)
    }

    // ---- 単発タスク (hobby) ----

    /** 単発タスク一覧 (GET /hobby) */
    fun getHobby(ctx: Context): List<HobbyItem> {
        val body = get(ctx, "/hobby")
        val arr = JSONArray(body)
        val list = ArrayList<HobbyItem>(arr.length())
        for (i in 0 until arr.length()) list.add(HobbyItem.from(arr.getJSONObject(i)))
        return list
    }

    /** 追加 (POST /hobby)。parentId を渡すと子タスク。note は詳細メモ、color はカレンダー色。 */
    fun addHobby(
        ctx: Context, name: String, parentId: Long?,
        durationMinutes: Int = 30, priority: Int = 5, location: String = "anywhere",
        note: String = "", color: String = ""
    ) {
        val json = JSONObject()
            .put("name", name)
            .put("duration_minutes", durationMinutes)
            .put("priority", priority)
            .put("location", location)
            .put("note", note)
            .put("color", color)
        if (parentId != null) json.put("parent_id", parentId)
        // note/color対応の作成エンドポイント（既存/hobbyルーターは変更せず新規に追加）
        post(ctx, "/hobby/create", json)
    }

    /** 単発タスクの内容を編集 (POST /hobby/:id/edit)。渡した項目だけ更新。 */
    fun editHobby(
        ctx: Context, id: Long, name: String,
        durationMinutes: Int, priority: Int, location: String, note: String, color: String
    ) {
        val json = JSONObject()
            .put("name", name)
            .put("duration_minutes", durationMinutes)
            .put("priority", priority)
            .put("location", location)
            .put("note", note)
            .put("color", color)
        post(ctx, "/hobby/$id/edit", json)
    }

    /** 完了 (POST /hobby/:id/complete) */
    fun completeHobby(ctx: Context, id: Long) {
        post(ctx, "/hobby/$id/complete", null)
    }

    /** 完了状態を設定 (PUT /hobby/:id) */
    fun setHobbyCompleted(ctx: Context, id: Long, completed: Boolean) {
        put(ctx, "/hobby/$id", JSONObject().put("is_completed", if (completed) 1 else 0))
    }

    /** 削除 (DELETE /hobby/:id) 子タスクも連鎖削除 */
    fun deleteHobby(ctx: Context, id: Long) {
        delete(ctx, "/hobby/$id")
    }

    // ---- 教材 (materials) ----

    /** 教材一覧 (GET /materials) 期日昇順。計算済みの周・残り・実測ペース付き。 */
    fun getMaterials(ctx: Context): List<MaterialItem> {
        val body = get(ctx, "/materials")
        val arr = JSONArray(body)
        val list = ArrayList<MaterialItem>(arr.length())
        for (i in 0 until arr.length()) list.add(MaterialItem.from(arr.getJSONObject(i)))
        return list
    }

    /**
     * 教材追加 (POST /materials)。deadline は "yyyy-MM-ddTHH:mm:ss"。
     * 総時間・進捗%・難易度・理解度は**存在しない**（答えられない質問なので聞かない）。
     */
    fun addMaterial(
        ctx: Context, subject: String, name: String, totalProblems: Int, advancedRanges: String,
        targetRounds: Int, deadline: String, firstRoundDeadline: String, prereqMaterialId: Long?,
        studyType: String, needs: String, sessionMinutes: Int, priority: Int,
        color: String, memo: String, isExam: Boolean
    ) {
        post(ctx, "/materials", materialBody(
            subject, name, totalProblems, advancedRanges, targetRounds, deadline,
            firstRoundDeadline, prereqMaterialId, studyType, needs, sessionMinutes,
            priority, color, memo, isExam
        ))
    }

    /** 教材を編集 (POST /material/:id/edit)。渡した項目だけ更新。 */
    fun editMaterial(
        ctx: Context, id: Long, subject: String, name: String, totalProblems: Int, advancedRanges: String,
        targetRounds: Int, deadline: String, firstRoundDeadline: String, prereqMaterialId: Long?,
        studyType: String, needs: String, sessionMinutes: Int, priority: Int,
        color: String, memo: String, isExam: Boolean
    ) {
        post(ctx, "/material/$id/edit", materialBody(
            subject, name, totalProblems, advancedRanges, targetRounds, deadline,
            firstRoundDeadline, prereqMaterialId, studyType, needs, sessionMinutes,
            priority, color, memo, isExam
        ))
    }

    private fun materialBody(
        subject: String, name: String, totalProblems: Int, advancedRanges: String,
        targetRounds: Int, deadline: String, firstRoundDeadline: String, prereqMaterialId: Long?,
        studyType: String, needs: String, sessionMinutes: Int, priority: Int,
        color: String, memo: String, isExam: Boolean
    ): JSONObject {
        val json = JSONObject()
            .put("subject", subject)
            .put("name", name)
            .put("total_problems", totalProblems)
            .put("advanced_ranges", advancedRanges)
            .put("target_rounds", targetRounds)
            .put("deadline", deadline)
            .put("study_type", studyType)
            .put("needs", needs)
            .put("session_minutes", sessionMinutes)
            .put("priority", priority)
            .put("memo", memo)
            .put("is_exam", if (isExam) 1 else 0)
        if (firstRoundDeadline.isNotEmpty()) json.put("first_round_deadline", firstRoundDeadline)
        if (prereqMaterialId != null) json.put("prereq_material_id", prereqMaterialId)
        if (color.isNotEmpty()) json.put("color", color)
        return json
    }

    /** 教材削除 (DELETE /materials/:id) */
    fun deleteMaterial(ctx: Context, id: Long) {
        delete(ctx, "/materials/$id")
    }

    /**
     * 実績の記録 (POST /material/:id/attempt)。**唯一の進捗入力**。
     * 所要時間はここから測るので、ユーザーには聞かない。
     */
    fun recordAttempt(ctx: Context, id: Long, problems: Int, minutes: Int, eventId: Long? = null) {
        val json = JSONObject()
            .put("problems", problems)
            .put("minutes", minutes)
        if (eventId != null && eventId > 0) json.put("event_id", eventId)
        post(ctx, "/material/$id/attempt", json)
    }

    /** 直前の実績を取り消す (POST /material/:id/undo) */
    fun undoAttempt(ctx: Context, id: Long) {
        post(ctx, "/material/$id/undo", JSONObject())
    }

    /** 「間に合うのか」 (GET /plan) */
    fun getPlan(ctx: Context, days: Int = 45): PlanResult =
        PlanResult.from(JSONObject(get(ctx, "/plan?days=$days")))

    // ---- タスク提案 (widget) ----

    /** 空き時間に合うタスク候補 (GET /suggest?loc=&min=) */
    fun getSuggestions(ctx: Context, loc: String, min: Int, limit: Int = 3): List<SuggestItem> {
        val body = get(ctx, "/suggest?loc=$loc&min=$min&limit=$limit")
        val arr = JSONObject(body).optJSONArray("items") ?: JSONArray()
        val list = ArrayList<SuggestItem>(arr.length())
        for (i in 0 until arr.length()) list.add(SuggestItem.from(arr.getJSONObject(i)))
        return list
    }

    // ---- アプリ更新 (OTA) ----

    /**
     * 最新版アプリの情報を取得 (GET /app/version)。
     * サーバーは {version_code, version_name, url, notes} を返す。
     * url は最新APKのダウンロード先。
     */
    fun getAppVersion(ctx: Context): AppVersionInfo {
        val body = get(ctx, "/app/version")
        val o = JSONObject(body)
        return AppVersionInfo(
            versionCode = o.optInt("version_code", 0),
            versionName = o.optString("version_name", ""),
            url = o.optString("url", ""),
            notes = o.optString("notes", "")
        )
    }
}

/** サーバーが返す最新版アプリの情報 */
data class AppVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val notes: String
)
