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
    companion object {
        const val ACTION_RELAY_STATUS = "online.thenightwatcher.agentpet.RELAY_STATUS"
        const val EXTRA_RELAY_STATUS = "status"
    }
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
        val status = getSharedPreferences("relay", MODE_PRIVATE).getString("connection_status", "Connecting to relay…")
        return NotificationCompat.Builder(this, "agentpet").setSmallIcon(android.R.drawable.presence_online).setContentTitle("AgentPet is watching").setContentText(status).build()
    }
    private fun showPet() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val saved = getSharedPreferences("overlay", MODE_PRIVATE); x = saved.getInt("x", x); y = saved.getInt("y", y)
        val spriteSize = getSharedPreferences("relay", MODE_PRIVATE).getInt("pet_size", 156).coerceIn(80, 260)
        val params = WindowManager.LayoutParams(spriteSize + 16, spriteSize + 58, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        overlay = FrameLayout(this).apply {
            bubble = TextView(this@PetOverlayService).apply {
                text = "Ready to help"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setPadding(14, 8, 14, 8)
                background = GradientDrawable().apply { setColor(Color.rgb(43, 55, 75)); cornerRadius = 24f }
            }
            sprite = PetSpriteView(this@PetOverlayService)
            sprite?.setClipBindings(listOf("idle", "working", "waiting", "done", "celebrate", "sleepy").associateWith { mood ->
                getSharedPreferences("relay", MODE_PRIVATE).getInt("clip_$mood", -1)
            }.filterValues { it >= 0 })
            addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 54).apply { setMargins(4, 2, 4, 0) })
            addView(sprite, FrameLayout.LayoutParams(spriteSize, spriteSize).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })
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
        setConnectionStatus("Connecting to relay…")
        client?.close()
        client = RelayClient(
            prefs.getString("endpoint", "") ?: "",
            prefs.getString("token", "") ?: "",
            { updatePet(it) },
            { setConnectionStatus("CONNECTED — live updates active") },
            { reason -> setConnectionStatus("DISCONNECTED — $reason"); reconnectHandler.removeCallbacks(reconnect); reconnectHandler.postDelayed(reconnect, 5_000) },
        )
        client?.connect()
    }
    private fun setConnectionStatus(status: String) {
        getSharedPreferences("relay", MODE_PRIVATE).edit().putString("connection_status", status).apply()
        sendBroadcast(Intent(ACTION_RELAY_STATUS).setPackage(packageName).putExtra(EXTRA_RELAY_STATUS, status))
        if (::windowManager.isInitialized) getSystemService(NotificationManager::class.java).notify(7, notification())
    }
    private fun updatePet(frame: JSONObject) = Handler(mainLooper).post {
        val event = frame.optJSONObject("event") ?: return@post
        val name = event.optString("eventName", "idle")
        val mood = when (name.lowercase()) { "stop", "done", "sessionend" -> "done"; "pretooluse", "notification", "waiting" -> "waiting"; else -> "working" }
        sprite?.setMood(mood)
        val message = event.optString("message").ifBlank {
            when (mood) { "waiting" -> "I need your input"; "done" -> "All done!"; else -> "Working on it…" }
        }
        val multiAgent = getSharedPreferences("relay", MODE_PRIVATE).getBoolean("multi_agent_bubble", false)
        bubble?.text = if (multiAgent) "${event.optString("agentKind", "Agent")} · $mood\n$message" else message
    }
}
