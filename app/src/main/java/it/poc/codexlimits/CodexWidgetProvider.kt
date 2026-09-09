package it.poc.codexlimits

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.text.DateFormat
import java.util.Date

class CodexWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { updateOne(context, appWidgetManager, it) }
    }

    companion object {
        private const val PREFS = "codex_stats"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, CodexWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            ids.forEach { updateOne(context, manager, it) }
        }

        private fun updateOne(
            context: Context,
            manager: AppWidgetManager,
            widgetId: Int
        ) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val shortRemaining = prefs.getInt("short_remaining", -1)
            val weekRemaining = prefs.getInt("week_remaining", -1)
            val updatedAt = prefs.getLong("updated_at", 0L)

            val views = RemoteViews(context.packageName, R.layout.codex_widget)
            views.setTextViewText(
                R.id.widget_short,
                if (shortRemaining >= 0) "5h     $shortRemaining%" else "5h     —"
            )
            views.setTextViewText(
                R.id.widget_week,
                if (weekRemaining >= 0) "Week   $weekRemaining%" else "Week   —"
            )

            val updated = if (updatedAt > 0L) {
                "Agg. " + DateFormat.getDateTimeInstance(
                    DateFormat.SHORT,
                    DateFormat.SHORT
                ).format(Date(updatedAt))
            } else {
                "Apri app per aggiornare"
            }
            views.setTextViewText(R.id.widget_updated, updated)

            val intent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(widgetId, views)
        }
    }
}
