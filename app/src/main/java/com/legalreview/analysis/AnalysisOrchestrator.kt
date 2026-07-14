package com.legalreview.analysis

import com.legalreview.llm.LEGAL_ANALYSIS_SYSTEM_PROMPT
import com.legalreview.llm.LlmClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 串联：取到的协议文本 → 本地规则引擎预筛 → 云端大模型精分析 → 合并结果。
 */
class AnalysisOrchestrator(
    private val ruleEngine: RiskRuleEngine = RiskRuleEngine(),
    private val llmClient: LlmClient? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {

    /**
     * @param agreementText 已从屏幕提取的协议文本（无障碍或 OCR 得到）
     */
    suspend fun analyze(agreementText: String): AnalysisResult {
        val localHits = ruleEngine.scan(agreementText)
        val localFindings = localHits.map { it.toFinding() }

        val llmFindings: List<RiskFinding> = if (llmClient != null && agreementText.length >= 30) {
            runLlmAnalysis(agreementText, localHits).getOrElse { emptyList() }
        } else emptyList()

        val merged = merge(localFindings, llmFindings)
        return AnalysisResult(
            findings = merged,
            rawTextLength = agreementText.length,
            llmUsed = llmFindings.isNotEmpty()
        )
    }

    private suspend fun runLlmAnalysis(
        text: String,
        localHits: List<RiskHit>
    ): Result<List<RiskFinding>> {
        val localContext = if (localHits.isEmpty()) {
            "（本地规则引擎未命中明显风险）"
        } else {
            "本地规则引擎已命中以下类别，供参考：\n" +
                    localHits.joinToString("\n") { "- ${it.category.label}：${it.excerpt}" }
        }
        val userContent = "协议文本：\n$text\n\n本地预筛结果：\n$localContext"

        val resp = llmClient!!.chat(LEGAL_ANALYSIS_SYSTEM_PROMPT, userContent).getOrElse {
            return Result.failure(it)
        }
        return Result.success(parseLlmFindings(resp))
    }

    private fun parseLlmFindings(raw: String): List<RiskFinding> {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val element = runCatching { json.parseToJsonElement(cleaned) }.getOrNull() ?: return emptyList()
        val arr: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["findings"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { item ->
            val obj = item.jsonObject
            val catName = obj["category"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val category = runCatching { RiskCategory.valueOf(catName) }.getOrDefault(RiskCategory.OTHER)
            val sev = obj["severity"]?.jsonPrimitive?.contentOrNull ?: "LOW"
            RiskFinding(
                category = category.name,
                categoryLabel = category.label,
                severity = sev,
                severityLabel = severityLabel(sev),
                excerpt = obj["excerpt"]?.jsonPrimitive?.contentOrNull ?: "",
                explanation = obj["explanation"]?.jsonPrimitive?.contentOrNull ?: "",
                advice = obj["advice"]?.jsonPrimitive?.contentOrNull ?: "",
                source = "llm"
            )
        }
    }

    private fun merge(local: List<RiskFinding>, llm: List<RiskFinding>): List<RiskFinding> {
        // 以 LLM 为主，补充本地命中但 LLM 未覆盖的类别。
        val llmCategories = llm.map { it.category }.toSet()
        val localOnly = local.filter { it.category !in llmCategories }
        return (llm + localOnly).sortedByDescending { severityRank(it.severity) }
    }

    private fun severityRank(s: String): Int = when (s.uppercase()) {
        "HIGH" -> 3
        "MEDIUM" -> 2
        else -> 1
    }

    private fun severityLabel(s: String): String = when (s.uppercase()) {
        "HIGH" -> "高"
        "MEDIUM" -> "中"
        else -> "低"
    }

    private fun RiskHit.toFinding(): RiskFinding = RiskFinding(
        category = category.name,
        categoryLabel = category.label,
        severity = severity.name,
        severityLabel = severity.label,
        excerpt = excerpt,
        explanation = explanation,
        advice = "",
        source = "local"
    )
}
