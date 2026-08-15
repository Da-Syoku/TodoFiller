package dev.togar.dynasched.integration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.notify.AlarmScheduler

/**
 * ドパチルの「この予定を中断する」から呼ばれる受け口。
 *
 * ブロック画面の押し切り（1タップで5分素通り）を無くす代わりの出口。
 * 抜けること自体は許すが、**タダでは抜けさせない**:
 * 中断した予定は「できなかった」として後の空き時間へ再配置され、記録も残る。
 *
 * 送り方（ドパチル側）:
 *   Intent("dev.togar.dynasched.action.ABORT_SESSION").apply {
 *       setPackage("dev.togar.dynasched")
 *       putExtra("id", "sched_8842")   // StudySync が渡した窓の id をそのまま
 *   }
 *
 * `dev.togar.dynasched.permission.CONTROL_SESSION`（signature保護）が要る。
 */
class AbortSessionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "dev.togar.dynasched.action.ABORT_SESSION"
        const val EXTRA_ID = "id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // StudySync は "sched_<id>" で送っている。素の数値も一応受ける
        val raw = intent.getStringExtra(EXTRA_ID) ?: return
        val eventId = raw.removePrefix("sched_").toLongOrNull() ?: return
        if (eventId <= 0L) return

        val pending = goAsync()
        val ctx = context.applicationContext
        Thread {
            try {
                // 中断＝できなかった。サーバーが現在時刻以降へ再配置する
                Repo.current(ctx).skipTask(ctx, eventId)
            } catch (e: Exception) {
                // 通信できなくても下の解除は必ずやる。
                // ここで諦めると「圏外だと中断できない＝閉じ込められる」ことになる
            } finally {
                releaseLocally(ctx, eventId)
                pending.finish()
            }
        }.start()
    }

    /** 手元の窓を落として送り直し、制限を解く。終了アラームも取り消す。 */
    private fun releaseLocally(ctx: Context, eventId: Long) {
        try {
            AlarmScheduler.cancelEndAlarm(ctx, eventId)
            val json = Prefs.cachedEvents(ctx) ?: return
            val remaining = ScheduledEvent.fromJsonArray(json).filter { it.id != eventId }
            Prefs.saveCachedEvents(ctx, ScheduledEvent.toJsonArray(remaining))
            StudySync.send(ctx, remaining)
        } catch (ignore: Exception) {
        }
    }
}
