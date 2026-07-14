package com.legalreview.analysis

import kotlinx.serialization.Serializable

/**
 * 风险条款类别。本地规则引擎与大模型输出共用同一套类别，便于合并结果。
 */
@Serializable
enum class RiskCategory(val label: String) {
    AUTO_RENEW("自动续费/连续扣款"),
    UNILATERAL_MODIFY("单方修改权"),
    UNILATERAL_TERMINATE("单方解除/终止"),
    LIABILITY_LIMIT("免责/限责"),
    EXCESSIVE_INFO("过度收集个人信息"),
    THIRD_PARTY_SHARE("向第三方共享/转让"),
    JURISDICTION("管辖与仲裁"),
    IRREVOCABLE_AUTH("不可撤销授权"),
    HIGH_PENALTY("违约金过高"),
    HIDDEN_CLAUSE("隐蔽条款引用"),
    OTHER("其他风险")
}

@Serializable
enum class Severity(val label: String, val rank: Int) {
    HIGH("高", 3),
    MEDIUM("中", 2),
    LOW("低", 1)
}

/**
 * 一条风险规则。
 * @param patterns 命中关键词或正则（正则以 regex: 前缀标识；含正则元字符且无 regex: 前缀的视为正则）
 */
data class RiskRule(
    val id: String,
    val category: RiskCategory,
    val severity: Severity,
    val patterns: List<String>,
    val explanation: String
)

/**
 * 引擎对一条规则的命中结果。
 */
data class RiskHit(
    val ruleId: String,
    val category: RiskCategory,
    val severity: Severity,
    val matchedKeyword: String,
    val excerpt: String,
    val explanation: String
)

/**
 * 本地规则引擎：用关键词/正则预筛协议文本中的常见风险条款。
 * 输出供大模型精分析参考，也作为离线兜底结果。
 */
