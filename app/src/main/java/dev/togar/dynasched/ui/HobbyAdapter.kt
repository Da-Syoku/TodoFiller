package dev.togar.dynasched.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.R
import dev.togar.dynasched.api.HobbyItem

/**
 * 単発タスクの階層リスト。level に応じてインデント表示。
 * level 0（親）のみ「＋子」ボタンを表示する（2階層モデル）。
 */
class HobbyAdapter(
    private val onToggle: (HobbyItem, Boolean) -> Unit,
    private val onAddChild: (HobbyItem) -> Unit,
    private val onDelete: (HobbyItem) -> Unit,
    private val onEdit: (HobbyItem) -> Unit
) : RecyclerView.Adapter<HobbyAdapter.VH>() {

    private val items = ArrayList<HobbyItem>()

    fun submit(list: List<HobbyItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: LinearLayout = view.findViewById(R.id.itemRoot)
        val check: CheckBox = view.findViewById(R.id.checkBox)
        val title: TextView = view.findViewById(R.id.taskTitle)
        val sub: TextView = view.findViewById(R.id.taskSub)
        val textContainer: View = title.parent as View
        val addChild: Button = view.findViewById(R.id.addChildButton)
        val delete: Button = view.findViewById(R.id.deleteButton)
        val basePaddingStart: Int = view.paddingStart
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_hobby, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        // インデント（子タスクは右にずらす。深い階層でも溢れないよう1段24dp）
        val extra = (item.level * 24 * holder.itemView.resources.displayMetrics.density).toInt()
        holder.root.setPadding(
            holder.basePaddingStart + extra,
            holder.root.paddingTop,
            holder.root.paddingEnd,
            holder.root.paddingBottom
        )

        // チェックボックスはリスナを一旦外してから状態を設定（リサイクル対策）
        holder.check.setOnCheckedChangeListener(null)
        holder.check.isChecked = item.isCompleted
        holder.title.text = item.name
        // 完了なら打ち消し風にする
        holder.title.alpha = if (item.isCompleted) 0.5f else 1.0f

        // タイトル左にカレンダー色のドットを表示（葉タスクのみ）
        if (!item.hasChildren) {
            val d = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                val s = (10 * holder.itemView.resources.displayMetrics.density).toInt()
                setSize(s, s)
                setColor(dev.togar.dynasched.api.CalColor.colorFor(item.color))
            }
            d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight)
            holder.title.setCompoundDrawablesRelative(d, null, null, null)
            holder.title.compoundDrawablePadding =
                (8 * holder.itemView.resources.displayMetrics.density).toInt()
        } else {
            holder.title.setCompoundDrawablesRelative(null, null, null, null)
        }

        // 行のテキスト部分をタップで編集（葉タスクのみ）
        if (!item.hasChildren) {
            holder.textContainer.setOnClickListener { onEdit(item) }
        } else {
            holder.textContainer.setOnClickListener(null)
            holder.textContainer.isClickable = false
        }

        // 葉タスクのみ必要時間・場所を表示（親＝グループは非表示）
        if (item.hasChildren) {
            holder.sub.visibility = View.GONE
        } else {
            holder.sub.visibility = View.VISIBLE
            val h = item.durationMinutes / 60
            val m = item.durationMinutes % 60
            val dur = if (h > 0) "${h}時間${if (m > 0) "${m}分" else ""}" else "${m}分"
            val base = "$dur ・ ${item.locationLabel()}"
            // メモがあれば2行目に表示（長い場合は省略）
            holder.sub.text = if (item.note.isNotBlank()) {
                val memo = item.note.replace("\n", " ").let {
                    if (it.length > 40) it.substring(0, 40) + "…" else it
                }
                "$base\n📝 $memo"
            } else {
                base
            }
        }

        // 子を持つタスク（グループ）はチェック不可：葉タスクのみ完了できる
        if (item.hasChildren) {
            holder.check.visibility = View.INVISIBLE
        } else {
            holder.check.visibility = View.VISIBLE
            holder.check.setOnCheckedChangeListener { _, isChecked ->
                onToggle(item, isChecked)
            }
        }

        // どの階層でも子タスク（さらに細分化）を追加できる
        holder.addChild.visibility = View.VISIBLE
        holder.addChild.setOnClickListener { onAddChild(item) }
        holder.delete.setOnClickListener { onDelete(item) }
    }
}
