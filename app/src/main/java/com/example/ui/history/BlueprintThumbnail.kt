package com.example.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.domain.GeometryEngine
import com.example.domain.ShapeMode
import com.example.ui.theme.LocalCalibraTokens
import kotlin.math.hypot

@Composable
fun BlueprintThumbnail(
    shapeModeName: String,
    pointsJson: String,
    modifier: Modifier = Modifier
) {
    val tokens = LocalCalibraTokens.current
    val mode = ShapeMode.entries.firstOrNull { it.name == shapeModeName }

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0B132B))
    ) {
        // Subtle technical grid
        val step = size.width / 5f
        val gridColor = Color(0x2838BDF8)
        for (i in 1..4) {
            drawLine(gridColor, Offset(step * i, 0f), Offset(step * i, size.height), 1f)
            drawLine(gridColor, Offset(0f, step * i), Offset(size.width, step * i), 1f)
        }

        if (mode == null) {
            // Conversion glyph (double arrows)
            val c = tokens.laserPrimary
            val y1 = size.height * 0.38f
            val y2 = size.height * 0.62f
            drawLine(c, Offset(size.width * 0.22f, y1), Offset(size.width * 0.78f, y1), 3f, StrokeCap.Round)
            drawLine(c, Offset(size.width * 0.65f, y1 - 8f), Offset(size.width * 0.78f, y1), 3f, StrokeCap.Round)
            drawLine(c, Offset(size.width * 0.65f, y1 + 8f), Offset(size.width * 0.78f, y1), 3f, StrokeCap.Round)

            drawLine(tokens.laserSecondary, Offset(size.width * 0.78f, y2), Offset(size.width * 0.22f, y2), 3f, StrokeCap.Round)
            drawLine(tokens.laserSecondary, Offset(size.width * 0.35f, y2 - 8f), Offset(size.width * 0.22f, y2), 3f, StrokeCap.Round)
            drawLine(tokens.laserSecondary, Offset(size.width * 0.35f, y2 + 8f), Offset(size.width * 0.22f, y2), 3f, StrokeCap.Round)
            return@Canvas
        }

        val pts = GeometryEngine.deserializePoints(pointsJson).ifEmpty {
            GeometryEngine.defaultPointsFor(mode)
        }
        val pad = size.minDimension * 0.14f
        val innerW = size.width - pad * 2f
        val innerH = size.height - pad * 2f
        val px = pts.map { Offset(pad + it.x * innerW, pad + it.y * innerH) }

        when (mode) {
            ShapeMode.SNAP_3D -> {
                if (px.size >= 6) {
                    val backWall = Path().apply {
                        moveTo(px[0].x, px[0].y)
                        lineTo(px[1].x, px[1].y)
                        lineTo(px[2].x, px[2].y)
                        lineTo(px[3].x, px[3].y)
                        close()
                    }
                    val rightWall = Path().apply {
                        moveTo(px[1].x, px[1].y)
                        lineTo(px[4].x, px[4].y)
                        lineTo(px[5].x, px[5].y)
                        lineTo(px[2].x, px[2].y)
                        close()
                    }
                    drawPath(backWall, Color.White.copy(alpha = 0.14f))
                    drawPath(rightWall, tokens.arNodeBlue.copy(alpha = 0.22f))
                    drawPath(backWall, Color.White, style = Stroke(2.5f, join = StrokeJoin.Round))
                    drawPath(rightWall, Color.White, style = Stroke(2.5f, join = StrokeJoin.Round))
                    px.forEach {
                        drawCircle(Color.White, 4.5f, it)
                        drawCircle(tokens.arNodeBlue, 3f, it)
                    }
                }
            }
            ShapeMode.CIRCLE -> {
                if (px.size >= 2) {
                    val r = hypot(px[1].x - px[0].x, px[1].y - px[0].y).coerceIn(8f, innerW * 0.46f)
                    drawCircle(Color.White.copy(alpha = 0.14f), r, px[0])
                    drawCircle(Color.White, r, px[0], style = Stroke(2.8f))
                    drawCircle(Color.White, 5f, px[0])
                    drawCircle(tokens.arNodeBlue, 3.3f, px[0])
                }
            }
            ShapeMode.LINE, ShapeMode.ANGLE -> {
                val path = Path()
                px.forEachIndexed { idx, p ->
                    if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, Color.White, style = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                px.forEach {
                    drawCircle(Color.White, 5f, it)
                    drawCircle(tokens.arNodeBlue, 3.3f, it)
                }
            }
            ShapeMode.RECTANGLE, ShapeMode.POLYGON -> {
                if (px.size >= 3) {
                    val path = Path()
                    px.forEachIndexed { idx, p ->
                        if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                    }
                    path.close()
                    drawPath(path, Color.White.copy(alpha = 0.16f))
                    drawPath(path, Color.White, style = Stroke(2.8f, join = StrokeJoin.Round))
                    px.forEach {
                        drawCircle(Color.White, 4.5f, it)
                        drawCircle(tokens.arNodeBlue, 3f, it)
                    }
                }
            }
        }
    }
}
