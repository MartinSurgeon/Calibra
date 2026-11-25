package com.example.ui.measure

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max

/** Renders the active viewport into a bitmap, with or without its measurement overlay. */
typealias ViewportFrameProvider = suspend (withOverlay: Boolean) -> Bitmap?

/**
 * Lets the Measure screen grab "what the user sees" from whichever viewport is active (ARCore,
 * CameraX or the studio backdrop) without knowing how that viewport renders.
 */
class ViewportCaptureHandle {
    private var provider: ViewportFrameProvider? = null

    fun register(p: ViewportFrameProvider) {
        provider = p
    }

    fun unregister(p: ViewportFrameProvider) {
        if (provider === p) provider = null
    }

    /** The view with the measurement overlay burned in, for photos. */
    suspend fun capture(): Bitmap? = provider?.invoke(true)

    /** The clean camera (or studio) frame without overlays, e.g. to freeze it for sketching. */
    suspend fun captureFrame(): Bitmap? = provider?.invoke(false)
}

/** Draws [draw] on top of [base] with a Compose [DrawScope]; returns a mutable ARGB bitmap. */
internal fun renderOverlayOnto(base: Bitmap, density: Density, draw: DrawScope.() -> Unit): Bitmap {
    val target = if (base.isMutable && base.config == Bitmap.Config.ARGB_8888) {
        base
    } else {
        base.copy(Bitmap.Config.ARGB_8888, true)
    }
    CanvasDrawScope().draw(
        density,
        LayoutDirection.Ltr,
        Canvas(target.asImageBitmap()),
        Size(target.width.toFloat(), target.height.toFloat()),
        draw
    )
    return target
}

/**
 * Appends a caption band under a snapshot with the headline value, the dimension breakdown and
 * provenance, so a shared photo is self-explanatory.
 */
fun stampMeasurementCaption(
    snapshot: Bitmap,
    headline: String,
    detail: String,
    footer: String
): Bitmap {
    val width = snapshot.width
    val unit = max(1f, width / 1080f)
    val bandHeight = (200f * unit).toInt()
    val out = Bitmap.createBitmap(width, snapshot.height + bandHeight, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(out)
    canvas.drawBitmap(snapshot, 0f, 0f, null)

    val top = snapshot.height.toFloat()
    canvas.drawRect(0f, top, width.toFloat(), out.height.toFloat(), Paint().apply { color = 0xFF0A0D12.toInt() })
    canvas.drawRect(0f, top, width.toFloat(), top + 6f * unit, Paint().apply { color = 0xFF0A84FF.toInt() })

    val pad = 40f * unit
    val headlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 58f * unit
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCBD5E1.toInt()
        textSize = 32f * unit
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }
    val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7DB8FF.toInt()
        textSize = 26f * unit
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    canvas.drawText(ellipsize(headline, headlinePaint, width - pad * 2), pad, top + 78f * unit, headlinePaint)
    canvas.drawText(ellipsize(detail, detailPaint, width - pad * 2), pad, top + 128f * unit, detailPaint)
    canvas.drawText(ellipsize(footer, footerPaint, width - pad * 2), pad, top + 172f * unit, footerPaint)
    return out
}

/** Small rounded tag used for reticle state labels ("LEVEL", "PLUMB", "SNAP"). */
internal fun AndroidCanvas.drawTag(cx: Float, cy: Float, text: String, background: Int) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111827.toInt()
        textSize = 26f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        letterSpacing = 0.08f
    }
    val w = paint.measureText(text) + 28f
    val h = 40f
    val rect = RectF(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    drawRoundRect(rect, h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
    val fm = paint.fontMetrics
    drawText(text, cx - (w - 28f) / 2f, cy - (fm.ascent + fm.descent) / 2f, paint)
}

private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    var end = text.length
    while (end > 1 && paint.measureText(text, 0, end) + paint.measureText("…") > maxWidth) end--
    return text.substring(0, end) + "…"
}
