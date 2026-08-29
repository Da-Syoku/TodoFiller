package dev.togar.dynasched.ui

import android.content.Context
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog

/**
 * タグの入力欄。自由に打てて、**これまで使ったタグからも選べる**。
 *
 * 自由入力だけにすると「買い物」と「買物」が別物になって、絞り込みが効かなくなる。
 * かといって候補からしか選べないと、思いついた時にその場で足せない。
 * 両方を1つの部品にまとめて、どちらの入り口も残す。
 */
class TagInputView(
    ctx: Context,
    initial: String,
    private val knownTags: () -> List<String>
) : LinearLayout(ctx) {

    private val input = EditText(ctx).apply {
        setText(Tags.display(initial))
        hint = "#買い物 #家事 のように（空白かカンマ区切り）"
        textSize = 14f
        isSingleLine = true
    }

    /** 保存する形（カンマ区切り） */
    val value: String get() = Tags.normalize(input.text.toString())

    /** 外から差し替える（親タスクからの引き継ぎなど） */
    fun setValue(raw: String) {
        input.setText(Tags.display(raw))
    }

    init {
        orientation = HORIZONTAL
        val chooser = Button(ctx).apply {
            text = "選ぶ"
            textSize = 12f
            minWidth = 0
            setOnClickListener { chooseFromKnown() }
        }
        addView(input, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(chooser, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun chooseFromKnown() {
        val known = knownTags()
        if (known.isEmpty()) {
            android.widget.Toast.makeText(
                context, "まだタグがありません。まず打って作ってください", android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        val current = Tags.parse(input.text.toString()).toMutableSet()
        val checked = BooleanArray(known.size) { current.contains(known[it]) }
        AlertDialog.Builder(context)
            .setTitle("使ったことのあるタグ")
            .setMultiChoiceItems(known.toTypedArray(), checked) { _, which, isChecked ->
                if (isChecked) current.add(known[which]) else current.remove(known[which])
            }
            .setPositiveButton("決定") { _, _ ->
                input.setText(Tags.display(current.joinToString(",")))
            }
            .setNegativeButton("やめる", null)
            .show()
    }
}
