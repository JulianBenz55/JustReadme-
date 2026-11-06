package com.aryan.reader

import android.graphics.Bitmap
import com.aryan.reader.pdf.OcrLanguage
import com.aryan.reader.pdf.ocr.OcrResult
import timber.log.Timber

object OcrEngine {
    fun init(language: OcrLanguage) { Timber.d("OCR Init called in OSS flavor (No-op)")
    }

    suspend fun extractTextFromBitmap(
        bitmap: Bitmap,
        onModelDownloading: () -> Unit
    ): OcrResult? {
        Timber.d("OCR Extract called in OSS flavor (No-op)")
        return null
    }
}