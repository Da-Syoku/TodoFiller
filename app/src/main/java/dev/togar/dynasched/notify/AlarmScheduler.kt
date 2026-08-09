package dev.togar.dynasched.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.ScheduledEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 端末内の AlarmManager で各予定の開始時刻にローカル通知を予約する。
 * 外部プッシュサーバ(FCM等)を一切使わないので、アプリを閉じていても・通信が無くても鳴る。
 */
object AlarmScheduler {

    const val EXTRA_ID = "id"
    const val EXTRA_TITLE = "title"
    const val EXTRA_TEXT = "text"
    const val EXTRA_KIND = "kind"          // "start" | "end"
    const val EXTRA_GOAL_ID = "goal_id"
    const val EXTRA_END_MS = "end_ms"
    const val EXTRA_END_LABEL = "end_label"
    const val KIND_START = "start"
    const val KIND_END = "end"

    // 終了通知の requestCode/notifyId が開始通知と衝突しないためのオフセット
    const val END_ID_OFFSET = 1_000_000

    private fun alarmManager(ctx: Context) =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun piFlags(): Int {
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return flags
    }

    private fun startIntent(ctx: Context, ev: ScheduledEvent): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ID, ev.id)
            putExtra(EXTRA_TITLE, ev.title)
            putExtra(EXTRA_TEXT, "${ev.startTimeLabel()} 開始予定")
            putExtra(EXTRA_KIND, KIND_START)
            putExtra(EXTRA_GOAL_ID, ev.goalId ?: 0L)
            putExtra(EXTRA_END_MS, ev.endAsDate()?.time ?: 0L)
            putExtra(EXTRA_END_LABEL, ev.endTimeLabel())
        }
        // requestCode に id を使って予定ごとに一意化
        return PendingIntent.getBroadcast(ctx, ev.id.toInt(), intent, piFlags())
    }

    private fun endIntent(ctx: Context, ev: ScheduledEvent): PendingIntent {
        val intent = Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ID, ev.id)
            putExtra(EXTRA_TITLE, ev.title)
            putExtra(EXTRA_KIND, KIND_END)
            putExtra(EXTRA_GOAL_ID, ev.goalId ?: 0L)
            putExtra(EXTRA_END_MS, ev.endAsDate()?.time ?: 0L)
        }
        return PendingIntent.getBroadcast(ctx, ev.id.toInt() + END_ID_OFFSET, intent, piFlags())
    }

    /** 早期完了/できなかった時に、まだ鳴っていない終了アラームを取り消す */
    fun cancelEndAlarm(ctx: Context, eventId: Long) {
        val intent = Intent(ctx, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, eventId.toInt() + END_ID_OFFSET, intent,
            piFlags() or PendingIntent.FLAG_NO_CREATE
        )
        if (pi != null) {
            alarmManager(ctx).cancel(pi)
            pi.cancel()
        }
    }

    private fun setAlarm(am: AlarmManager, at: Long, pi: PendingIntent) {
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else true
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // 正確なアラーム権限が無い場合でも、おおよその時刻で通知する
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /** 予定リストから、未完了かつ未来のものに開始通知＋終了時フィードバック通知を予約する */
    fun scheduleAll(ctx: Context, events: List<ScheduledEvent>) {
        val am = alarmManager(ctx)
        val now = System.currentTimeMillis()
        for (ev in events) {
            if (ev.isCompleted) continue
            val start = ev.startAsDate()?.time
            if (start != null && start > now) {
                setAlarm(am, start, startIntent(ctx, ev))
            }
            val end = ev.endAsDate()?.time
            if (end != null && end > now) {
                setAlarm(am, end, endIntent(ctx, ev))
            }
        }
        // 再起動後に再登録できるようキャッシュ
        Prefs.saveCachedEvents(ctx, toJson(events))
    }

    /** 再起動時など、キャッシュ済みの予定から再登録する */
    fun rescheduleFromCache(ctx: Context) {
        val json = Prefs.cachedEvents(ctx) ?: return
        scheduleAll(ctx, fromJson(json))
    }

    // ---- キャッシュ用の簡易シリアライズ ----

    private fun toJson(events: List<ScheduledEvent>): String {
        val arr = JSONArray()
        for (ev in events) {
            arr.put(
                JSONObject()
                    .put("id", ev.id)
                    .put("title", ev.title)
                    .put("start_datetime", ev.startDatetime)
                    .put("end_datetime", ev.endDatetime)
                    .put("event_type", ev.eventType)
                    .put("is_completed", if (ev.isCompleted) 1 else 0)
                    .put("goal_id", ev.goalId ?: 0L)
            )
        }
        return arr.toString()
    }

    private fun fromJson(json: String): List<ScheduledEvent> {
        val arr = JSONArray(json)
        val list = ArrayList<ScheduledEvent>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(ScheduledEvent.from(arr.getJSONObject(i)))
        }
        return list
    }
}
