package com.legalreview.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 透明的授权 Activity：发起 MediaProjection 系统授权框，拿到结果后启动/初始化前台服务中的截图器。
 * 由用户在主界面点击「授权截屏」触发。
 */
class ProjectionAuthActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val captureManager = ScreenCaptureManager(this)
        val launcher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                // 启动前台服务并传入授权结果
                val serviceIntent = Intent(this, LegalOverlayService::class.java).apply {
                    putExtra(EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(serviceIntent)
                Log.i(TAG, "Projection authorized, starting overlay service")
            } else {
                Log.w(TAG, "Projection authorization denied")
            }
            finish()
        }
        // M14: 旋转等配置变更重建时不重复弹出授权框
        if (savedInstanceState == null) {
            launcher.launch(captureManager.createScreenCaptureIntent())
        }
    }

    companion object {
        private const val TAG = "ProjectionAuthActivity"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun start(context: Context) {
            val intent = Intent(context, ProjectionAuthActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
