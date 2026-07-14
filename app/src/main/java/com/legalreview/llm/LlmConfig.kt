package com.legalreview.llm

import kotlinx.serialization.Serializable

/**
 * 大模型 provider 配置。三家国内模型均 OpenAI 兼容，切换 provider 只改本配置。
 */
@Serializable
data class LlmConfig(
    val provider: String,   // "deepseek" | "zhipu" | "qwen"
    val baseUrl: String,    // 已含版本路径，如 https://api.deepseek.com/v1
    val apiKey: String,
    val model: String
)

/**
 * 三家 provider 的预设。baseUrl/模型名已固化正确路径，用户只需填 apiKey。
 */
object LlmProviderPresets {
    const val DEEPSEEK = "deepseek"
    const val ZHIPU = "zhipu"
    const val QWEN = "qwen"

    val ALL = listOf(
        LlmConfig(DEEPSEEK, "https://api.deepseek.com/v1", "", "deepseek-chat"),
        LlmConfig(ZHIPU, "https://open.bigmodel.cn/api/paas/v4", "", "glm-4-flash"),
        LlmConfig(QWEN, "https://dashscope.aliyuncs.com/compatible-mode/v1", "", "qwen-plus")
    )

    fun byProvider(provider: String): LlmConfig =
        ALL.firstOrNull { it.provider == provider } ?: ALL.first()
}
