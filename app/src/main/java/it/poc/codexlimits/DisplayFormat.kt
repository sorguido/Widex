package it.poc.codexlimits

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DisplayFormat {
    fun shortReset(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "—"
        return SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(epochSeconds * 1000L))
    }

    fun weekReset(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "—"
        return SimpleDateFormat("EEE d MMM · HH:mm", Locale.getDefault())
            .format(Date(epochSeconds * 1000L))
    }

    fun updatedAt(epochMillis: Long): String {
        if (epochMillis <= 0L) return "—"
        return SimpleDateFormat("HH:mm", Locale.getDefault())
            .format(Date(epochMillis))
    }
}
