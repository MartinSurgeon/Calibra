package com.example.ar

import android.opengl.Matrix
import androidx.compose.ui.geometry.Offset
import com.example.domain.Vec3

/**
 * Projects world-space points into view pixels using the current ARCore camera matrices.
 * Segments crossing behind the camera are clipped against the near plane so lines never
 * "wrap around" the screen. Not thread-safe: create one per frame on the GL thread.
 */
internal class ArProjector(
    private val viewMatrix: FloatArray,
    private val projectionMatrix: FloatArray,
    val width: Int,
    val height: Int
) {
    private val world = FloatArray(4)
    private val clip = FloatArray(4)

    private fun toView(p: Vec3, out: FloatArray) {
        world[0] = p.x.toFloat()
        world[1] = p.y.toFloat()
        world[2] = p.z.toFloat()
        world[3] = 1f
        Matrix.multiplyMV(out, 0, viewMatrix, 0, world, 0)
    }

    private fun viewToScreen(view: FloatArray): Offset? {
        Matrix.multiplyMV(clip, 0, projectionMatrix, 0, view, 0)
        val w = clip[3]
        if (w <= 1e-5f) return null
        val ndcX = clip[0] / w
        val ndcY = clip[1] / w
        return Offset((ndcX + 1f) * 0.5f * width, (1f - ndcY) * 0.5f * height)
    }

    /** Screen position of [p], or null if it is behind the camera. */
    fun project(p: Vec3): Offset? {
        val v = FloatArray(4)
        toView(p, v)
        if (v[2] > -NEAR_CLIP) return null
        return viewToScreen(v)
    }

    /** True if [p] projects inside the viewport (with [marginPx] slack). */
    fun isOnScreen(p: Offset, marginPx: Float = 0f): Boolean =
        p.x >= -marginPx && p.y >= -marginPx && p.x <= width + marginPx && p.y <= height + marginPx

    /** Screen segment for a world segment, clipped to the near plane; null if fully behind. */
    fun projectSegment(a: Vec3, b: Vec3): Pair<Offset, Offset>? {
        val va = FloatArray(4)
        val vb = FloatArray(4)
        toView(a, va)
        toView(b, vb)
        val aInFront = va[2] <= -NEAR_CLIP
        val bInFront = vb[2] <= -NEAR_CLIP
        if (!aInFront && !bInFront) return null
        if (!aInFront) clipToNear(va, vb)
        if (!bInFront) clipToNear(vb, va)
        val sa = viewToScreen(va) ?: return null
        val sb = viewToScreen(vb) ?: return null
        return sa to sb
    }

    private fun clipToNear(outside: FloatArray, inside: FloatArray) {
        val t = (-NEAR_CLIP - inside[2]) / (outside[2] - inside[2])
        for (i in 0..2) outside[i] = inside[i] + (outside[i] - inside[i]) * t
        outside[3] = 1f
    }

    companion object {
        const val NEAR_CLIP = 0.05f
        const val FAR_CLIP = 100f
    }
}
