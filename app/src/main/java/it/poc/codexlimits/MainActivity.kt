package it.poc.codexlimits

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var loginPanel: LinearLayout
    private lateinit var dashboardPanel: LinearLayout
    private lateinit var connectButton: Button
    private lateinit var loginCode: TextView
    private lateinit var loginStatus: TextView
    private lateinit var shortPercent: TextView
    private lateinit var weekPercent: TextView
    private lateinit var shortBar: ProgressBar
    private lateinit var weekBar: ProgressBar
    private lateinit var shortReset: TextView
    private lateinit var weekReset: TextView
    private lateinit var updatedAt: TextView
    private lateinit var planText: TextView
    private lateinit var refreshButton: Button
    private lateinit var dashboardStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        if (SecureAuthStore.hasCredentials(this)) {
            showDashboard()
            renderUsage(UsageRepository.readCached(this))
            RefreshScheduler.schedule(this)
            refreshLive()
        } else {
            showLogin()
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
            setBackgroundColor(Color.rgb(17, 19, 21))
        }
        val scroll = ScrollView(this).apply { addView(root) }

        root.addView(TextView(this).apply {
            text = getString(R.string.widex_title)
            setTextColor(Color.WHITE)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.codex_limits)
            setTextColor(Color.rgb(180, 185, 190))
            textSize = 14f
            letterSpacing = 0.12f
            setPadding(0, 0, 0, dp(18))
        })

        loginPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        root.addView(
            loginPanel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        loginPanel.addView(TextView(this).apply {
            text = getString(R.string.connect_account_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
        })
        loginPanel.addView(TextView(this).apply {
            text = getString(R.string.connect_account_description)
            setTextColor(Color.rgb(190, 194, 198))
            textSize = 14f
            setPadding(0, dp(8), 0, dp(16))
        })

        connectButton = Button(this).apply {
            text = getString(R.string.connect_account_button)
            setOnClickListener { startDeviceLogin() }
        }
        loginPanel.addView(connectButton)

        loginCode = TextView(this).apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, dp(8))
        }
        loginPanel.addView(loginCode)

        loginStatus = TextView(this).apply {
            setTextColor(Color.rgb(180, 185, 190))
            textSize = 13f
            setPadding(0, dp(10), 0, 0)
        }
        loginPanel.addView(loginStatus)

        dashboardPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(dashboardPanel)

        planText = TextView(this).apply {
            setTextColor(Color.rgb(180, 185, 190))
            textSize = 13f
            setPadding(0, 0, 0, dp(10))
        }
        dashboardPanel.addView(planText)

        val shortBlock = createLimitBlock(R.string.five_hours)
        shortPercent = shortBlock.percent
        shortBar = shortBlock.bar
        shortReset = shortBlock.reset
        dashboardPanel.addView(shortBlock.container)

        val weekBlock = createLimitBlock(R.string.week)
        weekPercent = weekBlock.percent
        weekBar = weekBlock.bar
        weekReset = weekBlock.reset
        dashboardPanel.addView(
            weekBlock.container,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        )

        updatedAt = TextView(this).apply {
            text = getString(R.string.updated_at, "—")
            setTextColor(Color.rgb(180, 185, 190))
            textSize = 13f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, dp(8))
        }
        dashboardPanel.addView(updatedAt)

        refreshButton = Button(this).apply {
            text = getString(R.string.update)
            setOnClickListener { refreshLive() }
        }
        dashboardPanel.addView(refreshButton)

        dashboardStatus = TextView(this).apply {
            setTextColor(Color.rgb(180, 185, 190))
            textSize = 12f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        dashboardPanel.addView(dashboardStatus)

        setContentView(scroll)
    }

    private data class LimitBlock(
        val container: LinearLayout,
        val percent: TextView,
        val bar: ProgressBar,
        val reset: TextView
    )

    private fun createLimitBlock(titleRes: Int): LimitBlock {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = getString(titleRes)
            setTextColor(Color.WHITE)
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        }
        val percent = TextView(this).apply {
            text = "—"
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.END
            setTypeface(typeface, Typeface.BOLD)
        }
        header.addView(
            label,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(
            percent,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        container.addView(header)

        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Color.rgb(110, 231, 183))
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(60, 65, 70))
        }
        container.addView(
            bar,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(12)
            ).apply { topMargin = dp(10) }
        )

        val reset = TextView(this).apply {
            text = getString(R.string.reset_unavailable)
            setTextColor(Color.rgb(180, 185, 190))
            textSize = 13f
            setPadding(0, dp(9), 0, 0)
        }
        container.addView(reset)

        return LimitBlock(container, percent, bar, reset)
    }

    private fun showLogin(message: String = "") {
        dashboardPanel.visibility = View.GONE
        loginPanel.visibility = View.VISIBLE
        connectButton.isEnabled = true
        loginCode.visibility = View.GONE
        loginStatus.text = message
    }

    private fun showDashboard() {
        loginPanel.visibility = View.GONE
        dashboardPanel.visibility = View.VISIBLE
    }

    private fun startDeviceLogin() {
        connectButton.isEnabled = false
        loginCode.visibility = View.GONE
        loginStatus.text = getString(R.string.requesting_code)

        executor.execute {
            try {
                val deviceCode = OpenAiClient.requestDeviceCode()
                main.post {
                    loginCode.text = deviceCode.userCode
                    loginCode.visibility = View.VISIBLE
                    loginStatus.text = getString(R.string.enter_code_wait)
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OpenAiClient.VERIFY_URL)))
                }

                val deadline = System.currentTimeMillis() + 15L * 60L * 1000L
                var authResponse: org.json.JSONObject? = null
                while (System.currentTimeMillis() < deadline && authResponse == null) {
                    Thread.sleep(deviceCode.intervalSeconds * 1000L)
                    authResponse = OpenAiClient.pollAuthorization(deviceCode)
                }
                if (authResponse == null) error(getString(R.string.authorization_timeout))

                val token = OpenAiClient.exchangeAuthorizationCode(authResponse)
                SecureAuthStore.save(this, token)
                RefreshScheduler.schedule(this)

                val result = UsageRepository.refresh(this)
                main.post {
                    showDashboard()
                    when (result) {
                        is UsageRepository.RefreshResult.Success -> {
                            renderUsage(result.usage)
                            dashboardStatus.text = ""
                        }

                        UsageRepository.RefreshResult.AuthRequired -> {
                            showLogin(getString(R.string.session_not_accepted))
                        }

                        is UsageRepository.RefreshResult.Error -> {
                            renderUsage(UsageRepository.readCached(this))
                            dashboardStatus.text = getString(R.string.update_failed, result.message)
                        }
                    }
                    CodexWidgetProvider.updateAll(this)
                }
            } catch (error: Throwable) {
                main.post {
                    connectButton.isEnabled = true
                    loginStatus.text = getString(
                        R.string.error_with_message,
                        error.message ?: error::class.java.simpleName
                    )
                }
            }
        }
    }

    private fun refreshLive() {
        refreshButton.isEnabled = false
        dashboardStatus.text = getString(R.string.updating)

        executor.execute {
            val result = UsageRepository.refresh(this)
            main.post {
                refreshButton.isEnabled = true
                when (result) {
                    is UsageRepository.RefreshResult.Success -> {
                        renderUsage(result.usage)
                        dashboardStatus.text = ""
                        CodexWidgetProvider.updateAll(this)
                    }

                    UsageRepository.RefreshResult.AuthRequired -> {
                        RefreshScheduler.cancel(this)
                        showLogin(getString(R.string.session_expired))
                    }

                    is UsageRepository.RefreshResult.Error -> {
                        renderUsage(UsageRepository.readCached(this))
                        dashboardStatus.text = getString(R.string.update_failed, result.message)
                    }
                }
            }
        }
    }

    private fun renderUsage(usage: UsageRepository.CachedUsage?) {
        if (usage == null) {
            renderUnavailable(shortPercent, shortBar, shortReset)
            renderUnavailable(weekPercent, weekBar, weekReset)
            updatedAt.text = getString(R.string.updated_at, "—")
            planText.text = ""
            return
        }

        if (usage.shortRemaining >= 0) {
            shortPercent.text = "${usage.shortRemaining}%"
            shortBar.progress = usage.shortRemaining
            shortReset.text = getString(
                R.string.reset,
                DisplayFormat.shortReset(usage.shortResetEpoch)
            )
        } else {
            renderUnavailable(shortPercent, shortBar, shortReset)
        }

        if (usage.weekRemaining >= 0) {
            weekPercent.text = "${usage.weekRemaining}%"
            weekBar.progress = usage.weekRemaining
            weekReset.text = getString(
                R.string.reset,
                DisplayFormat.weekReset(usage.weekResetEpoch)
            )
        } else {
            renderUnavailable(weekPercent, weekBar, weekReset)
        }

        updatedAt.text = getString(
            R.string.updated_at,
            DisplayFormat.updatedAt(usage.updatedAtMillis)
        )
        planText.text = if (usage.plan == "?") "" else getString(R.string.plan, usage.plan)
    }

    private fun renderUnavailable(percent: TextView, bar: ProgressBar, reset: TextView) {
        percent.text = "—"
        bar.progress = 0
        reset.text = getString(R.string.not_provided)
    }

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.rgb(29, 32, 35))
        cornerRadius = dp(16).toFloat()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
