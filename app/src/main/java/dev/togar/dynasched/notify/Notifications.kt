package dev.togar.dynasched.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
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

    /** 通知から直接「何問やった？」を打てるようにする入力欄 */
    private fun problemsInput(): RemoteInput =
        RemoteInput.Builder(NotifyActionReceiver.KEY_PROBLEMS)
            .setLabel("何問やった？")
            .build()

    /**
     * タスクのフィードバック通知。
     * running=true は開始直後の「実行中」通知（早く終わったらここから記録できる）、
     * false は終了時刻の確認通知。
     *
     * 教材の予定には「何問やった？」の入力欄付きアクションを出す。
     * **通知から直接打てることが重要**で、アプリを開かせると入力されなくなる。
     */
    fun showFeedback(
        ctx: Context, notifyId: Int, title: String,
        eventId: Long, materialId: Long, endMs: Long, running: Boolean, endLabel: String = ""
    ) {
        // MUTABLE にするのは入力欄を持つアクションだけ。
        // RemoteInput は結果を Intent に差し込むので MUTABLE が要るが、
        // ただのボタンまで MUTABLE にすると extras を差し替えられる余地を無駄に残す。
        fun actionIntent(action: String, rc: Int, mutable: Boolean = false): PendingIntent {
            val intent = Intent(ctx, NotifyActionReceiver::class.java).apply {
                this.action = action
                putExtra(NotifyActionReceiver.EXTRA_EVENT_ID, eventId)
                putExtra(NotifyActionReceiver.EXTRA_MATERIAL_ID, materialId)
                putExtra(NotifyActionReceiver.EXTRA_NOTIFY_ID, notifyId)
                putExtra(NotifyActionReceiver.EXTRA_END_MS, endMs)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
            return PendingIntent.getBroadcast(ctx, rc, intent, flags)
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
        if (materialId > 0) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0, "何問やった？",
                    actionIntent(NotifyActionReceiver.ACTION_COMPLETE_COUNT, notifyId + 1, mutable = true)
                ).addRemoteInput(problemsInput()).build()
            )
        }
        builder.addAction(0, "完了", actionIntent(NotifyActionReceiver.ACTION_COMPLETE, notifyId))
        builder.addAction(0, "できなかった", actionIntent(NotifyActionReceiver.ACTION_SKIP, notifyId + 2))
        try {
            NotificationManagerCompat.from(ctx).notify(notifyId, builder.build())
        } catch (e: SecurityException) {
        }
    }

    /**
     * 「暇なとき」で決めたことの確認通知。終わっているはずの時刻に出る。
     * できた → 単発タスクなら完了、教材なら入力した問数を記録。
     * できなかった → 何も記録しない。
     */
    fun showFreeTimeCheck(
        ctx: Context, title: String, suggestId: Long, suggestKind: String, minutes: Int = 0
    ) {
        // 教材の「できた」だけが入力欄を持つ。他は差し替えの余地を残さない。
        fun actionIntent(action: String, mutable: Boolean = false): PendingIntent {
            val intent = Intent(ctx, NotifyActionReceiver::class.java).apply {
                this.action = action
                putExtra(NotifyActionReceiver.EXTRA_SUGGEST_ID, suggestId)
                putExtra(NotifyActionReceiver.EXTRA_SUGGEST_KIND, suggestKind)
                putExtra(NotifyActionReceiver.EXTRA_MINUTES, minutes)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
            // 2つのアクションで requestCode を分けないと片方が上書きされる
            val rc = AlarmScheduler.CHECK_NOTIFY_ID + action.hashCode().and(0xFF)
            return PendingIntent.getBroadcast(ctx, rc, intent, flags)
        }
        val builder = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("「$title」はできましたか？")
            .setContentText("暇なときに決めたやつです")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (suggestKind == "material") {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    0, "何問やった？",
                    actionIntent(NotifyActionReceiver.ACTION_FREE_DONE, mutable = true)
                ).addRemoteInput(problemsInput()).build()
            )
        } else {
            builder.addAction(0, "できた", actionIntent(NotifyActionReceiver.ACTION_FREE_DONE))
        }
        builder.addAction(0, "できなかった", actionIntent(NotifyActionReceiver.ACTION_FREE_MISSED))
        try {
            NotificationManagerCompat.from(ctx).notify(AlarmScheduler.CHECK_NOTIFY_ID, builder.build())
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
