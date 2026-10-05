package online.thenightwatcher.agentpet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser
import java.util.concurrent.ConcurrentHashMap

/** Rasterizes the same checked-in agent SVG marks for compact Android bubbles. */
object AgentLogos {
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val pathTag = Regex("(?is)<path\\b([^>]*)>")
    private val attr = Regex("([\\w-]+)\\s*=\\s*['\"]([^'\"]*)['\"]")

    fun bitmap(context: Context, rawKind: String, requestedSize: Int): Bitmap? {
        val kind = when (rawKind.lowercase()) { "claude" -> "claude-code"; "kirocli" -> "kiro"; else -> rawKind.lowercase() }
        val size = requestedSize.coerceIn(20, 96)
        val key = "$kind:$size"
        return cache[key] ?: runCatching { render(context, kind, size) }.getOrNull()?.also { cache[key] = it }
    }

    private fun render(context: Context, kind: String, size: Int): Bitmap {
        val svg = runCatching { context.assets.open("agent-icons/$kind.svg").bufferedReader().use { it.readText() } }.getOrNull()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        if (svg == null) {
            drawFallback(canvas, paint, kind, size)
            return bitmap
        }
        val viewBox = Regex("viewBox\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE).find(svg)?.groupValues?.get(1)
            ?.split(Regex("\\s+"))?.mapNotNull(String::toFloatOrNull)
        val viewWidth = viewBox?.getOrNull(2) ?: 24f
        val viewHeight = viewBox?.getOrNull(3) ?: 24f
        val scale = (size - 4f) / maxOf(viewWidth, viewHeight)
        canvas.save()
        canvas.translate((size - viewWidth * scale) / 2f, (size - viewHeight * scale) / 2f)
        canvas.scale(scale, scale)
        var rendered = false
        for (match in pathTag.findAll(svg)) {
            val attributes = attr.findAll(match.groupValues[1]).associate { it.groupValues[1] to it.groupValues[2] }
            val data = attributes["d"] ?: continue
            val path = PathParser.createPathFromPathData(data) ?: continue
            val fill = attributes["fill"]
            val stroke = attributes["stroke"]
            if (fill != "none") {
                paint.style = Paint.Style.FILL
                paint.color = parseColor(fill) ?: Color.WHITE
                canvas.drawPath(path, paint)
                rendered = true
            } else if (stroke != null) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = attributes["stroke-width"]?.toFloatOrNull() ?: 1.8f
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = parseColor(stroke) ?: Color.WHITE
                canvas.drawPath(path, paint)
                rendered = true
            }
        }
        canvas.restore()
        if (!rendered) drawFallback(canvas, paint, kind, size)
        return bitmap
    }

    private fun drawFallback(canvas: Canvas, paint: Paint, kind: String, size: Int) {
        val colors = mapOf("codex" to 0xFF10A37F.toInt(), "claude-code" to 0xFFCC785C.toInt(), "gemini" to 0xFF4285F4.toInt(), "cursor" to 0xFF1A65E0.toInt(), "copilot" to 0xFF6E40C9.toInt(), "windsurf" to 0xFF06B6D4.toInt())
        paint.style = Paint.Style.FILL
        paint.color = colors[kind] ?: 0xFF718096.toInt()
        canvas.drawCircle(size / 2f, size / 2f, size * 0.46f, paint)
        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = size * 0.56f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        val label = kind.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
        val y = size / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(label, size / 2f, y, paint)
    }

    private fun parseColor(value: String?): Int? = value?.takeIf { it.startsWith("#") }?.let { runCatching { Color.parseColor(it) }.getOrNull() }
}
