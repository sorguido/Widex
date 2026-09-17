package it.poc.codexlimits

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant

object ResetCreditsClient {
    private const val RESET_CREDITS_URL =
        "https://chatgpt.com/backend-api/wham/rate-limit-reset-credits"

    data class Snapshot(
        val availableCount: Int,
        val expiries: List<Long>
    )

    fun fetch(accessToken: String, accountId: String): Snapshot? {
        var connection: HttpURLConnection? = null
        return try {
            val activeConnection =
                (URL(RESET_CREDITS_URL).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = false
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("ChatGPT-Account-ID", accountId)
                    setRequestProperty("User-Agent", "Widex/0.2")
                    setRequestProperty("Accept", "application/json")
                }
            connection = activeConnection

            val status = activeConnection.responseCode
            if (status !in 200..299) return null

            val body = activeConnection.inputStream.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
            }
            parse(JSONObject(body))
        } catch (_: Throwable) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(root: JSONObject): Snapshot? {
        if (!root.has("available_count") || root.isNull("available_count")) return null

        val availableCount = root.optInt("available_count", -1)
        if (availableCount < 0) return null

        val credits = root.optJSONArray("credits")
        val expiries = mutableListOf<Long>()

        if (credits != null) {
            for (index in 0 until credits.length()) {
                val credit = credits.optJSONObject(index) ?: continue
                val status = credit.optString("status", "available")
                if (status.isNotBlank() && !status.equals("available", ignoreCase = true)) {
                    continue
                }

                val rawExpiry = credit.optString("expires_at", "")
                parseExpiry(rawExpiry)?.let(expiries::add)
            }
        }

        return Snapshot(
            availableCount = availableCount,
            expiries = expiries.sorted()
        )
    }

    private fun parseExpiry(value: String): Long? {
        if (value.isBlank() || value.equals("null", ignoreCase = true)) return null
        return try {
            Instant.parse(value).epochSecond
        } catch (_: Throwable) {
            null
        }
    }
}
