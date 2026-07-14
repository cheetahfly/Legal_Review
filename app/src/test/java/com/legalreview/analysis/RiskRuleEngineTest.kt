package com.legalreview.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskRuleEngineTest {

    private val engine = RiskRuleEngine()

    @Test
    fun `auto renew keyword hits`() {
        val text = "试用期满后将自动续费，每月从你的账户连续扣款。"
        val hits = engine.scan(text)
        assertTrue("应命中自动续费", hits.any { it.category == RiskCategory.AUTO_RENEW })
        assertTrue(hits.any { it.matchedKeyword == "自动续费" })
        assertTrue(hits.any { it.matchedKeyword == "连续扣款" })
    }

    @Test
    fun `liability limit hits`() {
        val text = "本公司对因使用本服务造成的损失概不负责，赔偿上限为人民币100元。"
        val hits = engine.scan(text)
        assertTrue(hits.any { it.category == RiskCategory.LIABILITY_LIMIT })
        assertTrue(hits.any { it.matchedKeyword == "概不负责" })
    }

    @Test
    fun `jurisdiction regex hits`() {
        val text = "因本协议产生的争议，应提交北京仲裁委员会仲裁。"
        val hits = engine.scan(text)
        assertTrue(hits.any { it.category == RiskCategory.JURISDICTION })
    }

    @Test
    fun `high penalty percentage hits`() {
        val text = "如用户违约，需支付违约金30%作为赔偿。"
        val hits = engine.scan(text)
        assertTrue(hits.any { it.category == RiskCategory.HIGH_PENALTY })
    }

    @Test
    fun `excerpt contains context`() {
        val text = "前文若干字。本公司有权随时修改本协议，无需另行通知。后文若干字。"
        val hits = engine.scan(text, contextRadius = 10)
        val hit = hits.first { it.category == RiskCategory.UNILATERAL_MODIFY }
        assertTrue(hit.excerpt.contains("有权随时修改"))
    }

    @Test
    fun `blank text returns empty`() {
        assertEquals(emptyList<RiskHit>(), engine.scan(""))
        assertEquals(emptyList<RiskHit>(), engine.scan("   "))
    }

    @Test
    fun `no false positive on benign text`() {
        val text = "欢迎您使用本服务，我们将为您提供优质的使用体验。"
        val hits = engine.scan(text)
        assertTrue("正常文本不应误报", hits.isEmpty())
    }

    @Test
    fun `irrevocable auth hits`() {
        val text = "您在此授予平台不可撤销的、永久的免费使用许可。"
        val hits = engine.scan(text)
        assertTrue(hits.any { it.category == RiskCategory.IRREVOCABLE_AUTH })
    }

    /** C2: OCR / 无障碍文本中跨行的关键词，归一化后仍应命中。
     *  归一化（移除 \n）由 AnalysisOrchestrator 在做，这里测归一化后的效果。 */
    @Test
    fun `keyword hits across newlines after normalization`() {
        val raw = "本公司不承担\n任何责任，且有权\n随时修改。"
        val normalized = raw.replace("\n", "")
        val hits = engine.scan(normalized)
        assertTrue("换行归一化后 liability 规则应命中",
            hits.any { it.category == RiskCategory.LIABILITY_LIMIT })
        assertTrue("换行归一化后 unilateral_modify 字面量应命中",
            hits.any { it.category == RiskCategory.UNILATERAL_MODIFY })
    }

    /** M7: 1% 违约金不宜报"过高"，≥20% 才报。 */
    @Test
    fun `low percentage does not trigger high penalty`() {
        val text = "违约金按已支付费用的1%计算。"
        val hits = engine.scan(text)
        assertTrue("1% 不应命中 HIGH_PENALTY",
            hits.none { it.category == RiskCategory.HIGH_PENALTY })
    }

    @Test
    fun `high percentage still triggers high penalty`() {
        val text = "违约金按已支付费用的30%计算。"
        val hits = engine.scan(text)
        assertTrue("30% 应命中 HIGH_PENALTY",
            hits.any { it.category == RiskCategory.HIGH_PENALTY })
    }
}
