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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.AddTaskActivity
import dev.togar.dynasched.Prefs
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
    private lateinit var reorderBar: View
    private lateinit var sortButton: Button
    private lateinit var touchHelper: ItemTouchHelper

    /** 直近に読み込んだ生データ。並び替え・折りたたみは読み直さずに組み直す */
    private var loaded: List<HobbyItem> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_hobby, container, false)

        empty = root.findViewById(R.id.emptyText)
        swipe = root.findViewById(R.id.swipeRefresh)
        reorderBar = root.findViewById(R.id.reorderBar)
        sortButton = root.findViewById(R.id.sortButton)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler)
        val addButton = root.findViewById<Button>(R.id.addTaskButton)

        adapter = HobbyAdapter(
            onToggle = { item, checked -> toggle(item, checked) },
            onAddChild = { item -> openAddChild(item) },
            onDelete = { item -> confirmDelete(item) },
            onEdit = { item -> openEdit(item) },
            onCollapse = { item -> toggleCollapse(item) },
            onLongPress = { startReorder() }
        )
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        touchHelper = ItemTouchHelper(ReorderCallback())
        touchHelper.attachToRecyclerView(recycler)

        addButton.setOnClickListener {
            startActivity(Intent(requireContext(), AddTaskActivity::class.java))
        }
        sortButton.setOnClickListener { showViewMenu() }
        root.findViewById<Button>(R.id.reorderDoneButton).setOnClickListener { endReorder() }

        swipe.setOnRefreshListener { load() }
        updateSortLabel()
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onPause() {
        super.onPause()
        // 画面を離れたら並び替えモードは畳む（戻ってきて誤操作するのを防ぐ）
        if (adapter.reordering) endReorder()
    }

    // ---- 表示の設定 ----

    private fun sortMode(): TaskSort = TaskSort.from(Prefs.taskSort(requireContext()))

    private fun updateSortLabel() {
        val done = if (Prefs.doneAtBottom(requireContext())) "済↓" else "済＝"
        sortButton.text = "${shortLabel(sortMode())} / $done"
    }

    private fun shortLabel(s: TaskSort) = when (s) {
        TaskSort.MANUAL -> "手動"
        TaskSort.NEWEST -> "新しい順"
        TaskSort.OLDEST -> "古い順"
        TaskSort.PRIORITY -> "優先度順"
        TaskSort.LONGEST -> "長い順"
        TaskSort.SHORTEST -> "短い順"
    }

    /** 並び順と「完了の見せ方」をまとめて選ぶ */
    private fun showViewMenu() {
        val ctx = requireContext()
        val sorts = TaskSort.entries
        val doneBottom = Prefs.doneAtBottom(ctx)
        val labels = sorts.map { if (it == sortMode()) "● ${it.label}" else "　${it.label}" } +
            listOf(
                if (doneBottom) "● 完了したものを下にまとめる" else "　完了したものを下にまとめる",
                if (!doneBottom) "● 完了したものはその場に薄く残す" else "　完了したものはその場に薄く残す"
            ) +
            listOf("　すべて畳む", "　すべて開く")

        AlertDialog.Builder(ctx)
            .setTitle("並び順と表示")
            .setItems(labels.toTypedArray()) { _, i ->
                when {
                    i < sorts.size -> Prefs.setTaskSort(ctx, sorts[i].name)
                    i == sorts.size -> Prefs.setDoneAtBottom(ctx, true)
                    i == sorts.size + 1 -> Prefs.setDoneAtBottom(ctx, false)
                    i == sorts.size + 2 -> Prefs.setCollapsed(ctx, allParentIds())
                    else -> Prefs.setCollapsed(ctx, emptySet())
                }
                updateSortLabel()
                render()
            }
            .setNegativeButton("閉じる", null)
            .show()
    }

    private fun allParentIds(): Set<Long> =
        loaded.mapNotNull { it.parentId }.toSet()

    private fun toggleCollapse(item: HobbyItem) {
        val ctx = requireContext()
        val cur = Prefs.collapsed(ctx).toMutableSet()
        if (!cur.add(item.id)) cur.remove(item.id)
        Prefs.setCollapsed(ctx, cur)
        render()
    }

    // ---- 読み込みと描画 ----

    private fun load() {
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).getHobby(ctx) },
            onSuccess = { all ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                loaded = all
                render()
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                empty.visibility = View.VISIBLE
                empty.text = "読み込みエラー: ${Api.friendlyMessage(e)}"
            }
        )
    }

    /** 読み直さずに並べ直す。並び順や折りたたみを変えたときはこちらだけ */
    private fun render() {
        if (!isAdded) return
        val rows = TaskList.build(
            loaded, sortMode(), Prefs.collapsed(requireContext()),
            Prefs.doneAtBottom(requireContext())
        )
        adapter.submit(rows)
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = "タスクはありません"
    }

    // ---- 並び替えモード ----

    private fun startReorder() {
        if (adapter.reordering) return
        val ctx = requireContext().applicationContext
        if (!Repo.current(ctx).supportsReorder) {
            Toast.makeText(requireContext(), "並び替えは端末内モードだけです", Toast.LENGTH_SHORT).show()
            return
        }
        adapter.reordering = true
        reorderBar.visibility = View.VISIBLE
        swipe.isEnabled = false   // 下に引く操作がドラッグと喧嘩する
    }

    private fun endReorder() {
        adapter.reordering = false
        reorderBar.visibility = View.GONE
        swipe.isEnabled = true
        load()
    }

    /**
     * 並び替えモード中だけ効くドラッグとスワイプ。
     *
     * - 上下：同じ親を持つもの同士だけ入れ替える（親をまたぐと階層が壊れる）
     * - 右へ払う：すぐ上の兄弟の子になる
     * - 左へ払う：子をやめて親の隣に上がる
     *
     * 優先度順で並べている時に上下へ動かすと、**落とした先の優先度に合わせる**。
     * 優先度は低中高の3段しかないので、順番そのものより「どの塊に入れたか」が答えになる。
     */
    private inner class ReorderCallback : ItemTouchHelper.Callback() {

        override fun isLongPressDragEnabled(): Boolean = adapter.reordering
        override fun isItemViewSwipeEnabled(): Boolean = adapter.reordering

        override fun getMovementFlags(
            rv: RecyclerView, holder: RecyclerView.ViewHolder
        ): Int {
            if (!adapter.reordering) return 0
            return makeMovementFlags(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                ItemTouchHelper.START or ItemTouchHelper.END
            )
        }

        override fun onMove(
            rv: RecyclerView, holder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean {
            val from = holder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            // 兄弟同士でなければ動かさない。ここを許すと木がねじれる
            if (adapter.rows().getOrNull(from)?.item?.parentId !=
                adapter.rows().getOrNull(to)?.item?.parentId
            ) return false
            adapter.moveVisually(from, to)
            return true
        }

        override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(rv, holder)
            if (!adapter.reordering) return
            persistOrder(holder.bindingAdapterPosition)
        }

        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {
            val pos = holder.bindingAdapterPosition
            val rows = adapter.rows()
            val item = rows.getOrNull(pos)?.item ?: return
            if (direction == ItemTouchHelper.END) {
                val newParent = TaskList.indentTarget(rows, pos)
                if (newParent == null) {
                    Toast.makeText(requireContext(), "上に兄弟がないので子にできません", Toast.LENGTH_SHORT).show()
                    render(); return
                }
                applyParent(item.id, newParent)
            } else {
                if (!TaskList.canOutdent(rows, pos)) {
                    Toast.makeText(requireContext(), "もう最上位です", Toast.LENGTH_SHORT).show()
                    render(); return
                }
                applyParent(item.id, TaskList.outdentParent(rows, pos))
            }
        }
    }

    /** ドラッグを離した時点の並びを保存する */
    private fun persistOrder(droppedAt: Int) {
        val rows = adapter.rows()
        val moved = rows.getOrNull(droppedAt)?.item ?: return
        val siblings = rows.filter { it.item.parentId == moved.parentId }.map { it.item.id }
        val ctx = requireContext().applicationContext
        val newPriority =
            if (sortMode() == TaskSort.PRIORITY)
                TaskList.priorityAfterMove(rows, moved.id, droppedAt)
            else null

        Api.async(
            work = {
                val repo = Repo.current(ctx)
                repo.reorderHobby(ctx, siblings)
                if (newPriority != null && newPriority != moved.priority) {
                    repo.setHobbyPriority(ctx, moved.id, newPriority)
                }
            },
            onSuccess = {
                if (!isAdded) return@async
                if (newPriority != null && newPriority != moved.priority) {
                    val label = when { newPriority >= 8 -> "高"; newPriority <= 3 -> "低"; else -> "中" }
                    Toast.makeText(requireContext(), "「${moved.name}」の優先度を$label にしました", Toast.LENGTH_SHORT).show()
                }
                loadKeepingMode()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "並び替えを保存できませんでした: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                loadKeepingMode()
            }
        )
    }

    private fun applyParent(id: Long, parentId: Long?) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).setHobbyParent(ctx, id, parentId) },
            onSuccess = { if (isAdded) loadKeepingMode() },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "変更できませんでした: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
                loadKeepingMode()
            }
        )
    }

    /** 並び替えモードを抜けずに読み直す */
    private fun loadKeepingMode() {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).getHobby(ctx) },
            onSuccess = { all ->
                if (!isAdded) return@async
                loaded = all
                render()
            },
            onError = { /* 直前の表示のままにする */ }
        )
    }

    private fun toggle(item: HobbyItem, checked: Boolean) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).setHobbyCompleted(ctx, item.id, checked) },
            onSuccess = { if (isAdded) load() },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "更新に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
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
                    work = { Repo.current(ctx).editHobby(appCtx, item.id, name, total, priority, location, note, color) },
                    onSuccess = {
                        if (!isAdded) return@async
                        Toast.makeText(requireContext(), "更新しました", Toast.LENGTH_SHORT).show()
                        load()
                    },
                    onError = { e ->
                        if (!isAdded) return@async
                        Toast.makeText(requireContext(), "更新に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
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
            work = { Repo.current(ctx).deleteHobby(ctx, item.id) },
            onSuccess = { if (isAdded) load() },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "削除に失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }
}
