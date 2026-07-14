package com.legalreview.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 可选的快捷路径：注册为系统无障碍服务后，悬浮按钮点击时优先读取当前屏幕节点文本，
 * 成功则跳过 MediaProjection 截图 + OCR（更快、更准、免 OCR 模型体积）。
 *
 * FLAG_SECURE 的 App / 部分 WebView 仍可能读不到，由调用方回退截图+OCR。
 *
 * 注意：本服务仅个人自用，用于读取屏幕文本做风险审查，不上架应用商店。
 */
class LegalAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 不在事件中处理：按需在悬浮按钮点击时主动 capture
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    /**
     * 抓取当前活动窗口的全部可见文本（递归遍历节点）。
     */
    private fun captureActiveWindowText(): String {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        collectText(root, sb)
        return sb.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder) {
        node.text?.let { out.append(it).append('\n') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, out)
        }
    }

    companion object {
        @Volatile
        private var instance: LegalAccessibilityService? = null

        /**
         * 若无障碍服务已启用，抓取当前屏幕文本；文本过短或服务未启用返回 null，调用方回退截图+OCR。
         */
        fun captureText(): String? {
            val svc = instance ?: return null
            val text = svc.captureActiveWindowText()
            return text.takeIf { it.length >= MIN_TEXT_LENGTH }
        }

        /**
         * 判断本无障碍服务是否已被用户在系统设置中开启。
         */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, LegalAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        private const val MIN_TEXT_LENGTH = 30
    }
}
