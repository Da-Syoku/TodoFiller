package dev.togar.dynasched.engine

import dev.togar.dynasched.calendar.AvailabilityWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone

/**
 * 「間に合うか」の判定。
 *
 * ここが誤ると**予定が1件も生成されなくなる**。足りないと判断された教材は
 * 応用を外され、周回を減らされ、すでに1周終えていれば残り0問になって
 * 配置対象から丸ごと消えるため。しかもその決定はDBへ書き戻されるので、
 * 画面上は「教材はあるのに予定が出ない」という形でしか表に出てこない。
 */
class PlanEngineTest {

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
    }

    private fun jst(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0): Long =
        GregorianCalendar(TimeZone.getTimeZone("Asia/Tokyo")).apply {
            clear(); set(y, mo - 1, d, h, mi, 0)
        }.timeInMillis

    private fun ymd(base: Long, offsetDays: Int): String {
        val c = Calendar.getInstance().apply { timeInMillis = base + offsetDays * 86400000L }
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 毎日2時間の枠が days 日ぶん */
    private fun dailyWindows(base: Long, days: Int): List<AvailabilityWindow> =
        (0 until days).map { i ->
            val d = ymd(base, i)
            AvailabilityWindow("${d}T09:00:00", "${d}T11:00:00", "home", "自習", i.toLong())
        }

    private val base = jst(2026, 9, 1)
    private val now = jst(2026, 9, 1, 8, 0)

    /** 800問×3周、1周だけ終わった状態。締切は40日後 */
    private fun material() = MaterialRow(
        id = 1, name = "ワーク", totalProblems = 800, targetRounds = 3,
        doneProblems = 800, deadline = "${ymd(base, 40)}T23:59:00", sessionMinutes = 50
    )

    @Test
    fun `締切までの枠を全部読めていれば周回は削られない`() {
        val out = PlanEngine.build(
            listOf(material()), emptyList(), dailyWindows(base, 45), emptyList(),
            StudyEngine(), 45, now
        )
        val (drop, planned) = out.decisions.getValue(1L)
        assertEquals("3周のままであること", 3, planned)
        assertTrue("応用が無いのに外された", !drop)
        assertTrue("やることが残っていない", out.rows.first().remainingProblems > 0)
    }

    @Test
    fun `カレンダーを短くしか読まないと残り0問まで削られる`() {
        // 移植時の不具合そのもの。7日ぶんしか読まずに45日先の締切を判定すると、
        // 8日目以降の空きが全部0とみなされて「絶対に間に合わない」になる。
        val out = PlanEngine.build(
            listOf(material()), emptyList(), dailyWindows(base, 7), emptyList(),
            StudyEngine(), 45, now
        )
        assertEquals("1周まで削られるはず（この状態を再発させない）", 1, out.decisions.getValue(1L).second)
        assertEquals(
            "1周に削られると1周ぶん済んでいるので残り0問になる",
            0, out.rows.first().remainingProblems
        )
    }

    @Test
    fun `残り0問になった教材は配置されない`() {
        // 上の削り込みが配置まで波及することの確認。「予定が生成されない」の直接の原因。
        val trimmed = material().copy(plannedRounds = 1)
        val placed = Scheduler.run(
            listOf(trimmed), emptyList(), dailyWindows(base, 7), emptyList(), emptyList(),
            StudyEngine(), 7, now
        )
        assertTrue("残り0問なのに配置された", placed.isEmpty())
    }

    @Test
    fun `枠が足りなければ応用から先に外す`() {
        val m = MaterialRow(
            id = 2, name = "問題集", totalProblems = 500, advancedRanges = "401-500",
            targetRounds = 1, deadline = "${ymd(base, 10)}T23:59:00", sessionMinutes = 50
        )
        val out = PlanEngine.build(
            listOf(m), emptyList(), dailyWindows(base, 45), emptyList(), StudyEngine(), 45, now
        )
        val (drop, planned) = out.decisions.getValue(2L)
        assertTrue("応用を外していない", drop)
        assertEquals("1周なのに減らされた", 1, planned)
        assertTrue(out.rows.first().trimmed.isNotEmpty())
    }
}
