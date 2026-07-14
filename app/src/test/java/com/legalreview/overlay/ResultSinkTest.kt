package com.legalreview.overlay

import com.legalreview.analysis.AnalysisResult
import com.legalreview.analysis.RiskFinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ResultSink 是纯 Kotlin/JVM 的 JSON 编解码器，可在普通 JUnit 中跑。
 * 不需要 Robolectric。
 */
class ResultSinkTest {

    @Test
    fun `encode then decode roundtrip preserves all fields`() {
        val original = AnalysisResult(
            findings = listOf(
                RiskFinding(
                    category = "AUTO_RENEW",
                    categoryLabel = "自动续费",
                    severity = "HIGH",
                    severityLabel = "高",
                    excerpt = "试用期结束后自动续费",
                    explanation = "可能产生持续扣款",
                    advice = "退订前留意",
                    source = "local"
                ),
                RiskFinding(
                    category = "OTHER",
                    categoryLabel = "其他",
                    severity = "MEDIUM",
                    severityLabel = "中",
                    excerpt = "模糊条款",
                    explanation = "e",
                    advice = "a",
                    source = "llm"
                )
            ),
            rawTextLength = 1234,
            llmUsed = true
        )

        val encoded = ResultSink.encode(original)
        val decoded = ResultSink.decode(encoded)

        assertEquals(original, decoded)
    }

    @Test
    fun `decode tolerates unknown fields`() {
        // ignoreUnknownKeys = true：将来 RiskFinding 加字段时，旧 JSON 仍能解码
        val raw = """
            {
              "findings": [
                {"category":"X","categoryLabel":"x","severity":"LOW","severityLabel":"低",
                 "excerpt":"e","explanation":"e","advice":"a","source":"local",
                 "futureField":"should be ignored","nested":{"a":1}}
              ],
              "rawTextLength": 42,
              "llmUsed": false,
              "unknownTopLevel": "ignored"
            }
        """.trimIndent()

        val decoded = ResultSink.decode(raw)
        assertEquals(1, decoded.findings.size)
        assertEquals("X", decoded.findings[0].category)
        assertEquals(42, decoded.rawTextLength)
        assertFalse(decoded.llmUsed)
    }

    @Test
    fun `decode of corrupted json returns empty AnalysisResult`() {
        // 故意坏 JSON：解码失败应降级为空 result（不让分析结果丢失）
        val decoded = ResultSink.decode("{not valid json")
        assertEquals(0, decoded.findings.size)
        assertEquals(0, decoded.rawTextLength)
        assertFalse(decoded.llmUsed)
    }

    @Test
    fun `decode of empty string returns empty AnalysisResult`() {
        val decoded = ResultSink.decode("")
        assertEquals(0, decoded.findings.size)
        assertEquals(0, decoded.rawTextLength)
        assertFalse(decoded.llmUsed)
    }

    @Test
    fun `decode of empty json object returns empty AnalysisResult`() {
        // 合法的 {} 应该解码出空 findings（不是降级路径）
        val decoded = ResultSink.decode("{}")
        assertEquals(0, decoded.findings.size)
        assertEquals(0, decoded.rawTextLength)
        assertFalse(decoded.llmUsed)
    }

    @Test
    fun `encode of empty result produces parseable json`() {
        val encoded = ResultSink.encode(AnalysisResult(emptyList(), 0, false))
        // 应包含必需字段
        assertTrue(encoded.contains("\"findings\""))
        assertTrue(encoded.contains("\"rawTextLength\""))
        assertTrue(encoded.contains("\"llmUsed\""))

        // 还能再解码回来
        val decoded = ResultSink.decode(encoded)
        assertEquals(0, decoded.findings.size)
        assertFalse(decoded.llmUsed)
    }
}