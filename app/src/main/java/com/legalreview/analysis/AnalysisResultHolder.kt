package com.legalreview.analysis

/**
 * 进程内缓存最近一次分析结果，供 ResultActivity 通过引用取用，
 * 避免 AnalysisResult JSON 通过 Intent extra 传递触发 TransactionTooLargeException（H1）。
 * Intent extra 仍作为进程被杀后的 fallback。
 */
internal object AnalysisResultHolder {
    @Volatile
    private var current: AnalysisResult? = null

    fun put(result: AnalysisResult) {
        current = result
    }

    fun get(): AnalysisResult? = current
}
