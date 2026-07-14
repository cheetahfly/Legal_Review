package com.legalreview.llm

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleLlmClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `parses content from chat completion response`() = runBlocking {
        val responseBody = """
            {"choices":[{"message":{"role":"assistant","content":"[{\"category\":\"AUTO_RENEW\"}]"}}]}
        """.trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val config = LlmConfig(
            provider = "deepseek",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "sk-test",
            model = "deepseek-chat"
        )
        val client = OpenAiCompatibleLlmClient(config)

        val result = client.chat("system prompt", "user text")

        assertTrue(result.isSuccess)
        val content = result.getOrThrow()
        assertTrue(content.contains("AUTO_RENEW"))

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.endsWith("/chat/completions"))
        val sentBody = Json.parseToJsonElement(recorded.body.readUtf8())
        // 简单断言请求体包含 model 与 messages
        assertTrue(sentBody.toString().contains("deepseek-chat"))
        assertTrue(sentBody.toString().contains("system prompt"))
        assertTrue(sentBody.toString().contains("user text"))
    }

    @Test
    fun `returns failure on http error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val config = LlmConfig(
            provider = "deepseek",
            baseUrl = server.url("/v1").toString().trimEnd('/'),
            apiKey = "bad-key",
            model = "deepseek-chat"
        )
        val client = OpenAiCompatibleLlmClient(config)

        val result = client.chat("s", "u")
        assertTrue(result.isFailure)
    }
}
