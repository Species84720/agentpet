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
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Messenger-style, user-draggable overlay. It contains no credentials in its UI. */
class PetOverlayService : Service() {
    companion object {
        const val ACTION_RELAY_STATUS = "online.thenightwatcher.agentpet.RELAY_STATUS"
        const val ACTION_CARE_UPDATED = "online.thenightwatcher.agentpet.CARE_UPDATED"
        const val ACTION_PET_CHANGED = "online.thenightwatcher.agentpet.PET_CHANGED"
        const val EXTRA_RELAY_STATUS = "status"
    }
    private lateinit var windowManager: WindowManager
    private var overlay: FrameLayout? = null
    private var sprite: PetSpriteView? = null
    private var bubble: TextView? = null
    private var client: RelayClient? = null
    private val sessions = linkedMapOf<String, JSONObject>()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnect = Runnable { connect() }
    private var careSyncing = false
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == ACTION_PET_CHANGED) loadSelectedPet() }
    }
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, settingsReceiver, IntentFilter(ACTION_PET_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }
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
    override fun onDestroy() { reconnectHandler.removeCallbacksAndMessages(null); client?.close(); unregisterReceiver(settingsReceiver); overlay?.let { windowManager.removeView(it) }; overlay = null; super.onDestroy() }
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
        val multi = getSharedPreferences("relay", MODE_PRIVATE).getBoolean("multi_agent_bubble", true)
        val bubbleHeight = if (multi) 112 else 62
        var expanded = false
        val params = WindowManager.LayoutParams(spriteSize + 56, spriteSize + bubbleHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        overlay = FrameLayout(this).apply {
            bubble = TextView(this@PetOverlayService).apply {
                val prefs = getSharedPreferences("relay", MODE_PRIVATE)
                text = "Ready to help"; textSize = when (prefs.getString("bubble_font", "medium")) { "small" -> 11f; "large" -> 16f; else -> 13f }
                gravity = Gravity.CENTER; setPadding(14, 8, 14, 8); alpha = prefs.getInt("bubble_opacity", 92).coerceIn(30, 100) / 100f
                val light = prefs.getString("bubble_theme", "system") == "light"
                setTextColor(if (light) Color.rgb(25, 29, 38) else Color.WHITE)
                maxLines = if (multi) 4 else 2
                background = GradientDrawable().apply { setColor(if (light) Color.rgb(243, 246, 252) else Color.rgb(43, 55, 75)); cornerRadius = 24f }
            }
            sprite = PetSpriteView(this@PetOverlayService)
            sprite?.setClipBindings(listOf("idle", "working", "waiting", "done", "celebrate", "sleepy").associateWith { mood ->
                getSharedPreferences("relay", MODE_PRIVATE).getInt("clip_$mood", -1)
            }.filterValues { it >= 0 })
            sprite?.configureAnimation(getSharedPreferences("relay", MODE_PRIVATE).getBoolean("animations_enabled", true), getSharedPreferences("relay", MODE_PRIVATE).getInt("animation_fps", 5))
            addView(bubble, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, bubbleHeight - 8).apply { setMargins(4, 2, 4, 0) })
            addView(sprite, FrameLayout.LayoutParams(spriteSize, spriteSize).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL })
            var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0; var moved = false
            setOnTouchListener { _, event -> when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; baseX = params.x; baseY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> { moved = moved || kotlin.math.abs(event.rawX - downX) > 12 || kotlin.math.abs(event.rawY - downY) > 12; params.x = baseX + (event.rawX - downX).toInt(); params.y = baseY + (event.rawY - downY).toInt(); windowManager.updateViewLayout(this, params); true }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        expanded = !expanded
                        params.width = if (expanded) 360 else spriteSize + 56
                        params.height = spriteSize + if (expanded) 220 else bubbleHeight
                        bubble?.maxLines = if (expanded) 12 else if (multi) 4 else 2
                        bubble?.layoutParams = (bubble?.layoutParams as? FrameLayout.LayoutParams)?.apply { height = if (expanded) 204 else bubbleHeight - 8 }
                        windowManager.updateViewLayout(this, params)
                    }
                    saved.edit().putInt("x", params.x).putInt("y", params.y).apply(); true
                }
                else -> false
            }}
        }
        windowManager.addView(overlay, params)
        loadSelectedPet()
    }
    private fun loadSelectedPet() {
        val relay = getSharedPreferences("relay", MODE_PRIVATE)
        val sheet = relay.getString("pet_sheet", "") ?: ""
        val name = relay.getString("pet_name", "this pet") ?: "this pet"
        if (sheet.isNotBlank()) sprite?.load(sheet) { loaded -> if (!loaded) bubble?.text = "Couldn't load $name" }
        else PetCatalog.load { pets -> pets.firstOrNull()?.let { chosen ->
            relay.edit().putString("pet_sheet", chosen.spritesheetUrl).putString("pet_name", chosen.name).putString("pet_slug", chosen.slug).apply()
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
            { setConnectionStatus("CONNECTED — live updates active"); syncCare() },
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
        if (frame.optString("type") == "care_delta") {
            syncCare()
            return@post
        }
        if (frame.optString("type") == "snapshot") {
            val snapshot = frame.optJSONArray("sessions") ?: return@post
            sessions.clear()
            for (i in 0 until snapshot.length()) snapshot.optJSONObject(i)?.let { e -> e.optString("sessionId").takeIf(String::isNotBlank)?.let { sessions[it] = e } }
            sessions.values.lastOrNull()?.let(::renderEvent)
            return@post
        }
        val event = frame.optJSONObject("event") ?: return@post
        event.optString("sessionId").takeIf(String::isNotBlank)?.let { sessions[it] = event }
        MobilePetCare.recordEvent(this, event)
        sendBroadcast(Intent(ACTION_CARE_UPDATED).setPackage(packageName))
        if (event.optString("eventName").lowercase() in setOf("stop", "done", "sessionend", "session_end")) syncCare(forceSave = true)
        renderEvent(event)
    }
    private fun syncCare(forceSave: Boolean = false) {
        if (careSyncing) {
            reconnectHandler.postDelayed({ syncCare(forceSave) }, 1_000)
            return
        }
        val relay = client ?: return
        careSyncing = true
        relay.fetchAndroidCare { payload ->
            Handler(mainLooper).post {
                if (payload == null) { careSyncing = false; return@post }
                val remoteVersion = payload.optInt("version", 0)
                if (remoteVersion > MobilePetCare.cloudVersion(this)) {
                    MobilePetCare.importJson(this, payload.optJSONObject("care") ?: JSONObject())
                    MobilePetCare.setCloudVersion(this, remoteVersion)
                }
                val deltas = payload.optJSONArray("deltas")
                val ids = mutableListOf<String>()
                if (deltas != null) for (i in 0 until deltas.length()) {
                    val delta = deltas.optJSONObject(i) ?: continue
                    val id = delta.optString("id")
                    if (id.isNotBlank()) {
                        MobilePetCare.applyTokenDelta(this, id, delta.optInt("tokens", 0))
                        ids += id
                    }
                }
                if (ids.isEmpty() && !forceSave) {
                    careSyncing = false
                    sendBroadcast(Intent(ACTION_CARE_UPDATED).setPackage(packageName))
                    return@post
                }
                relay.consumeAndroidCare(remoteVersion, MobilePetCare.exportJson(this), ids) { version ->
                    Handler(mainLooper).post {
                        careSyncing = false
                        if (version != null) {
                            MobilePetCare.setCloudVersion(this, version)
                            sendBroadcast(Intent(ACTION_CARE_UPDATED).setPackage(packageName))
                        } else reconnectHandler.postDelayed({ syncCare(forceSave) }, 2_000)
                    }
                }
            }
        }
    }
    private fun moodFor(event: JSONObject): String {
        val name = event.optString("eventName", "idle")
        return when (name.lowercase()) { "stop", "done", "sessionend", "session_end" -> "done"; "pretooluse", "permissionrequest", "notification", "waiting" -> "waiting"; else -> "working" }
    }
    private fun renderEvent(event: JSONObject) {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val mood = moodFor(event)
        sprite?.setMood(mood)
        val reactive = if (prefs.getBoolean("reactive_bubbles", true)) ActivityPhrases.message(event, prefs.getString("activity_theme", "chef") ?: "chef") else null
        val message = prefs.getString("message_$mood", "")?.trim().orEmpty().ifBlank { reactive ?: event.optString("message") }.ifBlank {
            when (mood) { "waiting" -> "I need your input"; "done" -> "All done!"; else -> "Working on it…" }
        }
        if (!prefs.getBoolean("multi_agent_bubble", true)) { bubble?.text = message; return }
        val minState = prefs.getString("bubble_min_state", "all")
        var rows = sessions.values.filter { candidate ->
            val state = moodFor(candidate)
            when (minState) { "working" -> state == "working"; "working_waiting" -> state == "working" || state == "waiting"; "done_above" -> state in setOf("working", "waiting", "done"); else -> true }
        }
        if (prefs.getString("bubble_grouping", "byKind") == "byKind") rows = rows.distinctBy { it.optString("agentKind") }
        rows = rows.takeLast(prefs.getInt("bubble_max", 5).coerceIn(1, 10))
        val mode = prefs.getString("bubble_mode", "carousel")
        val visible = when (mode) { "carousel" -> if (rows.isEmpty()) rows else listOf(rows[(System.currentTimeMillis() / 3000 % rows.size).toInt()]); "compact" -> rows.take(2); else -> rows }
        val lines = visible.map { row ->
            val state = moodFor(row); val agent = row.optString("agentKind", "agent").replaceFirstChar { it.uppercase() }
            val custom = prefs.getString("message_$state", "")?.trim().orEmpty()
            val detail = custom.takeIf { it.isNotBlank() }
                ?: (if (prefs.getBoolean("reactive_bubbles", true)) ActivityPhrases.message(row, prefs.getString("activity_theme", "chef") ?: "chef") else null)
                ?: row.optString("message").ifBlank { state }
            "$agent · $detail"
        }.toMutableList()
        if (mode == "compact" && rows.size > 2) lines += "+${rows.size - 2} more active"
        bubble?.text = lines.ifEmpty { listOf("${event.optString("agentKind", "Agent")} · $message") }.joinToString("\n")
    }
}
