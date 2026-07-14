package com.legalreview.analysis

import kotlinx.serialization.Serializable

/**
 * 一条最终展示给用户的风险条款。来源可为本地规则引擎或大模型。
 */
@Serializable
data class RiskFinding(
    val category: String,        // RiskCategory 名称
    val categoryLabel: String,   // 中文展示
    val severity: String,        // HIGH / MEDIUM / LOW
    val severityLabel: String,   // 高 / 中 / 低
    val excerpt: String,
    val explanation: String,
    val advice: String = "",
    val source: String           // "local" | "llm"
)

@Serializable
data class AnalysisResult(
    val findings: List<RiskFinding>,
    val rawTextLength: Int,
    val llmUsed: Boolean
)
