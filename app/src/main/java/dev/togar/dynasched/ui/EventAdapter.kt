package dev.togar.dynasched.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import dev.togar.dynasched.R
import dev.togar.dynasched.api.ScheduledEvent

/**
 * 予定リスト用アダプタ。
 * showDate=true のとき日付ラベルも表示する（週間ビュー用）。
 */
class EventAdapter(
    private val showDate: Boolean,
    private val onComplete: (ScheduledEvent) -> Unit
) : RecyclerView.Adapter<EventAdapter.VH>() {

    private val items = ArrayList<ScheduledEvent>()

    fun submit(list: List<ScheduledEvent>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.eventTime)
        val title: TextView = view.findViewById(R.id.eventTitle)
        val sub: TextView = view.findViewById(R.id.eventSub)
        val completeBtn: Button = view.findViewById(R.id.completeButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ev = items[position]
        holder.time.text = ev.startTimeLabel()

        val titlePrefix = if (ev.isCompleted) "✓ " else ""
        holder.title.text = titlePrefix + ev.title

        val typeLabel = when (ev.eventType) {
            "study" -> "学習"
            "hobby" -> "趣味"
            "test" -> "テスト"
            else -> ev.eventType
        }
        holder.sub.text = if (showDate) "${ev.startDateLabel()} ・ $typeLabel" else typeLabel

        if (ev.isCompleted) {
            holder.completeBtn.visibility = View.GONE
        } else {
            holder.completeBtn.visibility = View.VISIBLE
            holder.completeBtn.setOnClickListener { onComplete(ev) }
        }
    }
}
