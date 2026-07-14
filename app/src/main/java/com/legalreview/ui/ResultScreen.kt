package com.legalreview.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.legalreview.analysis.AnalysisResult
import com.legalreview.analysis.FindingSource
import com.legalreview.analysis.RiskFinding
import com.legalreview.analysis.Severity

@Composable
fun ResultScreen(result: AnalysisResult, onClose: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("协议风险审查结果", style = MaterialTheme.typography.headlineSmall)
            Text(
                "共 ${result.findings.size} 条风险 · 文本长度 ${result.rawTextLength} · " +
                        "云端分析：${llmStatusText(result)}",
                style = MaterialTheme.typography.bodySmall
            )
            if (result.findings.isEmpty()) {
                // L3: 空结果占位，避免大片空白
                Text(
                    "未发现明显风险条款",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    // L4: 稳定 key（merge 后每类别最多一条）
                    items(result.findings, key = { it.category.name }) { finding ->
                        RiskCard(finding)
                    }
                }
            }
            Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("关闭")
            }
        }
    }
}

/** H2: 把 LLM 失败原因透传到 UI，区分"无风险"与"调用失败" */
private fun llmStatusText(result: AnalysisResult): String = when {
    result.llmError != null -> "失败（${result.llmError}）"
    result.llmUsed -> "已用"
    else -> "未用"
}

@Composable
private fun RiskCard(finding: RiskFinding) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(finding.category.label) })
                Text(
                    finding.severity.label,
                    color = severityColor(finding.severity),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    if (finding.source == FindingSource.LLM) "云端" else "本地",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }
            if (finding.excerpt.isNotBlank()) {
                Text("「${finding.excerpt}」", style = MaterialTheme.typography.bodySmall)
            }
            Text(finding.explanation, style = MaterialTheme.typography.bodyMedium)
            if (finding.advice.isNotBlank()) {
                Text("建议：${finding.advice}", style = MaterialTheme.typography.bodySmall, color = Color(0xFF1F6FEB))
            }
        }
    }
}

private fun severityColor(severity: Severity): Color = when (severity) {
    Severity.HIGH -> Color(0xFFD32F2F)
    Severity.MEDIUM -> Color(0xFFF57C00)
    Severity.LOW -> Color(0xFF6A6A6A)
}
