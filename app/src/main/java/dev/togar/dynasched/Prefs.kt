package dev.togar.dynasched

import android.content.Context

/**
 * 認証トークンやユーザー情報を端末に保存する薄いラッパー。
 * 外部ライブラリは使わず標準の SharedPreferences のみ。
 */
object Prefs {
    private const val FILE = "dynasched_prefs"
    private const val KEY_TOKEN = "jwt"
    private const val KEY_EMAIL = "email"
    private const val KEY_NAME = "name"
    private const val KEY_EVENTS = "cached_events" // 再起動時の通知再登録に使う
    private const val KEY_SCHED_CACHE = "schedule_cache"     // 今日の予定のオフライン表示用
    private const val KEY_SCHED_CACHE_DAY = "schedule_cache_day"
    private const val KEY_FILL_DAYS = "fill_days"  // 何日先まで空き時間を埋めるか
    const val DEFAULT_FILL_DAYS = 7

    private fun sp(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var tokenCache: String? = null

    fun saveAuth(ctx: Context, token: String, email: String?, name: String?) {
        sp(ctx).edit()
            .putString(KEY_TOKEN, token)
            .putString(KEY_EMAIL, email ?: "")
            .putString(KEY_NAME, name ?: "")
            .apply()
        tokenCache = token
    }

    fun token(ctx: Context): String? =
        sp(ctx).getString(KEY_TOKEN, null)

    fun email(ctx: Context): String =
        sp(ctx).getString(KEY_EMAIL, "") ?: ""

    fun name(ctx: Context): String =
        sp(ctx).getString(KEY_NAME, "") ?: ""

    fun isLoggedIn(ctx: Context): Boolean =
        !token(ctx).isNullOrEmpty()

    fun logout(ctx: Context) {
        sp(ctx).edit().clear().apply()
        tokenCache = null
    }

    fun saveCachedEvents(ctx: Context, json: String) {
        sp(ctx).edit().putString(KEY_EVENTS, json).apply()
    }

    fun cachedEvents(ctx: Context): String? =
        sp(ctx).getString(KEY_EVENTS, null)

    /**
     * 「今日の予定」画面のオフライン表示用スナップショット。
     * 日付を一緒に持ち、日付が変わったキャッシュは使わない（前日の予定を今日として出さないため）。
     */
    fun saveScheduleCache(ctx: Context, ymd: String, json: String) {
        sp(ctx).edit()
            .putString(KEY_SCHED_CACHE, json)
            .putString(KEY_SCHED_CACHE_DAY, ymd)
            .apply()
    }

    /** 指定日のキャッシュ。日付が違えば null */
    fun scheduleCache(ctx: Context, ymd: String): String? {
        val p = sp(ctx)
        if (p.getString(KEY_SCHED_CACHE_DAY, null) != ymd) return null
        return p.getString(KEY_SCHED_CACHE, null)
    }

    /** 何日先まで空き時間を自動充填するか（1〜30、既定7） */
    fun fillDays(ctx: Context): Int =
        sp(ctx).getInt(KEY_FILL_DAYS, DEFAULT_FILL_DAYS).coerceIn(1, 30)

    fun setFillDays(ctx: Context, days: Int) {
        sp(ctx).edit().putInt(KEY_FILL_DAYS, days.coerceIn(1, 30)).apply()
    }

    /** ウィジェットの現在地設定（"home" | "out"） */
    fun widgetLoc(ctx: Context): String =
        sp(ctx).getString("widget_loc", "home") ?: "home"

    fun setWidgetLoc(ctx: Context, loc: String) {
        sp(ctx).edit().putString("widget_loc", if (loc == "out") "out" else "home").apply()
    }

    /**
     * 読み書きする端末カレンダーのID。未設定(-1)なら主カレンダーを自動で選ぶ。
     * 仕事用など複数アカウントがある端末で、意図しない方を読まないための逃げ道。
     */
    fun calendarId(ctx: Context): Long? =
        sp(ctx).getLong("calendar_id", -1L).let { if (it >= 0) it else null }

    fun setCalendarId(ctx: Context, id: Long) {
        sp(ctx).edit().putLong("calendar_id", id).apply()
    }

    /**
     * 端末内完結で動くか（true）、これまで通りサーバーに問い合わせるか（false）。
     *
     * 移植で何か壊れた時に戻れるよう、両方の方式を残して設定で切り替える。
     * 既定はサーバー方式のまま。取り込みを済ませてから自分で切り替える。
     */
    fun localMode(ctx: Context): Boolean = sp(ctx).getBoolean("local_mode", false)

    fun setLocalMode(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("local_mode", on).apply()
    }

    /** サーバーからの初回取り込みが済んでいるか（二重取り込みを防ぐ） */
    fun imported(ctx: Context): Boolean = sp(ctx).getBoolean("imported", false)

    fun setImported(ctx: Context, done: Boolean) {
        sp(ctx).edit().putBoolean("imported", done).apply()
    }
}
