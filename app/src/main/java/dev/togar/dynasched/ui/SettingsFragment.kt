package dev.togar.dynasched.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.BuildConfig
import dev.togar.dynasched.LoginActivity
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.notify.AlarmScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 設定画面：ユーザー情報・通知再設定・スケジューラ再実行・ログアウト */
class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.fragment_settings, container, false)

        val userText = root.findViewById<TextView>(R.id.userText)
        val urlText = root.findViewById<TextView>(R.id.urlText)
        val resyncBtn = root.findViewById<Button>(R.id.resyncButton)
        val runBtn = root.findViewById<Button>(R.id.runSchedulerButton)
        val checkUpdateBtn = root.findViewById<Button>(R.id.checkUpdateButton)
        val versionText = root.findViewById<TextView>(R.id.versionText)
        val fillDaysInput = root.findViewById<android.widget.EditText>(R.id.fillDaysInput)
        val logoutBtn = root.findViewById<Button>(R.id.logoutButton)

        fillDaysInput.setText(Prefs.fillDays(requireContext()).toString())

        val local = Prefs.localMode(requireContext())
        val name = Prefs.name(requireContext())
        val email = Prefs.email(requireContext())
        // 端末内方式ではログインもサーバーも無いので、その話を出さない
        userText.text = when {
            local -> "端末内で動作中"
            name.isNotEmpty() || email.isNotEmpty() -> "$name\n$email"
            else -> "ログイン中"
        }
        urlText.text = if (local) "サーバーは使っていません"
                       else "接続先: ${BuildConfig.API_BASE_URL}"
        logoutBtn.visibility = if (local) View.GONE else View.VISIBLE
        versionText.text = "現在のバージョン: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        resyncBtn.setOnClickListener { resyncNotifications() }
        root.findViewById<Button>(R.id.calendarCheckButton).setOnClickListener {
            CalendarCheckDialog.show(requireActivity())
        }
        setupModeSwitch(root)
        runBtn.setOnClickListener {
            // 入力された日数を保存してからスケジューラを実行
            val n = fillDaysInput.text.toString().toIntOrNull() ?: Prefs.DEFAULT_FILL_DAYS
            Prefs.setFillDays(requireContext(), n)
            fillDaysInput.setText(Prefs.fillDays(requireContext()).toString())
            runScheduler()
        }
        checkUpdateBtn.setOnClickListener {
            Toast.makeText(requireContext(), "更新を確認中…", Toast.LENGTH_SHORT).show()
            (requireActivity() as? androidx.appcompat.app.AppCompatActivity)?.let {
                dev.togar.dynasched.update.UpdateChecker.check(it, force = true)
            }
        }
        logoutBtn.setOnClickListener { logout() }

        return root
    }

    private fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * サーバー方式と端末内方式の切替。両方の実装を残してあるので行き来できる。
     * 端末内にするにはカレンダー権限が要るので、無ければ先に取る。
     */
    private fun setupModeSwitch(root: View) {
        val sw = root.findViewById<android.widget.Switch>(R.id.localModeSwitch)
        val hint = root.findViewById<TextView>(R.id.localModeHint)
        val importBtn = root.findViewById<Button>(R.id.importButton)

        fun refreshHint() {
            hint.text = if (Prefs.localMode(requireContext()))
                "端末内で動いています。ログインもサーバーも使いません。"
            else
                "サーバー(${BuildConfig.API_BASE_URL})を使っています。"
            importBtn.isEnabled = !Prefs.localMode(requireContext())
        }

        sw.isChecked = Prefs.localMode(requireContext())
        refreshHint()

        sw.setOnCheckedChangeListener { _, checked ->
            if (checked && !CalendarCheckDialog.hasPermission(requireActivity())) {
                sw.isChecked = false
                CalendarCheckDialog.show(requireActivity())   // 権限を要求する
                return@setOnCheckedChangeListener
            }
            if (checked && !Prefs.imported(requireContext())) {
                sw.isChecked = false
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("先に取り込みますか？")
                    .setMessage("サーバーのデータをまだ端末へ取り込んでいません。\n取り込まずに切り替えると、端末側は空の状態から始まります。")
                    .setPositiveButton("取り込む") { _, _ -> runImport { sw.isChecked = true } }
                    .setNegativeButton("空のまま切り替える") { _, _ ->
                        Prefs.setLocalMode(requireContext(), true)
                        sw.isChecked = true
                        refreshHint()
                    }
                    .setNeutralButton("やめる", null)
                    .show()
                return@setOnCheckedChangeListener
            }
            Prefs.setLocalMode(requireContext(), checked)
            refreshHint()
            Toast.makeText(
                requireContext(),
                if (checked) "端末内で動かします" else "サーバーを使います",
                Toast.LENGTH_SHORT
            ).show()
        }

        importBtn.setOnClickListener { runImport(null) }
    }

    /** サーバーから端末へ1度だけ引き取る */
    private fun runImport(onDone: (() -> Unit)?) {
        Toast.makeText(requireContext(), "取り込み中…", Toast.LENGTH_SHORT).show()
        val ctx = requireContext().applicationContext
        Api.async(
            work = { dev.togar.dynasched.data.Importer.importFromServer(ctx) },
            onSuccess = { r ->
                if (!isAdded) return@async
                if (r.error != null) {
                    Toast.makeText(requireContext(), "取り込めませんでした: ${r.error}", Toast.LENGTH_LONG).show()
                    return@async
                }
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("取り込みました")
                    .setMessage(
                        "教材 ${r.materials}件 / 単発タスク ${r.hobbies}件\n\n" +
                            "実績の明細はサーバーから取れないので、実測ペースはここから測り直しになります。\n" +
                            "「前提の教材」の設定も引き継げないので、必要なら入れ直してください。"
                    )
                    .setPositiveButton("OK") { _, _ -> onDone?.invoke() }
                    .show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun resyncNotifications() {
        Toast.makeText(requireContext(), "通知を再設定中…", Toast.LENGTH_SHORT).show()
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).getSchedule(ctx, today()) },
            onSuccess = { all ->
                if (!isAdded) return@async
                AlarmScheduler.scheduleAll(ctx, all)
                dev.togar.dynasched.integration.StudySync.send(ctx, all)
                Toast.makeText(requireContext(), "通知を再設定しました", Toast.LENGTH_SHORT).show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }

    /**
     * 再生成。**結果を必ず数字で見せる。**
     *
     * 以前は成功トーストを出すだけだったので、0件しか置けていない時と
     * 正常な時が見分けられなかった。「使い方が悪いのか不具合なのか」を
     * ここで判別できるようにしておく。
     */
    private fun runScheduler() {
        val days = Prefs.fillDays(requireContext())
        Toast.makeText(requireContext(), "スケジューラ実行中…（${days}日先まで）", Toast.LENGTH_SHORT).show()
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Repo.current(ctx).runScheduler(ctx, days) },
            onSuccess = { report ->
                if (!isAdded) return@async
                androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(
                        if (report.serverMode) "再生成しました"
                        else "再生成: ${report.placed}件"
                    )
                    .setMessage(report.describe())
                    .setPositiveButton("閉じる", null)
                    .show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun logout() {
        Prefs.logout(requireContext())
        val intent = Intent(requireContext(), LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        requireActivity().finish()
    }
}
