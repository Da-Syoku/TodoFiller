package dev.togar.dynasched

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduleRepo
import dev.togar.dynasched.ui.FreeTimeDialog
import dev.togar.dynasched.ui.MaterialFragment
import dev.togar.dynasched.ui.HobbyFragment
import dev.togar.dynasched.ui.SettingsFragment
import dev.togar.dynasched.ui.TodayFragment
import dev.togar.dynasched.update.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        /** ウィジェットの「暇」から開かれたとき、条件入力ダイアログを出す */
        const val EXTRA_SHOW_FREE_TIME = "show_free_time"
    }

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 結果は問わない */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 端末内だけで動かす設定ならログインは要らない
        if (!Prefs.localMode(this) && !Prefs.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        askNotificationPermission()
        syncNotifications()  // 予定タブを廃止したので、起動時に通知予約を更新する
        dev.togar.dynasched.notify.DailyRunReceiver.schedule(this)  // 毎日の自動実行を仕掛け直す
        dev.togar.dynasched.notify.BedtimeReceiver.schedule(this)   // 「今日はここまで」
        UpdateChecker.check(this)  // オンライン更新の確認（新しい版があれば案内）

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_today -> TodayFragment()
                R.id.nav_single -> HobbyFragment()
                R.id.nav_material -> MaterialFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> TodayFragment()
            }
            showFragment(fragment)
            true
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_today
            if (intent?.getBooleanExtra(EXTRA_SHOW_FREE_TIME, false) == true) {
                FreeTimeDialog.show(this)
            }
        }
    }

    /** すでに起動している状態でウィジェットの「暇」が押されたとき */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SHOW_FREE_TIME, false)) {
            FreeTimeDialog.show(this)
        }
    }

    override fun onStart() {
        super.onStart()
        // 端末内方式ではトークンを使わないので、失効の面倒を見る必要がない
        if (Prefs.localMode(this)) return
        // トークンが切れた時は、各画面がエラーを出す前にログインへ戻す
        Api.onUnauthorized = {
            if (!isFinishing && !isDestroyed) {
                Prefs.logout(this)
                Toast.makeText(this, "ログインの有効期限が切れました", Toast.LENGTH_LONG).show()
                val intent = Intent(this, LoginActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Api.onUnauthorized = null
    }

    /**
     * 今日の予定を取得してローカル通知を予約し直す（バックグラウンド）。
     * 予定タブを廃止したので起動時に必ず1度通す。TodayFragment も同じものを見るが、
     * ScheduleRepo が重複要求をまとめるので通信は1回で済む。
     */
    private fun syncNotifications() {
        val ctx = applicationContext
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        Api.async(
            work = { ScheduleRepo.refresh(ctx, today) },
            onSuccess = { /* 予約はrefreshの中で済んでいる */ },
            onError = { /* 通信できない時は何もしない */ }
        )
    }

    private fun showFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .commit()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
