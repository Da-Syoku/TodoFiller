package dev.togar.dynasched.api

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 目標タスク（goals）。期日(deadline)つき。テスト・レポートなど。
 * deadline は "yyyy-MM-dd'T'HH:mm:ss" 形式。
 *
 * v13 追加フィールド:
 * - studyType: "exercise"(演習系=日中・長ブロック) / "memorize"(暗記系=夜・短ブロック)。出現時間帯に影響。
 * - difficulty: 難易度 1〜5（高いほど1日の学習量が増える）
 * - understanding: 理解度 1〜5（低いほど1日の学習量が増える）
 * - progress: ワーク進捗 0〜100(%)（残量 = total × (1 - progress/100)）
 * - memo: 補足メモ
 */
data class GoalItem(
    val id: Long,
    val name: String,
    val deadline: String,
    val totalMinutes: Int,
    val priority: Int,
    val color: String,
    val studyType: String,
    val difficulty: Int,
    val understanding: Int,
    val progress: Int,
    val memo: String,
    val sessionMinutes: Int,
    val progressNote: String,
    val isExam: Boolean
) {
    fun deadlineDate(): Date? = try {
        val s = if (deadline.length == 16) deadline + ":00" else deadline
        PARSER.parse(s)
    } catch (e: Exception) { null }

    /** 例: "07/20 (月) 09:00" */
    fun deadlineLabel(): String {
        val d = deadlineDate() ?: return deadline
        return LABEL.format(d)
    }

    /** 期日まであと何日（過ぎていればマイナス） */
    fun daysRemaining(): Long {
        val d = deadlineDate() ?: return 0
        val diff = d.time - System.currentTimeMillis()
        return TimeUnit.MILLISECONDS.toDays(diff)
    }

    /** 演習系なら true */
    fun isExercise(): Boolean = studyType != "memorize"

    /** 種別の日本語表示 */
    fun studyTypeLabel(): String = if (isExercise()) "演習" else "暗記"

    companion object {
        private val PARSER = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        private val LABEL = SimpleDateFormat("MM/dd (E) HH:mm", Locale.JAPAN)

        const val TYPE_EXERCISE = "exercise"
        const val TYPE_MEMORIZE = "memorize"

        fun from(o: JSONObject): GoalItem = GoalItem(
            id = o.optLong("id"),
            name = o.optString("name", ""),
            deadline = o.optString("deadline", ""),
            totalMinutes = o.optInt("total_minutes", 300),
            priority = o.optInt("priority", 5),
            color = o.optString("color", "#E24A90"),
            studyType = o.optString("study_type", TYPE_EXERCISE),
            difficulty = o.optInt("difficulty", 3),
            understanding = o.optInt("understanding", 3),
            progress = o.optInt("progress", 0),
            memo = o.optString("memo", ""),
            sessionMinutes = o.optInt("session_minutes", 50),
            progressNote = o.optString("progress_note", ""),
            isExam = o.optInt("is_exam", 0) == 1
        )
    }
}
