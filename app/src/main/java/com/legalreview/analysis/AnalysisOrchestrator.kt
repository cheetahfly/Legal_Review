package com.legalreview.analysis

import com.legalreview.llm.LEGAL_ANALYSIS_SYSTEM_PROMPT
import com.legalreview.llm.ErrorKind
import com.legalreview.llm.LlmClient
import com.legalreview.llm.LlmException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 串联：取到的协议文本 -> 本地规则引擎预筛 -> 云端大模型精分析 -> 合并结果。
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
        // C2: 归一化换行。OCR/无障碍文本在视觉换行处插入 \n，会导致正则 .* 跨行失效、
        // 字面量关键词被截断而漏报。中文无需空格分词，直接移除换行即可。
        val normalized = agreementText.replace("\r", "").replace("\n", "")
        val localHits = ruleEngine.scan(normalized)
        // M8: 本地按 category 去重（同类别多次命中只保留首条），减少 UI 重复项与 LLM token 浪费
        val localFindings = localHits.distinctBy { it.category }.map { it.toFinding() }

        val client = llmClient
        if (client == null || normalized.length < MIN_LLM_INPUT_LENGTH) {
            return AnalysisResult(
                findings = localFindings,
                rawTextLength = agreementText.length,
                llmUsed = false
            )
        }

        // H4: 截断超长文本，避免超出模型上下文窗口触发 4xx 静默失败
        val truncated = normalized.take(MAX_LLM_INPUT_LENGTH)
        val outcome = runLlmAnalysis(truncated, localHits, client)
        val llmFindings = outcome.getOrElse { emptyList() }
        val merged = merge(localFindings, llmFindings)
        return AnalysisResult(
            findings = merged,
            rawTextLength = agreementText.length,
            llmUsed = true, // L5: 表示已尝试调用 LLM（含成功返回空数组）
            llmError = outcome.exceptionOrNull()?.let { describeLlmError(it) }
        )
    }

    private suspend fun runLlmAnalysis(
        text: String,
        localHits: List<RiskHit>,
        client: LlmClient // M2: 显式传入非空 client，消除 !!
    ): Result<List<RiskFinding>> {
        val localContext = if (localHits.isEmpty()) {
            "（本地规则引擎未命中明显风险）"
        } else {
            "本地规则引擎已命中以下类别，供参考：\n" +
                    localHits.joinToString("\n") { "- ${it.category.label}：${it.excerpt}" }
        }
        val userContent = "<agreement_text>\n${PiiRedactor.redact(text)}\n</agreement_text>\n\n本地预筛结果：\n$localContext"

        val resp = client.chat(LEGAL_ANALYSIS_SYSTEM_PROMPT, userContent).getOrElse {
            return Result.failure(it)
        }
        return Result.success(parseLlmFindings(resp))
    }

    private fun parseLlmFindings(raw: String): List<RiskFinding> {
        val cleaned = extractJson(raw) ?: return emptyList()
        val element = runCatching { json.parseToJsonElement(cleaned) }.getOrNull() ?: return emptyList()
        val arr: JsonArray = when (element) {
            is JsonArray -> element
            is JsonObject -> (element["findings"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        // H3: 每条 item 独立 runCatching，单条异常不炸整批
        return arr.mapNotNull { item ->
            runCatching {
                val obj = item.jsonObject
                val catName = obj["category"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val category = runCatching { RiskCategory.valueOf(catName) }.getOrDefault(RiskCategory.OTHER)
                val sevName = obj["severity"]?.jsonPrimitive?.contentOrNull ?: "LOW"
                // M5: 归一化大小写
                val severity = runCatching { Severity.valueOf(sevName.uppercase()) }.getOrDefault(Severity.LOW)
                RiskFinding(
                    category = category,
                    severity = severity,
                    excerpt = obj["excerpt"]?.jsonPrimitive?.contentOrNull ?: "",
                    explanation = obj["explanation"]?.jsonPrimitive?.contentOrNull ?: "",
                    advice = obj["advice"]?.jsonPrimitive?.contentOrNull ?: "",
                    source = FindingSource.LLM
                )
            }.getOrNull()
        }
    }

    /**
     * M6: 从 LLM 原始返回中提取首个 JSON 数组或对象，容忍：
     * - 直接是 JSON（数组或对象）
     * - markdown 代码块包裹（```json / ```JSON / ``` 均可，大小写不敏感）
     * - 代码块前后有附加文本
     */
    private fun extractJson(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) return trimmed
        val codeBlock = Regex("""```[a-zA-Z]*\s*([\s\S]*?)\s*```""").find(trimmed)
        if (codeBlock != null) {
            val inner = codeBlock.groupValues[1].trim()
            if (inner.startsWith("[") || inner.startsWith("{")) return inner
        }
        val arrMatch = Regex("""\[[\s\S]*\]""").find(trimmed)
        if (arrMatch != null) return arrMatch.value
        return Regex("""\{[\s\S]*\}""").find(trimmed)?.value
    }

    private fun merge(local: List<RiskFinding>, llm: List<RiskFinding>): List<RiskFinding> {
        // H9: 同类别取更高 severity（而非 LLM 一律覆盖本地），避免 LLM 低估覆盖本地 HIGH。
        // local 在前，同 rank 时保留本地。
        val byCategory = (local + llm).groupBy { it.category }
        return byCategory.values.map { findings ->
            findings.maxByOrNull { it.severity.rank }!!
        }.sortedByDescending { it.severity.rank }
    }

    private fun describeLlmError(t: Throwable): String = when (t) {
        is LlmException -> when (t.kind) {
            ErrorKind.AUTH -> "认证失败（检查 API Key）"
            ErrorKind.RATE_LIMIT -> "限流，稍后重试"
            ErrorKind.NETWORK -> "网络错误"
            ErrorKind.SERVER -> "服务端错误"
            else -> t.message ?: "未知错误"
        }
        else -> t.message?.take(120) ?: t::class.simpleName ?: "未知错误"
    }

    private fun RiskHit.toFinding(): RiskFinding = RiskFinding(
        category = category,
        severity = severity,
        excerpt = excerpt,
        explanation = explanation,
        advice = "",
        source = FindingSource.LOCAL
    )

    companion object {
        // M13: 抽常量并说明依据
        private const val MIN_LLM_INPUT_LENGTH = 30  // 太短模型无上下文，跳过 LLM
        private const val MAX_LLM_INPUT_LENGTH = 8000 // 截断避免超 context window
    }
}
