package dev.togar.dynasched

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * dynasched://auth?token=...&email=...&name=... を受け取り、
 * トークンを保存してメイン画面へ遷移する。
 */
class AuthCallbackActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val data = intent?.data
        val token = data?.getQueryParameter("token")

        if (token.isNullOrEmpty()) {
            Toast.makeText(this, "ログインに失敗しました", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val email = data.getQueryParameter("email")
        val name = data.getQueryParameter("name")
        Prefs.saveAuth(this, token, email, name)

        Toast.makeText(this, "ログインしました", Toast.LENGTH_SHORT).show()
        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
