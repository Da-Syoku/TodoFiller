package dev.togar.dynasched

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.ui.ColorPaletteView
import dev.togar.dynasched.ui.DurationPickerView
import dev.togar.dynasched.ui.LabeledSlider

/**
 * タスク追加画面（Googleカレンダー風）。名前・必要時間・場所・優先度を入力。
 * parent_id を intent で渡すと子タスクとして追加する。
 */
class AddTaskActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PARENT_ID = "parent_id"
        const val EXTRA_PARENT_NAME = "parent_name"
    }

    // 場所: 表示ラベル → API値
    private val locationValues = arrayOf("anywhere", "home", "out")
    private val locationLabels = arrayOf("どこでも", "家のみ", "外のみ")
    // 優先度: 表示ラベル → 値
    private val priorityValues = arrayOf(3, 5, 8)
    private val priorityLabels = arrayOf("低", "中", "高")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_task)

        val parentId = if (intent.hasExtra(EXTRA_PARENT_ID))
            intent.getLongExtra(EXTRA_PARENT_ID, -1L) else null
        val parentName = intent.getStringExtra(EXTRA_PARENT_NAME)

        val title = findViewById<TextView>(R.id.screenTitle)
        val parentLabel = findViewById<TextView>(R.id.parentLabel)
        val nameInput = findViewById<EditText>(R.id.nameInput)
        val locationSpinner = findViewById<Spinner>(R.id.locationSpinner)
        val prioritySpinner = findViewById<Spinner>(R.id.prioritySpinner)
        val noteInput = findViewById<EditText>(R.id.noteInput)
        val saveButton = findViewById<Button>(R.id.saveButton)

        val durationPicker = DurationPickerView(this, 30)
        findViewById<android.widget.FrameLayout>(R.id.durationContainer).addView(durationPicker)
        val colorPalette = ColorPaletteView(this, 0)
        findViewById<android.widget.FrameLayout>(R.id.colorContainer).addView(colorPalette)

        if (parentId != null && parentId >= 0) {
            title.text = "子タスクを追加"
            parentLabel.visibility = TextView.VISIBLE
            parentLabel.text = "親: ${parentName ?: ""}"
        }

        locationSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, locationLabels
        )
        // 優先度はスワイプで選ぶ（スピナーを隠して同じ位置にスライダーを差し込む）
        prioritySpinner.visibility = android.view.View.GONE
        val prioritySlider = LabeledSlider(this, priorityValues.toList(), 5) { v ->
            priorityLabels[priorityValues.toList().indexOf(v).coerceIn(0, 2)]
        }
        val prioParent = prioritySpinner.parent as android.view.ViewGroup
        prioParent.addView(prioritySlider, prioParent.indexOfChild(prioritySpinner))

        saveButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, "タスク名を入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var total = durationPicker.totalMinutes
            if (total <= 0) total = 30
            val location = locationValues[locationSpinner.selectedItemPosition]
            val priority = prioritySlider.value
            val note = noteInput.text.toString().trim()
            val color = dev.togar.dynasched.api.CalColor.idAt(colorPalette.selectedIndex)

            saveButton.isEnabled = false
            val ctx = applicationContext
            val pid = if (parentId != null && parentId >= 0) parentId else null
            Api.async(
                work = { Repo.current(ctx).addHobby(ctx, name, pid, total, priority, location, note, color) },
                onSuccess = {
                    Toast.makeText(this, "追加しました", Toast.LENGTH_SHORT).show()
                    finish()
                },
                onError = { e ->
                    saveButton.isEnabled = true
                    Toast.makeText(this, "追加に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
}
