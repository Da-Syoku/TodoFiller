package dev.togar.dynasched.data

/**
 * スケジューラを1回動かした結果。
 *
 * **「実行しました」とだけ言って何も起きていない**のが一番たちが悪い。
 * 予定が生成されない理由は、枠が無い・締切を過ぎた・テスト期間・前提の教材待ち…と
 * いくつもあって、どれも黙って起こる。使う側からは自分の設定ミスなのか
 * アプリの不具合なのか区別が付かないので、materialize した数と
 * 落とした理由をそのまま持ち帰る。
 */
data class RunReport(
    /** 端末内方式ではない（サーバーに投げたので中身が分からない） */
    val serverMode: Boolean = false,
    val calendarName: String = "",
    val calendarWritable: Boolean = true,
    /** 何日先まで配置したか / 計画を見た日数 */
    val fillDays: Int = 0,
    val planDays: Int = 0,
    val windows: Int = 0,
    val busy: Int = 0,
    val examPeriods: Int = 0,
    /** 配置に使える空き時間の合計（分, fillDays ぶん） */
    val freeMinutes: Int = 0,
    val materialsActive: Int = 0,
    val hobbiesActive: Int = 0,
    val placedStudy: Int = 0,
    val placedHobby: Int = 0,
    val placedMinutes: Int = 0,
    /** カレンダーへの書き戻し。書けていなければ 0 のまま */
    val calRemoved: Int = 0,
    val calAdded: Int = 0,
    /** 配置されなかった教材の理由（「教材名: 理由」） */
    val skipped: List<String> = emptyList()
) {
    val placed: Int get() = placedStudy + placedHobby

    /** 設定画面に出す本文 */
    fun describe(): String {
        if (serverMode) return "サーバーに再生成を依頼しました。\n結果はサーバー側で処理されます。"
        val sb = StringBuilder()
        sb.append("カレンダー: ${calendarName.ifEmpty { "(見つかりません)" }}\n")
        if (!calendarWritable) sb.append("⚠ このカレンダーは読み取り専用です。書き込めません。\n")
        sb.append("読み取り: 枠${windows}件 / 予定${busy}件 / テスト期間${examPeriods}件\n")
        sb.append("${fillDays}日ぶんの空き: ${freeMinutes}分\n")
        sb.append("教材${materialsActive}件 / 単発${hobbiesActive}件\n\n")
        sb.append("配置: 勉強${placedStudy}コマ + 単発${placedHobby}件 (計${placedMinutes}分)\n")
        sb.append("カレンダー: 削除${calRemoved}件 / 追加${calAdded}件\n")

        if (placed == 0) {
            sb.append("\n--- 1件も置けませんでした ---\n")
            when {
                calendarName.isEmpty() ->
                    sb.append("端末にカレンダーがありません。Googleカレンダーの同期を確認してください。")
                windows == 0 ->
                    sb.append("作業できる枠が0件です。予定のタイトルの末尾に「家」か「外」を付けてください。")
                freeMinutes == 0 ->
                    sb.append("枠はありますが、他の予定で全部埋まっています。")
                materialsActive == 0 && hobbiesActive == 0 ->
                    sb.append("置くものがありません。教材か単発タスクを追加してください。")
                else -> sb.append("下の理由を見てください。")
            }
            sb.append("\n")
        }
        if (calAdded == 0 && placed > 0) {
            sb.append("\n⚠ 予定は作れましたが**カレンダーに1件も書けていません**。\n")
            sb.append("設定→カレンダーの読み取りを確認→書き込みテスト で原因が出ます。\n")
        }
        if (skipped.isNotEmpty()) {
            sb.append("\n--- 置かなかった教材 ---\n")
            for (s in skipped.take(15)) sb.append("・$s\n")
            if (skipped.size > 15) sb.append("…ほか${skipped.size - 15}件\n")
        }
        return sb.toString()
    }
}
