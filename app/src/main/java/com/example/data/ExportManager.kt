package com.example.data

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.domain.GeometryEngine
import com.example.domain.ShapeMode
import com.example.domain.UnitConverterEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot

object ExportManager {

    private val dateFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private fun ensureExportDir(context: Context): File {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun buildCsvString(measurements: List<MeasurementEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("ID,Title,Category,Shape,PrimaryValue,Unit,Details,Calibration,Notes,Pinned,Timestamp")
        for (m in measurements) {
            val cols = listOf(
                m.id.toString(),
                csvEscape(m.title),
                csvEscape(m.category),
                csvEscape(m.shapeMode),
                UnitConverterEngine.formatValue(m.primaryValue, 4),
                csvEscape(m.primaryUnit),
                csvEscape(m.secondarySummary),
                csvEscape(m.calibrationRef),
                csvEscape(m.notes),
                m.pinned.toString(),
                csvEscape(dateFormatter.format(Date(m.createdAtMillis)))
            )
            sb.appendLine(cols.joinToString(","))
        }
        return sb.toString()
    }

    private fun csvEscape(raw: String): String {
        val needsQuotes = raw.contains(',') || raw.contains('"') || raw.contains('\n') || raw.contains('\r')
        val escaped = raw.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    fun buildBackupJson(measurements: List<MeasurementEntity>): String {
        val root = JSONObject()
        root.put("app", "Calibra")
        root.put("schemaVersion", 1)
        root.put("exportedAt", System.currentTimeMillis())
        val array = JSONArray()
        for (m in measurements) {
            val obj = JSONObject()
            obj.put("title", m.title)
            obj.put("category", m.category)
            obj.put("shapeMode", m.shapeMode)
            obj.put("primaryValue", m.primaryValue)
            obj.put("primaryUnit", m.primaryUnit)
            obj.put("secondarySummary", m.secondarySummary)
            obj.put("notes", m.notes)
            obj.put("calibrationRef", m.calibrationRef)
            obj.put("pointsJson", m.pointsJson)
            obj.put("createdAtMillis", m.createdAtMillis)
            obj.put("pinned", m.pinned)
            array.put(obj)
        }
        root.put("measurements", array)
        return root.toString(2)
    }

    fun parseBackupJson(jsonText: String): List<MeasurementEntity> {
        val root = JSONObject(jsonText)
        val arr = root.optJSONArray("measurements") ?: return emptyList()
        val list = ArrayList<MeasurementEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                MeasurementEntity(
                    title = obj.optString("title", "Imported Measurement"),
                    category = obj.optString("category", "Length"),
                    shapeMode = obj.optString("shapeMode", ShapeMode.LINE.name),
                    primaryValue = obj.optDouble("primaryValue", 0.0),
                    primaryUnit = obj.optString("primaryUnit", "cm"),
                    secondarySummary = obj.optString("secondarySummary", ""),
                    notes = obj.optString("notes", ""),
                    calibrationRef = obj.optString("calibrationRef", "Imported"),
                    pointsJson = obj.optString("pointsJson", ""),
                    createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis()),
                    pinned = obj.optBoolean("pinned", false)
                )
            )
        }
        return list
    }

    suspend fun writeCsvToUri(
        context: Context,
        uri: Uri,
        measurements: List<MeasurementEntity>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val csv = buildCsvString(measurements)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open output destination")
        }
    }

    suspend fun writeBackupJsonToUri(
        context: Context,
        uri: Uri,
        measurements: List<MeasurementEntity>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = buildBackupJson(measurements)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
            } ?: error("Unable to open output destination")
        }
    }

    suspend fun readBackupJsonFromUri(
        context: Context,
        uri: Uri
    ): Result<List<MeasurementEntity>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use {
                it.readText()
            } ?: error("Unable to read backup file")
            parseBackupJson(text)
        }
    }

    suspend fun writePdfToUri(
        context: Context,
        uri: Uri,
        measurements: List<MeasurementEntity>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                renderPdfReport(out, measurements)
            } ?: error("Unable to open PDF destination")
        }
    }

    suspend fun createShareablePdf(
        context: Context,
        measurements: List<MeasurementEntity>
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(ensureExportDir(context), "calibra_report_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { out ->
                renderPdfReport(out, measurements)
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    suspend fun createShareableCsv(
        context: Context,
        measurements: List<MeasurementEntity>
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val file = File(ensureExportDir(context), "calibra_log_${System.currentTimeMillis()}.csv")
            file.writeText(buildCsvString(measurements), Charsets.UTF_8)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
    }

    fun launchShareIntent(context: Context, uri: Uri, mimeType: String, subject: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, subject))
    }

    /**
     * Generates a crisp multi-page A4 engineering PDF report using Android's native PdfDocument,
     * including a dark-navy header, summary KPI bar, and vector blueprint thumbnails for each shape.
     */
    private fun renderPdfReport(output: OutputStream, measurements: List<MeasurementEntity>) {
        val document = PdfDocument()
        val pageWidth = 595 // A4 at 72 dpi
        val pageHeight = 842
        val margin = 36f

        val headerBgPaint = Paint().apply { color = AndroidColor.parseColor("#0D1B2A") }
        val accentPaint = Paint().apply { color = AndroidColor.parseColor("#1B4DFF") }
        val cardBgPaint = Paint().apply { color = AndroidColor.parseColor("#F4F6FA") }
        val cardStrokePaint = Paint().apply {
            color = AndroidColor.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#93C5FD")
            textSize = 10.5f
        }
        val itemTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#0F172A")
            textSize = 12.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#1B4DFF")
            textSize = 14f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#334155")
            textSize = 9.5f
        }
        val mutedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#64748B")
            textSize = 8.5f
        }
        val blueprintBgPaint = Paint().apply { color = AndroidColor.parseColor("#0B132B") }
        val blueprintLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#38BDF8")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val blueprintNodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.parseColor("#00E676")
            style = Paint.Style.FILL
        }

        val itemsPerPage = 7
        val totalPages = ((measurements.size - 1).coerceAtLeast(0) / itemsPerPage) + 1

        for (pageIndex in 0 until totalPages) {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex + 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            // Header bar
            canvas.drawRect(0f, 0f, pageWidth.toFloat(), 86f, headerBgPaint)
            canvas.drawRect(0f, 82f, pageWidth.toFloat(), 86f, accentPaint)
            canvas.drawText("CALIBRA • MEASUREMENT REPORT", margin, 38f, titlePaint)
            canvas.drawText(
                "Generated ${dateFormatter.format(Date())}   |   Total Records: ${measurements.size}   |   Page ${pageIndex + 1}/$totalPages",
                margin,
                60f,
                subTitlePaint
            )

            var y = 108f
            val startIdx = pageIndex * itemsPerPage
            val endIdx = minOf(startIdx + itemsPerPage, measurements.size)

            if (measurements.isEmpty()) {
                canvas.drawText("No recorded measurements available in the log.", margin, y + 20f, itemTitlePaint)
            } else {
                for (i in startIdx until endIdx) {
                    val m = measurements[i]
                    val cardHeight = 92f
                    val cardRect = RectF(margin, y, pageWidth - margin, y + cardHeight)
                    canvas.drawRoundRect(cardRect, 8f, 8f, cardBgPaint)
                    canvas.drawRoundRect(cardRect, 8f, 8f, cardStrokePaint)

                    // Blueprint mini-thumbnail on left
                    val thumbRect = RectF(margin + 10f, y + 10f, margin + 82f, y + 82f)
                    canvas.drawRoundRect(thumbRect, 6f, 6f, blueprintBgPaint)
                    drawMiniShape(canvas, thumbRect, m.shapeMode, m.pointsJson, blueprintLinePaint, blueprintNodePaint)

                    // Details
                    val textX = margin + 96f
                    canvas.drawText(m.title.take(42), textX, y + 24f, itemTitlePaint)
                    val valueStr = "${UnitConverterEngine.formatValue(m.primaryValue, 2)} ${m.primaryUnit}"
                    val valueWidth = valuePaint.measureText(valueStr)
                    canvas.drawText(valueStr, pageWidth - margin - 12f - valueWidth, y + 24f, valuePaint)

                    canvas.drawText(
                        "${m.category} • ${m.shapeMode} • Cal: ${m.calibrationRef}",
                        textX,
                        y + 42f,
                        mutedPaint
                    )
                    if (m.secondarySummary.isNotBlank()) {
                        canvas.drawText(m.secondarySummary.take(72), textX, y + 58f, bodyPaint)
                    }
                    val noteLine = buildString {
                        if (m.notes.isNotBlank()) append("Note: ${m.notes.take(44)}  •  ")
                        append(dateFormatter.format(Date(m.createdAtMillis)))
                    }
                    canvas.drawText(noteLine, textX, y + 76f, mutedPaint)

                    y += cardHeight + 10f
                }
            }

            // Footer
            canvas.drawText(
                "Calibra Offline Engineering Archive — Verify physical tolerances for critical construction.",
                margin,
                pageHeight - 20f,
                mutedPaint
            )

            document.finishPage(page)
        }

        document.writeTo(output)
        document.close()
    }

    private fun drawMiniShape(
        canvas: android.graphics.Canvas,
        rect: RectF,
        shapeModeName: String,
        pointsJson: String,
        linePaint: Paint,
        nodePaint: Paint
    ) {
        val mode = ShapeMode.entries.firstOrNull { it.name == shapeModeName } ?: ShapeMode.LINE
        val pts = GeometryEngine.deserializePoints(pointsJson).ifEmpty {
            GeometryEngine.defaultPointsFor(mode)
        }
        val pad = 10f
        val innerW = rect.width() - pad * 2
        val innerH = rect.height() - pad * 2
        fun px(x: Float) = rect.left + pad + x * innerW
        fun py(y: Float) = rect.top + pad + y * innerH

        when (mode) {
            ShapeMode.SNAP_3D -> {
                if (pts.size >= 6) {
                    val p1 = Path().apply {
                        moveTo(px(pts[0].x), py(pts[0].y))
                        lineTo(px(pts[1].x), py(pts[1].y))
                        lineTo(px(pts[2].x), py(pts[2].y))
                        lineTo(px(pts[3].x), py(pts[3].y))
                        close()
                    }
                    val p2 = Path().apply {
                        moveTo(px(pts[1].x), py(pts[1].y))
                        lineTo(px(pts[4].x), py(pts[4].y))
                        lineTo(px(pts[5].x), py(pts[5].y))
                        lineTo(px(pts[2].x), py(pts[2].y))
                        close()
                    }
                    canvas.drawPath(p1, linePaint)
                    canvas.drawPath(p2, linePaint)
                    pts.forEach { canvas.drawCircle(px(it.x), py(it.y), 2.2f, nodePaint) }
                }
            }
            ShapeMode.CIRCLE -> {
                if (pts.size >= 2) {
                    val cx = px(pts[0].x)
                    val cy = py(pts[0].y)
                    val rx = px(pts[1].x)
                    val ry = py(pts[1].y)
                    val r = hypot(rx - cx, ry - cy).coerceIn(6f, innerW * 0.45f)
                    canvas.drawCircle(cx, cy, r, linePaint)
                    canvas.drawCircle(cx, cy, 2.5f, nodePaint)
                }
            }
            ShapeMode.LINE, ShapeMode.ANGLE -> {
                val path = Path()
                pts.forEachIndexed { idx, p ->
                    if (idx == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y))
                }
                canvas.drawPath(path, linePaint)
                pts.forEach { canvas.drawCircle(px(it.x), py(it.y), 2.5f, nodePaint) }
            }
            ShapeMode.RECTANGLE, ShapeMode.POLYGON -> {
                if (pts.size >= 3) {
                    val path = Path()
                    pts.forEachIndexed { idx, p ->
                        if (idx == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y))
                    }
                    path.close()
                    canvas.drawPath(path, linePaint)
                    pts.forEach { canvas.drawCircle(px(it.x), py(it.y), 2.2f, nodePaint) }
                }
            }
        }
    }
}
