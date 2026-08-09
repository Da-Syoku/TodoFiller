package dev.togar.dynasched

import android.app.Application
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.notify.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 通知チャンネルを起動時に必ず用意しておく
        Notifications.ensureChannel(this)

        // API失敗の自動報告用にContextを渡す
        Api.appContext = applicationContext

        // クラッシュ（未捕捉例外）をサーバーへ報告してから通常のクラッシュ処理へ渡す
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val t = Thread { Api.reportError(applicationContext, "crash", e) }
                t.start()
                t.join(3000) // 送信を最大3秒だけ待つ
            } catch (ignore: Exception) {
            }
            prev?.uncaughtException(thread, e)
        }
    }
}
