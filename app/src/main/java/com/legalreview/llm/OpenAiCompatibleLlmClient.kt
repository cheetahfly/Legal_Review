package com.legalreview.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
                val url = "${config.baseUrl.trimEnd('/')}/chat/completions"
                executeWithRetry(url, body)
            }
        }

    /**
     * H8: 对 429/5xx/网络错误指数退避重试 MAX_RETRIES 次；4xx（除 429）立即抛出。
     */
    private suspend fun executeWithRetry(url: String, body: String): String {
        var lastError: LlmException? = null
        repeat(MAX_RETRIES + 1) { attempt ->
            if (attempt > 0) {
                delay(RETRY_BASE_DELAY_MS * (1L shl (attempt - 1)))
            }
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer ${config.apiKey}")
                    .header("Content-Type", "application/json")
                    .post(body.toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        return parseContent(resp.body?.string() ?: throw LlmException("空响应"))
                    }
                    val code = resp.code
                    val respBody = resp.body?.string().orEmpty()
                    val error = classifyHttpError(code, respBody)
                    if (error.retryable && attempt < MAX_RETRIES) {
                        lastError = error
                    } else {
                        throw error
                    }
                }
            } catch (e: LlmException) {
                throw e
            } catch (e: java.io.IOException) {
                val netError = LlmException("网络错误：${e.message}", null, ErrorKind.NETWORK)
                if (attempt < MAX_RETRIES) lastError = netError else throw netError
            }
        }
        throw lastError ?: LlmException("请求失败")
    }

    /**
     * H8: 按状态码分类错误，便于 UI 区分。
     * L7: message 只含 HTTP code，响应体放 responseBody 仅供本地排查，不进入可上报字段。
     */
    private fun classifyHttpError(code: Int, body: String): LlmException {
        val kind = when (code) {
            401, 403 -> ErrorKind.AUTH
            429 -> ErrorKind.RATE_LIMIT
            in 400..499 -> ErrorKind.CLIENT
            in 500..599 -> ErrorKind.SERVER
            else -> ErrorKind.UNKNOWN
        }
        return LlmException("HTTP $code", body.take(500), kind)
    }

    /**
     * M17: content 可能是字符串，也可能是多模态数组 [{"type":"text","text":"..."}]。
     */
    private fun parseContent(respText: String): String {
        val root = json.parseToJsonElement(respText).jsonObject
        val choices = root["choices"]?.jsonArray
            ?: throw LlmException("响应缺少 choices", respText.take(200), ErrorKind.CLIENT)
        if (choices.isEmpty()) throw LlmException("choices 为空", respText.take(200), ErrorKind.CLIENT)
        val message = choices.first().jsonObject["message"]
            ?: throw LlmException("响应缺少 message", respText.take(200), ErrorKind.CLIENT)
        val content = message.jsonObject["content"]
            ?: throw LlmException("响应缺少 content", respText.take(200), ErrorKind.CLIENT)
        return when (content) {
            is JsonPrimitive -> content.content
            is JsonArray -> content.joinToString("") { el ->
                el.jsonObject["text"]?.jsonPrimitive?.content ?: ""
            }
            else -> throw LlmException("content 类型不支持", null, ErrorKind.CLIENT)
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
            put("max_tokens", 2048) // H4: 限制响应长度，避免超长 JSON 被截断
            put("response_format", buildJsonObject { put("type", "json_object") }) // M16
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_RETRIES = 2
        private const val RETRY_BASE_DELAY_MS = 500L

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

/**
 * LLM 调用异常。
 * @param message 简短描述（HTTP code / 网络错误），不含响应体，安全可上报
 * @param responseBody 响应体片段，仅供本地排查
 * @param kind 错误分类，供 UI 展示与重试决策
 */
class LlmException(
    message: String,
    val responseBody: String? = null,
    val kind: ErrorKind = ErrorKind.UNKNOWN
) : RuntimeException(message) {
    val retryable: Boolean get() = kind == ErrorKind.RATE_LIMIT || kind == ErrorKind.SERVER
}

enum class ErrorKind { AUTH, RATE_LIMIT, CLIENT, SERVER, NETWORK, UNKNOWN }

/**
 * 工厂：按 config 创建客户端。当前三家共用同一实现。
 * H5: 支持注入共享 OkHttpClient，避免每次分析新建连接池/线程池。
 */
object LlmClientFactory {
    fun create(config: LlmConfig, httpClient: OkHttpClient? = null): LlmClient =
        OpenAiCompatibleLlmClient(config, httpClient ?: OpenAiCompatibleLlmClient.defaultHttpClient())
}
