package com.example.ui.measure

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.example.domain.NormPoint
import com.example.domain.ShapeMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Live 3D Isometric Architectural Mini-Model floating above the bottom camera dock,
 * dynamically reflecting the measured shape's real aspect proportions (3D cutaway room,
 * extruded box, cylinder, or polygon prism).
 */
@Composable
fun IsometricRoomPreview(
    shapeMode: ShapeMode,
    points: List<NormPoint>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w * 0.5f
        val baseY = h * 0.80f

        // Compute normalized width/depth ratio from user's live points
        val (ratioW, ratioD, ratioH) = computeProportions(shapeMode, points)
        val scale = size.minDimension * 0.44f

        // Isometric projection helper (x: right-down, y: left-down, z: up)
        val isoCos = 0.866f
        val isoSin = 0.46f
        fun iso(x: Float, y: Float, z: Float): Offset {
            val sx = cx + (x - y) * isoCos * scale
            val sy = baseY - (x + y) * isoSin * scale - z * scale * 0.85f
            return Offset(sx, sy)
        }

        // Soft ambient drop shadow beneath the 3D miniature model
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x55000000), Color.Transparent),
                center = Offset(cx, baseY + 6f),
                radius = w * 0.44f
            ),
            topLeft = Offset(cx - w * 0.44f, baseY - h * 0.12f),
            size = androidx.compose.ui.geometry.Size(w * 0.88f, h * 0.28f)
        )

        when (shapeMode) {
            ShapeMode.CIRCLE -> drawIsometricCylinder(cx, baseY, scale * 0.72f, ratioH)
            else -> drawIsometricCutawayRoom(
                iso = ::iso,
                rx = ratioW,
                ry = ratioD,
                rz = ratioH,
                isRoom = shapeMode == ShapeMode.SNAP_3D
            )
        }
    }
}

private fun computeProportions(mode: ShapeMode, pts: List<NormPoint>): Triple<Float, Float, Float> {
    return when (mode) {
        ShapeMode.SNAP_3D -> {
            if (pts.size >= 6) {
                val w = abs(pts[1].x - pts[0].x).coerceIn(0.25f, 0.75f)
                val h = abs(pts[3].y - pts[0].y).coerceIn(0.22f, 0.55f)
                val d = hypot(pts[4].x - pts[1].x, pts[4].y - pts[1].y).coerceIn(0.16f, 0.55f)
                Triple((w * 1.45f).coerceIn(0.55f, 0.95f), (d * 2.4f).coerceIn(0.50f, 0.95f), (h * 1.15f).coerceIn(0.28f, 0.55f))
            } else Triple(0.82f, 0.82f, 0.40f)
        }
        ShapeMode.RECTANGLE -> {
            if (pts.size >= 4) {
                val w = abs(pts[1].x - pts[0].x).coerceIn(0.2f, 0.8f)
                val h = abs(pts[2].y - pts[1].y).coerceIn(0.2f, 0.7f)
                Triple((w * 1.35f).coerceIn(0.45f, 0.92f), (h * 1.25f).coerceIn(0.45f, 0.90f), 0.34f)
            } else Triple(0.80f, 0.72f, 0.34f)
        }
        else -> Triple(0.76f, 0.76f, 0.36f)
    }
}

