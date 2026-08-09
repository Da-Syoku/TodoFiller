package dev.togar.dynasched.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.R
import dev.togar.dynasched.api.TimetableSlot

/** 定期スロットのリスト表示 */
class SlotAdapter(
    private val onDelete: (TimetableSlot) -> Unit
) : RecyclerView.Adapter<SlotAdapter.VH>() {

    private val items = ArrayList<TimetableSlot>()

    fun submit(list: List<TimetableSlot>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val bar: View = view.findViewById(R.id.typeBar)
        val time: TextView = view.findViewById(R.id.slotTime)
        val label: TextView = view.findViewById(R.id.slotLabel)
        val type: TextView = view.findViewById(R.id.slotType)
        val delete: Button = view.findViewById(R.id.deleteSlotButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_slot, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        holder.time.text = "${s.startTime}\n${s.endTime}"
        holder.label.text = if (s.label.isNotEmpty()) s.label else "(無題)"
        val typeLabel = when (s.slotType) {
            "class" -> "授業"
            "work" -> "バイト"
            "meal" -> "食事"
            "sleep" -> "睡眠"
            else -> "その他"
        }
        holder.type.text = typeLabel
        holder.bar.setBackgroundColor(
            when (s.slotType) {
                "class" -> Color.parseColor("#4A90E2")
                "work" -> Color.parseColor("#E2904A")
                "meal" -> Color.parseColor("#E2C84A")
                "sleep" -> Color.parseColor("#8A4AE2")
                else -> Color.parseColor("#90E24A")
            }
        )
        holder.delete.setOnClickListener { onDelete(s) }
    }
}
