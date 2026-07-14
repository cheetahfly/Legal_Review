package com.legalreview.analysis

/**
 * C3: 把送往前端 LLM 的协议文本中的个人敏感信息脱敏，避免银行余额页/表单 autofill
 * 等场景下的 PII 泄露到第三方服务器。本地规则扫描仍用原文（风险关键词不含 PII）。
 */
internal object PiiRedactor {
    private val patterns = listOf(
        Regex("1[3-9]\\d{9}") to "[手机号]",                      // 11 位手机号
        Regex("[1-9]\\d{16}[0-9Xx]") to "[身份证]",                // 18 位身份证（末位 X）
        Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}") to "[邮箱]"
    )

    fun redact(text: String): String =
        patterns.fold(text) { acc, (pattern, mask) -> pattern.replace(acc, mask) }
}
