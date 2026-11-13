package com.aryan.reader.webimport

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import java.io.ByteArrayOutputStream

/**
 * Synthetic 600x900 cover for imported web novels that offer no usable `og:image`
 * (counterpart of Readest's `coverGenerator.ts`). Rendered as PNG because the library's EPUB
 * cover extraction decodes bitmaps and cannot read SVG covers. The colour pair is hashed from the
 * author so the same writer always gets the same cover style.
 */
internal object CoverGenerator {
    private const val W = 600
    private const val H = 900
    private const val SPLIT_Y = 540
    private const val PAD = 56

    private val PALETTE = listOf(
        0xFF0EBAE3.toInt() to 0xFFE2F4F9.toInt(), // cyan
        0xFF3E89C7.toInt() to 0xFFE5EEF6.toInt(), // blue
        0xFFE27D5C.toInt() to 0xFFF7DDD0.toInt(), // coral
        0xFF5D9D7E.toInt() to 0xFFDCE8E1.toInt(), // sage
        0xFF9B6BAC.toInt() to 0xFFE5D9EB.toInt(), // lavender
        0xFFD8A55E.toInt() to 0xFFF4E5C9.toInt(), // ochre
        0xFF2A9D8F.toInt() to 0xFFD5EAE7.toInt(), // teal
        0xFF7E6C6C.toInt() to 0xFFE5DDDD.toInt(), // mauve
        0xFF3E5C76.toInt() to 0xFFD9DEE4.toInt(), // navy
        0xFFA36677.toInt() to 0xFFE8D6DA.toInt(), // wine
    )

    fun generate(title: String, author: String, siteName: String): EpubCover {
        val byline = author.ifBlank { siteName }
        val (topColor, bottomColor) = PALETTE[(byline.hashCode() and 0x7FFFFFFF) % PALETTE.size]

        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = topColor
        canvas.drawRect(0f, 0f, W.toFloat(), SPLIT_Y.toFloat(), fill)
        fill.color = bottomColor
        canvas.drawRect(0f, SPLIT_Y.toFloat(), W.toFloat(), H.toFloat(), fill)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 56f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val layout = StaticLayout.Builder
            .obtain(title, 0, title.length, titlePaint, W - 2 * PAD)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .setMaxLines(5)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(PAD.toFloat(), 100f)
        layout.draw(canvas)
        canvas.restore()

        val bylinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF2B2B2B.toInt()
            textSize = 32f
        }
        val line = TextUtils.ellipsize(byline, bylinePaint, (W - 2 * PAD).toFloat(), TextUtils.TruncateAt.END)
        canvas.drawText(line, 0, line.length, PAD.toFloat(), 830f, bylinePaint)

        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        return EpubCover(out.toByteArray(), "image/png")
    }
}
