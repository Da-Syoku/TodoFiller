package dev.togar.dynasched.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.R
import dev.togar.dynasched.api.MaterialItem
import dev.togar.dynasched.api.PlanItem

/**
 * 教材一覧。各行に「間に合う/間に合わない」を出す。
 *
 * 前回テストで従えなかった理由が「足りるか確信が持てない」だったので、
 * 一覧を眺めた時点でその答えが目に入るようにしてある。
 */
class MaterialAdapter(
    private val onEdit: (MaterialItem) -> Unit,
    private val onRecord: (MaterialItem) -> Unit
) : RecyclerView.Adapter<MaterialAdapter.VH>() {

    private val items = ArrayList<MaterialItem>()
    private var plan: Map<Long, PlanItem> = emptyMap()

    fun submit(list: List<MaterialItem>, planItems: List<PlanItem>) {
        items.clear()
        items.addAll(list)
        plan = planItems.associateBy { it.id }
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bar: View = view.findViewById(R.id.materialBar)
        val name: TextView = view.findViewById(R.id.materialName)
        val progress: TextView = view.findViewById(R.id.materialProgress)
        val verdict: TextView = view.findViewById(R.id.materialVerdict)
        val trimmed: TextView = view.findViewById(R.id.materialTrimmed)
        val remain: TextView = view.findViewById(R.id.materialRemain)
        val record: Button = view.findViewById(R.id.recordButton)
        val edit: Button = view.findViewById(R.id.editMaterialButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_material, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val m = items[position]
        val p = plan[m.id]

        holder.name.text = if (m.subject.isNotEmpty()) "${m.subject} ${m.name}" else m.name

        // 実測が溜まる前は「（仮）」と出す。推測値を実測のような顔で見せない。
        val paceText = if (p?.measured == true) "実測 ${m.paceLabel()}" else "${m.paceLabel()}（仮）"
        holder.progress.text = "${m.progressLabel()} ・ $paceText ・ ${m.studyTypeLabel()}"

        if (p == null) {
            holder.verdict.text = "計画を計算中"
            holder.verdict.setTextColor(DIM)
        } else if (p.blockedByPrereq) {
            holder.verdict.text = "前提の教材が1周終わるまで出ません"
            holder.verdict.setTextColor(DIM)
        } else {
            holder.verdict.text = p.verdictLabel()
            holder.verdict.setTextColor(if (p.ok) OK else NG)
        }

        // 自動で削った内容と、1周目が中間期限に間に合わない警告
        val notes = ArrayList<String>()
        if (p != null && p.trimmed.isNotEmpty()) notes.add(p.trimmed.joinToString(" → "))
        if (p != null && p.hasFirstRound && !p.firstRoundOk) {
            notes.add("1周目が中間期限に間に合いません（不足 ${PlanItem.hm(-p.firstRoundSlack)}）")
        }
        holder.trimmed.text = notes.joinToString("\n")
        holder.trimmed.visibility = if (notes.isEmpty()) View.GONE else View.VISIBLE

        val days = m.daysRemaining()
        holder.remain.text = when {
            days < 0 -> "期限切れ"
            days == 0L -> "今日"
            else -> "あと${days}日"
        }

        try { holder.bar.setBackgroundColor(Color.parseColor(m.color)) } catch (e: Exception) {}
        holder.record.setOnClickListener { onRecord(m) }
        holder.edit.setOnClickListener { onEdit(m) }
        holder.itemView.setOnClickListener { onEdit(m) }
    }

    private companion object {
        val OK = Color.parseColor("#4CAF50")
        val NG = Color.parseColor("#E2574A")
        val DIM = Color.parseColor("#9E9E9E")
    }
}
