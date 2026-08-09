package dev.togar.dynasched

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.notify.AlarmScheduler
import dev.togar.dynasched.ui.GoalFragment
import dev.togar.dynasched.ui.HobbyFragment
import dev.togar.dynasched.ui.SettingsFragment
import dev.togar.dynasched.update.UpdateChecker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 結果は問わない */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Prefs.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)
        askNotificationPermission()
        syncNotifications()  // 予定タブを廃止したので、起動時に通知予約を更新する
        UpdateChecker.check(this)  // オンライン更新の確認（新しい版があれば案内）

        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_single -> HobbyFragment()
                R.id.nav_goal -> GoalFragment()
                R.id.nav_settings -> SettingsFragment()
                else -> HobbyFragment()
            }
            showFragment(fragment)
            true
        }

        if (savedInstanceState == null) {
            nav.selectedItemId = R.id.nav_single
        }
    }

    /** 今日の予定を取得してローカル通知を予約し直す（バックグラウンド） */
    private fun syncNotifications() {
        val ctx = applicationContext
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        Api.async(
            work = { Api.getSchedule(ctx, today) },
            onSuccess = { events -> AlarmScheduler.scheduleAll(ctx, events) },
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
