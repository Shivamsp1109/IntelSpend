package com.spendwise.data.ingestion.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class OcrProcessor @Inject constructor() {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(bitmap: Bitmap, page: Int = 0): OcrResult {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()

            val lines = mutableListOf<OcrLine>()
            val words = mutableListOf<OcrWord>()

            for (block in result.textBlocks) {
                for (line in block.lines) {
                    line.boundingBox?.let { box ->
                        lines.add(
                            OcrLine(
                                text = line.text,
                                left = box.left,
                                top = box.top,
                                right = box.right,
                                bottom = box.bottom,
                                height = box.height()
                            )
                        )
                    }
                    // Element-level boxes are the whole point: ML Kit frequently splits a
                    // table row into several lines at different x, so line boxes alone can't
                    // tell us which column a number belongs to.
                    for (element in line.elements) {
                        val box = element.boundingBox ?: continue
                        words.add(
                            OcrWord(
                                text = element.text,
                                left = box.left.toFloat(),
                                top = box.top.toFloat(),
                                right = box.right.toFloat(),
                                bottom = box.bottom.toFloat(),
                                page = page
                            )
                        )
                    }
                }
            }

            OcrResult(result.text, lines, words)
        } catch (e: Exception) {
            e.printStackTrace()
            OcrResult("", emptyList(), emptyList())
        }
    }
}
