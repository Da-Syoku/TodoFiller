package dev.togar.dynasched.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 端末再起動後、予約していたアラームは消えるのでキャッシュから再登録する */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmScheduler.rescheduleFromCache(context)
            DailyRunReceiver.schedule(context)   // 毎日の自動実行も取り直す
        }
    }
}
