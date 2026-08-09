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

    /** タスク完了 (POST /schedule/:id/complete) ※バックエンドにPOSTエイリアスを追加済み */
    fun completeTask(ctx: Context, id: Long) {
        post(ctx, "/schedule/$id/complete", null)
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

    // ---- 定期タスク (timetable) ----

    /** 定期スロット一覧 (GET /timetable) */
    fun getTimetable(ctx: Context): List<TimetableSlot> {
        val body = get(ctx, "/timetable")
        val arr = JSONArray(body)
        val list = ArrayList<TimetableSlot>(arr.length())
        for (i in 0 until arr.length()) list.add(TimetableSlot.from(arr.getJSONObject(i)))
        return list
    }

    /** 定期スロット追加 (POST /timetable)。dayOfWeek: 1=月..7=日, 時刻は "HH:MM" */
    fun addTimetableSlot(
        ctx: Context, dayOfWeek: Int, startTime: String, endTime: String,
        label: String, slotType: String, location: String = "home"
    ) {
        val json = JSONObject()
            .put("day_of_week", dayOfWeek)
            .put("start_time", startTime)
            .put("end_time", endTime)
            .put("label", label)
            .put("slot_type", slotType)
            .put("location", location)
        post(ctx, "/timetable", json)
    }

    /** 定期スロット更新 (PUT /timetable/:id)。既存の予定を書き直す。 */
    fun updateTimetableSlot(
        ctx: Context, id: Long, dayOfWeek: Int, startTime: String, endTime: String,
        label: String, slotType: String, location: String
    ) {
        val json = JSONObject()
            .put("day_of_week", dayOfWeek)
            .put("start_time", startTime)
            .put("end_time", endTime)
            .put("label", label)
            .put("slot_type", slotType)
            .put("location", location)
        put(ctx, "/timetable/$id", json)
    }

    /** 定期スロット削除 (DELETE /timetable/:id) */
    fun deleteTimetableSlot(ctx: Context, id: Long) {
        delete(ctx, "/timetable/$id")
    }

    /**
     * 週間ルーチンをGoogleカレンダーへ「毎週繰り返し予定」として書き出す
     * (POST /routine/sync)。Google通信を含むため長めのタイムアウト。
     */
    fun syncRoutineCalendar(ctx: Context) {
        post(ctx, "/routine/sync", JSONObject(), timeoutMs = 90000)
    }

    // ---- 目標タスク (goals) ----

    /** 目標一覧 (GET /goals) 期日昇順 */
    fun getGoals(ctx: Context): List<GoalItem> {
        val body = get(ctx, "/goals")
        val arr = JSONArray(body)
        val list = ArrayList<GoalItem>(arr.length())
        for (i in 0 until arr.length()) list.add(GoalItem.from(arr.getJSONObject(i)))
        return list
    }

    /**
     * 目標追加 (POST /goals)。deadline は "yyyy-MM-ddTHH:mm:ss"。
     * studyType/difficulty/understanding/progress/color/memo は省略時サーバー既定。
     */
    fun addGoal(
        ctx: Context, name: String, deadline: String, totalMinutes: Int, priority: Int,
        studyType: String = GoalItem.TYPE_EXERCISE, difficulty: Int = 3, understanding: Int = 3,
        progress: Int = 0, color: String = "", memo: String = "", sessionMinutes: Int = 50,
        progressNote: String = "", isExam: Boolean = false
    ) {
        val json = JSONObject()
            .put("name", name)
            .put("deadline", deadline)
            .put("total_minutes", totalMinutes)
            .put("priority", priority)
            .put("study_type", studyType)
            .put("difficulty", difficulty)
            .put("understanding", understanding)
            .put("progress", progress)
            .put("memo", memo)
            .put("session_minutes", sessionMinutes)
            .put("progress_note", progressNote)
            .put("is_exam", if (isExam) 1 else 0)
        if (color.isNotEmpty()) json.put("color", color)
        post(ctx, "/goals", json)
    }

    /**
     * 目標を編集 (POST /goal/:id/edit)。渡した項目だけ更新（サーバーは列存在チェック付き動的UPDATE）。
     */
    fun editGoal(
        ctx: Context, id: Long, name: String, deadline: String, totalMinutes: Int, priority: Int,
        studyType: String, difficulty: Int, understanding: Int, progress: Int, color: String, memo: String,
        sessionMinutes: Int = 50, progressNote: String = "", isExam: Boolean = false
    ) {
        val json = JSONObject()
            .put("name", name)
            .put("deadline", deadline)
            .put("total_minutes", totalMinutes)
            .put("priority", priority)
            .put("study_type", studyType)
            .put("difficulty", difficulty)
            .put("understanding", understanding)
            .put("progress", progress)
            .put("color", color)
            .put("memo", memo)
            .put("session_minutes", sessionMinutes)
            .put("progress_note", progressNote)
            .put("is_exam", if (isExam) 1 else 0)
        post(ctx, "/goal/$id/edit", json)
    }

    /** 目標削除 (DELETE /goals/:id) */
    fun deleteGoal(ctx: Context, id: Long) {
        delete(ctx, "/goals/$id")
    }

    /** 進捗の相対加算・理解度の記録 (POST /goal/:id/bump)。通知アクションから使う。 */
    fun bumpGoal(ctx: Context, id: Long, progressDelta: Int = 0, understanding: Int = 0) {
        val json = JSONObject()
        if (progressDelta != 0) json.put("progress_delta", progressDelta)
        if (understanding in 1..5) json.put("understanding", understanding)
        post(ctx, "/goal/$id/bump", json)
    }

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
