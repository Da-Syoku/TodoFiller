package dev.togar.dynasched.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 控えの形。
 *
 * DBが要る部分（書き出し・取り込み本体）は端末でしか動かないので、
 * ここでは**壊れた控えを掴んだ時に黙って通さないこと**だけを固定する。
 * 別アプリのJSONを読み込んで全消ししてしまうのが一番まずい。
 */
class BackupFormatTest {

    private fun backup(materials: Int = 0, attempts: Int = 0, hobbies: Int = 0): String {
        val o = JSONObject()
        o.put("format", Backup.FORMAT)
        o.put("version", Backup.VERSION)
        fun arr(n: Int) = org.json.JSONArray().apply { repeat(n) { put(JSONObject()) } }
        o.put("materials", arr(materials))
        o.put("attempts", arr(attempts))
        o.put("hobby_tasks", arr(hobbies))
        return o.toString()
    }

    @Test
    fun `件数を数えられる`() {
        val r = Backup.peek(backup(materials = 3, attempts = 12, hobbies = 5))
        assertEquals(3, r.materials)
        assertEquals(12, r.attempts)
        assertEquals(5, r.hobbies)
    }

    @Test(expected = IllegalStateException::class)
    fun `よそのJSONは受け付けない`() {
        Backup.peek("""{"format":"something-else","materials":[]}""")
    }

    @Test(expected = IllegalStateException::class)
    fun `目印の無いJSONも受け付けない`() {
        Backup.peek("""{"materials":[{"id":1}]}""")
    }

    @Test
    fun `中身が空の控えも読める`() {
        // 端末を入れ替えた直後など、空のまま書き出すことはある
        assertEquals(0, Backup.peek(backup()).materials)
    }

    @Test
    fun `書き出し名は日時が入って上書きされない`() {
        val name = Backup.suggestedFileName()
        assert(name.startsWith("skimas-backup-")) { name }
        assert(name.endsWith(".json")) { name }
    }
}
