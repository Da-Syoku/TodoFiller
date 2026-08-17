package dev.togar.dynasched.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.togar.dynasched.Prefs
import java.util.Calendar

/**
 * 就寝時刻に「今日はここまで」と伝える。
 *
 * 前回のテストで一番効かなかった原因のひとつが「スマホをいじりすぎた」ことだった。
 * 予定を出すだけでは終わりが決まらないので、**終わりの側から線を引く**。
 * 配置も就寝時刻で打ち切られているので、ここから先は予定が無いのが正しい状態になる。
 */
class BedtimeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        schedule(ctx)   // 次の日ぶんを取り直す
        if (!Prefs.bedtimeNotice(ctx)) return
        Notifications.ensureChannel(ctx)
        Notifications.show(
            ctx, NOTIFY_ID, "今日はここまで",
            "${label(Prefs.bedtimeMinutes(ctx))}になりました。続きは明日に回してください。"
        )
    }

    companion object {
        private const val REQUEST_CODE = 900_003
        private const val NOTIFY_ID = 900_003

        private fun label(min: Int) = String.format("%02d:%02d", min / 60, min % 60)

        /** 次の就寝時刻に鳴らす。設定を変えたら呼び直すこと */
        fun schedule(ctx: Context) {
            val app = ctx.applicationContext
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                app, REQUEST_CODE, Intent(app, BedtimeReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            if (!Prefs.bedtimeNotice(app)) {
                am.cancel(pi)
                return
            }
            val bed = Prefs.bedtimeMinutes(app)
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, bed / 60)
                set(Calendar.MINUTE, bed % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
            }
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pi)
            } catch (e: SecurityException) {
                // 正確なアラームが許可されていない端末では諦める（通知が出ないだけ）
            }
        }
    }
}
