package com.legalreview.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.legalreview.analysis.AnalysisResultHolder
import com.legalreview.overlay.ResultSink

/**
 * 展示分析结果的活动：优先从进程内 [AnalysisResultHolder] 取结果（H1，避免 Intent 超大），
 * fallback 到 Intent extra 的 JSON。由悬浮按钮分析完成或通知点击拉起。
 */
class ResultActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = AnalysisResultHolder.get()
            ?: ResultSink.decode(intent.getStringExtra(EXTRA_RESULT_JSON).orEmpty())
        setContent {
            MaterialTheme {
                Surface { ResultScreen(result = result, onClose = { finish() }) }
            }
        }
    }

    companion object {
        const val EXTRA_RESULT_JSON = "result_json"
    }
}
