package it.poc.codexlimits

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.concurrent.Executors

class CodexWidgetProvider : AppWidgetProvider() {
    companion object {
        private const val ACTION_REFRESH = "it.poc.codexlimits.ACTION_REFRESH"
        private val executor = Executors.newSingleThreadExecutor()

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CodexWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach {
                updateOne(context, manager, it)
            }
        }

        private fun updateOne(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.codex_widget)
            val authenticated = SecureAuthStore.hasCredentials(context)
            val usage = UsageRepository.readCached(context)

            if (!authenticated) {
                setUnavailable(
                    views,
                    shortWindow = true,
                    message = context.getString(R.string.access_required)
                )
                setUnavailable(
                    views,
                    shortWindow = false,
                    message = context.getString(R.string.open_widex)
                )
                views.setTextViewText(R.id.widget_updated, "")
            } else if (usage == null) {
                val tapRefresh = context.getString(R.string.tap_refresh)
                setUnavailable(views, shortWindow = true, message = tapRefresh)
                setUnavailable(views, shortWindow = false, message = tapRefresh)
                views.setTextViewText(
                    R.id.widget_updated,
                    context.getString(R.string.tap_update)
                )
            } else {
                if (usage.shortRemaining >= 0) {
                    views.setTextViewText(R.id.widget_short_value, "${usage.shortRemaining}%")
                    views.setProgressBar(R.id.widget_short_bar, 100, usage.shortRemaining, false)
                    views.setTextViewText(
                        R.id.widget_short_reset,
                        context.getString(
                            R.string.reset,
                            DisplayFormat.shortReset(usage.shortResetEpoch)
                        )
                    )
                } else {
                    setUnavailable(
                        views,
                        shortWindow = true,
                        message = context.getString(R.string.not_provided)
                    )
                }

                if (usage.weekRemaining >= 0) {
                    views.setTextViewText(R.id.widget_week_value, "${usage.weekRemaining}%")
                    views.setProgressBar(R.id.widget_week_bar, 100, usage.weekRemaining, false)
                    views.setTextViewText(
                        R.id.widget_week_reset,
                        context.getString(
                            R.string.reset,
                            DisplayFormat.weekReset(usage.weekResetEpoch)
                        )
                    )
                } else {
                    setUnavailable(
                        views,
                        shortWindow = false,
                        message = context.getString(R.string.not_provided)
                    )
                }

                views.setTextViewText(
                    R.id.widget_updated,
                    context.getString(
                        R.string.widget_updated,
                        DisplayFormat.updatedAt(usage.updatedAtMillis)
                    )
                )
            }

            val openIntent = Intent(context, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                context,
                10,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPendingIntent)

            val refreshIntent = Intent(context, CodexWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                11,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)

            manager.updateAppWidget(widgetId, views)
        }

        private fun setUnavailable(views: RemoteViews, shortWindow: Boolean, message: String) {
            if (shortWindow) {
                views.setTextViewText(R.id.widget_short_value, "—")
                views.setProgressBar(R.id.widget_short_bar, 100, 0, false)
                views.setTextViewText(R.id.widget_short_reset, message)
            } else {
                views.setTextViewText(R.id.widget_week_value, "—")
                views.setProgressBar(R.id.widget_week_bar, 100, 0, false)
                views.setTextViewText(R.id.widget_week_reset, message)
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        RefreshScheduler.schedule(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateOne(context, appWidgetManager, it) }
        RefreshScheduler.schedule(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REFRESH) {
            super.onReceive(context, intent)
            return
        }

        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, CodexWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.codex_widget)
            views.setTextViewText(
                R.id.widget_updated,
                context.getString(R.string.widget_updating)
            )
            manager.partiallyUpdateAppWidget(widgetId, views)
        }

        val pendingResult = goAsync()
        executor.execute {
            try {
                UsageRepository.refresh(context.applicationContext)
                updateAll(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
