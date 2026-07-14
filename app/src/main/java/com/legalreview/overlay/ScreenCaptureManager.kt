package com.legalreview.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 封装 MediaProjection 截屏。
 *
 * 用法：
 * 1. Activity 调用 [requestProjection] 拿到授权后的 resultCode + Intent。
 * 2. 调用 [start] 初始化 MediaProjection（需在前台服务上下文中）。
 * 3. 调用 [capture] 拿一张 Bitmap。
 *
 * 注意：Android 14+ 要求 startForeground(mediaProjection 类型) 在 getMediaProjection 之前。
 */
class ScreenCaptureManager(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    private val projectionManager: MediaProjectionManager =
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    /** 创建授权 Intent，供 Activity startActivityForResult。 */
    fun createScreenCaptureIntent(): Intent = projectionManager.createScreenCaptureIntent()

    /**
     * 用授权结果初始化 MediaProjection。
     * 必须在调用方已 startForeground(mediaProjection) 之后调用。
     */
    fun start(resultCode: Int, data: Intent) {
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        Log.i(TAG, "MediaProjection started")
    }

    fun stop() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        Log.i(TAG, "MediaProjection stopped")
    }

    /**
     * 截取一帧屏幕，返回 Bitmap。
     * 在主线程调用；内部等待 ImageReader 回调。
     */
    suspend fun capture(width: Int, height: Int, density: Int): Result<Bitmap> = suspendCoroutine { cont ->
        val projection = mediaProjection
        if (projection == null) {
            cont.resume(Result.failure(IllegalStateException("MediaProjection not started")))
            return@suspendCoroutine
        }

        // 释放上一次的资源
        virtualDisplay?.release()
        imageReader?.close()

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        reader.setOnImageAvailableListener({ r ->
            val image: Image? = r.acquireLatestImage()
            if (image != null) {
                val bitmap = imageToBitmap(image, width, height)
                image.close()
                r.setOnImageAvailableListener(null, null)
                cont.resume(Result.success(bitmap))
            }
        }, handler)

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "LegalReviewCapture",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        } catch (e: Exception) {
            cont.resume(Result.failure(e))
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(buffer)
        // 裁掉 padding
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
