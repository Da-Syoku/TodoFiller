package dev.togar.dynasched.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 生成した予定を端末のカレンダーへ書き戻す。ここへ書けばGoogleカレンダーへ同期して戻る。
 *
 * **自動生成の目印は「タイトル末尾の `%`」**。サーバー版から変えていない。
 * CalendarContract の拡張プロパティは同期アダプタ権限が要ることがあるのに対し、
 * `%` は端末でもGoogleカレンダー上でも同じに見えて、人間が見ても判別できる。
 * 既にマニュアルで「末尾が % のものは触らない」と説明済みなので、運用も変わらない。
 */
object CalendarWriter {

    private const val DAY_MS = 24L * 3600 * 1000

    /**
     * 生成済み予定を消して、渡された予定を書き直す（全置換）。
     * 差分更新にすると、消し漏れた予定がカレンダーに残り続ける。
     *
     * @return 削除数 と 追加数
     */
    fun replaceGenerated(
        ctx: Context, calendarId: Long,
        events: List<Triple<String, String, String>>,   // (title, start, end) いずれもローカル文字列
        daysAhead: Int
    ): Pair<Int, Int> {
        val removed = clearGenerated(ctx, calendarId, daysAhead)
        var added = 0
        for ((title, start, end) in events) {
            val s = parseLocal(start) ?: continue
            val e = parseLocal(end) ?: continue
            if (insert(ctx, calendarId, title + " " + CalendarRepo.GENERATED_SUFFIX, s, e)) added++
        }
        return removed to added
    }

    /**
     * 末尾が `%` の予定を消す。**過去のものは消さない**（やった記録として残す）。
     */
    fun clearGenerated(ctx: Context, calendarId: Long, daysAhead: Int): Int {
        val now = System.currentTimeMillis()
        return clearGeneratedBetween(ctx, calendarId, now, now + (daysAhead + 1).toLong() * DAY_MS)
    }

    /**
     * 指定した期間の自動生成予定を消す。
     *
     * 掃除用に**過去も含めて**消せる形を分けてある。サーバー時代に別カレンダーへ
     * 書かれた予定は、端末内方式のアプリからは視界の外にあって普通の実行では消えない。
     */
    fun clearGeneratedBetween(ctx: Context, calendarId: Long, fromMs: Long, toMs: Long): Int {
        var removed = 0
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().apply {
            ContentUris.appendId(this, fromMs)
            ContentUris.appendId(this, toMs)
        }.build()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE),
                "${CalendarContract.Instances.CALENDAR_ID}=?", arrayOf(calendarId.toString()), null
            )?.use { c ->
                val ids = HashSet<Long>()
                while (c.moveToNext()) {
                    val title = c.getString(1) ?: continue
                    if (title.trim().endsWith(CalendarRepo.GENERATED_SUFFIX)) ids.add(c.getLong(0))
                }
                for (id in ids) {
                    val n = ctx.contentResolver.delete(
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null
                    )
                    removed += n
                }
            }
        } catch (e: SecurityException) {
            return 0
        } catch (e: Exception) {
            return removed
        }
        return removed
    }

    /**
     * 教材の試験日をカレンダーへ。23:59 か 00:00 なら終日、それ以外はその時刻に終わる1時間の予定。
     * 既存のIDが渡されたら書き換え、無ければ新規作成してIDを返す。
     */
    fun upsertDeadline(
        ctx: Context, calendarId: Long, title: String, deadline: String, existingId: Long?
    ): Long? {
        val end = parseLocal(deadline) ?: return null
        val cal = Calendar.getInstance().apply { timeInMillis = end }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val mi = cal.get(Calendar.MINUTE)
        val allDay = (h == 23 && mi == 59) || (h == 0 && mi == 0)

        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            if (allDay) {
                // 終日はUTCの深夜で入れる決まり
                val day = utcMidnightOf(end)
                put(CalendarContract.Events.ALL_DAY, 1)
                put(CalendarContract.Events.DTSTART, day)
                put(CalendarContract.Events.DTEND, day + DAY_MS)
                put(CalendarContract.Events.EVENT_TIMEZONE, "UTC")
            } else {
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.DTSTART, end - 3600_000L)
                put(CalendarContract.Events.DTEND, end)
            }
        }
        return try {
            if (existingId != null) {
                val n = ctx.contentResolver.update(
                    ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, existingId),
                    v, null, null
                )
                if (n > 0) existingId else insertRaw(ctx, v)
            } else insertRaw(ctx, v)
        } catch (e: Exception) { null }
    }

    fun deleteEvent(ctx: Context, eventId: Long) {
        try {
            ctx.contentResolver.delete(
                ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null
            )
        } catch (e: Exception) { /* 既に消えていても構わない */ }
    }

    // ---- 内部 ----

    private fun insert(ctx: Context, calendarId: Long, title: String, start: Long, end: Long): Boolean {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, end)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.ALL_DAY, 0)
        }
        return insertRaw(ctx, v) != null
    }

    private fun insertRaw(ctx: Context, v: ContentValues): Long? = try {
        ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            ?.let { ContentUris.parseId(it) }
    } catch (e: SecurityException) { null } catch (e: Exception) { null }

    /** `yyyy-MM-dd'T'HH:mm:ss` を端末ローカルとして読む */
    private fun parseLocal(s: String): Long? = try {
        val t = if (s.length == 16) "$s:00" else s
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getDefault() }.parse(t)?.time
    } catch (e: Exception) { null }

    /** その瞬間が属するローカル日付の、UTC深夜 */
    private fun utcMidnightOf(millis: Long): Long {
        val local = Calendar.getInstance().apply { timeInMillis = millis }
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        }.timeInMillis
    }
}
