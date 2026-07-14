package com.legalreview.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenAI 兼容的统一实现，覆盖 DeepSeek / 智谱 GLM / 通义千问。
 * 切换 provider 只需换 LlmConfig（baseUrl + model + apiKey）。
 */
class OpenAiCompatibleLlmClient(
    private val config: LlmConfig,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = defaultJson()
) : LlmClient {

    override suspend fun chat(systemPrompt: String, userContent: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildRequestBody(systemPrompt, userContent)
                val request = Request.Builder()
                    .url("${config.baseUrl.trimEnd('/')}/chat/completions")
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw LlmException("HTTP ${resp.code}: ${resp.body?.string()?.take(500)}")
                    }
                    val respText = resp.body?.string()
                        ?: throw LlmException("Empty response body")
                    val root = json.parseToJsonElement(respText).jsonObject
                    val choices = root["choices"]?.jsonArray
                        ?: throw LlmException("No choices in response")
                    choices.first()
                        .jsonObject["message"]!!
                        .jsonObject["content"]!!
                        .jsonPrimitive.content
                }
            }
        }

    private fun buildRequestBody(systemPrompt: String, userContent: String): String {
        val messages = JsonArray(listOf(
            buildJsonObject {
                put("role", "system")
                put("content", systemPrompt)
            },
            buildJsonObject {
                put("role", "user")
                put("content", userContent)
            }
        ))
        val obj = buildJsonObject {
            put("model", config.model)
            put("messages", messages)
            put("temperature", 0.2)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }
    }
}

class LlmException(message: String) : RuntimeException(message)

/**
 * 工厂：按 config 创建客户端。当前三家共用同一实现。
 */
object LlmClientFactory {
    fun create(config: LlmConfig): LlmClient = OpenAiCompatibleLlmClient(config)
}
