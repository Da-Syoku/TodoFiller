package dev.togar.dynasched.data

import android.content.Context
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.HobbyItem
import dev.togar.dynasched.api.MaterialItem
import dev.togar.dynasched.api.PlanResult
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.api.SuggestItem

/**
 * データの出どころ。**サーバー方式と端末内方式の2つを両方持つ。**
 *
 * 端末内完結が目的だが、片方だけにすると移植で何か壊れた時に戻る先が無くなる。
 * 後から変えるのに工程が要る分岐なので、両方実装して設定で切り替える形にした。
 * 画面はこのインターフェースだけを見ればよく、どちらで動いているかを知らない。
 *
 * すべてワーカースレッドから呼ぶこと（`Api.async` の中など）。
 */
interface Repo {

    /** 指定日から7日分 */
    fun getSchedule(ctx: Context, dateYmd: String): List<ScheduledEvent>

    /** 完了。problems < 0 なら実測ペースからの推定で記録する */
    fun completeTask(ctx: Context, id: Long, problems: Int = -1)

    /** できなかった。予定を消して後の空き時間へ再配置する */
    fun skipTask(ctx: Context, id: Long)

    /** 予定を組み直す。何が起きたか（何件置けたか・なぜ置けなかったか）を返す */
    fun runScheduler(ctx: Context, days: Int): RunReport

    fun getHobby(ctx: Context): List<HobbyItem>
    fun addHobby(
        ctx: Context, name: String, parentId: Long?, durationMinutes: Int,
        priority: Int, location: String, note: String, color: String, tags: String = ""
    )
    fun editHobby(
        ctx: Context, id: Long, name: String, durationMinutes: Int,
        priority: Int, location: String, note: String, color: String, tags: String = ""
    )
    fun completeHobby(ctx: Context, id: Long)
    fun setHobbyCompleted(ctx: Context, id: Long, completed: Boolean)
    fun deleteHobby(ctx: Context, id: Long)

    /**
     * 並び替え・階層の変更ができるか。
     * サーバー側に対応する口が無いので、端末内方式だけ true。
     * 画面はこれを見て並び替えモードの入口ごと隠す（押せるのに効かない、を作らない）。
     */
    val supportsReorder: Boolean get() = false

    /** 同じ親を持つタスクの並び順を、渡された順で保存する */
    fun reorderHobby(ctx: Context, orderedIds: List<Long>) = Unit

    /** 親を付け替える（子タスク化／子をやめる）。自分の子孫は親にできない */
    fun setHobbyParent(ctx: Context, id: Long, parentId: Long?) = Unit

    /** 優先度だけを変える（優先度順で並び替えたとき） */
    fun setHobbyPriority(ctx: Context, id: Long, priority: Int) = Unit

    fun getMaterials(ctx: Context): List<MaterialItem>
    fun addMaterial(ctx: Context, m: MaterialInput)
    fun editMaterial(ctx: Context, id: Long, m: MaterialInput)
    fun deleteMaterial(ctx: Context, id: Long)

    /** 実績の記録。これが唯一の進捗入力 */
    fun recordAttempt(ctx: Context, id: Long, problems: Int, minutes: Int, eventId: Long? = null)
    fun undoAttempt(ctx: Context, id: Long)

    /** 「間に合うのか」 */
    fun getPlan(ctx: Context, days: Int = 45): PlanResult

    /** @param tags 空でなければ、このタグが付いた単発タスクだけを候補にする */
    fun getSuggestions(
        ctx: Context, loc: String, min: Int, limit: Int, tags: Set<String> = emptySet()
    ): List<SuggestItem>

    companion object {
        /** いま有効な方式。設定で切り替える */
        fun current(ctx: Context): Repo =
            if (Prefs.localMode(ctx)) LocalRepo else ServerRepo
    }
}

/** 教材の入力値。引数が多いので束ねる */
data class MaterialInput(
    val subject: String,
    val name: String,
    val totalProblems: Int,
    val advancedRanges: String,
    val targetRounds: Int,
    val deadline: String,
    val firstRoundDeadline: String,
    val prereqMaterialId: Long?,
    val studyType: String,
    val needs: String,
    val sessionMinutes: Int,
    val priority: Int,
    val color: String,
    val memo: String,
    val isExam: Boolean
)
