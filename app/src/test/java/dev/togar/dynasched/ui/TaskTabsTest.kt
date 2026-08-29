package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * グループ／タグを横タブに並べる部分。
 *
 * タブは「いま見えている範囲」を決める。ここがずれると、
 * **あるはずのタスクが出てこない**か、逆に関係ないものが混ざる。
 * どちらも画面を触っただけでは原因が分からない類なので固定する。
 */
class TaskTabsTest {

    private fun task(id: Long, name: String, parent: Long? = null, tags: String = "", order: Int = 0) =
        HobbyItem(
            id = id, name = name, parentId = parent, isCompleted = false,
            tags = tags, sortOrder = order
        )

    private val tree = listOf(
        task(1, "買い物リスト", order = 1),
        task(2, "牛乳", parent = 1, tags = "食料"),
        task(3, "洗剤", parent = 1, tags = "日用品"),
        task(4, "大掃除", order = 2),
        task(5, "風呂", parent = 4),
        task(9, "単独タスク", order = 3, tags = "食料")
    )

    // ---- タブの並び ----

    @Test
    fun `グループは子を持つタスクだけがタブになる`() {
        val tabs = TaskTabs.tabs(tree, TabSource.GROUP)
        assertEquals(listOf("すべて", "買い物リスト", "大掃除"), tabs.map { it.label })
        assertEquals(TaskTabs.ALL, tabs.first().key)
    }

    @Test
    fun `タグのタブは使われているタグから作る`() {
        val tabs = TaskTabs.tabs(tree, TabSource.TAG)
        assertEquals(listOf("すべて", "#食料", "#日用品"), tabs.map { it.label })
    }

    @Test
    fun `出さない指定ならタブは空`() {
        assertTrue(TaskTabs.tabs(tree, TabSource.NONE).isEmpty())
    }

    // ---- タブの中身 ----

    @Test
    fun `すべてタブは全部見せる`() {
        assertEquals(tree.size, TaskTabs.apply(tree, TaskTabs.ALL).size)
    }

    @Test
    fun `グループのタブは配下だけを見せ、親自身は出さない`() {
        // 見出しがグループ名なので、中でもう一度出すと同じ名前が2回並ぶ
        val shown = TaskTabs.apply(tree, "g:1").map { it.name }
        assertEquals(listOf("牛乳", "洗剤"), shown)
    }

    @Test
    fun `タグのタブは当てはまる枝を見せる`() {
        val shown = TaskTabs.apply(tree, "t:食料").map { it.name }
        // 「牛乳」と、親の「買い物リスト」（木を保つため）と「単独タスク」
        assertTrue(shown.contains("牛乳"))
        assertTrue("木が壊れて親が落ちた", shown.contains("買い物リスト"))
        assertTrue(shown.contains("単独タスク"))
        assertTrue("関係ない枝が混ざった", !shown.contains("風呂"))
    }

    @Test
    fun `壊れたキーでも全部返す（何も見えなくなるより良い）`() {
        assertEquals(tree.size, TaskTabs.apply(tree, "g:あいうえお").size)
    }

    @Test
    fun `消えたグループのタブは存在しないと分かる`() {
        val tabs = TaskTabs.tabs(tree, TabSource.GROUP)
        assertTrue(TaskTabs.exists(tabs, "g:1"))
        assertTrue("消したグループが残っている", !TaskTabs.exists(tabs, "g:999"))
        assertTrue("すべては常にある", TaskTabs.exists(tabs, TaskTabs.ALL))
    }

    @Test
    fun `配下は孫まで降りる`() {
        val deep = listOf(
            task(1, "親"), task(2, "子", parent = 1), task(3, "孫", parent = 2)
        )
        assertEquals(listOf("子", "孫"), TaskList.descendantsOf(deep, 1).map { it.name })
    }
}
