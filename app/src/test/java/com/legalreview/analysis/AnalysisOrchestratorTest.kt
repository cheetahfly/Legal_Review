package com.legalreview.analysis

import com.legalreview.llm.LlmClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisOrchestratorTest {

    /**
     * 可控的 LlmClient 假实现：按入队顺序返回预设结果（成功内容或失败）。
     */
    private class FakeLlmClient : LlmClient {
        private val queue: ArrayDeque<Result<String>> = ArrayDeque()
        var callCount = 0
            private set

        fun enqueue(content: String) = apply { queue.addLast(Result.success(content)) }
        fun enqueueFailure(error: Throwable) = apply { queue.addLast(Result.failure(error)) }

        override suspend fun chat(systemPrompt: String, userContent: String): Result<String> {
            callCount++
            return queue.removeFirstOrNull()
                ?: Result.failure(IllegalStateException("no queued response"))
        }
    }

    private val sampleAgreement = """
        一、本服务试用期满后将自动续费，每月从你的账户连续扣款。
        二、本公司对因使用本服务造成的损失概不负责，赔偿上限为人民币100元。
        三、因本协议产生的争议，应提交北京仲裁委员会仲裁。
    """.trimIndent()

    @Test
    fun `no llm client returns only local findings`() = runBlocking {
        val orchestrator = AnalysisOrchestrator(llmClient = null)

        val result = orchestrator.analyze(sampleAgreement)

        assertFalse(result.llmUsed)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.all { it.source == FindingSource.LOCAL })
        val cats = result.findings.map { it.category }.toSet()
        assertTrue(RiskCategory.AUTO_RENEW in cats)
        assertTrue(RiskCategory.LIABILITY_LIMIT in cats)
        assertTrue(RiskCategory.JURISDICTION in cats)
    }

    @Test
    fun `text shorter than threshold skips llm`() = runBlocking {
        val llm = FakeLlmClient().enqueue("[]")
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze("短文本不触发LLM")

        assertEquals(0, llm.callCount)
        assertFalse(result.llmUsed)
    }

    @Test
    fun `llm failure falls back to local findings only`() = runBlocking {
        val llm = FakeLlmClient().enqueueFailure(RuntimeException("network down"))
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze(sampleAgreement)

        // LLM 已尝试调用但失败：llmUsed=true，llmError 非空，结果全来自本地
        assertTrue(result.llmUsed)
        assertNotNull(result.llmError)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.all { it.source == FindingSource.LOCAL })
    }

    @Test
    fun `llm success merges with local findings`() = runBlocking {
        // LLM 报了 AUTO_RENEW(HIGH) 和 OTHER(MEDIUM)；本地另命中 LIABILITY_LIMIT、JURISDICTION
        val llmJson = """
            [
              {"category":"AUTO_RENEW","severity":"HIGH","excerpt":"自动续费","explanation":"e","advice":"a"},
              {"category":"OTHER","severity":"MEDIUM","excerpt":"x","explanation":"e","advice":"a"}
            ]
        """.trimIndent()
        val llm = FakeLlmClient().enqueue(llmJson)
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze(sampleAgreement)

        assertTrue(result.llmUsed)
        val cats = result.findings.map { it.category }
        // AUTO_RENEW 去重后只一条
        assertEquals(1, cats.count { it == RiskCategory.AUTO_RENEW })
        assertTrue(RiskCategory.OTHER in cats)
        // 本地命中但 LLM 未覆盖的类别应被补充
        assertTrue(RiskCategory.LIABILITY_LIMIT in cats)
        assertTrue(RiskCategory.JURISDICTION in cats)
    }

    @Test
    fun `llm returning empty array keeps local findings`() = runBlocking {
        val llm = FakeLlmClient().enqueue("[]")
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze(sampleAgreement)

        // LLM 成功返回空数组：llmUsed=true（已调用），无 llmError，本地结果保留
        assertTrue(result.llmUsed)
        assertFalse(result.llmError != null)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.all { it.source == FindingSource.LOCAL })
    }

    @Test
    fun `llm returning markdown wrapped json is parsed`() = runBlocking {
        // 用 OTHER 类别（本地规则不会命中），确保该 finding 来自 LLM
        val llmJson = """
            ```json
            [{"category":"OTHER","severity":"HIGH","excerpt":"自动续费","explanation":"e","advice":"a"}]
            ```
        """.trimIndent()
        val llm = FakeLlmClient().enqueue(llmJson)
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze(sampleAgreement)

        assertTrue(result.llmUsed)
        assertTrue(result.findings.any { it.category == RiskCategory.OTHER && it.source == FindingSource.LLM })
    }

    @Test
    fun `llm returning non-json degrades to local findings`() = runBlocking {
        val llm = FakeLlmClient().enqueue("这不是JSON，模型瞎说的")
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze(sampleAgreement)

        // LLM 调用成功但返回非 JSON：llmUsed=true，解析得空，仅本地结果
        assertTrue(result.llmUsed)
        assertTrue(result.findings.all { it.source == FindingSource.LOCAL })
    }

    @Test
    fun `merged findings sorted by severity descending`() = runBlocking {
        // LOW / HIGH / MEDIUM 混合，期望排序后 HIGH 在前
        val llmJson = """
            [
              {"category":"HIDDEN_CLAUSE","severity":"LOW","excerpt":"x","explanation":"e","advice":"a"},
              {"category":"AUTO_RENEW","severity":"HIGH","excerpt":"y","explanation":"e","advice":"a"},
              {"category":"OTHER","severity":"MEDIUM","excerpt":"z","explanation":"e","advice":"a"}
            ]
        """.trimIndent()
        val llm = FakeLlmClient().enqueue(llmJson)
        // 用一段不命中本地规则的长文本
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze("这是一段足够长的协议文本，用于确保触发 LLM 分析路径，本身不含风险关键词。")

        val ranks = result.findings.map { it.severity.rank }
        // 列表应非递增
        assertEquals(ranks, ranks.sortedDescending())
        // 第一条应为 HIGH
        assertEquals(3, ranks.first())
    }

    /** H3: 混合类型 JSON 数组中单条异常不炸整批。 */
    @Test
    fun `mixed json array with invalid item keeps valid ones`() = runBlocking {
        val llmJson = """
            [
              {"category":"AUTO_RENEW","severity":"HIGH","excerpt":"x","explanation":"e","advice":"a"},
              "garbage string",
              {"category":"OTHER","severity":"MEDIUM","excerpt":"y","explanation":"e","advice":"a"}
            ]
        """.trimIndent()
        val llm = FakeLlmClient().enqueue(llmJson)
        val orchestrator = AnalysisOrchestrator(llmClient = llm)
        val text = "这是一段足够长的文本用于触发 LLM 分析路径，本身不含风险关键词，请 LLM 正常返回结果。"
        val result = orchestrator.analyze(text)
        assertTrue(result.llmUsed)
        assertTrue(result.findings.any { it.category == RiskCategory.AUTO_RENEW })
        assertTrue(result.findings.any { it.category == RiskCategory.OTHER })
    }

    /** M6: 大写 ```JSON 代码块包裹的 JSON 仍能解析。 */
    @Test
    fun `uppercase codeblock json is parsed`() = runBlocking {
        val llmJson = """
            ```JSON
            [{"category":"OTHER","severity":"HIGH","excerpt":"x","explanation":"e","advice":"a"}]
            ```
        """.trimIndent()
        val llm = FakeLlmClient().enqueue(llmJson)
        val orchestrator = AnalysisOrchestrator(llmClient = llm)
        val text = "这是一段足够长的文本用于触发 LLM 分析路径，本身不含风险关键词，请 LLM 正常返回结果。"
        val result = orchestrator.analyze(text)
        assertTrue(result.llmUsed)
        assertTrue(result.findings.any { it.category == RiskCategory.OTHER })
    }

    /** H9: LLM 低严重度不覆盖本地高严重度同类别。 */
    @Test
    fun `llm lower severity does not override local higher`() = runBlocking {
        // sampleAgreement 本地命中 LIABILITY_LIMIT(HIGH)
        val llmJson = """
            [{"category":"LIABILITY_LIMIT","severity":"LOW","excerpt":"x","explanation":"e","advice":"a"}]
        """.trimIndent()
        val llm = FakeLlmClient().enqueue(llmJson)
        val orchestrator = AnalysisOrchestrator(llmClient = llm)
        val result = orchestrator.analyze(sampleAgreement)

        // LIABILITY_LIMIT 应为 HIGH（本地 HIGH > LLM LOW）
        assertTrue(result.findings.any { it.category == RiskCategory.LIABILITY_LIMIT && it.severity == Severity.HIGH })
    }
}
