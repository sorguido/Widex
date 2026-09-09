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
            else -> throw HttpException(response.status, safe(response.body), "Device authorization")
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
            ?: error("La risposta usage non contiene rate_limit")
        val primary = rate.optJSONObject("primary_window")
            ?: error("La risposta usage non contiene primary_window")
        val secondary = rate.optJSONObject("secondary_window")
            ?: error("La risposta usage non contiene secondary_window")

        val shortUsed = primary.optInt("used_percent", -1)
        val weekUsed = secondary.optInt("used_percent", -1)
        require(shortUsed >= 0 && weekUsed >= 0) { "Percentuali usage non valide" }

        return UsageData(
            plan = root.optString("plan_type", "?"),
            shortRemaining = (100 - shortUsed).coerceIn(0, 100),
            weekRemaining = (100 - weekUsed).coerceIn(0, 100),
            shortResetEpoch = primary.optLong("reset_at", 0L),
            weekResetEpoch = secondary.optLong("reset_at", 0L)
        )
    }

    private fun parseTokenResponse(json: JSONObject, previousRefreshToken: String?): TokenBundle {
        val accessToken = json.getString("access_token")
        val refreshToken = json.optString("refresh_token").ifBlank {
            previousRefreshToken.orEmpty()
        }
        require(refreshToken.isNotBlank()) { "OpenAI non ha restituito un refresh token" }

        val idToken = json.optString("id_token", "")
        val accountId = extractAccountId(idToken)
            ?: extractAccountId(accessToken)
            ?: error("chatgpt_account_id non trovato nel token")

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
