package dev.togar.dynasched.api

import android.content.Context
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.integration.StudySync
import dev.togar.dynasched.notify.AlarmScheduler

/**
 * 予定の取得と、その結果に伴う副作用（アラーム予約・キャッシュ・ドパチルへの全置換）を
 * 1か所にまとめたもの。
 *
 * 以前は MainActivity と TodayFragment が起動時にそれぞれ同じことをしていたので、
 * **毎回の起動で同じ通信が2回・同じアラーム予約が2回・同じブロードキャストが2回**
 * 走っていた。短時間の重複要求はここで1回にまとめる。
 */
object ScheduleRepo {

    /** この時間内の再要求は前回の結果を使い回す */
    private const val FRESH_MS = 20_000L

    private var fetchedAt = 0L
    private var fetchedDate = ""
    private var events: List<ScheduledEvent>? = null

    /**
     * 指定日から7日分を取得する。ワーカースレッドから呼ぶこと。
     * @param force 引っ張って更新した時など、キャッシュを無視したい場合
     */
    @Synchronized
    fun refresh(ctx: Context, dateYmd: String, force: Boolean = false): List<ScheduledEvent> {
        val now = System.currentTimeMillis()
        val cached = events
        if (!force && cached != null && fetchedDate == dateYmd && now - fetchedAt < FRESH_MS) {
            return cached
        }
        val list = Repo.current(ctx).getSchedule(ctx, dateYmd)
        events = list
        fetchedDate = dateYmd
        fetchedAt = now
        // 通知は取得できた全期間ぶんを予約する（今日だけにすると翌日以降が鳴らなくなる）
        AlarmScheduler.scheduleAll(ctx, list)
        StudySync.send(ctx, list)
        return list
    }

    /** 完了・中断などで内容が変わった直後に呼ぶ */
    @Synchronized
    fun invalidate() {
        events = null
        fetchedAt = 0L
    }
}
