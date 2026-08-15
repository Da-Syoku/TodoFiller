package dev.togar.dynasched.data

import android.content.Context
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.Api

/**
 * サーバーにあるデータを端末へ1度だけ引き取る。
 *
 * 実測ペースの元になる**実績を捨てたくない**ので手入力ではなく取り込みにした。
 * ただしサーバーは実績の明細(attempts)を返さないため、
 * 「何問まで進んだか」(done_problems) は移せても、**実測ペースはゼロから測り直しになる**。
 * ここは正直に諦める。数字を捏造して移すより、仮値から測り直すほうが信用できる。
 */
object Importer {

    data class Result(val materials: Int, val hobbies: Int, val error: String? = null)

    /**
     * 取り込む。ワーカースレッドから呼ぶこと。
     * 端末側に既にデータがある場合は何もしない（二重に増やさない）。
     */
    fun importFromServer(ctx: Context, force: Boolean = false): Result {
        if (!force && !LocalRepo.isEmpty(ctx)) return Result(0, 0, "端末に既にデータがあります")
        var materials = 0
        var hobbies = 0
        try {
            for (m in Api.getMaterials(ctx)) {
                LocalRepo.importMaterial(
                    ctx,
                    MaterialInput(
                        subject = m.subject,
                        name = m.name,
                        totalProblems = m.totalProblems,
                        advancedRanges = m.advancedRanges,
                        targetRounds = m.targetRounds,
                        deadline = m.deadline,
                        firstRoundDeadline = m.firstRoundDeadline,
                        // 前提のIDはサーバー側の採番なので、そのままでは繋がらない。
                        // 数件しかないものなので、取り込み後に手で設定し直す
                        prereqMaterialId = null,
                        studyType = m.studyType,
                        needs = m.needs,
                        sessionMinutes = m.sessionMinutes,
                        priority = m.priority,
                        color = m.color,
                        memo = m.memo,
                        isExam = m.isExam
                    ),
                    doneProblems = m.doneProblems
                )
                materials++
            }
            for (h in Api.getHobby(ctx)) {
                if (h.isCompleted) continue   // 済んだ用事は持ち込まない
                LocalRepo.addHobby(
                    ctx, h.name, null, h.durationMinutes, h.priority, h.location, h.note, h.color
                )
                hobbies++
            }
            Prefs.setImported(ctx, true)
            return Result(materials, hobbies)
        } catch (e: Exception) {
            return Result(materials, hobbies, Api.friendlyMessage(e))
        }
    }
}
