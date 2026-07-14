package com.legalreview.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.legalreview.llm.LlmConfig
import com.legalreview.llm.LlmProviderPresets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机/模拟器上验证 EncryptedSharedPreferences 真路径可用（AndroidKeyStore 在
 * instrumentation 上下文中能正常工作，与单元测试里的 Robolectric 沙箱不同）。
 */
@RunWith(AndroidJUnit4::class)
class SettingsRepositoryIntegrationTest {

    private lateinit var repo: SettingsRepository

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repo = SettingsRepository(context)
    }

    @After
    fun tearDown() {
        // 清掉 API Key，免得遗留到下次
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(SettingsRepository.FILE_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun encryptedPrefs_roundtrip_preservesConfig() {
        val saved = LlmConfig(
            provider = LlmProviderPresets.ZHIPU,
            baseUrl = "https://example.com/v1",
            apiKey = "sk-integration-test-secret",
            model = "glm-4-flash"
        )
        repo.saveConfig(saved)

        val loaded = repo.loadConfig()
        assertEquals(saved.provider, loaded.provider)
        assertEquals(saved.apiKey, loaded.apiKey)
        assertEquals(saved.model, loaded.model)
        // baseUrl 应来自 preset，不是保存时的空串
        assertEquals(LlmProviderPresets.byProvider(LlmProviderPresets.ZHIPU).baseUrl, loaded.baseUrl)
    }

    @Test
    fun encryptedPrefs_storedValueIsNotPlaintext() {
        // 真机环境下 EncryptedSharedPreferences 应当确实加密；密文中不应出现明文 key
        val secret = "sk-plaintext-canary-${System.currentTimeMillis()}"
        repo.saveConfig(LlmConfig(LlmProviderPresets.DEEPSEEK, "u", secret, "deepseek-chat"))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefsFile = context.getSharedPreferences(
            SettingsRepository.FILE_NAME,
            android.content.Context.MODE_PRIVATE
        )
        val dump = prefsFile.all.values.joinToString()
        assertNotEquals("密文不应等于明文", secret, dump)
        assertTrue("明文 key 不应出现在存储中：$dump", !dump.contains(secret))
    }

    @Test
    fun defaultConfig_isDeepseekWithEmptyKey() {
        // 在 tearDown 之后的状态下，默认应当是 DeepSeek + 空 key
        val config = repo.loadConfig()
        assertEquals(LlmProviderPresets.DEEPSEEK, config.provider)
        assertEquals("", config.apiKey)
    }
}