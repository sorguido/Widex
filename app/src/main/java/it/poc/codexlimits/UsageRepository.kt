package it.poc.codexlimits

import android.content.Context

object UsageRepository {
    private const val PREFS = "codex_stats"
    private const val REFRESH_EARLY_MS = 60_000L

    sealed class RefreshResult {
        data class Success(val usage: CachedUsage) : RefreshResult()
        data object AuthRequired : RefreshResult()
        data class Error(val message: String) : RefreshResult()
    }

    data class CachedUsage(
        val plan: String,
        val shortRemaining: Int,
        val weekRemaining: Int,
        val shortResetEpoch: Long,
        val weekResetEpoch: Long,
        val updatedAtMillis: Long
    )

    fun readCached(context: Context): CachedUsage? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val shortRemaining = prefs.getInt("short_remaining", -1)
        val weekRemaining = prefs.getInt("week_remaining", -1)
        if (shortRemaining < 0 && weekRemaining < 0) return null

        return CachedUsage(
            plan = prefs.getString("plan", "?") ?: "?",
            shortRemaining = shortRemaining,
            weekRemaining = weekRemaining,
            shortResetEpoch = prefs.getLong("short_reset", 0L),
            weekResetEpoch = prefs.getLong("week_reset", 0L),
            updatedAtMillis = prefs.getLong("updated_at", 0L)
        )
    }

    fun refresh(context: Context): RefreshResult {
        var token = SecureAuthStore.load(context) ?: return RefreshResult.AuthRequired

        try {
            if (token.expiresAtMillis <= System.currentTimeMillis() + REFRESH_EARLY_MS) {
                token = refreshToken(context, token)
            }

            val usage = try {
                OpenAiClient.fetchUsage(token.accessToken, token.accountId)
            } catch (error: OpenAiClient.HttpException) {
                if (error.statusCode != 401) throw error
                token = refreshToken(context, token)
                OpenAiClient.fetchUsage(token.accessToken, token.accountId)
            }

            if (usage.shortRemaining < 0 && usage.weekRemaining < 0) {
                return RefreshResult.Error("OpenAI non ha restituito nessuna finestra di limite utilizzabile")
            }

            val cached = CachedUsage(
                plan = usage.plan,
                shortRemaining = usage.shortRemaining,
                weekRemaining = usage.weekRemaining,
                shortResetEpoch = usage.shortResetEpoch,
                weekResetEpoch = usage.weekResetEpoch,
                updatedAtMillis = System.currentTimeMillis()
            )
            saveCached(context, cached)
            return RefreshResult.Success(cached)
        } catch (error: OpenAiClient.HttpException) {
            if (error.statusCode == 400 || error.statusCode == 401 || error.statusCode == 403) {
                if (error.message?.startsWith("Token refresh") == true) {
                    SecureAuthStore.clear(context)
                    return RefreshResult.AuthRequired
                }
            }
            return RefreshResult.Error(error.message ?: "Errore HTTP")
        } catch (error: Throwable) {
            return RefreshResult.Error(error.message ?: error::class.java.simpleName)
        }
    }

    fun clearAll(context: Context) {
        SecureAuthStore.clear(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun refreshToken(
        context: Context,
        current: OpenAiClient.TokenBundle
    ): OpenAiClient.TokenBundle {
        val refreshed = OpenAiClient.refreshTokens(current.refreshToken)
        SecureAuthStore.save(context, refreshed)
        return refreshed
    }

    private fun saveCached(context: Context, usage: CachedUsage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("plan", usage.plan)
            .putInt("short_remaining", usage.shortRemaining)
            .putInt("week_remaining", usage.weekRemaining)
            .putLong("short_reset", usage.shortResetEpoch)
            .putLong("week_reset", usage.weekResetEpoch)
            .putLong("updated_at", usage.updatedAtMillis)
            .apply()
    }
}
