package dev.togar.dynasched.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.togar.dynasched.AddTaskActivity
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.HobbyItem

/**
 * 単発タスク（階層Todo）画面。
 * 最上位タスク＝グループ、その下に子タスク（分割）をぶら下げられる（2階層）。
 */
class HobbyFragment : Fragment() {

    private lateinit var adapter: HobbyAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var empty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_hobby, container, false)

        empty = root.findViewById(R.id.emptyText)
        swipe = root.findViewById(R.id.swipeRefresh)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler)
        val addButton = root.findViewById<Button>(R.id.addTaskButton)

        adapter = HobbyAdapter(
            onToggle = { item, checked -> toggle(item, checked) },
            onAddChild = { item -> openAddChild(item) },
            onDelete = { item -> confirmDelete(item) },
            onEdit = { item -> openEdit(item) }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        // 追加ボタン → 専用の追加画面へ遷移
        addButton.setOnClickListener {
            startActivity(Intent(requireContext(), AddTaskActivity::class.java))
        }

        swipe.setOnRefreshListener { load() }
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    /** フラットなリストを深さ優先で並べ、level と hasChildren を付与する（任意の深さ） */
    private fun buildOrdered(all: List<HobbyItem>): List<HobbyItem> {
        val byParent = all.groupBy { it.parentId }
        val existingIds = all.map { it.id }.toHashSet()
        val result = ArrayList<HobbyItem>()
        val visited = HashSet<Long>()

        fun addSubtree(item: HobbyItem, level: Int) {
            if (!visited.add(item.id)) return // 循環参照ガード
            item.level = level
            val children = byParent[item.id].orEmpty()
            item.hasChildren = children.isNotEmpty()
            result.add(item)
            for (c in children) addSubtree(c, level + 1)
        }

        // ルート＝parent_idがnull、または親が存在しない孤児
        for (item in all) {
            if (item.parentId == null || !existingIds.contains(item.parentId)) {
                addSubtree(item, 0)
            }
        }
        return result
    }

    private fun load() {
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.getHobby(ctx) },
            onSuccess = { all ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                val ordered = buildOrdered(all)
                adapter.submit(ordered)
                empty.visibility = if (ordered.isEmpty()) View.VISIBLE else View.GONE
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                empty.visibility = View.VISIBLE
                empty.text = "読み込みエラー: ${e.message}"
            }
        )
    }

    private fun toggle(item: HobbyItem, checked: Boolean) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.setHobbyCompleted(ctx, item.id, checked) },
            onSuccess = { if (isAdded) load() },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "更新に失敗: ${e.message}", Toast.LENGTH_LONG).show()
                load()
            }
        )
    }

    /** 単発タスクをタップしたときの編集ダイアログ（名前・必要時間・場所・優先度・色・メモ） */
    private fun openEdit(item: HobbyItem) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        fun label(t: String) = android.widget.TextView(ctx).apply {
            text = t; textSize = 12f; setPadding(0, pad / 2, 0, 0)
        }

        val nameInput = android.widget.EditText(ctx).apply { setText(item.name); hint = "タスク名" }
        root.addView(nameInput)

        root.addView(label("必要時間"))
        val durationPicker = DurationPickerView(ctx, item.durationMinutes)
        root.addView(durationPicker)

        root.addView(label("場所"))
        val locValues = arrayOf("anywhere", "home", "out")
        val locSpinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("どこでも", "家のみ", "外のみ"))
            setSelection(locValues.indexOf(item.location).let { if (it >= 0) it else 0 })
        }
        root.addView(locSpinner)

        root.addView(label("優先度"))
        val prioValues = listOf(3, 5, 8)
        val prioNames = arrayOf("低", "中", "高")
        val prioSlider = LabeledSlider(ctx, prioValues, item.priority) { v ->
            prioNames[prioValues.indexOf(v).coerceIn(0, 2)]
        }
        root.addView(prioSlider)

        root.addView(label("カレンダーの色"))
        val colorPalette = ColorPaletteView(ctx, dev.togar.dynasched.api.CalColor.indexOfId(item.color))
        root.addView(colorPalette)

        root.addView(label("メモ"))
        val noteInput = android.widget.EditText(ctx).apply {
            setText(item.note)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(noteInput)

        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("タスクを編集")
            .setView(scroll)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(ctx, "タスク名を入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                var total = durationPicker.totalMinutes
                if (total <= 0) total = 30
                val location = locValues[locSpinner.selectedItemPosition]
                val priority = prioSlider.value
                val color = dev.togar.dynasched.api.CalColor.idAt(colorPalette.selectedIndex)
                val note = noteInput.text.toString().trim()
                val appCtx = requireContext().applicationContext
                Api.async(
                    work = { Api.editHobby(appCtx, item.id, name, total, priority, location, note, color) },
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
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun openAddChild(parent: HobbyItem) {
        val intent = Intent(requireContext(), AddTaskActivity::class.java)
        intent.putExtra(AddTaskActivity.EXTRA_PARENT_ID, parent.id)
        intent.putExtra(AddTaskActivity.EXTRA_PARENT_NAME, parent.name)
        startActivity(intent)
    }

    private fun confirmDelete(item: HobbyItem) {
        val msg = if (item.hasChildren)
            "「${item.name}」を削除しますか？（配下の子タスクもすべて削除されます）"
        else
            "「${item.name}」を削除しますか？"
        AlertDialog.Builder(requireContext())
            .setMessage(msg)
            .setPositiveButton("削除") { _, _ -> doDelete(item) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun doDelete(item: HobbyItem) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.deleteHobby(ctx, item.id) },
            onSuccess = { if (isAdded) load() },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "削除に失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }
}
