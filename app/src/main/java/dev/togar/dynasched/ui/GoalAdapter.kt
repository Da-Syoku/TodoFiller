package dev.togar.dynasched.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.R
import dev.togar.dynasched.api.GoalItem

/** 目標一覧の表示 */
class GoalAdapter(
    private val onEdit: (GoalItem) -> Unit,
    private val onDelete: (GoalItem) -> Unit
) : RecyclerView.Adapter<GoalAdapter.VH>() {

    private val items = ArrayList<GoalItem>()

    fun submit(list: List<GoalItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bar: View = view.findViewById(R.id.goalBar)
        val name: TextView = view.findViewById(R.id.goalName)
        val deadline: TextView = view.findViewById(R.id.goalDeadline)
        val remain: TextView = view.findViewById(R.id.goalRemain)
        val edit: Button = view.findViewById(R.id.editGoalButton)
        val delete: Button = view.findViewById(R.id.deleteGoalButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_goal, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val g = items[position]
        holder.name.text = g.name

        val hours = g.totalMinutes / 60
        val mins = g.totalMinutes % 60
        val target = if (hours > 0) "${hours}時間${if (mins > 0) "${mins}分" else ""}" else "${mins}分"
        // 例: "期日 07/20 (月) 09:00 ・ 目標5時間 ・ 演習 ・ 進捗40%"
        holder.deadline.text = "期日 ${g.deadlineLabel()} ・ 目標${target} ・ ${g.studyTypeLabel()} ・ 進捗${g.progress}%"

        val days = g.daysRemaining()
        holder.remain.text = when {
            days < 0 -> "期限切れ"
            days == 0L -> "今日"
            else -> "あと${days}日"
        }

        try { holder.bar.setBackgroundColor(Color.parseColor(g.color)) } catch (e: Exception) {}
        holder.edit.setOnClickListener { onEdit(g) }
        holder.delete.setOnClickListener { onDelete(g) }
        holder.itemView.setOnClickListener { onEdit(g) }
    }
}
