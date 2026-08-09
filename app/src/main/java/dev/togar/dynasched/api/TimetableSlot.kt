package dev.togar.dynasched.api

import org.json.JSONObject

/**
 * 定期タスク（timetable_slots）。毎週決まった曜日・時刻の予定（授業・バイトなど）。
 * day_of_week はサーバー規約で 1=月曜 … 7=日曜。
 * start_time / end_time は "HH:MM" 形式。
 */
data class TimetableSlot(
    val id: Long,
    val dayOfWeek: Int,
    val startTime: String,
    val endTime: String,
    val label: String,
    val slotType: String,
    val location: String
) {
    /** 時刻を分に変換（"HH:MM" → 分） */
    fun startMin(): Int = toMin(startTime)
    fun endMin(): Int = toMin(endTime)

    companion object {
        fun toMin(t: String): Int {
            val p = t.split(":")
            return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
        }

        fun from(o: JSONObject): TimetableSlot = TimetableSlot(
            id = o.optLong("id"),
            dayOfWeek = o.optInt("day_of_week", 1),
            startTime = o.optString("start_time", ""),
            endTime = o.optString("end_time", ""),
            label = o.optString("label", ""),
            slotType = o.optString("slot_type", "class"),
            location = if (o.isNull("location")) "home" else o.optString("location", "home")
        )
    }
}
