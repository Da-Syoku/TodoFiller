package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem

/**
 * 単発タスクの一覧を「どう並べてどこまで見せるか」だけを決める。
 *
 * 画面（RecyclerView）から切り離してあるのは、並び替え・折りたたみ・完了の
 * まとめ方が絡むと**目で確かめるのが一番あてにならない**ため。ここは純関数だけに
 * して、テストで固定する。
 *
 * 木の形は必ず保つ。完了を下にまとめるのも、折りたたむのも、
 * **兄弟の中での話**に閉じている。親子をまたいで動かすと階層が壊れる。
 */

/** 並び順。どれも「兄弟どうしの比較」に使う */
enum class TaskSort(val label: String) {
    MANUAL("手動（並び替え順）"),
    NEWEST("追加が新しい順"),
    OLDEST("追加が古い順"),
    PRIORITY("優先度が高い順"),
    LONGEST("必要時間が長い順"),
    SHORTEST("必要時間が短い順");

    companion object {
        fun from(name: String?): TaskSort =
            entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/** 画面に出す1行 */
data class TaskRow(
    val item: HobbyItem,
    val level: Int,
    val hasChildren: Boolean,
    val collapsed: Boolean,
    /** 配下の葉タスクの数・完了数・合計分数（折りたたみ中の要約に使う） */
    val leafCount: Int = 0,
    val leafDone: Int = 0,
    val leafMinutes: Int = 0
) {
    /** 折りたたんだ親の1行に出す要約 */
    fun summary(): String {
        if (!hasChildren) return ""
        val h = leafMinutes / 60
        val m = leafMinutes % 60
        val dur = if (h > 0) "${h}時間${if (m > 0) "${m}分" else ""}" else "${m}分"
        return "${leafCount}件（済${leafDone}）・$dur"
    }
}

object TaskList {

    /**
     * 一覧を組む。
     *
     * @param collapsed 折りたたんでいる親のID。配下は行として出さない
     * @param doneAtBottom 完了したものを兄弟の末尾へ寄せる。false ならその場に半透明で残す
     */
    fun build(
        all: List<HobbyItem>,
        sort: TaskSort,
        collapsed: Set<Long>,
        doneAtBottom: Boolean
    ): List<TaskRow> {
        val byParent = all.groupBy { it.parentId }
        val existing = all.mapTo(HashSet()) { it.id }
        val out = ArrayList<TaskRow>(all.size)
        val visited = HashSet<Long>()

        fun childrenOf(id: Long?): List<HobbyItem> =
            byParent[id].orEmpty().sortedWith(comparator(sort, doneAtBottom))

        /** 配下の葉を数える（自分が葉なら自分を数える） */
        fun leaves(item: HobbyItem, seen: MutableSet<Long>): Triple<Int, Int, Int> {
            if (!seen.add(item.id)) return Triple(0, 0, 0)
            val kids = byParent[item.id].orEmpty()
            if (kids.isEmpty()) {
                return Triple(1, if (item.isCompleted) 1 else 0, item.durationMinutes)
            }
            var n = 0; var done = 0; var min = 0
            for (k in kids) {
                val (a, b, c) = leaves(k, seen)
                n += a; done += b; min += c
            }
            return Triple(n, done, min)
        }

        fun emit(item: HobbyItem, level: Int) {
            if (!visited.add(item.id)) return   // 循環参照ガード
            val kids = childrenOf(item.id)
            val isCollapsed = kids.isNotEmpty() && collapsed.contains(item.id)
            val agg = if (kids.isEmpty()) Triple(0, 0, 0) else leaves(item, HashSet())
            out.add(
                TaskRow(
                    item = item,
                    level = level,
                    hasChildren = kids.isNotEmpty(),
                    collapsed = isCollapsed,
                    leafCount = agg.first,
                    leafDone = agg.second,
                    leafMinutes = agg.third
                )
            )
            if (isCollapsed) return
            for (k in kids) emit(k, level + 1)
        }

        // ルート＝parent_id が null、または親が消えている孤児
        val roots = all.filter { it.parentId == null || !existing.contains(it.parentId) }
            .sortedWith(comparator(sort, doneAtBottom))
        for (r in roots) emit(r, 0)
        return out
    }

    /**
     * 兄弟どうしの比較。
     *
     * 完了を下へ寄せる指定があるときは、**どの並び順よりも先に**効かせる。
     * そうしないと「済んだものが優先度順の途中に居座る」ことになる。
     */
    fun comparator(sort: TaskSort, doneAtBottom: Boolean): Comparator<HobbyItem> {
        val inner = when (sort) {
            TaskSort.MANUAL -> compareBy<HobbyItem>({ it.sortOrder }, { it.id })
            TaskSort.NEWEST -> compareByDescending { it.id }          // idは採番順＝追加順
            TaskSort.OLDEST -> compareBy { it.id }
            TaskSort.PRIORITY -> compareBy<HobbyItem> { -it.priority }
                .thenBy { it.sortOrder }.thenBy { it.id }
            TaskSort.LONGEST -> compareBy<HobbyItem> { -it.durationMinutes }.thenBy { it.id }
            TaskSort.SHORTEST -> compareBy<HobbyItem>({ it.durationMinutes }, { it.id })
        }
        return if (doneAtBottom) compareBy<HobbyItem> { it.isCompleted }.then(inner) else inner
    }

    /**
     * 並び替えで行を動かした結果の、**同じ親を持つ兄弟の新しい並び**。
     *
     * 親をまたぐ移動は認めない（階層が壊れるため）。動かせない時は null を返し、
     * 呼び出し側はドラッグを無視する。
     */
    fun moveWithinSiblings(rows: List<TaskRow>, from: Int, to: Int): List<Long>? {
        if (from !in rows.indices || to !in rows.indices || from == to) return null
        val moved = rows[from].item
        val target = rows[to].item
        if (moved.parentId != target.parentId) return null
        val siblings = rows.filter { it.item.parentId == moved.parentId }
            .map { it.item.id }.toMutableList()
        val i = siblings.indexOf(moved.id)
        val j = siblings.indexOf(target.id)
        if (i < 0 || j < 0) return null
        siblings.removeAt(i)
        siblings.add(j, moved.id)
        return siblings
    }

    /**
     * 右スワイプ＝子タスク化。**すぐ上の兄弟**の子になる。
     * 上に兄弟が無ければ子にできない（親がいないため）ので null。
     */
    fun indentTarget(rows: List<TaskRow>, position: Int): Long? {
        val row = rows.getOrNull(position) ?: return null
        val prev = rows.take(position).lastOrNull { it.item.parentId == row.item.parentId }
            ?: return null
        return prev.item.id
    }

    /** 左スワイプ＝子をやめられるか。最上位のものはこれ以上上がれない */
    fun canOutdent(rows: List<TaskRow>, position: Int): Boolean =
        rows.getOrNull(position)?.item?.parentId != null

    /**
     * 子をやめたときの新しい親（親の親）。**null は「最上位へ上がる」という結果**で、
     * 失敗ではない。呼ぶ前に [canOutdent] で確かめること。
     */
    fun outdentParent(rows: List<TaskRow>, position: Int): Long? {
        val parentId = rows.getOrNull(position)?.item?.parentId ?: return null
        return rows.firstOrNull { it.item.id == parentId }?.item?.parentId
    }

    /**
     * 優先度の並びで動かしたときの、新しい優先度。
     *
     * 優先度は 低3/中5/高8 の3段しかないので、**落とした場所の隣に合わせる**のが
     * 一番素直に効く（高の集まりへ落としたら高になる）。
     */
    fun priorityAfterMove(rows: List<TaskRow>, movedId: Long, newIndex: Int): Int? {
        val others = rows.filter { it.item.id != movedId }
        val above = others.getOrNull(newIndex - 1)?.item?.priority
        val below = others.getOrNull(newIndex)?.item?.priority
        return above ?: below
    }
}
