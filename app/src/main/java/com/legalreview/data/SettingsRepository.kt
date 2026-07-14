package com.legalreview.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.legalreview.llm.LlmConfig
import com.legalreview.llm.LlmProviderPresets

/**
 * 设置存储：用 EncryptedSharedPreferences 保存 API Key 等敏感信息。
 * provider/model 明文存普通 prefs 即可，但统一存加密 prefs 更简单。
 */
class SettingsRepository @VisibleForTesting constructor(
    private val prefs: SharedPreferences
) {

    constructor(context: Context) : this(createEncryptedPrefs(context))

    fun loadConfig(): LlmConfig {
        val provider = prefs.getString(KEY_PROVIDER, LlmProviderPresets.DEEPSEEK)!!
        val preset = LlmProviderPresets.byProvider(provider)
        val apiKey = prefs.getString(KEY_API_KEY, "") ?: ""
        val model = prefs.getString(KEY_MODEL, preset.model) ?: preset.model
        return preset.copy(apiKey = apiKey, model = model)
    }

    fun saveConfig(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_PROVIDER, config.provider)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .apply()
    }

    companion object {
        internal const val FILE_NAME = "legal_review_secrets"
        private const val KEY_PROVIDER = "llm_provider"
        private const val KEY_API_KEY = "llm_api_key"
        private const val KEY_MODEL = "llm_model"

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                FILE_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}