private fun DrawScope.drawIsometricCutawayRoom(
    iso: (Float, Float, Float) -> Offset,
    rx: Float,
    ry: Float,
    rz: Float,
    isRoom: Boolean
) {
    // Ground floor quad: (0,0) front corner, (rx,0) right, (rx,ry) back, (0,ry) left
    val f0 = iso(0f, 0f, 0f)
    val f1 = iso(rx, 0f, 0f)
    val f2 = iso(rx, ry, 0f)
    val f3 = iso(0f, ry, 0f)

    // Wall top corners
    val t0 = iso(0f, 0f, rz)
    val t1 = iso(rx, 0f, rz)
    val t2 = iso(rx, ry, rz)
    val t3 = iso(0f, ry, rz)

    val wallLight = Color(0xF5FFFFFF)
    val wallMid = Color(0xEBE8ECF2)
    val wallShade = Color(0xE0D4DCE8)
    val floorColor = Color(0xF0F1F4F8)
    val edgeLine = Color(0x6694A3B8)

    // Back-left wall (f3 -> f2 -> t2 -> t3)
    drawQuad(f3, f2, t2, t3, wallMid, edgeLine)
    // Back-right wall (f2 -> f1 -> t1 -> t2)
    drawQuad(f2, f1, t1, t2, wallShade, edgeLine)
    // Floor surface
    drawQuad(f0, f1, f2, f3, floorColor, edgeLine)

    if (isRoom) {
        // Subtle architectural interior room details (table + partitions) matching clean white diorama
        val tblZ = rz * 0.32f
        val pA = iso(rx * 0.34f, ry * 0.34f, tblZ)
        val pB = iso(rx * 0.66f, ry * 0.34f, tblZ)
        val pC = iso(rx * 0.66f, ry * 0.66f, tblZ)
        val pD = iso(rx * 0.34f, ry * 0.66f, tblZ)
        drawQuad(pA, pB, pC, pD, Color(0xF8FFFFFF), Color(0x5564748B))

        // Inner partition divider
        val div0 = iso(rx * 0.56f, ry * 0.68f, 0f)
        val div1 = iso(rx * 0.56f, ry * 0.96f, 0f)
        val div2 = iso(rx * 0.56f, ry * 0.96f, rz * 0.88f)
        val div3 = iso(rx * 0.56f, ry * 0.68f, rz * 0.88f)
        drawQuad(div0, div1, div2, div3, wallLight, edgeLine)
    }

    // Translucent front-left & front-right cutaway walls with window openings
    val frontLeftHeight = rz * 0.78f
    val fl0 = iso(0f, 0f, frontLeftHeight)
    val fl3 = iso(0f, ry, frontLeftHeight)
    drawQuad(f0, f3, fl3, fl0, Color(0xCCFFFFFF), Color(0x99FFFFFF))

    val fr1 = iso(rx, 0f, frontLeftHeight)
    drawQuad(f0, f1, fr1, fl0, Color(0xB8DCE3EE), Color(0x99FFFFFF))

    // Window cut-outs on front walls
    val winColor = Color(0x660A84FF)
    drawQuad(
        iso(0f, ry * 0.22f, rz * 0.24f),
        iso(0f, ry * 0.48f, rz * 0.24f),
        iso(0f, ry * 0.48f, rz * 0.62f),
        iso(0f, ry * 0.22f, rz * 0.62f),
        winColor,
        Color(0x99FFFFFF)
    )
    drawQuad(
        iso(rx * 0.26f, 0f, rz * 0.24f),
        iso(rx * 0.58f, 0f, rz * 0.24f),
        iso(rx * 0.58f, 0f, rz * 0.62f),
        iso(rx * 0.26f, 0f, rz * 0.62f),
        winColor,
        Color(0x99FFFFFF)
    )

    // Crisp white top wall rims
    drawLine(Color.White, t3, t2, strokeWidth = 2.2f)
    drawLine(Color.White, t2, t1, strokeWidth = 2.2f)
    drawLine(Color.White, fl3, fl0, strokeWidth = 2f)
    drawLine(Color.White, fl0, fr1, strokeWidth = 2f)
}

private fun DrawScope.drawIsometricCylinder(cx: Float, baseY: Float, radius: Float, heightRatio: Float) {
    val rx = radius * 0.95f
    val ry = radius * 0.46f
    val h = radius * (heightRatio * 1.9f).coerceIn(0.6f, 1.3f)
    val topY = baseY - h

    // Cylinder body
    val bodyPath = Path().apply {
        moveTo(cx - rx, topY)
        lineTo(cx - rx, baseY)
        lineTo(cx + rx, baseY)
        lineTo(cx + rx, topY)
        close()
    }
    drawPath(
        bodyPath,
        brush = Brush.horizontalGradient(
            listOf(Color(0xE8DCE4F0), Color(0xF6FFFFFF), Color(0xD8C5D0E0)),
            startX = cx - rx,
            endX = cx + rx
        )
    )
    // Bottom ellipse
    drawOval(
        color = Color(0xE0CFD8E6),
        topLeft = Offset(cx - rx, baseY - ry),
        size = androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f)
    )
    // Top ellipse
    drawOval(
        color = Color(0xFAFFFFFF),
        topLeft = Offset(cx - rx, topY - ry),
        size = androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f)
    )
    drawOval(
        color = Color(0xAA0A84FF),
        topLeft = Offset(cx - rx, topY - ry),
        size = androidx.compose.ui.geometry.Size(rx * 2f, ry * 2f),
        style = Stroke(2f)
    )
    // Diameter axis indicator on top face
    val angle = PI / 6.0
    val dx = (cos(angle) * rx * 0.85).toFloat()
    val dy = (sin(angle) * ry * 0.85).toFloat()
    drawLine(Color(0xFF0A84FF), Offset(cx - dx, topY - dy), Offset(cx + dx, topY + dy), 2.5f)
}

private fun DrawScope.drawQuad(
    a: Offset,
    b: Offset,
    c: Offset,
    d: Offset,
    fill: Color,
    stroke: Color
) {
    val path = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(c.x, c.y)
        lineTo(d.x, d.y)
        close()
    }
    drawPath(path, fill)
    drawPath(path, stroke, style = Stroke(1.2f, join = StrokeJoin.Round))
}
