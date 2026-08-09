package dev.togar.dynasched.ui

import android.app.TimePickerDialog
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.TimetableSlot
import java.util.Locale

/**
 * 週間ルーティーン画面。Googleカレンダーの週表示のように
 * 横軸＝曜日（日〜土）、縦軸＝時間のグリッド。空きマスをタップで追加、ブロックをタップで削除。
 * ルーティーンには「その時間どこにいるか（家/外）」も設定できる。
 */
class RecurringFragment : Fragment() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var grid: LinearLayout
    private lateinit var dayHeader: LinearLayout

    private var slots: List<TimetableSlot> = emptyList()

    // コピー中の予定（貼り付け先の空きマスをタップすると複製される）
    private var copiedSlot: TimetableSlot? = null

    private val dayNames = arrayOf("日", "月", "火", "水", "木", "金", "土")
    private val colToServer = intArrayOf(7, 1, 2, 3, 4, 5, 6) // 列→サーバー曜日(1=月..7=日)
    private val hourStart = 6
    private val hourEnd = 23   // 6:00〜23:00 を表示
    private val snapMin = 5    // ドラッグ移動時のスナップ（分）
    private val dayStartMin get() = hourStart * 60
    private val dayEndMin get() = hourEnd * 60

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_recurring, container, false)
        swipe = root.findViewById(R.id.swipeRefresh)
        grid = root.findViewById(R.id.gridContainer)
        dayHeader = root.findViewById(R.id.dayHeader)
        swipe.setOnRefreshListener { load() }
        // 週間ルーチンのカレンダー同期は廃止（&o/&h タグによるカレンダー読み取り方式へ移行）
        root.findViewById<Button>(R.id.calendarSyncButton).visibility = View.GONE
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun load() {
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.getTimetable(ctx) },
            onSuccess = { list ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                slots = list
                buildGrid()
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                Toast.makeText(requireContext(), "読み込みエラー: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun typeColor(type: String): Int = when (type) {
        "class" -> Color.parseColor("#4A90E2")
        "work" -> Color.parseColor("#E2904A")
        "meal" -> Color.parseColor("#E2C84A")
        "sleep" -> Color.parseColor("#8A4AE2")
        else -> Color.parseColor("#90E24A")
    }

    /**
     * Googleカレンダーの週表示風。時間を比例配置（分単位）で描画し、
     * ブロックは長押しドラッグで別の曜日・時刻へ移動できる。タップで編集メニュー。
     */
    private fun buildGrid() {
        val ctx = requireContext()
        val pxPerHour = dp(64)
        val pxPerMin = pxPerHour / 60f
        val hours = hourEnd - hourStart
        val totalH = pxPerHour * hours
        val axisW = dp(38)
        val lineColor = Color.parseColor("#33FFFFFF")
        val bgGrid = Color.parseColor("#141428")

        // 曜日ヘッダ（左に時間軸ぶんのスペーサ＋7列）
        dayHeader.removeAllViews()
        val spacer = View(ctx)
        spacer.layoutParams = LinearLayout.LayoutParams(axisW, dp(22))
        dayHeader.addView(spacer)
        for (c in 0 until 7) {
            val tv = TextView(ctx)
            tv.layoutParams = LinearLayout.LayoutParams(0, dp(22), 1f)
            tv.gravity = Gravity.CENTER
            tv.text = dayNames[c]
            tv.textSize = 12f
            tv.setTextColor(if (c == 0) Color.parseColor("#E2904A") else Color.WHITE)
            dayHeader.addView(tv)
        }

        grid.removeAllViews()
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL

        // 時間軸
        val axis = LinearLayout(ctx)
        axis.orientation = LinearLayout.VERTICAL
        axis.layoutParams = LinearLayout.LayoutParams(axisW, totalH)
        for (h in hourStart until hourEnd) {
            val t = TextView(ctx)
            t.layoutParams = LinearLayout.LayoutParams(axisW, pxPerHour)
            t.text = "${h}:00"
            t.textSize = 9f
            t.setTextColor(Color.parseColor("#AAAAAA"))
            t.gravity = Gravity.END or Gravity.TOP
            t.setPadding(0, 0, dp(3), 0)
            axis.addView(t)
        }
        row.addView(axis)

        // 予定エリア
        val area = FrameLayout(ctx)
        area.layoutParams = LinearLayout.LayoutParams(0, totalH, 1f)
        area.setBackgroundColor(bgGrid)
        for (i in 0..hours) {
            val ln = View(ctx)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(1).coerceAtLeast(1))
            lp.topMargin = (i * pxPerHour) - if (i == hours) dp(1) else 0
            ln.layoutParams = lp
            ln.setBackgroundColor(lineColor)
            area.addView(ln)
        }
        row.addView(area)
        grid.addView(row)

        // 列幅はレイアウト後に確定するので post で配置
        area.post {
            if (!isAdded) return@post
            val colW = area.width / 7f
            if (colW <= 0f) return@post

            for (k in 1 until 7) {
                val v = View(ctx)
                val lp = FrameLayout.LayoutParams(dp(1).coerceAtLeast(1), totalH)
                lp.leftMargin = (k * colW).toInt()
                v.layoutParams = lp
                v.setBackgroundColor(lineColor)
                area.addView(v)
            }

            for (slot in slots) {
                val col = colToServer.indexOf(slot.dayOfWeek).let { if (it >= 0) it else 0 }
                val startC = slot.startMin().coerceIn(dayStartMin, dayEndMin - snapMin)
                val endC = slot.endMin().coerceIn(startC + snapMin, dayEndMin)
                val block = makeBlock(slot)
                val lp = FrameLayout.LayoutParams(
                    (colW).toInt() - dp(2),
                    maxOf(dp(16), ((endC - startC) * pxPerMin).toInt())
                )
                lp.leftMargin = (col * colW).toInt() + dp(1)
                lp.topMargin = ((startC - dayStartMin) * pxPerMin).toInt()
                block.layoutParams = lp
                attachBlockTouch(block, slot, colW, pxPerMin)
                area.addView(block)
            }

            // 空き場所タップで追加（座標から曜日・時刻を算出）
            area.isClickable = true
            var downX = 0f; var downY = 0f
            area.setOnTouchListener { _, e -> downX = e.x; downY = e.y; false }
            area.setOnClickListener {
                val col = (downX / colW).toInt().coerceIn(0, 6)
                val minute = (dayStartMin + (downY / pxPerMin)).toInt().coerceIn(dayStartMin, dayEndMin - 30)
                onEmptyCellTap(colToServer[col], minute / 60)
            }
        }
    }

    private fun makeBlock(slot: TimetableSlot): TextView {
        val tv = TextView(requireContext())
        tv.text = "${slot.label}\n${slot.startTime}〜${slot.endTime}"
        tv.textSize = 9f
        tv.setTextColor(Color.WHITE)
        tv.setBackgroundColor(typeColor(slot.slotType))
        tv.setPadding(dp(3), dp(2), dp(2), dp(2))
        tv.maxLines = 3
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        return tv
    }

    /**
     * 予定ブロックの操作：
     *  - 軽いタップ → 編集メニュー（編集/複製/削除）
     *  - 長押ししてからドラッグ → 別の曜日・時刻へ移動（5分刻みにスナップ）
     * ドラッグ確定までは縦スクロールを妨げない。
     */
    private fun attachBlockTouch(block: View, slot: TimetableSlot, colW: Float, pxPerMin: Float) {
        var downX = 0f; var downY = 0f
        var startLeft = 0; var startTop = 0
        var dragMode = false

        val gd = android.view.GestureDetector(requireContext(),
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    showSlotMenu(slot); return true
                }
                override fun onLongPress(e: android.view.MotionEvent) {
                    dragMode = true
                    downX = e.rawX; downY = e.rawY
                    val lp = block.layoutParams as FrameLayout.LayoutParams
                    startLeft = lp.leftMargin; startTop = lp.topMargin
                    swipe.isEnabled = false
                    block.parent.requestDisallowInterceptTouchEvent(true)
                    block.bringToFront()
                    block.alpha = 0.85f
                }
            })

        block.isClickable = true
        block.setOnTouchListener { v, e ->
            gd.onTouchEvent(e)
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (dragMode) { v.translationX = e.rawX - downX; v.translationY = e.rawY - downY; true } else false
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (dragMode) {
                        val dx = e.rawX - downX; val dy = e.rawY - downY
                        v.translationX = 0f; v.translationY = 0f
                        v.alpha = 1f
                        dragMode = false
                        swipe.isEnabled = true
                        v.parent.requestDisallowInterceptTouchEvent(false)
                        val newCol = Math.round((startLeft + dx) / colW).coerceIn(0, 6)
                        val dur = slot.endMin() - slot.startMin()
                        var newStart = dayStartMin + Math.round(((startTop + dy) / pxPerMin) / snapMin) * snapMin
                        newStart = newStart.coerceIn(dayStartMin, dayEndMin - dur)
                        val newDay = colToServer[newCol]
                        if (newDay != slot.dayOfWeek || newStart != slot.startMin()) {
                            val s = String.format(Locale.US, "%02d:%02d", newStart / 60, newStart % 60)
                            val en = newStart + dur
                            val eStr = String.format(Locale.US, "%02d:%02d", en / 60, en % 60)
                            updateSlot(slot.id, newDay, slot.label, slot.slotType, slot.location, s, eStr)
                        }
                        true
                    } else {
                        swipe.isEnabled = true
                        false
                    }
                }
                else -> false
            }
        }
    }

    private fun serverDayName(d: Int): String = dayNames[if (d == 7) 0 else d]

    /** 空きマスをタップ：コピー中の予定があれば貼り付けを提案、無ければ新規追加。 */
    private fun onEmptyCellTap(serverDay: Int, hour: Int) {
        val copied = copiedSlot
        if (copied == null) {
            showSlotDialog(serverDay, hour, null)
            return
        }
        val dur = copied.endMin() - copied.startMin()
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("「${copied.label}」を貼り付け")
            .setMessage("${serverDayName(serverDay)}曜 ${hour}:00 に同じ内容で複製しますか？")
            .setPositiveButton("貼り付け") { _, _ ->
                val start = String.format(Locale.US, "%02d:00", hour)
                val endMinTotal = minOf(hour * 60 + dur, 23 * 60 + 59)
                val end = String.format(Locale.US, "%02d:%02d", endMinTotal / 60, endMinTotal % 60)
                addSlot(serverDay, copied.label, copied.slotType, copied.location, start, end)
            }
            .setNeutralButton("新規追加") { _, _ -> showSlotDialog(serverDay, hour, null) }
            .setNegativeButton("コピー解除") { _, _ ->
                copiedSlot = null
                Toast.makeText(ctx, "コピーを解除しました", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /** 既存の予定をタップしたときのメニュー（編集／複製／削除） */
    private fun showSlotMenu(slot: TimetableSlot) {
        val items = arrayOf("編集", "複製（別の日にコピー）", "削除")
        AlertDialog.Builder(requireContext())
            .setTitle("「${slot.label}」${slot.startTime}〜${slot.endTime}")
            .setItems(items) { _, which ->
                val hour = slot.startMin() / 60
                when (which) {
                    0 -> showSlotDialog(slot.dayOfWeek, hour, slot, null)   // 編集
                    1 -> showSlotDialog(slot.dayOfWeek, hour, null, slot)   // 複製
                    2 -> confirmDelete(slot)                                 // 削除
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /**
     * 予定の追加／編集／複製ダイアログ。
     * existing 非null → 編集（保存はPUT、削除ボタンあり）。
     * prefill 非null（existing null）→ 複製（値をコピーして新規追加、曜日を選び直せる）。
     * どちらもnull → 空きマスからの新規追加。
     * 曜日スピナーを設けたので、編集時は別の曜日へ移動、複製時は別の曜日へコピーもできる。
     */
    private fun showSlotDialog(serverDay: Int, hour: Int, existing: TimetableSlot?, prefill: TimetableSlot? = null) {
        val ctx = requireContext()
        val src = existing ?: prefill   // 値の供給元（編集元 or 複製元）
        val pad = dp(20)
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        // 曜日スピナー（日〜土）。初期選択は対象の曜日。
        val daySpinner = Spinner(ctx)
        daySpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, dayNames)
        val initDay = src?.dayOfWeek ?: serverDay
        val initDayPos = colToServer.indexOf(initDay).let { if (it >= 0) it else 0 }
        daySpinner.setSelection(initDayPos)
        inner.addView(TextView(ctx).apply { text = "曜日"; textSize = 12f })
        inner.addView(daySpinner)

        val nameInput = EditText(ctx).apply {
            hint = "名前（例：数学の授業、睡眠）"
            setText(src?.label ?: "")
        }
        inner.addView(nameInput)

        val typeSpinner = Spinner(ctx)
        val typeLabels = arrayOf("授業", "バイト", "食事", "睡眠", "その他")
        val typeValues = arrayOf("class", "work", "meal", "sleep", "other")
        typeSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, typeLabels)
        if (src != null) {
            val idx = typeValues.indexOf(src.slotType)
            if (idx >= 0) typeSpinner.setSelection(idx)
        }
        inner.addView(typeSpinner)

        val locSpinner = Spinner(ctx)
        val locLabels = arrayOf("家にいる", "外にいる")
        val locValues = arrayOf("home", "out")
        locSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, locLabels)
        if (src != null) {
            val idx = locValues.indexOf(src.location)
            if (idx >= 0) locSpinner.setSelection(idx)
        }
        inner.addView(locSpinner)

        // 時刻の初期値：編集/複製時は元の時刻、追加時は押したマスの時間
        var startTime = src?.startTime?.takeIf { it.isNotEmpty() }
            ?: String.format(Locale.US, "%02d:00", hour)
        var endTime = src?.endTime?.takeIf { it.isNotEmpty() }
            ?: String.format(Locale.US, "%02d:00", minOf(hour + 1, 23))
        val startBtn = Button(ctx).apply { text = "開始: $startTime" }
        val endBtn = Button(ctx).apply { text = "終了: $endTime" }
        startBtn.setOnClickListener {
            val p = startTime.split(":")
            TimePickerDialog(ctx, { _, hh, mm ->
                startTime = String.format(Locale.US, "%02d:%02d", hh, mm)
                startBtn.text = "開始: $startTime"
            }, p[0].toInt(), p[1].toInt(), true).show()
        }
        endBtn.setOnClickListener {
            val p = endTime.split(":")
            TimePickerDialog(ctx, { _, hh, mm ->
                endTime = String.format(Locale.US, "%02d:%02d", hh, mm)
                endBtn.text = "終了: $endTime"
            }, p[0].toInt(), p[1].toInt(), true).show()
        }
        inner.addView(startBtn)
        inner.addView(endBtn)

        val scroll = android.widget.ScrollView(ctx).apply { addView(inner) }

        val isEdit = existing != null
        val title = when {
            isEdit -> "予定を編集"
            prefill != null -> "予定を複製"
            else -> "${serverDayName(serverDay)}曜日に追加"
        }
        val builder = AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(if (isEdit) "保存" else "追加") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "名前を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (endTime <= startTime) {
                    Toast.makeText(ctx, "終了は開始より後にしてください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val chosenDay = colToServer[daySpinner.selectedItemPosition]
                val slotType = typeValues[typeSpinner.selectedItemPosition]
                val location = locValues[locSpinner.selectedItemPosition]
                if (isEdit) {
                    updateSlot(existing!!.id, chosenDay, name, slotType, location, startTime, endTime)
                } else {
                    addSlot(chosenDay, name, slotType, location, startTime, endTime)
                }
            }
            .setNegativeButton("キャンセル", null)
        if (isEdit) {
            builder.setNeutralButton("削除") { _, _ -> confirmDelete(existing!!) }
        }
        builder.show()
    }

    private fun addSlot(day: Int, name: String, slotType: String, location: String, start: String, end: String) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.addTimetableSlot(ctx, day, start, end, name, slotType, location) },
            onSuccess = {
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "追加しました", Toast.LENGTH_SHORT).show()
                load()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "追加に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun updateSlot(id: Long, day: Int, name: String, slotType: String, location: String, start: String, end: String) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.updateTimetableSlot(ctx, id, day, start, end, name, slotType, location) },
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

    private fun confirmDelete(slot: TimetableSlot) {
        val locLabel = if (slot.location == "out") "外" else "家"
        AlertDialog.Builder(requireContext())
            .setMessage("「${slot.label}」(${slot.startTime}〜${slot.endTime}, ${locLabel}) を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                val ctx = requireContext().applicationContext
                Api.async(
                    work = { Api.deleteTimetableSlot(ctx, slot.id) },
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
