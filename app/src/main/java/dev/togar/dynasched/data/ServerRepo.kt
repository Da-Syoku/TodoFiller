package dev.togar.dynasched.data

import android.content.Context
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.HobbyItem
import dev.togar.dynasched.api.MaterialItem
import dev.togar.dynasched.api.PlanResult
import dev.togar.dynasched.api.ScheduledEvent
import dev.togar.dynasched.api.SuggestItem

/**
 * これまで通りサーバー(api.togar.dev)に問い合わせる方式。
 * 端末内方式で何か壊れた時に戻れるよう残してある。既存の Api をそのまま呼ぶだけ。
 */
object ServerRepo : Repo {

    override fun getSchedule(ctx: Context, dateYmd: String) = Api.getSchedule(ctx, dateYmd)
    override fun completeTask(ctx: Context, id: Long, problems: Int) = Api.completeTask(ctx, id, problems)
    override fun skipTask(ctx: Context, id: Long) = Api.skipTask(ctx, id)
    override fun runScheduler(ctx: Context, days: Int): RunReport {
        Api.runScheduler(ctx, days)
        // サーバーは件数を返さないので、中身は端末内方式のときだけ埋まる
        return RunReport(serverMode = true, fillDays = days)
    }

    override fun getHobby(ctx: Context): List<HobbyItem> = Api.getHobby(ctx)

    // タグはサーバー側に列が無いので落ちる。端末内方式の機能として割り切る
    override fun addHobby(
        ctx: Context, name: String, parentId: Long?, durationMinutes: Int,
        priority: Int, location: String, note: String, color: String, tags: String
    ) = Api.addHobby(ctx, name, parentId, durationMinutes, priority, location, note, color)

    override fun editHobby(
        ctx: Context, id: Long, name: String, durationMinutes: Int,
        priority: Int, location: String, note: String, color: String, tags: String
    ) = Api.editHobby(ctx, id, name, durationMinutes, priority, location, note, color)

    override fun completeHobby(ctx: Context, id: Long) = Api.completeHobby(ctx, id)
    override fun setHobbyCompleted(ctx: Context, id: Long, completed: Boolean) =
        Api.setHobbyCompleted(ctx, id, completed)
    override fun deleteHobby(ctx: Context, id: Long) = Api.deleteHobby(ctx, id)

    override fun getMaterials(ctx: Context): List<MaterialItem> = Api.getMaterials(ctx)

    override fun addMaterial(ctx: Context, m: MaterialInput) = Api.addMaterial(
        ctx, m.subject, m.name, m.totalProblems, m.advancedRanges, m.targetRounds,
        m.deadline, m.firstRoundDeadline, m.prereqMaterialId, m.studyType, m.needs,
        m.sessionMinutes, m.priority, m.color, m.memo, m.isExam
    )

    override fun editMaterial(ctx: Context, id: Long, m: MaterialInput) = Api.editMaterial(
        ctx, id, m.subject, m.name, m.totalProblems, m.advancedRanges, m.targetRounds,
        m.deadline, m.firstRoundDeadline, m.prereqMaterialId, m.studyType, m.needs,
        m.sessionMinutes, m.priority, m.color, m.memo, m.isExam
    )

    override fun deleteMaterial(ctx: Context, id: Long) = Api.deleteMaterial(ctx, id)

    override fun recordAttempt(ctx: Context, id: Long, problems: Int, minutes: Int, eventId: Long?) =
        Api.recordAttempt(ctx, id, problems, minutes, eventId)

    override fun undoAttempt(ctx: Context, id: Long) = Api.undoAttempt(ctx, id)

    override fun getPlan(ctx: Context, days: Int): PlanResult = Api.getPlan(ctx, days)

    override fun getSuggestions(
        ctx: Context, loc: String, min: Int, limit: Int, tags: Set<String>
    ): List<SuggestItem> = Api.getSuggestions(ctx, loc, min, limit)
}
