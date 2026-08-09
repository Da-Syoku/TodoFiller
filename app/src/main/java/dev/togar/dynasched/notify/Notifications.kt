package dev.togar.dynasched.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.togar.dynasched.R

object Notifications {
    const val CHANNEL_ID = "dynasched_tasks"

    fun ensureChannel(ctx: Context) {
        // minSdk 26 なので NotificationChannel は常に利用可能
        val channel = NotificationChannel(
            CHANNEL_ID,
            "タスク通知",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "予定の開始時刻をお知らせします"
            enableVibration(true)
        }
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    fun show(ctx: Context, notifyId: Int, title: String, text: String) {
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        // POST_NOTIFICATIONS 未許可時に例外で落ちないようガード
        try {
            NotificationManagerCompat.from(ctx).notify(notifyId, builder.build())
        } catch (e: SecurityException) {
            // 通知権限が無いだけなので無視
        }
    }

    /**
     * タスクのフィードバック通知。
     * running=true は開始直後の「実行中」通知（早く終わったらここから記録できる）、
     * false は終了時刻の確認通知。
     * アクション: 完了 / (goal のみ) 完了+進捗10% / できなかった。
     */
    fun showFeedback(
        ctx: Context, notifyId: Int, title: String,
        eventId: Long, goalId: Long, endMs: Long, running: Boolean, endLabel: String = ""
    ) {
        fun actionIntent(action: String): PendingIntent {
            val intent = Intent(ctx, NotifyActionReceiver::class.java).apply {
                this.action = action
                putExtra(NotifyActionReceiver.EXTRA_EVENT_ID, eventId)
                putExtra(NotifyActionReceiver.EXTRA_GOAL_ID, goalId)
                putExtra(NotifyActionReceiver.EXTRA_NOTIFY_ID, notifyId)
                putExtra(NotifyActionReceiver.EXTRA_END_MS, endMs)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return PendingIntent.getBroadcast(ctx, notifyId, intent, flags)
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (running) {
            builder.setContentTitle("実行中: $title")
                .setContentText("〜$endLabel 予定。早く終わったらここから記録")
                .setSilent(true) // 開始通知(音あり)と同時に出るので音は鳴らさない
        } else {
            builder.setContentTitle("「$title」は終わりましたか？")
                .setContentText("お疲れさまです。結果を記録しましょう")
        }
        builder.addAction(0, "完了", actionIntent(NotifyActionReceiver.ACTION_COMPLETE))
        if (goalId > 0) {
            builder.addAction(0, "完了+進捗10%", actionIntent(NotifyActionReceiver.ACTION_COMPLETE_PROGRESS))
        }
        builder.addAction(0, "できなかった", actionIntent(NotifyActionReceiver.ACTION_SKIP))
        try {
            NotificationManagerCompat.from(ctx).notify(notifyId, builder.build())
        } catch (e: SecurityException) {
        }
    }

    /** 早く終わったとき等の「次のおすすめ」通知 */
    fun showSuggestion(ctx: Context, text: String) {
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("次のおすすめ")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        try {
            NotificationManagerCompat.from(ctx).notify(999_999_001, builder.build())
        } catch (e: SecurityException) {
        }
    }
}
