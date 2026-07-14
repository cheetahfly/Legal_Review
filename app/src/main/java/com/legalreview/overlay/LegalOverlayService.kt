package com.legalreview.overlay

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.legalreview.accessibility.LegalAccessibilityService
import com.legalreview.analysis.AnalysisOrchestrator
import com.legalreview.analysis.AnalysisResult
import com.legalreview.analysis.AnalysisResultHolder
import com.legalreview.analysis.FindingSource
import com.legalreview.analysis.RiskCategory
import com.legalreview.analysis.RiskFinding
import com.legalreview.analysis.Severity
import com.legalreview.data.SettingsRepository
import com.legalreview.llm.LlmClientFactory
import com.legalreview.llm.OpenAiCompatibleLlmClient
import com.legalreview.ocr.OcrRecognizer
import com.legalreview.ui.ResultActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 前台服务，承载悬浮按钮 + MediaProjection 截图 + OCR + 分析调度。
 * foregroundServiceType="mediaProjection" 在 Manifest 中声明。
 *
 * 启动方式：先通过 [ProjectionAuthActivity] 拿到 MediaProjection 授权，
 * 再 startForegroundService 传入 resultCode + data，本服务在 onStartCommand 中初始化截图器。
 */
class LegalOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private val serviceLifecycleOwner = ServiceLifecycleOwner()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var screenCaptureManager: ScreenCaptureManager? = null
    private val ocrRecognizer = OcrRecognizer()
    // H5: 共享 OkHttpClient 与 SettingsRepository，避免每次分析新建（连接池/线程池泄漏）
    private val sharedHttpClient by lazy { OpenAiCompatibleLlmClient.defaultHttpClient() }
    private val settingsRepo by lazy { SettingsRepository(this) }

    private val layoutParams = WindowManager.LayoutParams().apply {
        type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 200
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startForeground(NOTIFICATION_ID, buildNotification())
        showFloatingButton()
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次进入前台都需 startForeground（Android 9+ 严格）
        startForeground(NOTIFICATION_ID, buildNotification())
        // 从 ProjectionAuthActivity 传入授权结果，初始化截图器。
        intent?.let {
            val resultCode = it.getIntExtra(ProjectionAuthActivity.EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            // L8: 用类型安全的新重载（Android 13+）
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(ProjectionAuthActivity.EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra(ProjectionAuthActivity.EXTRA_RESULT_DATA)
            }
            if (resultCode == Activity.RESULT_OK && data != null) {
                // H6: 重新授权前释放旧 MediaProjection，避免泄漏（Android 14+ 每 token 仅允许一个活跃投影）
                screenCaptureManager?.stop()
                screenCaptureManager = ScreenCaptureManager(this).also { mgr ->
                    mgr.start(resultCode, data)
                }
                Log.i(TAG, "ScreenCaptureManager initialized")
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        ocrRecognizer.close()
        screenCaptureManager?.stop()
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        serviceLifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        floatingView?.let { windowManager.removeView(it) }
        floatingView = null
    }

    private fun showFloatingButton() {
        if (floatingView != null) return
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    FloatingButton(onClick = { onFloatingButtonClicked() })
                }
            }
        }
        // 为 ComposeView 提供 LifecycleOwner（Service 本身不是 LifecycleOwner）。
        composeView.setViewTreeLifecycleOwner(serviceLifecycleOwner)
        composeView.setViewTreeSavedStateRegistryOwner(serviceLifecycleOwner)
        windowManager.addView(composeView, layoutParams)
        floatingView = composeView
    }

    private fun onFloatingButtonClicked() {
        val view = floatingView ?: return
        view.visibility = View.INVISIBLE
        view.postDelayed({
            Log.i(TAG, "Floating button clicked")
            coroutineScope.launch {
                // C1: 分析流水线（OCR 像素复制/规则扫描/无障碍节点遍历）移出主线程，避免 ANR
                val result = withContext(Dispatchers.Default) { runAnalysis() }
                view.visibility = View.VISIBLE
                showResultNotification(result)
                openResultScreen(result)
            }
        }, FRAME_DELAY_MS)
    }

    private suspend fun runAnalysis(): AnalysisResult {
        val errorText: String = run {
            // 优先尝试无障碍快捷路径：免截图、免 OCR、更快更准。
            val accessibleText = runCatching {
                LegalAccessibilityService.captureText()
            }.getOrNull()
            if (!accessibleText.isNullOrBlank()) {
                Log.i(TAG, "无障碍取文本成功 长度 ${accessibleText.length}（跳过截图+OCR）")
                val orchestrator = buildOrchestrator()
                return orchestrator.analyze(accessibleText)
            }

            val capture = screenCaptureManager
            if (capture == null) {
                Log.w(TAG, "ScreenCaptureManager 未初始化（MediaProjection 未授权）")
                return@run "请先在主界面点击「授权并启动悬浮按钮」完成截屏授权后再使用。"
            }
            val metrics = resources.displayMetrics
            val w = metrics.widthPixels
            val h = metrics.heightPixels
            val density = metrics.densityDpi

            val bitmap = capture.capture(w, h, density).getOrElse {
                Log.e(TAG, "截图失败: ${it.message}")
                return@run "截图失败：${it.message}"
            }
            Log.i(TAG, "截图成功 ${bitmap.width}x${bitmap.height}")

            val ocrText = ocrRecognizer.recognize(bitmap).getOrElse {
                Log.e(TAG, "OCR 失败: ${it.message}")
                return@run "OCR 识别失败：${it.message}"
            }
            Log.i(TAG, "OCR 文本长度 ${ocrText.length}")

            if (ocrText.isBlank()) {
                return@run "未识别到文本。该应用可能禁止截屏（FLAG_SECURE），请手动复制协议文字。"
            }

            val orchestrator = buildOrchestrator()
            return orchestrator.analyze(ocrText)
        }
        // 走到这里说明流程中途出错，返回只含一条"其他风险"说明的占位结果，供结果页统一展示。
        return errorResult(errorText)
    }

    private fun errorResult(message: String): AnalysisResult =
        AnalysisResult(
            findings = listOf(
                RiskFinding(
                    category = RiskCategory.OTHER,
                    severity = Severity.LOW,
                    excerpt = "",
                    explanation = message,
                    advice = "",
                    source = FindingSource.LOCAL
                )
            ),
            rawTextLength = 0,
            llmUsed = false
        )

    private fun buildOrchestrator(): AnalysisOrchestrator {
        val config = settingsRepo.loadConfig()
        val llmClient = if (config.apiKey.isNotBlank()) {
            // H5: 复用共享 OkHttpClient
            LlmClientFactory.create(config, sharedHttpClient)
        } else null
        return AnalysisOrchestrator(llmClient = llmClient)
    }

    private fun formatResult(result: AnalysisResult): String {
        if (result.findings.isEmpty()) return "未发现明显风险条款。"
        return buildString {
            append("发现 ${result.findings.size} 条需注意：\n\n")
            result.findings.take(5).forEachIndexed { i, f ->
                append("${i + 1}. [${f.severity.label}] ${f.category.label}\n")
                if (f.explanation.isNotBlank()) append("   ${f.explanation}\n")
                if (f.advice.isNotBlank()) append("   建议：${f.advice}\n")
                append("\n")
            }
            if (result.findings.size > 5) append("…其余 ${result.findings.size - 5} 条详见App。")
        }
    }

    private fun showResultNotification(result: AnalysisResult) {
        val message = formatResult(result)
        val manager = getSystemService(NotificationManager::class.java)
        val notif = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle("协议审查结果")
            .setContentText(message.take(40))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setContentIntent(resultPendingIntent(result))
            .build()
        manager.notify(RESULT_NOTIFICATION_ID, notif)
    }

    /**
     * 通知点击的跳转：把结构化结果序列化后传给 ResultActivity 展示。
     * H1: 同时写入进程内 holder，ResultActivity 优先从 holder 取，避免 Intent 超大崩溃。
     */
    private fun resultPendingIntent(result: AnalysisResult): PendingIntent {
        AnalysisResultHolder.put(result)
        val intent = Intent(this, ResultActivity::class.java).apply {
            putExtra(ResultActivity.EXTRA_RESULT_JSON, ResultSink.encode(result))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            this, RESULT_REQUEST_CODE, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** 悬浮按钮分析完成后直接拉起结果页（沉浸式查看，不必等用户点通知）。 */
    private fun openResultScreen(result: AnalysisResult) {
        AnalysisResultHolder.put(result)
        val intent = Intent(this, ResultActivity::class.java).apply {
            putExtra(ResultActivity.EXTRA_RESULT_JSON, ResultSink.encode(result))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // H1: JSON 超 Binder ~1MB 上限时 startActivity 抛 TransactionTooLargeException，不能让服务崩
        runCatching { startActivity(intent) }.onFailure {
            Log.e(TAG, "打开结果页失败: ${it.message}")
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "协议把关前台服务",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID, "审查结果",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("协议把关")
            .setContentText("悬浮按钮运行中")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "LegalOverlayService"
        private const val CHANNEL_ID = "legal_overlay_channel"
        private const val RESULT_CHANNEL_ID = "legal_result_channel"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_ID = 1002
        private const val RESULT_REQUEST_CODE = 1003
        private const val FRAME_DELAY_MS = 60L

        fun hasOverlayPermission(context: Context): Boolean =
            Settings.canDrawOverlays(context)
    }
}

/**
 * 给 ComposeView 用的 LifecycleOwner + SavedStateRegistryOwner。
 * Service 本身不是，需手动驱动。
 */
private class ServiceLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this).apply {
        performRestore(null)
    }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry
    fun handleLifecycleEvent(event: Lifecycle.Event) {
        registry.handleLifecycleEvent(event)
    }
}

@Composable
private fun FloatingButton(onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = Color(0xFF1F6FEB),
        shadowElevation = 6.dp,
        onClick = onClick
    ) {
        Icon(
            imageVector = Icons.Filled.Shield,
            contentDescription = "协议把关",
            tint = Color.White,
            modifier = Modifier.size(56.dp)
        )
    }
}
