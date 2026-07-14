package com.legalreview.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.legalreview.overlay.ResultSink

/**
 * 展示分析结果的活动：从 Intent extra 读取序列化的 AnalysisResult，交给 ResultScreen 渲染。
 * 由悬浮按钮分析完成或通知点击拉起。
 */
class ResultActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent.getStringExtra(EXTRA_RESULT_JSON).orEmpty()
        val result = ResultSink.decode(raw)
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
