package com.legalreview.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 封装 ML Kit 中文文本识别（bundled 模型，离线可用，不依赖 GMS 运行时下载）。
 */
class OcrRecognizer(
    private val recognizer: TextRecognizer = TextRecognition.getClient(
        ChineseTextRecognizerOptions.Builder().build()
    )
) {
    /**
     * 识别 Bitmap 中的中文文本，按行拼接返回纯文本。
     */
    suspend fun recognize(bitmap: Bitmap): Result<String> = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text
                cont.resume(Result.success(text))
            }
            .addOnFailureListener { e ->
                cont.resume(Result.failure(e))
            }
    }

    fun close() {
        recognizer.close()
    }
}
