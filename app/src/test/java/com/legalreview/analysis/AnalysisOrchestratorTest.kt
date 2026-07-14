package com.legalreview.analysis

import com.legalreview.llm.LlmClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertTrue(result.findings.all { it.source == "local" })
        // 至少命中自动续费、免责限责、管辖三类
        val cats = result.findings.map { it.category }.toSet()
        assertTrue(RiskCategory.AUTO_RENEW.name in cats)
        assertTrue(RiskCategory.LIABILITY_LIMIT.name in cats)
        assertTrue(RiskCategory.JURISDICTION.name in cats)
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

        // LLM 失败 → llmUsed 为 false，结果全来自本地
        assertFalse(result.llmUsed)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.all { it.source == "local" })
    }

    @Test
    fun `llm success merges with local, llm takes precedence`() = runBlocking {
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
        // AUTO_RENEW 来自 LLM（去重后只一条），OTHER 来自 LLM
        assertEquals(1, cats.count { it == RiskCategory.AUTO_RENEW.name })
        assertTrue(RiskCategory.OTHER.name in cats)
        // 本地命中但 LLM 未覆盖的类别应被补充
        assertTrue(RiskCategory.LIABILITY_LIMIT.name in cats)
        assertTrue(RiskCategory.JURISDICTION.name in cats)
        // 这些补充项来源为 local
        assertTrue(result.findings.any { it.category == RiskCategory.LIABILITY_LIMIT.name && it.source == "local" })
    }

    @Test
    fun `llm returning empty array keeps local findings`() = runBlocking {
        val llm = FakeLlmClient().enqueue("[]")
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze(sampleAgreement)

        // LLM 空数组 → llmUsed 仍为 false（isEmpty 判定），但本地结果保留
        assertFalse(result.llmUsed)
        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.all { it.source == "local" })
    }

    @Test
    fun `llm returning markdown wrapped json is parsed`() = runBlocking {
        val llmJson = """
            ```json
            [{"category":"AUTO_RENEW","severity":"HIGH","excerpt":"自动续费","explanation":"e","advice":"a"}]
            ```
        """.trimIndent()
        val llm = FakeLlmClient().enqueue(llmJson)
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze(sampleAgreement)

        assertTrue(result.llmUsed)
        assertTrue(result.findings.any { it.category == RiskCategory.AUTO_RENEW.name && it.source == "llm" })
    }

    @Test
    fun `llm returning non-json degrades to local findings`() = runBlocking {
        val llm = FakeLlmClient().enqueue("这不是JSON，模型瞎说的")
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze(sampleAgreement)

        // 解析失败 → LLM 贡献空，仅本地结果
        assertFalse(result.llmUsed)
        assertTrue(result.findings.all { it.source == "local" })
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
        // 用空文本不行（<30 跳过 LLM），用一段不命中本地规则的长文本
        val orchestrator = AnalysisOrchestrator(llmClient = llm)

        val result = orchestrator.analyze("这是一段足够长的协议文本，用于确保触发 LLM 分析路径，本身不含风险关键词。")

        val ranks = result.findings.map { sevRank(it.severity) }
        // 列表应非递增
        assertEquals(ranks, ranks.sortedDescending())
        // 第一条应为 HIGH
        assertEquals(3, ranks.first())
    }

    private fun sevRank(s: String): Int = when (s.uppercase()) {
        "HIGH" -> 3
        "MEDIUM" -> 2
        else -> 1
    }
}
