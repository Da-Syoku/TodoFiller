package dev.togar.dynasched.api

import android.graphics.Color

/**
 * Googleカレンダーのイベント色（colorId 1〜11）と、アプリ内表示用の色/名前の対応。
 * color は colorId の文字列（"" は既定色）。カレンダー書き出し時に colorId として使う。
 */
object CalColor {

    // colorId, 表示名, 近似HEX
    data class Item(val id: String, val name: String, val hex: String)

    val items: List<Item> = listOf(
        Item("", "既定", "#4A90E2"),
        Item("1", "ラベンダー", "#7986CB"),
        Item("2", "セージ", "#33B679"),
        Item("3", "グレープ", "#8E24AA"),
        Item("4", "フラミンゴ", "#E67C73"),
        Item("5", "バナナ", "#F6BF26"),
        Item("6", "ミカン", "#F4511E"),
        Item("7", "ピーコック", "#039BE5"),
        Item("8", "グラファイト", "#616161"),
        Item("9", "ブルーベリー", "#3F51B5"),
        Item("10", "バジル", "#0B8043"),
        Item("11", "トマト", "#D50000")
    )

    val labels: Array<String> get() = items.map { it.name }.toTypedArray()

    fun indexOfId(id: String?): Int {
        val v = id ?: ""
        val i = items.indexOfFirst { it.id == v }
        return if (i >= 0) i else 0
    }

    fun idAt(index: Int): String = items.getOrNull(index)?.id ?: ""

    /** colorId → 表示用の色（Int）。既定/不明は青系。 */
    fun colorFor(id: String?): Int {
        val item = items.firstOrNull { it.id == (id ?: "") } ?: items[0]
        return try { Color.parseColor(item.hex) } catch (e: Exception) { Color.parseColor("#4A90E2") }
    }

    fun hexFor(id: String?): String =
        (items.firstOrNull { it.id == (id ?: "") } ?: items[0]).hex
}
