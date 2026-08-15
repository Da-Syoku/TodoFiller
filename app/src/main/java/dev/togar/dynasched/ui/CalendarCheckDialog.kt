package dev.togar.dynasched.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Toast
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.calendar.CalendarRepo
import dev.togar.dynasched.calendar.CalendarSnapshot

/**
 * 端末のカレンダーから何が読めているかを確認するための画面。
 *
 * サーバー経由をやめて CalendarContract へ移す途中の段階で、
 * **タグの付け方と読み取り結果が一致しているか**を実機で確かめるために置いている。
 * ここが合っていないと以降の配置が全部ずれるので、最初に目で見て確認する。
 */
object CalendarCheckDialog {

    private const val REQ_PERMISSION = 4101

    fun show(activity: Activity) {
        if (!hasPermission(activity)) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR),
                REQ_PERMISSION
            )
            AlertDialog.Builder(activity)
                .setTitle("カレンダーの許可が要ります")
                .setMessage("許可したら、もう一度この画面を開いてください。")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        val days = Prefs.fillDays(activity)
        val app = activity.applicationContext
        // カレンダーの読み取りは ContentProvider 越しなので、予定が多いと目に見えて待つ。
        // メインスレッドでやると画面が固まるため、必ずワーカーで読む。
        Api.async(
            work = { CalendarRepo.read(app, days, Prefs.calendarId(app)) },
            onSuccess = { snap ->
                if (activity.isFinishing) return@async
                AlertDialog.Builder(activity)
                    .setTitle("カレンダーの読み取り結果")
                    .setMessage(summarize(snap, days))
                    .setPositiveButton("閉じる", null)
                    .setNeutralButton("カレンダーを選ぶ") { _, _ -> chooseCalendar(activity) }
                    .show()
            },
            onError = { e ->
                if (activity.isFinishing) return@async
                Toast.makeText(activity, "読み取りに失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        )
    }

    fun hasPermission(activity: Activity): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun summarize(s: CalendarSnapshot, days: Int): String {
        if (s.calendarName.isEmpty()) {
            return "端末にカレンダーが見つかりませんでした。\nGoogleカレンダーの同期がONか確認してください。"
        }
        val sb = StringBuilder()
        sb.append("カレンダー: ${s.calendarName}\n")
        sb.append("これから${days}日ぶんを読みました\n\n")
        sb.append("作業できる枠: ${s.windows.size}件\n")
        sb.append("予定あり: ${s.busy.size}件\n")
        sb.append("テスト期間: ${s.examPeriods.size}件\n")
        if (s.skippedGenerated > 0) sb.append("自動生成(%)を読み飛ばし: ${s.skippedGenerated}件\n")

        if (s.windows.isEmpty()) {
            sb.append("\n枠が0件です。予定のタイトルの末尾に「家」か「外」を付けてください。")
            sb.append("\n枠が無いと何も配置されません。")
        } else {
            sb.append("\n--- 作業できる枠 ---\n")
            for (w in s.windows.take(10)) {
                sb.append("${w.start.substring(5, 16)} 〜 ${w.end.substring(11, 16)}  ")
                sb.append(if (w.location == "out") "外" else "家")
                sb.append("  ${w.title}\n")
            }
            if (s.windows.size > 10) sb.append("…ほか${s.windows.size - 10}件\n")
        }
        if (s.examPeriods.isNotEmpty()) {
            sb.append("\n--- テスト期間 ---\n")
            for (p in s.examPeriods) sb.append("${p.startDate} 〜 ${p.endDate}\n")
        }
        return sb.toString()
    }

    /** カレンダーが複数ある時に、どれを見るかを選ばせる */
    private fun chooseCalendar(activity: Activity) {
        val cals = CalendarRepo.listCalendars(activity)
        if (cals.isEmpty()) return
        val labels = cals.map {
            (if (it.isPrimary) "★ " else "") + it.displayName + "\n" + it.accountName
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("読み取るカレンダー")
            .setItems(labels) { _, i ->
                Prefs.setCalendarId(activity, cals[i].id)
                show(activity)
            }
            .setNegativeButton("閉じる", null)
            .show()
    }
}
