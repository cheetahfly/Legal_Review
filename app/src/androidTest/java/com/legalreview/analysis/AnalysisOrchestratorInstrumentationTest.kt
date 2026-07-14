package com.legalreview.analysis

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.legalreview.llm.LlmClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 在真机/模拟器上跑一次端到端分析流水线。
 * 用 FakeLlmClient 跳过网络，避免依赖外部服务。
 */
@RunWith(AndroidJUnit4::class)
class AnalysisOrchestratorInstrumentationTest {

    private class FakeLlmClient(private val content: String) : LlmClient {
        override suspend fun chat(systemPrompt: String, userContent: String): Result<String> =
            Result.success(content)
    }

    private val sampleAgreement = """
        一、本服务试用期满后将自动续费，每月从你的账户连续扣款。
        二、本公司对因使用本服务造成的损失概不负责，赔偿上限为人民币100元。
        三、因本协议产生的争议，应提交北京仲裁委员会仲裁。
    """.trimIndent()

    @Test
    fun localOnly_returnsLocalFindings() = runBlocking {
        val orchestrator = AnalysisOrchestrator(llmClient = null)
        val result = orchestrator.analyze(sampleAgreement)

        assertFalse(result.llmUsed)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.all { it.source == FindingSource.LOCAL })
    }

    @Test
    fun withLlm_mergesFindings() = runBlocking {
        // OTHER 本地规则不命中，确保该 finding 来自 LLM
        val llmJson = """
            [{"category":"OTHER","severity":"HIGH","excerpt":"x","explanation":"e","advice":"a"}]
        """.trimIndent()
        val orchestrator = AnalysisOrchestrator(llmClient = FakeLlmClient(llmJson))
        val result = orchestrator.analyze(sampleAgreement)

        assertTrue(result.llmUsed)
        assertTrue(result.findings.any { it.source == FindingSource.LLM && it.category == RiskCategory.OTHER })
        // 本地规则仍应贡献 LIABILITY_LIMIT 等
        assertTrue(result.findings.any { it.source == FindingSource.LOCAL && it.category == RiskCategory.LIABILITY_LIMIT })
    }
}