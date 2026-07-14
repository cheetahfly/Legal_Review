package com.legalreview.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import com.legalreview.accessibility.LegalAccessibilityService
import com.legalreview.data.SettingsRepository
import com.legalreview.llm.LlmProviderPresets
import com.legalreview.overlay.LegalOverlayService
import com.legalreview.overlay.ProjectionAuthActivity

/**
 * 主界面：设置 + 权限引导 + 启动悬浮按钮。
 */
@Composable
fun MainApp() {
    val context = LocalContext.current
    val settingsRepo = remember { SettingsRepository(context) }

    var config by remember { mutableStateOf(settingsRepo.loadConfig()) }
    var overlayGranted by remember {
        mutableStateOf(LegalOverlayService.hasOverlayPermission(context))
    }
    var accessibilityEnabled by remember {
        mutableStateOf(LegalAccessibilityService.isEnabled(context))
    }
    var dropdownExpanded by remember { mutableStateOf(false) }

    // 从设置页返回时刷新权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleObserver = remember {
        object : DefaultLifecycleObserver {
            override fun onResume(owner: androidx.lifecycle.LifecycleOwner) {
                overlayGranted = LegalOverlayService.hasOverlayPermission(context)
                accessibilityEnabled = LegalAccessibilityService.isEnabled(context)
            }
        }
    }
    DisposableEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose { lifecycleOwner.lifecycle.removeObserver(lifecycleObserver) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("协议把关", style = MaterialTheme.typography.headlineSmall)
        Text(
            "在其他App/网页遇到协议弹窗时，点悬浮按钮即可审查风险条款。",
            style = MaterialTheme.typography.bodyMedium
        )

        // 悬浮窗权限
        Text("悬浮窗权限：${if (overlayGranted) "已开启" else "未开启"}")
        if (!overlayGranted) {
            Button(onClick = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            }) { Text("去开启悬浮窗权限") }
        }

        // 无障碍快捷路径（可选）：开启后免截图/OCR，更快更准
        Text("无障碍快捷路径（可选）：${if (accessibilityEnabled) "已开启" else "未开启"}")
        Text(
            "开启后悬浮按钮优先直接读取屏幕文字，跳过截图+OCR；FLAG_SECURE/部分网页仍会回退截图。",
            style = MaterialTheme.typography.bodySmall
        )
        if (!accessibilityEnabled) {
            Button(onClick = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                context.startActivity(intent)
            }) { Text("去开启无障碍服务") }
        }

        Text("云端模型", style = MaterialTheme.typography.titleMedium)
        Box {
            TextButton(onClick = { dropdownExpanded = true }) {
                Text("${config.provider} · ${config.model}")
            }
            DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }) {
                LlmProviderPresets.ALL.forEach { preset ->
                    DropdownMenuItem(
                        text = { Text("${preset.provider} · ${preset.model}") },
                        onClick = {
                            config = config.copy(provider = preset.provider, baseUrl = preset.baseUrl, model = preset.model)
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }

        var apiKey by remember(config.provider) { mutableStateOf(config.apiKey) }
        OutlinedTextField(
            value = config.model,
            onValueChange = { config = config.copy(model = it) },
            label = { Text("模型名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; config = config.copy(apiKey = it) },
            label = { Text("API Key") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                settingsRepo.saveConfig(config)
            }) { Text("保存设置") }
            Button(
                onClick = {
                    // 先保存配置，再走 MediaProjection 授权并启动悬浮按钮
                    settingsRepo.saveConfig(config)
                    ProjectionAuthActivity.start(context)
                },
                enabled = overlayGranted
            ) { Text("授权并启动悬浮按钮") }
        }

        Text(
            "使用说明：\n" +
                    "1. 开启悬浮窗权限\n" +
                    "2. 填写云端模型 API Key 并保存\n" +
                    "3. 点击「授权并启动悬浮按钮」，允许屏幕共享\n" +
                    "4. 在其他 App/网页遇到协议弹窗时，点悬浮按钮即可审查\n" +
                    "5. 结果在结果页展示，同时在通知中可查看",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
