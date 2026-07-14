package com.legalreview.analysis

import kotlinx.serialization.Serializable

/**
 * 一条最终展示给用户的风险条款。来源可为本地规则引擎或大模型。
 *
 * category/severity/source 用枚举而非 String，保证类型安全、消除三处重复的 when 分支。
 * label 由枚举提供，不再冗余存储。
 */
@Serializable
data class RiskFinding(
    val category: RiskCategory,
    val severity: Severity,
    val excerpt: String,
    val explanation: String,
    val advice: String = "",
    val source: FindingSource
)

@Serializable
data class AnalysisResult(
    val findings: List<RiskFinding>,
    val rawTextLength: Int,
    val llmUsed: Boolean,
    /** LLM 调用失败时的原因，null 表示未调用或成功。供 UI 区分"无风险"与"调用失败"。 */
    val llmError: String? = null
)

/** 风险条款来源。 */
@Serializable
enum class FindingSource { LOCAL, LLM }
