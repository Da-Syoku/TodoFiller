package dev.togar.dynasched.api

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.togar.dynasched.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * サーバーとのやりとりと、バックグラウンド実行の入れ物。
 *
 * **アプリは端末内で完結している。** 予定・教材・タスクはすべて端末のSQLiteと
 * カレンダーにあり、サーバーは要らない。ここに残っているサーバー通信は
 * **アプリの更新の確認とエラー報告だけ**で、通信できなくても本体は普通に動く。
 *
 * `async` はHTTP専用ではなく、DBやカレンダーの読み書きを画面から追い出すための
 * 共通のワーカーとして全画面で使っている。
 *
 * 外部ライブラリ不使用。Android標準の HttpURLConnection と org.json だけ。
 */
object Api {
    private val base = BuildConfig.API_BASE_URL.trimEnd('/')

    /**
     * 単一スレッドだと、スケジューラ実行の間に他の画面の処理が全部詰まる。
     * 数本のプールにして、遅い呼び出しが他をブロックしないようにする。
     */
    private val io = Executors.newFixedThreadPool(3) { r ->
        Thread(r, "skimas-worker").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** バックグラウンドで処理し、結果/失敗をメインスレッドへ返す簡易ヘルパー */
    fun <T> async(
        work: () -> T,
        onSuccess: (T) -> Unit,
        onError: (Exception) -> Unit
    ) {
        io.execute {
            try {
                val result = work()
                mainHandler.post { onSuccess(result) }
            } catch (e: Exception) {
                appContext?.let { reportError(it, "worker", e) }
                mainHandler.post { onError(e) }
            }
        }
    }

    /**
     * 例外を利用者に見せられる日本語にする。
     * 生の `failed to connect to ... (port 443)` を出さないため。
     */
    fun friendlyMessage(e: Exception): String = when {
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
            post("/app/error", json, timeoutMs = 10000)
        } catch (ignore: Exception) {
            // 報告できなくても本処理には影響させない
        }
    }

    // ---- 低レベルHTTP ----

    private fun open(method: String, path: String, timeoutMs: Int = 15000): HttpURLConnection {
        val conn = URL(base + path).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept", "application/json")
        return conn
    }

    private fun readBody(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        if (stream == null) return ""
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            return reader.readText()
        }
    }

    private fun get(path: String): String {
        val conn = open("GET", path)
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

    private fun post(path: String, json: JSONObject?, timeoutMs: Int = 15000): String {
        val conn = open("POST", path, timeoutMs)
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

    // ---- 更新の確認 ----

    /**
     * 最新版アプリの情報を取得 (GET /app/version)。
     * サーバーは {version_code, version_name, url, notes} を返す。
     * url は最新APKのダウンロード先。
     */
    fun getAppVersion(ctx: Context): AppVersionInfo {
        val body = get("/app/version")
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
