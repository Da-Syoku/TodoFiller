package dev.togar.dynasched.api

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * バックエンドの scheduled_events 1件分。
 * start_datetime / end_datetime は "yyyy-MM-dd'T'HH:mm:ss" 形式の文字列。
 */
data class ScheduledEvent(
    val id: Long,
    val title: String,
    val startDatetime: String,
    val endDatetime: String,
    val eventType: String,
    val isCompleted: Boolean,
    val goalId: Long? = null
) {
    /** 開始時刻を Date に変換（失敗したら null） */
    fun startAsDate(): Date? = parse(startDatetime)

    /** 終了時刻を Date に変換（失敗したら null） */
    fun endAsDate(): Date? = parse(endDatetime)

    /** 例: "09:30" */
    fun startTimeLabel(): String {
        val d = startAsDate() ?: return ""
        return TIME_FMT.format(d)
    }

    /** 例: "10:15" */
    fun endTimeLabel(): String {
        val d = endAsDate() ?: return ""
        return TIME_FMT.format(d)
    }

    /** 例: "06/29 (月)" */
    fun startDateLabel(): String {
        val d = startAsDate() ?: return ""
        return DATE_FMT.format(d)
    }

    /** 当日(yyyy-MM-dd)に属するか */
    fun isOnDate(yyyyMMdd: String): Boolean =
        startDatetime.startsWith(yyyyMMdd)

    companion object {
        // バックエンドはタイムゾーンなしのローカル時刻文字列なので、端末ローカルとして解釈する
        private val PARSER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val TIME_FMT = SimpleDateFormat("HH:mm", Locale.JAPAN)
        private val DATE_FMT = SimpleDateFormat("MM/dd (E)", Locale.JAPAN)

        private fun parse(s: String): Date? = try {
            // 秒が無い "yyyy-MM-dd'T'HH:mm" にも一応対応
            val normalized = if (s.length == 16) s + ":00" else s
            PARSER.parse(normalized)
        } catch (e: Exception) {
            null
        }

        fun from(o: JSONObject): ScheduledEvent = ScheduledEvent(
            id = o.optLong("id"),
            title = o.optString("title", "(無題)"),
            startDatetime = o.optString("start_datetime", ""),
            endDatetime = o.optString("end_datetime", ""),
            eventType = o.optString("event_type", "study"),
            isCompleted = o.optInt("is_completed", 0) == 1,
            goalId = o.optLong("goal_id", 0L).let { if (it > 0L) it else null }
        )
    }
}
