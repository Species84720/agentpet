package online.thenightwatcher.agentpet

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/** Messenger-style, user-draggable overlay. It contains no credentials in its UI. */
class PetOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: FrameLayout? = null
    private var sprite: PetSpriteView? = null
    private var bubble: TextView? = null
    private var client: RelayClient? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnect = Runnable { connect() }
    private var x = 0; private var y = 180
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching {
            startForeground(7, notification())
            check(Settings.canDrawOverlays(this)) { "Display over other apps is not enabled." }
            if (overlay == null) showPet()
            getSharedPreferences("overlay", MODE_PRIVATE).edit().remove("last_error").apply()
        }.onFailure { error ->
            getSharedPreferences("overlay", MODE_PRIVATE).edit()
                .putString("last_error", "${error.javaClass.simpleName}: ${error.message ?: "Android rejected the overlay"}").apply()
            stopSelf()
            return START_NOT_STICKY
        }
        connect()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { reconnectHandler.removeCallbacksAndMessages(null); client?.close(); overlay?.let { windowManager.removeView(it) }; overlay = null; super.onDestroy() }
    private fun notification(): Notification {
        val channel = NotificationChannel("agentpet", "AgentPet companion", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, "agentpet").setSmallIcon(android.R.drawable.presence_online).setContentTitle("AgentPet is watching").setContentText("Tap the app to configure your floating companion.").build()
    }
    private fun showPet() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val saved = getSharedPreferences("overlay", MODE_PRIVATE); x = saved.getInt("x", x); y = saved.getInt("y", y)
        val params = WindowManager.LayoutParams(172, 206, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        overlay = FrameLayout(this).apply {
            bubble = TextView(this@PetOverlayService).apply {
                text = "Ready to help"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(14, 8, 14, 8)
                background = GradientDrawable().apply { setColor(Color.rgb(43, 55, 75)); cornerRadius = 24f }
            }
            sprite = PetSpriteView(this@PetOverlayService)
            addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 54).apply { setMargins(4, 2, 4, 0) })
            addView(sprite, FrameLayout.LayoutParams(156, 148).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })
            var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0
            setOnTouchListener { _, event -> when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; baseX = params.x; baseY = params.y; true }
                MotionEvent.ACTION_MOVE -> { params.x = baseX + (event.rawX - downX).toInt(); params.y = baseY + (event.rawY - downY).toInt(); windowManager.updateViewLayout(this, params); true }
                MotionEvent.ACTION_UP -> { saved.edit().putInt("x", params.x).putInt("y", params.y).apply(); true }
                else -> false
            }}
        }
        windowManager.addView(overlay, params)
        val relay = getSharedPreferences("relay", MODE_PRIVATE)
        val sheet = relay.getString("pet_sheet", "") ?: ""
        if (sheet.isNotBlank()) sprite?.load(sheet) { loaded -> if (!loaded) bubble?.text = "Couldn't load this pet" }
        else PetCatalog.load { pets -> pets.firstOrNull()?.let { chosen ->
            relay.edit().putString("pet_sheet", chosen.spritesheetUrl).apply()
            sprite?.load(chosen.spritesheetUrl) { loaded -> if (!loaded) bubble?.text = "Couldn't load ${chosen.name}" }
        } }
    }
    private fun connect() {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        client?.close()
        client = RelayClient(
            prefs.getString("endpoint", "") ?: "",
            prefs.getString("token", "") ?: "",
            { updatePet(it) },
            { reconnectHandler.removeCallbacks(reconnect); reconnectHandler.postDelayed(reconnect, 5_000) },
        )
        client?.connect()
    }
    private fun updatePet(frame: JSONObject) = Handler(mainLooper).post {
        val event = frame.optJSONObject("event") ?: return@post
        val name = event.optString("eventName", "idle")
        val mood = when (name.lowercase()) { "stop", "done", "sessionend" -> "done"; "pretooluse", "notification", "waiting" -> "waiting"; else -> "working" }
        sprite?.setMood(mood)
        bubble?.text = event.optString("message").ifBlank {
            when (mood) { "waiting" -> "I need your input"; "done" -> "All done!"; else -> "Working on it…" }
        }
    }
}
