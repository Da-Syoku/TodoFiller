package dev.togar.dynasched

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.togar.dynasched.api.Api

class LoginActivity : AppCompatActivity() {

    private companion object { const val REQ_CALENDAR = 4102 }

    private lateinit var loginButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 端末内だけで動かす設定なら、ログインという概念自体が無い
        if (Prefs.localMode(this) || Prefs.isLoggedIn(this)) {
            goMain()
            return
        }

        setContentView(R.layout.activity_login)
        loginButton = findViewById(R.id.loginButton)
        statusText = findViewById(R.id.statusText)

        loginButton.setOnClickListener { startGoogleLogin() }

        // サーバーを持たない人はここで詰む。端末内だけで始められる道を必ず用意する。
        findViewById<Button>(R.id.localStartButton).setOnClickListener { startLocalOnly() }
    }

    override fun onResume() {
        super.onResume()
        // ディープリンクでトークンを受け取った後に戻ってきた場合
        if (Prefs.isLoggedIn(this)) goMain()
    }

    /**
     * サーバーを使わずに始める。
     * カレンダーの権限だけ取れれば動くので、ログインもアカウントも要らない。
     */
    private fun startLocalOnly() {
        val granted = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    android.Manifest.permission.READ_CALENDAR,
                    android.Manifest.permission.WRITE_CALENDAR
                ),
                REQ_CALENDAR
            )
            return
        }
        Prefs.setLocalMode(this, true)
        goMain()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CALENDAR) return
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Prefs.setLocalMode(this, true)
            goMain()
        } else {
            Toast.makeText(
                this, "カレンダーを読めないと予定を組めません", Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun startGoogleLogin() {
        loginButton.isEnabled = false
        statusText.text = "ログインURLを取得中…"
        val ctx = applicationContext
        Api.async(
            work = { Api.getAuthUrl(ctx) },
            onSuccess = { url ->
                loginButton.isEnabled = true
                if (url.isEmpty()) {
                    statusText.text = "ログインURLの取得に失敗しました"
                    return@async
                }
                statusText.text = "ブラウザでGoogleにログインしてください"
                openCustomTab(url)
            },
            onError = { e ->
                loginButton.isEnabled = true
                statusText.text = "接続エラー: ${Api.friendlyMessage(e)}"
                Toast.makeText(this, "サーバーに接続できませんでした", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun openCustomTab(url: String) {
        try {
            val intent = CustomTabsIntent.Builder().build()
            intent.launchUrl(this, Uri.parse(url))
        } catch (e: Exception) {
            // Custom Tab が使えない端末では通常のブラウザへ
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
