package online.thenightwatcher.agentpet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plays the same transparent-gutter spritesheets used by the desktop app.
 * A row is an animation clip; rows 0...6 map to idle, working, waiting, done,
 * celebrate, sleepy and level-up respectively (with a safe clamp for smaller packs).
 */
class PetSpriteView(context: Context) : View(context) {
    private var clips: List<List<Bitmap>> = emptyList()
    private var mood = "idle"
    private var clipBindings: Map<String, Int> = emptyMap()
    private var animationsEnabled = true
    private var frameDelayMs = 220L
    private var frame = 0
    private val loadGeneration = AtomicInteger()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            val current = currentClip()
            if (animationsEnabled && current.size > 1) { frame = (frame + 1) % current.size; invalidate() }
            main.postDelayed(this, frameDelayMs)
        }
    }

    init { main.post(ticker) }

    fun setMood(value: String) {
        if (mood != value) { mood = value; frame = 0; invalidate() }
    }
    fun setClipBindings(value: Map<String, Int>) { clipBindings = value; frame = 0; invalidate() }
    fun configureAnimation(enabled: Boolean, fps: Int) { animationsEnabled = enabled; frameDelayMs = (1000L / fps.coerceIn(1, 12)); if (!enabled) frame = 0; invalidate() }

    fun load(url: String, onResult: (Boolean) -> Unit) {
        val generation = loadGeneration.incrementAndGet()
        loader.execute {
            val result = runCatching { slice(download(url)) }.getOrDefault(emptyList())
            main.post {
                if (generation != loadGeneration.get()) return@post
                clips = result
                frame = 0
                invalidate()
                onResult(result.isNotEmpty())
            }
        }
    }

    private fun currentClip(): List<Bitmap> {
        if (clips.isEmpty()) return emptyList()
        val defaultRow = when (mood) {
            "working" -> 1; "waiting" -> 2; "done" -> 3; "celebrate" -> 4; "sleepy" -> 5; else -> 0
        }
        val row = clipBindings[mood] ?: defaultRow
        return clips[row.coerceAtMost(clips.lastIndex)]
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frames = currentClip()
        if (frames.isEmpty()) return
        val bitmap = frames[frame % frames.size]
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val w = bitmap.width * scale; val h = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, android.graphics.RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2), paint)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); main.removeCallbacks(ticker); main.post(ticker) }
    override fun onDetachedFromWindow() { main.removeCallbacks(ticker); super.onDetachedFromWindow() }

    private fun download(value: String): Bitmap {
        val connection = (URL(value).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000; readTimeout = 20_000; setRequestProperty("Referer", "https://petdex.crafter.run/")
        }
        connection.inputStream.use { return BitmapFactory.decodeStream(it) ?: error("Invalid sprite sheet") }
    }

    private fun slice(sheet: Bitmap): List<List<Bitmap>> {
        fun segments(filled: BooleanArray): List<IntRange> {
            val result = mutableListOf<IntRange>(); var start = -1
            for (i in filled.indices) {
                if (filled[i] && start < 0) start = i
                if (!filled[i] && start >= 0) { result += start until i; start = -1 }
            }
            if (start >= 0) result += start until filled.size
            return result
        }
        val rows = BooleanArray(sheet.height) { y -> (0 until sheet.width).any { x -> (sheet.getPixel(x, y) ushr 24) > 16 } }
        return segments(rows).mapNotNull { row ->
            val columns = BooleanArray(sheet.width) { x -> (row).any { y -> (sheet.getPixel(x, y) ushr 24) > 16 } }
            segments(columns).map { col -> Bitmap.createBitmap(sheet, col.first, row.first, col.last - col.first + 1, row.last - row.first + 1) }.ifEmpty { null }
        }
    }

    private companion object { val loader = Executors.newSingleThreadExecutor() }
}

data class RemotePet(val name: String, val slug: String, val spritesheetUrl: String)

object PetCatalog {
    const val MANIFEST = "https://pets.thenightwatcher.online/manifest.json"
    fun load(onResult: (List<RemotePet>) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            val pets = runCatching {
                val connection = URL(MANIFEST).openConnection() as HttpURLConnection
                connection.inputStream.bufferedReader().use { reader ->
                    val rows = JSONObject(reader.readText()).optJSONArray("pets")
                    (0 until (rows?.length() ?: 0)).mapNotNull { i -> rows?.optJSONObject(i)?.let {
                        val sheet = it.optString("spritesheetUrl"); val slug = it.optString("slug")
                        if (sheet.isBlank()) null else RemotePet(it.optString("displayName", slug), slug, sheet)
                    } }
                }
            }.getOrDefault(emptyList())
            Handler(Looper.getMainLooper()).post { onResult(pets) }
        }
    }
}
