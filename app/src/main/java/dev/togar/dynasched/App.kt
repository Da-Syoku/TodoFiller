package dev.togar.dynasched

import android.app.Application
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.notify.Notifications

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 通知チャンネルを起動時に必ず用意しておく
        Notifications.ensureChannel(this)

        Api.appContext = applicationContext
        // クラッシュの自動報告はやめた。送り先のサーバーを畳んだので、
        // 送っても誰も見られない。落ちたら普通に端末のログに残る。
    }
}
