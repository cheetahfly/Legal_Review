package com.legalreview.data

import android.content.Context
import com.legalreview.llm.LlmConfig
import com.legalreview.llm.LlmProviderPresets
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    private lateinit var repo: SettingsRepository

    @Before
    fun setup() {
        // 注入普通 SharedPreferences 绕过 AndroidKeyStore，
        // 测试只覆盖我们的 load/save/preset 补全逻辑（不依赖加密实现）。
        // Robolectric 提供 Application Context。
        val context = RuntimeEnvironment.getApplication()
        // 每次 setup 用不同的 prefs 文件，让测试彼此隔离
        val prefsFile = "test_prefs_${System.nanoTime()}"
        val prefs = context.getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
        repo = SettingsRepository(prefs)
    }

    @Test
    fun `default config is deepseek with empty key`() {
        val config = repo.loadConfig()
        assertEquals(LlmProviderPresets.DEEPSEEK, config.provider)
        assertEquals("", config.apiKey)
        // 默认 model 来自预设
        assertEquals(LlmProviderPresets.byProvider(LlmProviderPresets.DEEPSEEK).model, config.model)
    }

    @Test
    fun `saved config survives reload`() {
        val saved = LlmConfig(
            provider = LlmProviderPresets.ZHIPU,
            baseUrl = "https://example.com/v1",
            apiKey = "sk-secret-12345",
            model = "glm-4-flash"
        )
        repo.saveConfig(saved)

        // 重新加载应读回相同值
        val loaded = repo.loadConfig()
        assertEquals(saved.provider, loaded.provider)
        assertEquals(saved.apiKey, loaded.apiKey)
        assertEquals(saved.model, loaded.model)
    }

    @Test
    fun `switching provider updates baseUrl via preset`() {
        // saveConfig 只存 provider/model/apiKey；baseUrl 由 preset 在 loadConfig 时补全
        repo.saveConfig(
            LlmConfig(
                provider = LlmProviderPresets.QWEN,
                baseUrl = "", // 存时不重要
                apiKey = "sk-qwen",
                model = "qwen-plus"
            )
        )

        val loaded = repo.loadConfig()
        assertEquals(LlmProviderPresets.QWEN, loaded.provider)
        // baseUrl 应来自通义预设，不是空串
        val qwenPreset = LlmProviderPresets.byProvider(LlmProviderPresets.QWEN)
        assertEquals(qwenPreset.baseUrl, loaded.baseUrl)
    }

    @Test
    fun `api key overwrite persists`() {
        repo.saveConfig(LlmConfig(LlmProviderPresets.DEEPSEEK, "u", "sk-old", "deepseek-chat"))
        repo.saveConfig(LlmConfig(LlmProviderPresets.DEEPSEEK, "u", "sk-new", "deepseek-chat"))

        assertEquals("sk-new", repo.loadConfig().apiKey)
    }
}