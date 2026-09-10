package it.poc.codexlimits

import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object OpenAiClient {
    const val ISSUER = "https://auth.openai.com"
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val VERIFY_URL = "$ISSUER/codex/device"
    private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    private const val FIVE_HOURS_SECONDS = 5L * 60L * 60L
    private const val ONE_WEEK_SECONDS = 7L * 24L * 60L * 60L

    data class DeviceCode(
        val deviceAuthId: String,
        val userCode: String,
        val intervalSeconds: Long
    )

    data class TokenBundle(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val accountId: String,
        val expiresAtMillis: Long
    )

    data class UsageData(
        val plan: String,
        val shortRemaining: Int,
        val weekRemaining: Int,
        val shortResetEpoch: Long,
        val weekResetEpoch: Long
    )

    private data class UsageWindow(
        val remaining: Int,
        val resetEpoch: Long,
        val windowSeconds: Long
    )

    data class HttpResult(val status: Int, val body: String)

    fun requestDeviceCode(): DeviceCode {
        val body = JSONObject().put("client_id", CLIENT_ID).toString()
        val response = postJson("$ISSUER/api/accounts/deviceauth/usercode", body)
        checkSuccess(response, "Device code")

        val json = JSONObject(response.body)
        return DeviceCode(
            deviceAuthId = json.getString("device_auth_id"),
            userCode = json.optString("user_code", json.optString("usercode")),
            intervalSeconds = json.optString("interval", "5")
                .toLongOrNull()?.coerceAtLeast(1L) ?: 5L
        )
    }

    fun pollAuthorization(deviceCode: DeviceCode): JSONObject? {
        val body = JSONObject()
            .put("device_auth_id", deviceCode.deviceAuthId)
            .put("user_code", deviceCode.userCode)
            .toString()

        val response = postJson("$ISSUER/api/accounts/deviceauth/token", body)
        return when {
            response.status in 200..299 -> JSONObject(response.body)
            response.status == 403 || response.status == 404 -> null
            else -> throw HttpException(
                response.status,
                safe(response.body),
                "Device authorization"
            )
        }
    }

    fun exchangeAuthorizationCode(auth: JSONObject): TokenBundle {
        val response = postForm(
            "$ISSUER/oauth/token",
            linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to auth.getString("authorization_code"),
                "redirect_uri" to "$ISSUER/deviceauth/callback",
                "client_id" to CLIENT_ID,
                "code_verifier" to auth.getString("code_verifier")
            )
        )
        checkSuccess(response, "Token exchange")
        return parseTokenResponse(JSONObject(response.body), previousRefreshToken = null)
    }

    fun refreshTokens(refreshToken: String): TokenBundle {
        val response = postForm(
            "$ISSUER/oauth/token",
            linkedMapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to CLIENT_ID
            )
        )
        if (response.status !in 200..299) {
            throw HttpException(response.status, safe(response.body), "Token refresh")
        }
        return parseTokenResponse(JSONObject(response.body), previousRefreshToken = refreshToken)
    }

    fun fetchUsage(accessToken: String, accountId: String): UsageData {
        val response = get(
            USAGE_URL,
            mapOf(
                "Authorization" to "Bearer $accessToken",
                "ChatGPT-Account-ID" to accountId,
                "User-Agent" to "Widex/0.2"
            )
        )
        if (response.status !in 200..299) {
            throw HttpException(response.status, safe(response.body), "Usage")
        }

        val root = JSONObject(response.body)
        val rate = root.optJSONObject("rate_limit")
            ?: error("Usage response does not contain rate_limit")

        val primaryJson = rate.optJSONObject("primary_window")
        val secondaryJson = rate.optJSONObject("secondary_window")
        if (primaryJson == null && secondaryJson == null) {
            error("Usage response does not contain any rate-limit windows")
        }

        val primary = primaryJson?.let(::parseUsageWindow)
        val secondary = secondaryJson?.let(::parseUsageWindow)
        val windows = listOfNotNull(primary, secondary)

        // Do not assume that primary always means 5h and secondary always means a week.
        // The backend exposes each duration, so identify the windows by their duration.
        var shortWindow = windows
            .filter { it.windowSeconds in 1..(24L * 60L * 60L) }
            .minByOrNull { kotlin.math.abs(it.windowSeconds - FIVE_HOURS_SECONDS) }
        var weekWindow = windows
            .filter { it.windowSeconds >= 2L * 24L * 60L * 60L }
            .minByOrNull { kotlin.math.abs(it.windowSeconds - ONE_WEEK_SECONDS) }

        // Defensive fallback if the backend omits limit_window_seconds.
        if (shortWindow == null && weekWindow == null) {
            shortWindow = primary
            weekWindow = secondary
        } else {
            if (shortWindow == null && primary != weekWindow && primary?.windowSeconds == 0L) {
                shortWindow = primary
            }
            if (weekWindow == null && secondary != shortWindow && secondary?.windowSeconds == 0L) {
                weekWindow = secondary
            }
        }

        return UsageData(
            plan = root.optString("plan_type", "?"),
            shortRemaining = shortWindow?.remaining ?: -1,
            weekRemaining = weekWindow?.remaining ?: -1,
            shortResetEpoch = shortWindow?.resetEpoch ?: 0L,
            weekResetEpoch = weekWindow?.resetEpoch ?: 0L
        )
    }

    private fun parseUsageWindow(json: JSONObject): UsageWindow {
        val used = when {
            json.has("used_percent") -> json.optDouble("used_percent", -1.0)
            json.has("usedPercent") -> json.optDouble("usedPercent", -1.0)
            else -> -1.0
        }
        require(used >= 0.0) { "Invalid usage percentage" }

        val windowSeconds = json.optLong("limit_window_seconds", 0L)
        val resetEpoch = json.optLong("reset_at", 0L)
        return UsageWindow(
            remaining = (100.0 - used).coerceIn(0.0, 100.0).toInt(),
            resetEpoch = resetEpoch,
            windowSeconds = windowSeconds
        )
    }

    private fun parseTokenResponse(
        json: JSONObject,
        previousRefreshToken: String?
    ): TokenBundle {
        val accessToken = json.getString("access_token")
        val refreshToken = json.optString("refresh_token").ifBlank {
            previousRefreshToken.orEmpty()
        }
        require(refreshToken.isNotBlank()) { "OpenAI did not return a refresh token" }

        val idToken = json.optString("id_token", "")
        val accountId = extractAccountId(idToken)
            ?: extractAccountId(accessToken)
            ?: error("chatgpt_account_id was not found in the token")

        val expiresIn = json.optString("expires_in", "3600").toLongOrNull() ?: 3600L
        return TokenBundle(
            accessToken = accessToken,
            refreshToken = refreshToken,
            idToken = idToken,
            accountId = accountId,
            expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L
        )
    }

    private fun extractAccountId(jwt: String): String? {
        if (jwt.isBlank()) return null
        val parts = jwt.split(".")
        if (parts.size < 2) return null

        return try {
            val bytes = Base64.decode(
                parts[1],
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            val payload = JSONObject(String(bytes, StandardCharsets.UTF_8))
            payload.optString("chatgpt_account_id").takeIf { it.isNotBlank() }
                ?: payload.optJSONObject("https://api.openai.com/auth")
                    ?.optString("chatgpt_account_id")?.takeIf { it.isNotBlank() }
                ?: payload.optString("https://api.openai.com/auth.chatgpt_account_id")
                    .takeIf { it.isNotBlank() }
                ?: payload.optJSONArray("organizations")
                    ?.takeIf { it.length() > 0 }
                    ?.optJSONObject(0)
                    ?.optString("id")
                    ?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    class HttpException(
        val statusCode: Int,
        val responseBody: String,
        operation: String
    ) : RuntimeException("$operation HTTP $statusCode: $responseBody")

    private fun checkSuccess(response: HttpResult, operation: String) {
        if (response.status !in 200..299) {
            throw HttpException(response.status, safe(response.body), operation)
        }
    }

    private fun postJson(url: String, body: String): HttpResult =
        request(url, "POST", "application/json", body, emptyMap())

    private fun postForm(url: String, values: Map<String, String>): HttpResult {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${enc(key)}=${enc(value)}"
        }
        return request(
            url,
            "POST",
            "application/x-www-form-urlencoded",
            body,
            emptyMap()
        )
    }

    private fun get(url: String, headers: Map<String, String>): HttpResult =
        request(url, "GET", null, null, headers)

    private fun request(
        url: String,
        method: String,
        contentType: String?,
        body: String?,
        headers: Map<String, String>
    ): HttpResult {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            contentType?.let { setRequestProperty("Content-Type", it) }
            setRequestProperty("Accept", "application/json")
        }

        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use {
                it.write(body.toByteArray(StandardCharsets.UTF_8))
            }
        }

        val status = connection.responseCode
        val stream: InputStream? = if (status in 200..399) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val responseBody = stream?.use { input ->
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).readText()
        }.orEmpty()
        connection.disconnect()
        return HttpResult(status, responseBody)
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    fun safe(raw: String): String = raw
        .replace(
            Regex("(?i)\"(access_token|refresh_token|id_token)\"\\s*:\\s*\"[^\"]+\""),
            "\"$1\":\"<redacted>\""
        )
        .take(1200)
}
