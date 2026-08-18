package dev.togar.dynasched.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import dev.togar.dynasched.data.Repo
import dev.togar.dynasched.MainActivity
import dev.togar.dynasched.Prefs
import dev.togar.dynasched.R
import dev.togar.dynasched.api.Api
import dev.togar.dynasched.api.ScheduledEvent

/**
 * ホーム画面ウィジェット。現在の場所（家/外・手動切替）と次の予定までの空き時間から、
 * いまできるタスク候補を GET /suggest で取得して表示する。
 */
class SuggestWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE_LOC = "dev.togar.dynasched.WIDGET_TOGGLE_LOC"
        const val ACTION_REFRESH = "dev.togar.dynasched.WIDGET_REFRESH"

        /** キャッシュ済み予定から「次の未完了予定開始までの分数」を出す（無ければ60分） */
        private fun freeMinutes(ctx: Context): Int {
            val json = Prefs.cachedEvents(ctx) ?: return 60
            return ScheduledEvent.freeMinutesUntilNext(ScheduledEvent.fromJsonArray(json))
        }

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, SuggestWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val loc = Prefs.widgetLoc(ctx)
            val min = freeMinutes(ctx)
            // まず読み込み中表示 → 取得後に本表示（RemoteViewsは取得完了後にまとめて更新）
            Thread {
                val views = RemoteViews(ctx.packageName, R.layout.widget_suggest)
                val locLabel = if (loc == "out") "外" else "家"
                views.setTextViewText(R.id.widgetTitle, "いまできること（$locLabel・${min}分）")
                try {
                    val items = Repo.current(ctx).getSuggestions(ctx, loc, min, 3)
                    val lines = listOf(R.id.widgetLine1, R.id.widgetLine2, R.id.widgetLine3)
                    for ((i, resId) in lines.withIndex()) {
                        if (i < items.size) {
                            val it = items[i]
                            val mark = if (it.kind == "material") "🎯" else "・"
                            views.setTextViewText(resId, "$mark ${it.title}（${it.minutes}分）")
                        } else {
                            views.setTextViewText(resId, "")
                        }
                    }
                    views.setTextViewText(
                        R.id.widgetStatus,
                        if (items.isEmpty()) "候補なし。ゆっくり休みましょう" else ""
                    )
                } catch (e: Exception) {
                    views.setTextViewText(R.id.widgetLine1, "取得できませんでした")
                    views.setTextViewText(R.id.widgetLine2, "")
                    views.setTextViewText(R.id.widgetLine3, "")
                    views.setTextViewText(R.id.widgetStatus, "タップで再読み込み")
                }
                attachIntents(ctx, views)
                for (id in ids) mgr.updateAppWidget(id, views)
            }.start()
        }

        private fun attachIntents(ctx: Context, views: RemoteViews) {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            fun broadcast(action: String, code: Int): PendingIntent {
                val i = Intent(ctx, SuggestWidgetProvider::class.java).apply { this.action = action }
                return PendingIntent.getBroadcast(ctx, code, i, flags)
            }
            views.setOnClickPendingIntent(R.id.widgetLocToggle, broadcast(ACTION_TOGGLE_LOC, 1))
            views.setOnClickPendingIntent(R.id.widgetRefresh, broadcast(ACTION_REFRESH, 2))
            // 本体タップでアプリを開く
            val open = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            views.setOnClickPendingIntent(
                R.id.widgetRoot,
                PendingIntent.getActivity(ctx, 3, open, flags)
            )
            // 「暇」→ アプリを開いて条件入力ダイアログを出す
            val free = Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_SHOW_FREE_TIME, true)
            views.setOnClickPendingIntent(
                R.id.widgetFree,
                PendingIntent.getActivity(ctx, 4, free, flags)
            )
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE_LOC -> {
                val cur = Prefs.widgetLoc(context)
                Prefs.setWidgetLoc(context, if (cur == "out") "home" else "out")
                updateAll(context)
            }
            ACTION_REFRESH -> updateAll(context)
        }
    }
}
