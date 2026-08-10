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
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.integration.StudySync
import dev.togar.dynasched.notify.AlarmScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 今日の予定を表示する画面 */
class TodayFragment : Fragment() {

    private lateinit var adapter: EventAdapter
    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var header: TextView
    private lateinit var empty: TextView

    /** キャッシュを出しただけで、まだサーバーから取れていない状態か */
    private var showingCache = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_list, container, false)

        header = root.findViewById(R.id.headerText)
        empty = root.findViewById(R.id.emptyText)
        swipe = root.findViewById(R.id.swipeRefresh)
        val recycler = root.findViewById<RecyclerView>(R.id.recycler)

        header.text = "今日の予定"
        adapter = EventAdapter(showDate = false) { ev -> complete(ev) }
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter

        swipe.setOnRefreshListener { load() }

        // 通信を待たずに前回の内容を出す（圏外・低速回線でも即座に今日の予定が見える）
        showCache()
        return root
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 保存済みスナップショットがあれば先に描画する */
    private fun showCache() {
        val cached = Prefs.scheduleCache(requireContext().applicationContext, today()) ?: return
        val events = ScheduledEvent.fromJsonArray(cached)
        if (events.isEmpty()) return
        adapter.submit(events)
        empty.visibility = View.GONE
        showingCache = true
        header.text = "今日の予定（前回の内容）"
    }

    private fun load() {
        swipe.isRefreshing = true
        val todayStr = today()
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.getSchedule(ctx, todayStr) },
            onSuccess = { all ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                showingCache = false
                header.text = "今日の予定"
                // 7日分のうち今日の分だけ表示する
                val todays = all.filter { it.isOnDate(todayStr) }
                    .sortedBy { it.startDatetime }
                adapter.submit(todays)
                empty.visibility = if (todays.isEmpty()) View.VISIBLE else View.GONE
                empty.text = "今日の予定はありません"
                Prefs.saveScheduleCache(ctx, todayStr, ScheduledEvent.toJsonArray(todays))
                // 通知は取得できた全期間ぶんを予約する（今日だけにすると翌日以降が鳴らなくなる）
                AlarmScheduler.scheduleAll(ctx, all)
                StudySync.send(ctx, all)   // ドパチルへ学習予定を全置換で渡す
            },
            onError = { e ->
                if (!isAdded) return@async
                swipe.isRefreshing = false
                if (showingCache) {
                    // 手元に出せる内容があるので消さず、状態だけ伝える
                    header.text = "今日の予定（オフライン表示）"
                    Toast.makeText(
                        requireContext(), Api.friendlyMessage(e), Toast.LENGTH_SHORT
                    ).show()
                } else {
                    empty.visibility = View.VISIBLE
                    empty.text = Api.friendlyMessage(e)
                }
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
                Toast.makeText(
                    requireContext(), "失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG
                ).show()
            }
        )
    }
}
