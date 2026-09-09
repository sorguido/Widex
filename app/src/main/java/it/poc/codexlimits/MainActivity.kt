package it.poc.codexlimits

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class MainActivity : Activity() {

    companion object {
        private const val ISSUER = "https://auth.openai.com"
        private const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        private const val VERIFY_URL = "$ISSUER/codex/device"
        private const val USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
    }

    private data class DeviceCode(
        val deviceAuthId: String,
        val userCode: String,
        val intervalSeconds: Long
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var codeText: TextView
    private lateinit var openBrowser: Button
    private lateinit var completeLogin: Button
    private lateinit var fetchAgain: Button

    private var deviceCode: DeviceCode? = null
    private var accessToken: String? = null
    private var accountId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        val scroll = ScrollView(this).apply { addView(container) }

        fun addButton(label: String): Button =
            Button(this).also { it.text = label; container.addView(it) }

        val title = TextView(this).apply {
            text = "Codex Limits — PoC"
            textSize = 24f
        }
        container.addView(title)

        val warning = TextView(this).apply {
            text = """

                PoC diagnostico:
                1) device-code login
                2) token solo in RAM
                3) GET /wham/usage
                4) cache locale delle sole percentuali

                Nessuna password e nessun token vengono scritti su disco.
            """.trimIndent()
            textSize = 14f
        }
        container.addView(warning)

        val requestCode = addButton("1. Richiedi codice OpenAI")

        codeText = TextView(this).apply {
            text = "Codice: —"
            textSize = 22f
            setPadding(0, 20, 0, 20)
        }
        container.addView(codeText)

        openBrowser = addButton("2. Apri pagina OpenAI").apply {
            isEnabled = false
        }
        completeLogin = addButton("3. Completa login e leggi limiti").apply {
            isEnabled = false
        }
        fetchAgain = addButton("Rileggi limiti").apply {
            isEnabled = false
        }

        status = TextView(this).apply {
            text = "\nPronto."
            textSize = 14f
            setTextIsSelectable(true)
        }
        container.addView(status)

        setContentView(scroll)

        requestCode.setOnClickListener { requestDeviceCode() }

        openBrowser.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VERIFY_URL)))
        }

        completeLogin.setOnClickListener {
            completeLoginAndFetch()
        }

        fetchAgain.setOnClickListener {
            fetchUsage()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun requestDeviceCode() {
        setBusy("Richiedo il codice a OpenAI…")
        executor.execute {
            try {
                val body = JSONObject().put("client_id", CLIENT_ID).toString()
                val response = postJson(
                    "$ISSUER/api/accounts/deviceauth/usercode",
                    body
                )
                if (response.status !in 200..299) {
                    fail("Device-code HTTP ${response.status}\n${safe(response.body)}")
                    return@execute
                }

                val json = JSONObject(response.body)
                val dc = DeviceCode(
                    deviceAuthId = json.getString("device_auth_id"),
                    userCode = json.optString(
                        "user_code",
                        json.optString("usercode")
                    ),
                    intervalSeconds = json.optString("interval", "5")
                        .toLongOrNull()
                        ?.coerceAtLeast(1L) ?: 5L
                )
                deviceCode = dc

                main.post {
                    codeText.text = "Codice: ${dc.userCode}"
                    openBrowser.isEnabled = true
                    completeLogin.isEnabled = true
                    status.text =
                        "Codice ottenuto. Apri OpenAI, accedi e inserisci il codice."
                }
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    private fun completeLoginAndFetch() {
        val dc = deviceCode ?: return
        setBusy("Attendo autorizzazione OpenAI…")

        executor.execute {
            try {
                val deadline = System.currentTimeMillis() + 15 * 60 * 1000L
                var authCode: JSONObject? = null

                while (System.currentTimeMillis() < deadline) {
                    val pollBody = JSONObject()
                        .put("device_auth_id", dc.deviceAuthId)
                        .put("user_code", dc.userCode)
                        .toString()

                    val poll = postJson(
                        "$ISSUER/api/accounts/deviceauth/token",
                        pollBody
                    )

                    if (poll.status in 200..299) {
                        authCode = JSONObject(poll.body)
                        break
                    }

                    if (poll.status != 403 && poll.status != 404) {
                        fail("Polling HTTP ${poll.status}\n${safe(poll.body)}")
                        return@execute
                    }

                    Thread.sleep(dc.intervalSeconds * 1000L)
                }

                if (authCode == null) {
                    fail("Autorizzazione non completata entro 15 minuti.")
                    return@execute
                }

                val authorizationCode = authCode.getString("authorization_code")
                val codeVerifier = authCode.getString("code_verifier")

                val form = linkedMapOf(
                    "grant_type" to "authorization_code",
                    "code" to authorizationCode,
                    "redirect_uri" to "$ISSUER/deviceauth/callback",
                    "client_id" to CLIENT_ID,
                    "code_verifier" to codeVerifier
                )

                val tokenResp = postForm("$ISSUER/oauth/token", form)
                if (tokenResp.status !in 200..299) {
                    fail("Token exchange HTTP ${tokenResp.status}\n${safe(tokenResp.body)}")
                    return@execute
                }

                val tokenJson = JSONObject(tokenResp.body)
                val access = tokenJson.getString("access_token")
                val idToken = tokenJson.optString("id_token", "")

                val acct = extractAccountId(idToken)
                    ?: extractAccountId(access)
                    ?: run {
                        fail("Login riuscito, ma chatgpt_account_id non trovato nel token.")
                        return@execute
                    }

                // PoC: token SOLO in RAM.
                accessToken = access
                accountId = acct

                main.post {
                    status.text = "Login riuscito. Leggo i limiti…"
                }
                fetchUsageOnWorker(access, acct)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    private fun fetchUsage() {
        val token = accessToken
        val acct = accountId
        if (token == null || acct == null) {
            status.text = "Sessione PoC non presente in RAM: rifai il login."
            return
        }
        setBusy("Leggo /wham/usage…")
        executor.execute {
            try {
                fetchUsageOnWorker(token, acct)
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    private fun fetchUsageOnWorker(token: String, acct: String) {
        val response = get(
            USAGE_URL,
            mapOf(
                "Authorization" to "Bearer $token",
                "ChatGPT-Account-ID" to acct,
                "User-Agent" to "CodexLimitsWidgetPoC/0.1"
            )
        )

        if (response.status !in 200..299) {
            fail(
                "USAGE HTTP ${response.status}\n" +
                    safe(response.body) +
                    "\n\nSe è 401/403, fermarsi qui: il PoC ha dimostrato che " +
                    "l'accesso diretto Android→wham non è accettato con questa sessione."
            )
            return
        }

        val root = JSONObject(response.body)
        val rate = root.optJSONObject("rate_limit")
            ?: run {
                fail("HTTP 200 ma manca rate_limit.\n${safe(response.body)}")
                return
            }

        val primary = rate.optJSONObject("primary_window")
        val secondary = rate.optJSONObject("secondary_window")

        val primaryUsed = primary?.optInt("used_percent", -1) ?: -1
        val secondaryUsed = secondary?.optInt("used_percent", -1) ?: -1

        val primaryRemaining =
            if (primaryUsed >= 0) (100 - primaryUsed).coerceIn(0, 100) else -1
        val secondaryRemaining =
            if (secondaryUsed >= 0) (100 - secondaryUsed).coerceIn(0, 100) else -1

        // Solo dati non sensibili in SharedPreferences.
        getSharedPreferences("codex_stats", MODE_PRIVATE)
            .edit()
            .putInt("short_remaining", primaryRemaining)
            .putInt("week_remaining", secondaryRemaining)
            .putLong("updated_at", System.currentTimeMillis())
            .apply()

        CodexWidgetProvider.updateAll(this)

        val plan = root.optString("plan_type", "?")
        val shortReset = primary?.optLong("reset_at", 0L) ?: 0L
        val weekReset = secondary?.optLong("reset_at", 0L) ?: 0L

        main.post {
            fetchAgain.isEnabled = true
            status.text = buildString {
                appendLine("SUCCESSO ✅")
                appendLine("Piano: $plan")
                appendLine("Finestra breve: usato $primaryUsed% → residuo $primaryRemaining%")
                appendLine("Reset breve (epoch): $shortReset")
                appendLine("Settimanale: usato $secondaryUsed% → residuo $secondaryRemaining%")
                appendLine("Reset week (epoch): $weekReset")
                appendLine()
                appendLine("Il widget è stato aggiornato con le percentuali in cache.")
                appendLine("Token NON persistiti: chiudendo il processo servirà rifare il login.")
            }
        }
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
                    ?.optString("chatgpt_account_id")
                    ?.takeIf { it.isNotBlank() }
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

    private data class HttpResult(val status: Int, val body: String)

    private fun postJson(url: String, body: String): HttpResult =
        request(url, "POST", "application/json", body, emptyMap())

    private fun postForm(url: String, values: Map<String, String>): HttpResult {
        val body = values.entries.joinToString("&") { (k, v) ->
            "${enc(k)}=${enc(v)}"
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
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (contentType != null) {
                setRequestProperty("Content-Type", contentType)
            }
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
            BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8))
                .readText()
        }.orEmpty()

        connection.disconnect()
        return HttpResult(status, responseBody)
    }

    private fun enc(s: String): String =
        URLEncoder.encode(s, StandardCharsets.UTF_8.name())

    private fun setBusy(message: String) {
        status.text = message
        fetchAgain.isEnabled = false
    }

    private fun fail(t: Throwable) {
        fail("${t::class.java.simpleName}: ${t.message ?: "errore"}")
    }

    private fun fail(message: String) {
        main.post {
            status.text = "ERRORE ❌\n$message"
        }
    }

    private fun safe(raw: String): String {
        // Non mostrare mai token. Limita l'output diagnostico.
        return raw
            .replace(Regex("(?i)\"(access_token|refresh_token|id_token)\"\\s*:\\s*\"[^\"]+\""),
                "\"$1\":\"<redacted>\"")
            .take(1500)
    }
}
