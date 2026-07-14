package com.legalreview.overlay

import com.legalreview.analysis.AnalysisResult
import kotlinx.serialization.json.Json

/**
 * 结果在 Service 与 ResultActivity 之间通过 Intent extra 传递，统一在此序列化/反序列化。
 */
object ResultSink {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(result: AnalysisResult): String = json.encodeToString(AnalysisResult.serializer(), result)

    fun decode(raw: String): AnalysisResult =
        runCatching { json.decodeFromString(AnalysisResult.serializer(), raw) }.getOrNull()
            ?: AnalysisResult(findings = emptyList(), rawTextLength = 0, llmUsed = false)
}
