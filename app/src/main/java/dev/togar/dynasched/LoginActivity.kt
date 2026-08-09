package dev.togar.dynasched

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import dev.togar.dynasched.api.Api

class LoginActivity : AppCompatActivity() {

    private lateinit var loginButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 既にログイン済みならメイン画面へ
        if (Prefs.isLoggedIn(this)) {
            goMain()
            return
        }

        setContentView(R.layout.activity_login)
        loginButton = findViewById(R.id.loginButton)
        statusText = findViewById(R.id.statusText)

        loginButton.setOnClickListener { startGoogleLogin() }
    }

    override fun onResume() {
        super.onResume()
        // ディープリンクでトークンを受け取った後に戻ってきた場合
        if (Prefs.isLoggedIn(this)) goMain()
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
                statusText.text = "接続エラー: ${e.message}"
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
