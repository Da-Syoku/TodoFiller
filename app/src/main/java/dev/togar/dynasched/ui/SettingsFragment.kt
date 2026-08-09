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

        val name = Prefs.name(requireContext())
        val email = Prefs.email(requireContext())
        userText.text = if (name.isNotEmpty() || email.isNotEmpty())
            "$name\n$email" else "ログイン中"
        urlText.text = "接続先: ${BuildConfig.API_BASE_URL}"
        versionText.text = "現在のバージョン: v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        resyncBtn.setOnClickListener { resyncNotifications() }
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

    private fun resyncNotifications() {
        Toast.makeText(requireContext(), "通知を再設定中…", Toast.LENGTH_SHORT).show()
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.getSchedule(ctx, today()) },
            onSuccess = { all ->
                if (!isAdded) return@async
                AlarmScheduler.scheduleAll(ctx, all)
                Toast.makeText(requireContext(), "通知を再設定しました", Toast.LENGTH_SHORT).show()
            },
            onError = { e ->
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "失敗: ${Api.friendlyMessage(e)}", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun runScheduler() {
        val days = Prefs.fillDays(requireContext())
        Toast.makeText(requireContext(), "スケジューラ実行中…（${days}日先まで）", Toast.LENGTH_SHORT).show()
        val ctx = requireContext().applicationContext
        Api.async(
            work = { Api.runScheduler(ctx, days) },
            onSuccess = {
                if (!isAdded) return@async
                Toast.makeText(requireContext(), "スケジュールを再生成し、Googleカレンダーに反映しました", Toast.LENGTH_LONG).show()
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
