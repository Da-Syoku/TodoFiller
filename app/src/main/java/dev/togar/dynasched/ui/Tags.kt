package dev.togar.dynasched.ui

import dev.togar.dynasched.api.HobbyItem

/**
 * タスクのタグ。
 *
 * DBには**カンマ区切りの1列**で持つ。別テーブルにすると、書き出し・取り込み・
 * 並び替え・親子の付け替えのすべてに関係が増える。タグは数十個の話なので、
 * 1列に収めて読み書きを単純に保つほうが壊れにくい。
 *
 * 入力は自由。打ち間違いは**既存タグから選べる**ことで減らす（設計上の答えは
 * 「禁止する」ではなく「選ばせる」）。
 */
object Tags {

    private const val SEPARATOR = ","

    /** 表記ゆれを潰す。前後の空白と先頭の # は落とし、空は捨てる */
    fun normalize(raw: String): String = parse(raw).joinToString(SEPARATOR)

    /** 保存形・入力形どちらからでもタグの一覧にする */
    fun parse(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', ' ', '　', '\n', '\t')
            .map { it.trim().removePrefix("#").removePrefix("＃").trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /** 画面に出す形 */
    fun display(raw: String?): String =
        parse(raw).joinToString(" ") { "#$it" }

    /** いま使われているタグ全部（多い順、次に名前順） */
    fun known(items: List<HobbyItem>): List<String> =
        items.flatMap { parse(it.tags) }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenBy { it.key })
            .map { it.key }

    fun has(item: HobbyItem, tag: String): Boolean = parse(item.tags).contains(tag)

    fun hasAny(item: HobbyItem, tags: Set<String>): Boolean {
        if (tags.isEmpty()) return true
        val own = parse(item.tags)
        return tags.any { own.contains(it) }
    }

    /**
     * 選んだタグで木を絞る。
     *
     * **自分か子孫のどれかが当てはまれば残す。** 当てはまった葉だけを残すと
     * 親が消えて、子タスクが行き場を失った状態で並ぶ。木の形を保ったまま
     * 「関係のある枝だけ」にするのが目的。
     */
    fun filterTree(all: List<HobbyItem>, selected: Set<String>): List<HobbyItem> {
        if (selected.isEmpty()) return all
        val byParent = all.groupBy { it.parentId }
        val keep = HashSet<Long>()

        fun visit(item: HobbyItem, seen: MutableSet<Long>): Boolean {
            if (!seen.add(item.id)) return false
            var hit = hasAny(item, selected)
            for (c in byParent[item.id].orEmpty()) {
                if (visit(c, seen)) hit = true
            }
            if (hit) keep.add(item.id)
            return hit
        }

        val existing = all.mapTo(HashSet()) { it.id }
        for (r in all) {
            if (r.parentId == null || !existing.contains(r.parentId)) visit(r, HashSet())
        }
        // 残した枝の途中が抜けないよう、祖先も戻す
        val byId = all.associateBy { it.id }
        for (id in keep.toList()) {
            var p = byId[id]?.parentId
            while (p != null && keep.add(p)) p = byId[p]?.parentId
        }
        return all.filter { keep.contains(it.id) }
    }
}
