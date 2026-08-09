package dev.togar.dynasched.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 予約したアラームが発火したときに通知を表示する（開始時の実行中通知/終了フィードバック通知） */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(AlarmScheduler.EXTRA_ID, 0L)
        val title = intent.getStringExtra(AlarmScheduler.EXTRA_TITLE) ?: "予定"
        val goalId = intent.getLongExtra(AlarmScheduler.EXTRA_GOAL_ID, 0L)
        val endMs = intent.getLongExtra(AlarmScheduler.EXTRA_END_MS, 0L)
        Notifications.ensureChannel(context)
        if (intent.getStringExtra(AlarmScheduler.EXTRA_KIND) == AlarmScheduler.KIND_END) {
            Notifications.showFeedback(
                context, id.toInt() + AlarmScheduler.END_ID_OFFSET, title, id, goalId, endMs, running = false
            )
        } else {
            // 開始直後から記録できる「実行中」通知（音は開始通知のみ）
            val text = intent.getStringExtra(AlarmScheduler.EXTRA_TEXT) ?: ""
            Notifications.show(context, id.toInt(), title, text)
            val endLabel = intent.getStringExtra(AlarmScheduler.EXTRA_END_LABEL) ?: ""
            Notifications.showFeedback(
                context, id.toInt(), title, id, goalId, endMs, running = true, endLabel = endLabel
            )
        }
    }
}