class RiskRuleEngine(
    private val rules: List<RiskRule> = DEFAULT_RULES
) {
    /**
     * 扫描文本，返回所有命中。
     * @param fullText 协议全文
     * @param contextRadius 命中关键词前后截取的字符数，用于展示上下文
     */
    fun scan(fullText: String, contextRadius: Int = 40): List<RiskHit> {
        if (fullText.isBlank()) return emptyList()
        val hits = mutableListOf<RiskHit>()
        for (rule in rules) {
            for (pattern in rule.patterns) {
                val regex = compilePattern(pattern) ?: continue
                regex.findAll(fullText).forEach { m ->
                    // 用实际命中文本 m.value，而非 pattern 字符串，避免把正则语法展示给用户
                    hits.add(buildHit(rule, m.value, fullText, m.range.first, contextRadius))
                }
            }
        }
        return hits
    }

    /**
     * 把一条 pattern 编译为 Regex。
     * - 前缀 regex: → 整条当正则，命中关键字取 group 0。
     * - 含正则元字符（. * + ? 等）→ 当正则处理，命中关键字取原 pattern。
     * - 其余纯字面量 → 用 Regex.escape 转义后按字面量匹配，忽略大小写。
     * 返回 (regex, 命中展示用 label)。
     */
    private fun compilePattern(pattern: String): Regex? {
        return when {
            pattern.startsWith(REGEX_PREFIX) -> {
                val expr = pattern.removePrefix(REGEX_PREFIX)
                runCatching { Regex(expr) }.getOrNull()
            }
            containsRegexMeta(pattern) -> {
                runCatching { Regex(pattern) }.getOrNull()
            }
            else -> {
                runCatching { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }.getOrNull()
            }
        }
    }

    private fun containsRegexMeta(s: String): Boolean =
        s.any { it in "\\.*+?^\$|()[]{}" }

    private fun buildHit(
        rule: RiskRule,
        matched: String,
        fullText: String,
        matchStart: Int,
        contextRadius: Int
    ): RiskHit {
        val from = (matchStart - contextRadius).coerceAtLeast(0)
        val to = (matchStart + matched.length + contextRadius).coerceAtMost(fullText.length)
        val excerpt = fullText.substring(from, to).replace('\n', ' ').trim()
        return RiskHit(
            ruleId = rule.id,
            category = rule.category,
            severity = rule.severity,
            matchedKeyword = matched,
            excerpt = excerpt,
            explanation = rule.explanation
        )
    }

    companion object {
        private const val REGEX_PREFIX = "regex:"

        /**
         * 默认规则集：覆盖通用风险类别。关键词尽量用"协议常见措辞"，避免误报。
         */
        val DEFAULT_RULES: List<RiskRule> = listOf(
            RiskRule(
                id = "auto_renew_1",
                category = RiskCategory.AUTO_RENEW,
                severity = Severity.HIGH,
                patterns = listOf("自动续费", "自动续期", "连续扣款", "自动扣款", "免费试用.*付费"),
                explanation = "可能默认开启自动续费/连续扣款，关闭前会持续扣款。"
            ),
            RiskRule(
                id = "unilateral_modify_1",
                category = RiskCategory.UNILATERAL_MODIFY,
                severity = Severity.MEDIUM,
                patterns = listOf("有权随时修改", "单方变更", "无需另行通知", "有权调整.*服务内容"),
                explanation = "平台可单方修改协议内容，可能不经你同意即生效。"
            ),
            RiskRule(
                id = "unilateral_terminate_1",
                category = RiskCategory.UNILATERAL_TERMINATE,
                severity = Severity.MEDIUM,
                patterns = listOf("有权随时终止", "单方终止", "随时中断"),
                explanation = "平台可单方随时终止服务，你的使用预期不受保障。"
            ),
            RiskRule(
                id = "liability_limit_1",
                category = RiskCategory.LIABILITY_LIMIT,
                severity = Severity.HIGH,
                patterns = listOf("不承担.*责任", "概不负责", "免除.*赔偿责任", "赔偿.*上限", "限额赔偿"),
                explanation = "平台排除或限制自身赔偿责任，出问题时你难以追偿。"
            ),
            RiskRule(
                id = "excessive_info_1",
                category = RiskCategory.EXCESSIVE_INFO,
                severity = Severity.HIGH,
                patterns = listOf("读取通讯录", "获取通话记录", "读取短信", "位置信息.*后台", "人脸.*采集", "指纹.*采集"),
                explanation = "收集的个人信息可能与功能无关，超出必要范围。"
            ),
            RiskRule(
                id = "third_party_share_1",
                category = RiskCategory.THIRD_PARTY_SHARE,
                severity = Severity.MEDIUM,
                patterns = listOf("向第三方共享", "提供给.*合作方", "关联方.*使用", "转让给第三方"),
                explanation = "你的信息可能被共享/转让给第三方，范围不明确。"
            ),
            RiskRule(
                id = "jurisdiction_1",
                category = RiskCategory.JURISDICTION,
                severity = Severity.MEDIUM,
                patterns = listOf("regex:提交.*仲裁", "仲裁委员会", "管辖法院.*所在地", "由.*法院管辖"),
                explanation = "争议解决方式/管辖地可能不利于你维权，提高诉讼成本。"
            ),
            RiskRule(
                id = "irrevocable_auth_1",
                category = RiskCategory.IRREVOCABLE_AUTH,
                severity = Severity.HIGH,
                patterns = listOf("不可撤销", "无偿.*永久.*使用"),
                explanation = "授予的授权不可撤销或永久有效，撤回困难。"
            ),
            RiskRule(
                id = "high_penalty_1",
                category = RiskCategory.HIGH_PENALTY,
                severity = Severity.MEDIUM,
                // M7: 要求违约金≥20%才命中，避免 1% 也报"过高"
                patterns = listOf("违约金.*([2-9]\\d%|100%)", "赔偿.*\\d+倍", "惩罚性赔偿"),
                explanation = "对你的违约责任设定较高违约金/赔偿，可能不对等。"
            ),
            RiskRule(
                id = "hidden_clause_1",
                category = RiskCategory.HIDDEN_CLAUSE,
                severity = Severity.LOW,
                patterns = listOf("详见.*链接", "参见.*网站", "以.*公布为准", "另行制定"),
                explanation = "部分条款通过链接/外部引用，需另行查看，易被忽略。"
            )
        )
    }
}
