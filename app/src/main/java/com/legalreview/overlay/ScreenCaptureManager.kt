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
import android.os.HandlerThread
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

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
    // H8: 专用 HandlerThread，避免 ImageReader 回调与 ~10MB 像素复制阻塞主线程
    private val captureThread = HandlerThread("CaptureThread").apply { start() }
    private val handler = Handler(captureThread.looper)

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
        captureThread.quitSafely()
        Log.i(TAG, "MediaProjection stopped")
    }

    /**
     * 截取一帧屏幕，返回 Bitmap。
     *
     * C4: 用 suspendCancellableCoroutine，取消时释放 ImageReader/VirtualDisplay。
     * C5: createVirtualDisplay 返回 null 时返回失败而非永久挂起。
     * H7: withTimeoutOrNull 超时返回失败，避免悬浮按钮永久不可见。
     */
    suspend fun capture(width: Int, height: Int, density: Int): Result<Bitmap> {
        val result = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine<Result<Bitmap>> { cont ->
                val projection = mediaProjection
                if (projection == null) {
                    cont.resume(Result.failure(IllegalStateException("MediaProjection not started")))
                    return@suspendCancellableCoroutine
                }

                // 释放上一次的资源
                virtualDisplay?.release()
                imageReader?.close()

                val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                imageReader = reader

                // C4: 协程取消（含超时）时释放资源
                cont.invokeOnCancellation {
                    reader.setOnImageAvailableListener(null, null)
                    reader.close()
                    virtualDisplay?.release()
                    virtualDisplay = null
                }

                reader.setOnImageAvailableListener({ r ->
                    val image: Image? = r.acquireLatestImage()
                    if (image != null) {
                        val bitmap = imageToBitmap(image, width, height)
                        image.close()
                        r.setOnImageAvailableListener(null, null)
                        cont.resume(Result.success(bitmap))
                    }
                }, handler)

                // C5: createVirtualDisplay 可能返回 null
                val display = try {
                    projection.createVirtualDisplay(
                        "LegalReviewCapture",
                        width, height, density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.surface, null, handler
                    )
                } catch (e: Exception) {
                    cont.resume(Result.failure(e))
                    return@suspendCancellableCoroutine
                }
                if (display == null) {
                    cont.resume(Result.failure(IllegalStateException("VirtualDisplay creation returned null")))
                    return@suspendCancellableCoroutine
                }
                virtualDisplay = display
            }
        }
        return result ?: Result.failure(TimeoutException("截屏超时 ${CAPTURE_TIMEOUT_MS}ms"))
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val paddedWidth = width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        // 裁掉 padding
        val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
        // M15: 回收中间填充 bitmap（~10MB），减少内存压力
        if (padded !== cropped) padded.recycle()
        return cropped
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
        private const val CAPTURE_TIMEOUT_MS = 5_000L // H7
    }
}
