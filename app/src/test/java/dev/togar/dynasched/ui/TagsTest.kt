package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * タグの読み書きと絞り込み。
 *
 * 表記ゆれを潰せていないと絞り込みが静かに空振りする（「#買い物」と「買い物」が別物になる）。
 * 絞り込みで木が壊れると、子タスクが親から切り離されて並ぶ。どちらも
 * 「動いているように見えて実は違う」類なので、ここで固定する。
 */
class TagsTest {

    private fun task(id: Long, name: String, parent: Long? = null, tags: String = "") =
        HobbyItem(id = id, name = name, parentId = parent, isCompleted = false, tags = tags)

    @Test
    fun `シャープや空白の揺れを潰す`() {
        assertEquals("買い物,家事", Tags.normalize(" #買い物  家事 "))
        assertEquals("買い物,家事", Tags.normalize("買い物,家事"))
        assertEquals("買い物,家事", Tags.normalize("＃買い物　家事"))   // 全角
    }

    @Test
    fun `同じタグを重ねて書いても1つ`() {
        assertEquals("買い物", Tags.normalize("#買い物 買い物 #買い物"))
    }

    @Test
    fun `空はから`() {
        assertEquals("", Tags.normalize("   "))
        assertEquals(emptyList<String>(), Tags.parse(null))
    }

    @Test
    fun `表示にはシャープを付ける`() {
        assertEquals("#買い物 #家事", Tags.display("買い物,家事"))
        assertEquals("", Tags.display(""))
    }

    @Test
    fun `よく使うタグが先に来る`() {
        val items = listOf(
            task(1, "A", tags = "買い物"),
            task(2, "B", tags = "買い物,家事"),
            task(3, "C", tags = "買い物")
        )
        assertEquals(listOf("買い物", "家事"), Tags.known(items))
    }

    // ---- 絞り込み ----

    private val tree = listOf(
        task(1, "親"),
        task(2, "子1", parent = 1, tags = "買い物"),
        task(3, "子2", parent = 1, tags = "家事"),
        task(9, "単独", tags = "買い物")
    )

    @Test
    fun `タグの付いた枝だけ残す`() {
        val r = Tags.filterTree(tree, setOf("買い物")).map { it.name }
        assertEquals(listOf("親", "子1", "単独"), r)
    }

    @Test
    fun `タグの無い親も子が当たれば残す`() {
        // 親が消えると子タスクが行き場を失って並ぶ。木の形は保つ
        val r = Tags.filterTree(tree, setOf("家事")).map { it.name }
        assertTrue("親が落ちた", r.contains("親"))
        assertTrue(r.contains("子2"))
        assertTrue("関係ない枝が残った", !r.contains("単独"))
    }

    @Test
    fun `複数のタグはどれかに当たれば残す`() {
        val r = Tags.filterTree(tree, setOf("買い物", "家事")).map { it.name }
        assertEquals(listOf("親", "子1", "子2", "単独"), r)
    }

    @Test
    fun `絞り込まない時は全部返す`() {
        assertEquals(tree.size, Tags.filterTree(tree, emptySet()).size)
    }

    @Test
    fun `当たるものが無ければ空`() {
        assertTrue(Tags.filterTree(tree, setOf("旅行")).isEmpty())
    }

    @Test
    fun `親子が輪になっても絞り込みは返る`() {
        val loop = listOf(task(1, "A", parent = 2, tags = "x"), task(2, "B", parent = 1))
        Tags.filterTree(loop, setOf("x"))   // 戻ってくれば十分（無限ループしない）
    }
}
