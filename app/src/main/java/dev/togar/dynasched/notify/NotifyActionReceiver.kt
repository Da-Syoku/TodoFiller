package dev.togar.dynasched.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.widget.SuggestWidgetProvider
import org.json.JSONArray

/**
 * フィードバック通知のアクションボタンを処理する。
 * 完了 → POST /schedule/:id/complete（早期完了なら次のおすすめを通知）
 * 完了+進捗10% → 上記に加えて POST /goal/:id/bump {progress_delta:10}
 * できなかった → POST /schedule/:id/skip {reschedule:1}（サーバーが現在時刻以降に再配置）
 */
class NotifyActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMPLETE = "dev.togar.dynasched.NOTIFY_COMPLETE"
        const val ACTION_COMPLETE_PROGRESS = "dev.togar.dynasched.NOTIFY_COMPLETE_PROGRESS"
        const val ACTION_SKIP = "dev.togar.dynasched.NOTIFY_SKIP"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_GOAL_ID = "goal_id"
        const val EXTRA_NOTIFY_ID = "notify_id"
        const val EXTRA_END_MS = "end_ms"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, 0L)
        val goalId = intent.getLongExtra(EXTRA_GOAL_ID, 0L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, 0L)
        val action = intent.action ?: return
        if (eventId <= 0L) return

        // 実行中通知(id)と終了通知(id+OFFSET)の両方を消し、未発火の終了アラームも取り消す
        val nm = NotificationManagerCompat.from(context)
        nm.cancel(eventId.toInt())
        nm.cancel(eventId.toInt() + AlarmScheduler.END_ID_OFFSET)
        AlarmScheduler.cancelEndAlarm(context, eventId)

        val earlyFinish = endMs > 0L && System.currentTimeMillis() < endMs - 60_000L

        // BroadcastReceiver は onReceive を抜けると殺され得るので goAsync で猶予をもらう
        val pending = goAsync()
        val appCtx = context.applicationContext
        Thread {
            try {
                when (action) {
                    ACTION_SKIP -> {
                        Api.skipTask(appCtx, eventId)
                        toast(appCtx, "この後の空き時間に再配置します")
                    }
                    ACTION_COMPLETE, ACTION_COMPLETE_PROGRESS -> {
                        Api.completeTask(appCtx, eventId)
                        if (action == ACTION_COMPLETE_PROGRESS && goalId > 0L) {
                            Api.bumpGoal(appCtx, goalId, progressDelta = 10)
                        }
                        if (earlyFinish) suggestNext(appCtx, eventId)
                    }
                }
                SuggestWidgetProvider.updateAll(appCtx)
            } catch (e: Exception) {
                toast(appCtx, "記録に失敗: ${Api.friendlyMessage(e)}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** 早く終わった分の空き時間に合う候補を取得して通知する */
    private fun suggestNext(ctx: Context, doneEventId: Long) {
        try {
            val min = freeMinutes(ctx, doneEventId)
            val items = Api.getSuggestions(ctx, Prefs.widgetLoc(ctx), min, 3)
            if (items.isEmpty()) return
            val text = items.joinToString("\n") {
                (if (it.kind == "goal") "🎯 " else "・") + "${it.title}（${it.minutes}分）"
            }
            Notifications.ensureChannel(ctx)
            Notifications.showSuggestion(ctx, text)
        } catch (e: Exception) {
            // 提案は取れなくても本処理（完了記録）は済んでいるので無視
        }
    }

    /** キャッシュ済み予定から、完了したイベントを除く次の予定開始までの分数 */
    private fun freeMinutes(ctx: Context, excludeId: Long): Int {
        val json = Prefs.cachedEvents(ctx) ?: return 60
        return try {
            val arr = JSONArray(json)
            val now = System.currentTimeMillis()
            var next = Long.MAX_VALUE
            for (i in 0 until arr.length()) {
                val ev = ScheduledEvent.from(arr.getJSONObject(i))
                if (ev.isCompleted || ev.id == excludeId) continue
                val t = ev.startAsDate()?.time ?: continue
                if (t > now && t < next) next = t
            }
            if (next == Long.MAX_VALUE) 60
            else ((next - now) / 60000L).toInt().coerceIn(15, 240)
        } catch (e: Exception) { 60 }
    }

    private fun toast(ctx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}
