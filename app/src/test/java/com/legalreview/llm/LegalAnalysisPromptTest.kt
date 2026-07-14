package com.legalreview.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalAnalysisPromptTest {

    @Test
    fun `system prompt lists all risk categories`() {
        // 每个类别名都应出现在 prompt 里，保证模型输出可被解析回 RiskCategory
        val categories = listOf(
            "AUTO_RENEW", "UNILATERAL_MODIFY", "UNILATERAL_TERMINATE", "LIABILITY_LIMIT",
            "EXCESSIVE_INFO", "THIRD_PARTY_SHARE", "JURISDICTION", "IRREVOCABLE_AUTH",
            "HIGH_PENALTY", "HIDDEN_CLAUSE", "OTHER"
        )
        for (c in categories) {
            assertTrue("prompt 缺少类别 $c", LEGAL_ANALYSIS_SYSTEM_PROMPT.contains(c))
        }
    }

    @Test
    fun `system prompt demands json array output`() {
        // 关键约束：只要 JSON、不要 markdown 标记、空则输出 []
        assertTrue(LEGAL_ANALYSIS_SYSTEM_PROMPT.contains("JSON"))
        assertTrue(LEGAL_ANALYSIS_SYSTEM_PROMPT.contains("空数组"))
        assertTrue(LEGAL_ANALYSIS_SYSTEM_PROMPT.contains("markdown") || LEGAL_ANALYSIS_SYSTEM_PROMPT.contains("代码块"))
    }

    @Test
    fun `system prompt is non-blank`() {
        assertTrue(LEGAL_ANALYSIS_SYSTEM_PROMPT.isNotBlank())
        assertTrue(LEGAL_ANALYSIS_SYSTEM_PROMPT.length > 50)
    }
}
