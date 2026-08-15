package dev.togar.dynasched.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.integration.StudySync
import dev.togar.dynasched.widget.SuggestWidgetProvider

/**
 * フィードバック通知のアクションボタンを処理する。
 *
 * 完了        → POST /schedule/:id/complete（早期完了なら次のおすすめを通知）
 * 何問やった  → 同上に問数を添える。**これが唯一の進捗入力**で、実測ペースの元になる
 * できなかった → POST /schedule/:id/skip {reschedule:1}（サーバーが現在時刻以降に再配置）
 *
 * 問数を入れずに「完了」を押した場合は、サーバーがコマの長さと実測ペースから推定して入れる。
 * 何も入らないより、推定でも入るほうがいい。
 */
class NotifyActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_COMPLETE = "dev.togar.dynasched.NOTIFY_COMPLETE"
        const val ACTION_COMPLETE_COUNT = "dev.togar.dynasched.NOTIFY_COMPLETE_COUNT"
        const val ACTION_SKIP = "dev.togar.dynasched.NOTIFY_SKIP"
        const val ACTION_FREE_DONE = "dev.togar.dynasched.FREE_DONE"
        const val ACTION_FREE_MISSED = "dev.togar.dynasched.FREE_MISSED"
        const val EXTRA_EVENT_ID = "event_id"
        const val EXTRA_MATERIAL_ID = "material_id"
        const val EXTRA_NOTIFY_ID = "notify_id"
        const val EXTRA_END_MS = "end_ms"
        const val EXTRA_SUGGEST_ID = "suggest_id"
        const val EXTRA_SUGGEST_KIND = "suggest_kind"
        const val EXTRA_MINUTES = "minutes"

        /** 通知から直接「何問やった？」を打ち込むための入力キー */
        const val KEY_PROBLEMS = "problems"

        /** 通知の入力欄から問数を取り出す。空・非数値は -1（＝サーバー推定に任せる）。 */
        fun problemsFrom(intent: Intent): Int {
            val text = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence(KEY_PROBLEMS)?.toString()?.trim() ?: return -1
            return text.filter { it.isDigit() }.toIntOrNull() ?: -1
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        // 「暇なとき」の確認は予定に紐づかないので、通常の予定処理より前に片付ける
        if (intent.action == ACTION_FREE_DONE || intent.action == ACTION_FREE_MISSED) {
            handleFreeTimeCheck(context, intent)
            return
        }

        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, 0L)
        val endMs = intent.getLongExtra(EXTRA_END_MS, 0L)
        val action = intent.action ?: return
        if (eventId <= 0L) return
        val problems = problemsFrom(intent)

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
                        Repo.current(appCtx).skipTask(appCtx, eventId)
                        toast(appCtx, "この後の空き時間に再配置します")
                    }
                    ACTION_COMPLETE, ACTION_COMPLETE_COUNT -> {
                        Repo.current(appCtx).completeTask(appCtx, eventId, problems)
                        toast(appCtx, if (problems >= 0) "${problems}問を記録しました" else "完了を記録しました")
                        if (earlyFinish) suggestNext(appCtx, eventId)
                    }
                }
                // 終わった予定を手元から外し、ドパチルの制限を即座に解く
                releaseWindow(appCtx, eventId)
                SuggestWidgetProvider.updateAll(appCtx)
            } catch (e: Exception) {
                toast(appCtx, "記録に失敗: ${Api.friendlyMessage(e)}")
            } finally {
                pending.finish()
            }
        }.start()
    }

    /**
     * 「暇なとき」で決めたことの答え合わせ。
     * できた → 単発タスクは完了、教材は問数を記録。できなかった → 何も記録しない。
     * 自分で決めてやらなかっただけなので、再配置もペナルティも無い。
     */
    private fun handleFreeTimeCheck(context: Context, intent: Intent) {
        NotificationManagerCompat.from(context).cancel(AlarmScheduler.CHECK_NOTIFY_ID)
        if (intent.action == ACTION_FREE_MISSED) return

        val id = intent.getLongExtra(EXTRA_SUGGEST_ID, 0L)
        val kind = intent.getStringExtra(EXTRA_SUGGEST_KIND) ?: "hobby"
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        val problems = problemsFrom(intent)
        if (id <= 0L) return

        val pending = goAsync()
        val appCtx = context.applicationContext
        Thread {
            try {
                if (kind == "material") {
                    // 問数を打っていないなら記録しない。適当な数を入れると実測ペースが濁る
                    if (problems > 0) {
                        Repo.current(appCtx).recordAttempt(appCtx, id, problems, minutes)
                        toast(appCtx, "${problems}問を記録しました")
                    }
                } else {
                    Repo.current(appCtx).completeHobby(appCtx, id)
                    toast(appCtx, "記録しました")
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
            val items = Repo.current(ctx).getSuggestions(ctx, Prefs.widgetLoc(ctx), min, 3)
            if (items.isEmpty()) return
            val text = items.joinToString("\n") {
                (if (it.kind == "material") "🎯 " else "・") + "${it.title}（${it.minutes}分）"
            }
            Notifications.ensureChannel(ctx)
            Notifications.showSuggestion(ctx, text)
        } catch (e: Exception) {
            // 提案は取れなくても本処理（完了記録）は済んでいるので無視
        }
    }

    /**
     * 終わった予定を端末内キャッシュから外し、残りをドパチルへ送り直す。
     * 早期完了のとき、次の判定を待たずにブロックが解ける。
     */
    private fun releaseWindow(ctx: Context, eventId: Long) {
        val json = Prefs.cachedEvents(ctx) ?: return
        val remaining = ScheduledEvent.fromJsonArray(json).filter { it.id != eventId }
        Prefs.saveCachedEvents(ctx, ScheduledEvent.toJsonArray(remaining))
        StudySync.send(ctx, remaining)
    }

    /** キャッシュ済み予定から、完了したイベントを除く次の予定開始までの分数 */
    private fun freeMinutes(ctx: Context, excludeId: Long): Int {
        val json = Prefs.cachedEvents(ctx) ?: return 60
        return ScheduledEvent.freeMinutesUntilNext(ScheduledEvent.fromJsonArray(json), excludeId)
    }

    private fun toast(ctx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    }
}
