package dev.togar.dynasched.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CalendarView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.CalColor
import dev.togar.dynasched.api.GoalItem
import java.util.Calendar
import java.util.Locale

/**
 * 目標タスク画面。月カレンダーの日付をタップすると、その日を期日とする目標を追加できる。
 * 一覧の「編集」または行タップで編集ダイアログを開く（追加と同一フォーム）。
 */
class GoalFragment : Fragment() {

    private lateinit var adapter: GoalAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView

    // ダイアログ内スピナーの値対応
    private val studyTypeValues = arrayOf(GoalItem.TYPE_EXERCISE, GoalItem.TYPE_MEMORIZE)
    private val studyTypeLabels = arrayOf("演習系（問題演習・記述）", "暗記系（用語・暗記）")
    private val prioValues = arrayOf(3, 5, 8)
    private val prioLabels = arrayOf("低", "中", "高")
    private val levelLabels = arrayOf("1", "2", "3", "4", "5") // 難易度・理解度 共通(値=index+1)
    private val sessionValues = arrayOf(25, 40, 50, 60, 90)
    private val sessionLabels = arrayOf("25分", "40分", "50分", "60分", "90分")

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_goal, container, false)

        empty = root.findViewById(R.id.emptyText)
        swipe = root.findViewById(R.id.swipeRefresh)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler)
        val calendar = root.findViewById<CalendarView>(R.id.calendarView)
        val addButton = root.findViewById<Button>(R.id.addGoalButton)

        adapter = GoalAdapter(
            onEdit = { g -> showGoalDialog(g, null) },
            onDelete = { g -> confirmDelete(g) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        // カレンダーの日付タップ → その日を期日に新規追加
        calendar.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val c = Calendar.getInstance()
            c.set(year, month, dayOfMonth, 23, 59, 0)
            showGoalDialog(null, c)
        }

        // ＋追加ボタンは今日の日付で追加ダイアログ
        addButton.setOnClickListener { showGoalDialog(null, null) }

        swipe.setOnRefreshListener { load() }
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.getGoals(ctx) },
            onSuccess = { list ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                adapter.submit(list)
                empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                empty.visibility = View.VISIBLE
                empty.text = "読み込みエラー: ${e.message}"
            }
        )
    }

    /**
     * 目標の追加/編集ダイアログ。
     * @param existing 非nullなら編集、nullなら新規追加
     * @param presetDate 新規追加時の初期期日（カレンダータップ等）。nullなら今日。
     */
    private fun showGoalDialog(existing: GoalItem?, presetDate: Calendar?) {
        val ctx = requireContext()
        val density = ctx.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        // 初期期日の決定
        val cal = Calendar.getInstance()
        if (existing != null) {
            existing.deadlineDate()?.let { cal.time = it }
        } else if (presetDate != null) {
            cal.time = presetDate.time
        } else {
            cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59)
        }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(t: String) = TextView(ctx).apply {
            text = t; textSize = 12f; setPadding(0, pad / 2, 0, 0)
        }

        // 名前
        val nameInput = EditText(ctx).apply {
            hint = "目標名（例：数学の期末テスト）"
            setText(existing?.name ?: "")
        }
        layout.addView(nameInput)

        // 期日（日付ボタン）
        layout.addView(label("期日"))
        val dateBtn = Button(ctx)
        fun refreshDateBtn() {
            dateBtn.text = String.format(
                Locale.JAPAN, "%04d/%02d/%02d",
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
            )
        }
        refreshDateBtn()
        dateBtn.setOnClickListener {
            DatePickerDialog(ctx, { _, y, m, d ->
                cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
                refreshDateBtn()
            }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
        }
        layout.addView(dateBtn)

        // 時刻（時刻ボタン）
        layout.addView(label("時刻"))
        val timeBtn = Button(ctx)
        fun refreshTimeBtn() {
            timeBtn.text = String.format(
                Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
            )
        }
        refreshTimeBtn()
        timeBtn.setOnClickListener {
            TimePickerDialog(ctx, { _, h, m ->
                cal.set(Calendar.HOUR_OF_DAY, h); cal.set(Calendar.MINUTE, m)
                refreshTimeBtn()
            }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
        }
        layout.addView(timeBtn)

        // 目標時間（時・分ホイール）
        layout.addView(label("目標時間"))
        val durationPicker = DurationPickerView(ctx, existing?.totalMinutes ?: 300)
        layout.addView(durationPicker)

        // 種別（演習系/暗記系）
        layout.addView(label("種別（演習=日中に長め / 暗記=夜に短め）"))
        val typeSpinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, studyTypeLabels)
            val idx = studyTypeValues.indexOf(existing?.studyType ?: GoalItem.TYPE_EXERCISE)
            setSelection(if (idx >= 0) idx else 0)
        }
        layout.addView(typeSpinner)

        // 定期テスト（カレンダーの「テスト期間」予定の間はこれだけが配置される）
        val examSwitch = android.widget.Switch(ctx).apply {
            text = "定期テスト（テスト期間中はこれ優先）"
            textSize = 13f
            isChecked = existing?.isExam ?: false
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        layout.addView(examSwitch)

        // 1コマの長さ（この単位でスケジュールに刻まれる）
        layout.addView(label("1コマの長さ（この単位で配置される）"))
        val sessionSlider = LabeledSlider(ctx, sessionValues.toList(), existing?.sessionMinutes ?: 50) { "${it}分" }
        layout.addView(sessionSlider)

        // 優先度
        layout.addView(label("優先度"))
        val prioSlider = LabeledSlider(ctx, prioValues.toList(), existing?.priority ?: 5) { v ->
            prioLabels[prioValues.indexOf(v).coerceIn(0, prioLabels.size - 1)]
        }
        layout.addView(prioSlider)

        // 難易度（高いほど頻度↑）
        layout.addView(label("難易度（高いほど1日の学習量↑）"))
        val diffSlider = LabeledSlider(ctx, (1..5).toList(), existing?.difficulty ?: 3)
        layout.addView(diffSlider)

        // 理解度（低いほど頻度↑）
        layout.addView(label("理解度（低いほど1日の学習量↑）"))
        val undSlider = LabeledSlider(ctx, (1..5).toList(), existing?.understanding ?: 3)
        layout.addView(undSlider)

        // 進捗（%）
        layout.addView(label("ワーク進捗（%）"))
        val progressSlider = LabeledSlider(ctx, (0..100 step 5).toList(), ((existing?.progress ?: 0) / 5) * 5) { "${it}%" }
        layout.addView(progressSlider)

        // 進捗メモ（どこまでやったかの自由記述）
        layout.addView(label("進捗メモ（例: ワークp.34まで）"))
        val progressNoteInput = EditText(ctx).apply {
            setText(existing?.progressNote ?: "")
            hint = "どこまで進んだかをメモ"
        }
        layout.addView(progressNoteInput)

        // 色（パレット）
        layout.addView(label("カレンダーの色"))
        val colorPalette = ColorPaletteView(ctx, colorIndexForHex(existing?.color))
        layout.addView(colorPalette)

        // メモ
        layout.addView(label("メモ"))
        val memoInput = EditText(ctx).apply {
            setText(existing?.memo ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        layout.addView(memoInput)

        val scroll = ScrollView(ctx).apply { addView(layout) }

        AlertDialog.Builder(ctx)
            .setTitle(if (existing == null) "目標を追加" else "目標を編集")
            .setView(scroll)
            .setPositiveButton(if (existing == null) "追加" else "保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "目標名を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val minutes = durationPicker.totalMinutes.let { if (it > 0) it else 300 }
                val priority = prioSlider.value
                val studyType = studyTypeValues[typeSpinner.selectedItemPosition]
                val difficulty = diffSlider.value
                val understanding = undSlider.value
                val progress = progressSlider.value
                val color = CalColor.hexFor(CalColor.idAt(colorPalette.selectedIndex))
                val session = sessionSlider.value
                val progressNote = progressNoteInput.text.toString().trim()
                val memo = memoInput.text.toString().trim()
                val deadline = String.format(
                    Locale.US, "%04d-%02d-%02dT%02d:%02d:00",
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
                )

                val isExam = examSwitch.isChecked
                if (existing == null) {
                    saveNew(name, deadline, minutes, priority, studyType, difficulty, understanding, progress, color, memo, session, progressNote, isExam)
                } else {
                    saveEdit(existing.id, name, deadline, minutes, priority, studyType, difficulty, understanding, progress, color, memo, session, progressNote, isExam)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun saveNew(
        name: String, deadline: String, minutes: Int, priority: Int, studyType: String,
        difficulty: Int, understanding: Int, progress: Int, color: String, memo: String, session: Int, progressNote: String, isExam: Boolean
    ) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.addGoal(ctx, name, deadline, minutes, priority, studyType, difficulty, understanding, progress, color, memo, session, progressNote, isExam) },
            onSuccess = {
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "目標を追加しました", Toast.LENGTH_SHORT).show()
                load()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "追加に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun saveEdit(
        id: Long, name: String, deadline: String, minutes: Int, priority: Int, studyType: String,
        difficulty: Int, understanding: Int, progress: Int, color: String, memo: String, session: Int, progressNote: String, isExam: Boolean
    ) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.editGoal(ctx, id, name, deadline, minutes, priority, studyType, difficulty, understanding, progress, color, memo, session, progressNote, isExam) },
            onSuccess = {
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "更新しました", Toast.LENGTH_SHORT).show()
                load()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "更新に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    /** goal の色は HEX 保存。CalColor の近似HEXと一致するものを選ぶ（無ければ既定=0）。 */
    private fun colorIndexForHex(hex: String?): Int {
        if (hex.isNullOrEmpty()) return 0
        val i = CalColor.items.indexOfFirst { it.hex.equals(hex, ignoreCase = true) }
        return if (i >= 0) i else 0
    }

    private fun confirmDelete(g: GoalItem) {
        AlertDialog.Builder(requireContext())
            .setMessage("「${g.name}」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                val ctx = requireContext().applicationContext
                Api.async(
                    work = { Api.deleteGoal(ctx, g.id) },
                    onSuccess = { if (isAdded) load() },
                    onError = { e ->
                        if (!isAdded) return@async
                        Toast.makeText(requireContext(), "削除に失敗: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                )
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
