package dev.togar.dynasched.engine

import dev.togar.dynasched.calendar.AvailabilityWindow
import dev.togar.dynasched.calendar.BusyBlock
import java.util.Calendar

/**
 * 「間に合うのか」を分数で出す。サーバーの `services/plan.js` の移植。
 *
 * 前回のテストでスキマスが使われなかった最大の理由は
 * 「この予定で過不足なく足りるのか確信が持てなかった」こと。ここがその答えになる。
 *
 * 足りないときは黙って詰め込まず、削った内容を必ず返す。
 * 削り順は 1) 応用を外す 2) 周回を減らす。
 */

/** 教材1件ぶんの計画 */
data class PlanRow(
    val materialId: Long,
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
    val needMinutesUntrimmed: Int,
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
)

/** 計画全体 */
data class PlanOutcome(
    val rows: List<PlanRow>,
    val freeMinutesTotal: Int,
    val freeMinutesLeft: Int,
    val hobbyMinutes: Int,
    /** 反映すべき「応用を外す/周回を減らす」の結果（教材ID → (drop, rounds)） */
    val decisions: Map<Long, Pair<Boolean, Int>>
)

object PlanEngine {

    private class Day(val day: String, val total: Int) { var left: Int = total }

    /**
     * 日ごとの空き分数。
     *
     * 端末のカレンダーは何日先まででも読めるので、サーバー版のような
     * 「同期が届いていない先を平均で概算する」処理は要らない。
     * ここが端末内完結にした素直な利点で、見積もりが推定でなく実測になる。
     */
    private fun buildPool(
        windows: List<AvailabilityWindow>, busy: List<BusyBlock>, days: Int, nowMillis: Long,
        wakeStart: Int, wakeEnd: Int
    ): List<Day> {
        val now = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val todayMid = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val out = ArrayList<Day>(days)
        for (i in 0 until days) {
            val c = (todayMid.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, i) }
            val ds = Scheduler.ymd(c)
            var mins = 0
            for (iv in Scheduler.computeFree(ds, busy, windows, wakeStart, wakeEnd)) {
                val s = if (i == 0) maxOf(iv.start, nowMin + 5) else iv.start
                if (iv.end > s) mins += iv.end - s
            }
            out.add(Day(ds, mins))
        }
        return out
    }

    /**
     * 期限日までに、この教材が実際に使える枠の合計。
     * 配置側は1教材あたり1日 MAX_PER_DAY までしか置かないので、同じ上限を掛けて数える。
     * 掛けないと「画面は間に合うと言うのに配置が追いつかない」ズレになる。
     */
    private fun capacityUntil(pool: List<Day>, dayStr: String): Int =
        pool.filter { it.day <= dayStr }.sumOf { minOf(it.left, Scheduler.MAX_PER_DAY) }

    /** 期限日までの枠から minutes 分を早い日から取る（1日の上限つき） */
    private fun consume(pool: List<Day>, dayStr: String, minutes: Int): Int {
        var rest = minutes
        for (p in pool) {
            if (rest <= 0) break
            if (p.day > dayStr) break
            val take = minOf(p.left, rest, Scheduler.MAX_PER_DAY)
            p.left -= take
            rest -= take
        }
        return minutes - rest
    }

    private fun dayOf(dt: String): String = dt.take(10)

    fun build(
        materials: List<MaterialRow>,
        hobbies: List<HobbyRow>,
        windows: List<AvailabilityWindow>,
        busy: List<BusyBlock>,
        engine: StudyEngine,
        days: Int = 45,
        nowMillis: Long = System.currentTimeMillis(),
        wakeStart: Int = Scheduler.WAKE_START,
        wakeEnd: Int = Scheduler.WAKE_END
    ): PlanOutcome {
        val d = days.coerceIn(1, 120)
        val pool = buildPool(windows, busy, d, nowMillis, wakeStart, wakeEnd)

        // 単発タスクも同じ枠を食うので先に引いておく（配置は単発が優先されるため）
        val hobbyMinutes = hobbies.sumOf { maxOf(15, it.durationMinutes) }
        if (hobbyMinutes > 0 && pool.isNotEmpty()) consume(pool, pool.last().day, hobbyMinutes)

        val sorted = materials.sortedBy { it.deadline }
        val rows = ArrayList<PlanRow>(sorted.size)
        val decisions = HashMap<Long, Pair<Boolean, Int>>()

        for (m in sorted) {
            val dl = dayOf(m.deadline)
            val target = maxOf(1, m.targetRounds)
            val capacity = capacityUntil(pool, dl)
            val firstRoundCapacity =
                if (m.firstRoundDeadline.isNotEmpty()) capacityUntil(pool, dayOf(m.firstRoundDeadline)) else 0

            // まず削らずに見積もる
            var drop = false
            var planned = target
            var st = engine.stateOf(m, overrideDrop = false, overrideRounds = target)
            var need = engine.remainingMinutes(m, st)
            val needFull = need
            val trimmed = ArrayList<String>()

            // 1) 応用を外す
            if (need > capacity && st.advanced > 0) {
                drop = true
                st = engine.stateOf(m, overrideDrop = true, overrideRounds = planned)
                need = engine.remainingMinutes(m, st)
                trimmed.add("応用${st.advanced}問を外した")
            }
            // 2) 周回を減らす
            while (need > capacity && planned > 1) {
                planned -= 1
                st = engine.stateOf(m, overrideDrop = drop, overrideRounds = planned)
                need = engine.remainingMinutes(m, st)
                trimmed.add("${planned}周に減らした")
            }

            decisions[m.id] = drop to planned
            consume(pool, dl, need)

            val blocked = m.prereqMaterialId?.let { pid ->
                materials.firstOrNull { it.id == pid }?.let { engine.stateOf(it).finishedRounds < 1 } ?: false
            } ?: false

            val hasFirst = m.firstRoundDeadline.isNotEmpty() && st.finishedRounds < 1
            val need1 = if (hasFirst)
                Math.round((st.perRound - st.doneInRound) * engine.paceFor(m.id, 1)).toInt() else 0

            rows.add(
                PlanRow(
                    materialId = m.id,
                    subject = m.subject,
                    name = m.name,
                    round = st.round,
                    plannedRounds = st.plannedRounds,
                    targetRounds = target,
                    perRound = st.perRound,
                    doneInRound = st.doneInRound,
                    remainingProblems = st.remainingProblems,
                    paceMinutes = Math.round(engine.paceFor(m.id, st.round) * 10) / 10.0,
                    measured = engine.isMeasured(m.id),
                    needMinutes = need,
                    needMinutesUntrimmed = needFull,
                    capacityMinutes = capacity,
                    slackMinutes = capacity - need,
                    ok = need <= capacity,
                    trimmed = trimmed,
                    dropAdvanced = drop,
                    advancedProblems = st.advanced,
                    deadline = m.deadline,
                    blockedByPrereq = blocked,
                    firstRoundDeadline = m.firstRoundDeadline,
                    firstRoundOk = !hasFirst || need1 <= firstRoundCapacity,
                    firstRoundSlack = firstRoundCapacity - need1,
                    hasFirstRound = hasFirst
                )
            )
        }

        return PlanOutcome(
            rows = rows,
            freeMinutesTotal = pool.sumOf { it.total },
            freeMinutesLeft = pool.sumOf { it.left },
            hobbyMinutes = hobbyMinutes,
            decisions = decisions
        )
    }
}
