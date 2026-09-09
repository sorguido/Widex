package it.poc.codexlimits

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SecureAuthStore {
    private const val PREFS = "widex_secure_auth"
    private const val KEY_ALIAS = "widex_auth_key_v1"
    private const val FIELD_IV = "iv"
    private const val FIELD_DATA = "data"

    fun hasCredentials(context: Context): Boolean = load(context) != null

    fun save(context: Context, token: OpenAiClient.TokenBundle) {
        val json = JSONObject()
            .put("access_token", token.accessToken)
            .put("refresh_token", token.refreshToken)
            .put("id_token", token.idToken)
            .put("account_id", token.accountId)
            .put("expires_at", token.expiresAtMillis)
            .toString()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(json.toByteArray(Charsets.UTF_8))

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(FIELD_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(FIELD_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun load(context: Context): OpenAiClient.TokenBundle? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ivBase64 = prefs.getString(FIELD_IV, null) ?: return null
        val dataBase64 = prefs.getString(FIELD_DATA, null) ?: return null

        return try {
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val encrypted = Base64.decode(dataBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, iv)
            )
            val json = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            OpenAiClient.TokenBundle(
                accessToken = json.getString("access_token"),
                refreshToken = json.getString("refresh_token"),
                idToken = json.optString("id_token", ""),
                accountId = json.getString("account_id"),
                expiresAtMillis = json.getLong("expires_at")
            )
        } catch (_: Throwable) {
            clear(context)
            null
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }
}
