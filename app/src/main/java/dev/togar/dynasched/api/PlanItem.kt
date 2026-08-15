package dev.togar.dynasched.api

import org.json.JSONArray
import org.json.JSONObject

/**
 * GET /plan の1教材ぶん。「間に合うのか」を分数で答える。
 *
 * 前回のテストでスキマスが使われなかった最大の理由は
 * 「この予定で過不足なく足りるのか確信が持てなかった」こと。ここがその答え。
 */
data class PlanItem(
    val id: Long,
    val subject: String,
    val name: String,
    val round: Int,
    val plannedRounds: Int,
    val targetRounds: Int,
    val perRound: Int,
    val doneInRound: Int,
    val remainingProblems: Int,
    val paceMinutes: Double,
    val measured: Boolean,
    val needMinutes: Int,
    val capacityMinutes: Int,
    val slackMinutes: Int,
    val ok: Boolean,
    val trimmed: List<String>,
    val dropAdvanced: Boolean,
    val advancedProblems: Int,
    val deadline: String,
    val blockedByPrereq: Boolean,
    val firstRoundDeadline: String,
    val firstRoundOk: Boolean,
    val firstRoundSlack: Int,
    val hasFirstRound: Boolean
) {
    /** 例: "間に合う（余裕 2時間30分）" / "間に合わない（不足 1時間36分）" */
    fun verdictLabel(): String {
        val d = kotlin.math.abs(slackMinutes)
        return if (ok) "間に合う（余裕 ${hm(d)}）" else "間に合わない（不足 ${hm(d)}）"
    }

    companion object {
        fun hm(minutes: Int): String {
            val h = minutes / 60
            val m = minutes % 60
            return if (h > 0) "${h}時間${if (m > 0) "${m}分" else ""}" else "${m}分"
        }

        fun from(o: JSONObject): PlanItem {
            val fr = o.optJSONObject("first_round")
            val tr = o.optJSONArray("trimmed") ?: JSONArray()
            val trimmed = ArrayList<String>(tr.length())
            for (i in 0 until tr.length()) trimmed.add(tr.optString(i, ""))
            return PlanItem(
                id = o.optLong("id"),
                subject = o.optString("subject", ""),
                name = o.optString("name", ""),
                round = o.optInt("round", 1),
                plannedRounds = o.optInt("planned_rounds", 1),
                targetRounds = o.optInt("target_rounds", 1),
                perRound = o.optInt("per_round", 0),
                doneInRound = o.optInt("done_in_round", 0),
                remainingProblems = o.optInt("remaining_problems", 0),
                paceMinutes = o.optDouble("pace_minutes", 0.0),
                measured = o.optBoolean("measured", false),
                needMinutes = o.optInt("need_minutes", 0),
                capacityMinutes = o.optInt("capacity_minutes", 0),
                slackMinutes = o.optInt("slack_minutes", 0),
                ok = o.optBoolean("ok", false),
                trimmed = trimmed,
                dropAdvanced = o.optBoolean("drop_advanced", false),
                advancedProblems = o.optInt("advanced_problems", 0),
                deadline = o.optString("deadline", ""),
                blockedByPrereq = o.optBoolean("blocked_by_prereq", false),
                firstRoundDeadline = fr?.optString("deadline", "") ?: "",
                firstRoundOk = fr?.optBoolean("ok", true) ?: true,
                firstRoundSlack = fr?.optInt("slack_minutes", 0) ?: 0,
                hasFirstRound = fr != null
            )
        }
    }
}

/** GET /plan 全体。枠の総量と、カレンダー同期がどこまで届いているか。 */
data class PlanResult(
    val items: List<PlanItem>,
    val calendarHorizon: String,
    val estimatedPerDay: Int,
    val knownDays: Int,
    val freeMinutesTotal: Int,
    val hobbyMinutes: Int
) {
    companion object {
        fun from(o: JSONObject): PlanResult {
            val arr = o.optJSONArray("items") ?: JSONArray()
            val list = ArrayList<PlanItem>(arr.length())
            for (i in 0 until arr.length()) list.add(PlanItem.from(arr.getJSONObject(i)))
            return PlanResult(
                items = list,
                calendarHorizon = o.optString("calendar_horizon", ""),
                estimatedPerDay = o.optInt("estimated_minutes_per_day", 0),
                knownDays = o.optInt("known_days", 0),
                freeMinutesTotal = o.optInt("free_minutes_total", 0),
                hobbyMinutes = o.optInt("hobby_minutes", 0)
            )
        }
    }
}
