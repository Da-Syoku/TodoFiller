package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 単発タスク一覧の並べ方。
 *
 * 並び順・折りたたみ・完了のまとめ方が絡むと、目で見て確かめるのが一番あてにならない。
 * **木の形が壊れていないこと**を含めてここで固定する。
 */
class TaskListTest {

    private fun task(
        id: Long, name: String, parent: Long? = null, done: Boolean = false,
        minutes: Int = 30, priority: Int = 5, order: Int = 0
    ) = HobbyItem(
        id = id, name = name, parentId = parent, isCompleted = done,
        durationMinutes = minutes, priority = priority, sortOrder = order
    )

    private fun names(rows: List<TaskRow>) = rows.joinToString(",") {
        "  ".repeat(it.level) + it.item.name
    }

    // ---- 並び順 ----

    private val flat = listOf(
        task(1, "古い", minutes = 90, priority = 3, order = 3),
        task(2, "中間", minutes = 15, priority = 8, order = 1),
        task(3, "新しい", minutes = 45, priority = 5, order = 2)
    )

    @Test
    fun `手動の並びは保存した順`() {
        val r = TaskList.build(flat, TaskSort.MANUAL, emptySet(), false)
        assertEquals("中間,新しい,古い", names(r))
    }

    @Test
    fun `追加が新しい順と古い順`() {
        assertEquals("新しい,中間,古い", names(TaskList.build(flat, TaskSort.NEWEST, emptySet(), false)))
        assertEquals("古い,中間,新しい", names(TaskList.build(flat, TaskSort.OLDEST, emptySet(), false)))
    }

    @Test
    fun `優先度と必要時間で並べられる`() {
        assertEquals("中間,新しい,古い", names(TaskList.build(flat, TaskSort.PRIORITY, emptySet(), false)))
        assertEquals("古い,新しい,中間", names(TaskList.build(flat, TaskSort.LONGEST, emptySet(), false)))
        assertEquals("中間,新しい,古い", names(TaskList.build(flat, TaskSort.SHORTEST, emptySet(), false)))
    }

    // ---- 完了の扱い ----

    @Test
    fun `完了を下にまとめる指定はどの並び順より先に効く`() {
        val list = listOf(
            task(1, "済A", done = true, priority = 8),
            task(2, "未B", priority = 3),
            task(3, "未C", priority = 5)
        )
        assertEquals("未C,未B,済A", names(TaskList.build(list, TaskSort.PRIORITY, emptySet(), true)))
    }

    @Test
    fun `まとめない指定なら完了もその場に残る`() {
        val list = listOf(
            task(1, "済A", done = true, priority = 8),
            task(2, "未B", priority = 3)
        )
        assertEquals("済A,未B", names(TaskList.build(list, TaskSort.PRIORITY, emptySet(), false)))
    }

    // ---- 階層と折りたたみ ----

    private val tree = listOf(
        task(1, "親", order = 1),
        task(2, "子1", parent = 1, minutes = 30, order = 1),
        task(3, "子2", parent = 1, minutes = 60, done = true, order = 2),
        task(4, "孫", parent = 2, minutes = 20),
        task(9, "単独", order = 2)
    )

    @Test
    fun `階層は深さ優先で段付きになる`() {
        assertEquals("親,  子1,    孫,  子2,単独", names(TaskList.build(tree, TaskSort.MANUAL, emptySet(), false)))
    }

    @Test
    fun `畳んだ親は1行になって配下が消える`() {
        val r = TaskList.build(tree, TaskSort.MANUAL, setOf(1L), false)
        assertEquals("親,単独", names(r))
        assertTrue(r.first().collapsed)
    }

    @Test
    fun `畳んだ親の要約は配下の葉を数える`() {
        val r = TaskList.build(tree, TaskSort.MANUAL, setOf(1L), false)
        // 葉は「孫(20分)」と「子2(60分・済)」の2件。子1は親なので数えない
        assertEquals("2件（済1）・1時間20分", r.first().summary())
    }

    @Test
    fun `親が消えた子はルートとして出す`() {
        val orphan = listOf(task(5, "孤児", parent = 99))
        assertEquals("孤児", names(TaskList.build(orphan, TaskSort.MANUAL, emptySet(), false)))
    }

    @Test
    fun `親子が輪になっても一覧は返る`() {
        // 壊れたデータで無限ループに入ると、アプリが二度と開かなくなる
        val loop = listOf(task(1, "A", parent = 2), task(2, "B", parent = 1))
        val r = TaskList.build(loop, TaskSort.MANUAL, emptySet(), false)
        assertTrue("行が出ない", r.size <= 2)
    }

    // ---- 並び替え ----

    @Test
    fun `兄弟どうしなら入れ替わる`() {
        val rows = TaskList.build(flat, TaskSort.MANUAL, emptySet(), false)
        assertEquals(listOf(3L, 2L, 1L), TaskList.moveWithinSiblings(rows, 0, 1))
    }

    @Test
    fun `親をまたぐ移動は認めない`() {
        val rows = TaskList.build(tree, TaskSort.MANUAL, emptySet(), false)
        // 「親」(level0) を「子1」(level1) の位置へ落とす
        assertNull(TaskList.moveWithinSiblings(rows, 0, 1))
    }

    @Test
    fun `子タスク化はすぐ上の兄弟の下に入る`() {
        val rows = TaskList.build(flat, TaskSort.MANUAL, emptySet(), false)  // 中間,新しい,古い
        assertEquals(2L, TaskList.indentTarget(rows, 1))
        assertNull("先頭は子にできない", TaskList.indentTarget(rows, 0))
    }

    @Test
    fun `子をやめると親の隣に上がる`() {
        val rows = TaskList.build(tree, TaskSort.MANUAL, emptySet(), false)
        val magoIndex = rows.indexOfFirst { it.item.name == "孫" }
        assertTrue(TaskList.canOutdent(rows, magoIndex))
        assertEquals("孫の新しい親は「子1」の親＝「親」", 1L, TaskList.outdentParent(rows, magoIndex))

        val oyaIndex = rows.indexOfFirst { it.item.name == "親" }
        assertTrue("最上位はもう上がれない", !TaskList.canOutdent(rows, oyaIndex))
    }

    @Test
    fun `優先度順で動かすと落とした先の優先度になる`() {
        val list = listOf(
            task(1, "高1", priority = 8), task(2, "高2", priority = 8),
            task(3, "低", priority = 3)
        )
        val rows = TaskList.build(list, TaskSort.PRIORITY, emptySet(), false)
        // 「低」を先頭（高の塊の中）へ持っていく
        assertEquals(8, TaskList.priorityAfterMove(rows, 3L, 1))
        // 末尾へ戻せば下の隣に合わせる（隣が無ければ上の隣）
        assertEquals(8, TaskList.priorityAfterMove(rows, 3L, 2))
    }
}
