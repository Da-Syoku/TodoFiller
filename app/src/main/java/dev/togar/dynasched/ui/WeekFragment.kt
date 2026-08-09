package dev.togar.dynasched.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduledEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 今日から7日分の予定を表示する画面 */
class WeekFragment : Fragment() {

    private lateinit var adapter: EventAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var header: TextView
    private lateinit var empty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_list, container, false)

        header = root.findViewById(R.id.headerText)
        empty = root.findViewById(R.id.emptyText)
        swipe = root.findViewById(R.id.swipeRefresh)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler)

        header.text = "今週の予定（7日間）"
        adapter = EventAdapter(showDate = true) { ev -> complete(ev) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipe.setOnRefreshListener { load() }
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun load() {
        swipe.isRefreshing = true
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.getSchedule(ctx, today()) },
            onSuccess = { all ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                val sorted = all.sortedBy { it.startDatetime }
                adapter.submit(sorted)
                empty.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
                empty.text = "予定はありません"
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                empty.visibility = View.VISIBLE
                empty.text = "読み込みエラー: ${e.message}"
            }
        )
    }

    private fun complete(ev: ScheduledEvent) {
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.completeTask(ctx, ev.id) },
            onSuccess = {
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "完了しました", Toast.LENGTH_SHORT).show()
                load()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }
}
