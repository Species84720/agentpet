package online.thenightwatcher.agentpet

import android.app.*
import android.content.*
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.TextView
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Messenger-style, user-draggable overlay. It contains no credentials in its UI. */
class PetOverlayService : Service() {
    companion object {
        const val ACTION_RELAY_STATUS = "online.thenightwatcher.agentpet.RELAY_STATUS"
        const val ACTION_CARE_UPDATED = "online.thenightwatcher.agentpet.CARE_UPDATED"
        const val ACTION_APPROVALS_UPDATED = "online.thenightwatcher.agentpet.APPROVALS_UPDATED"
        const val ACTION_PET_CHANGED = "online.thenightwatcher.agentpet.PET_CHANGED"
        const val ACTION_GAME_CHANGED = "online.thenightwatcher.agentpet.GAME_CHANGED"
        const val ACTION_AGENTS_UPDATED = "online.thenightwatcher.agentpet.AGENTS_UPDATED"
        const val EXTRA_RELAY_STATUS = "status"
        private const val DONE_SESSION_IDLE_MS = 30_000L
        // Match desktop SessionStore's staleActiveAfter policy.
        private const val ACTIVE_SESSION_STALE_MS = 5 * 60 * 1_000L
        private const val IDLE_SESSION_REMOVE_MS = 10 * 60 * 1_000L
        private const val SESSION_SWEEP_INTERVAL_MS = 15_000L
    }
    private lateinit var windowManager: WindowManager
    private var overlay: FrameLayout? = null
    private var sprite: PetSpriteView? = null
    private var bubble: TextView? = null
    private var overlayLayoutParams: WindowManager.LayoutParams? = null
    private var bubbleBaseHeight = 62
    private var spriteSizePx = 156
    private var bubbleExpanded = false
    private var concurrentAgents = 0
    private var client: RelayClient? = null
    private val pendingApprovals = linkedMapOf<String, JSONObject>()
    private val sessions = linkedMapOf<String, JSONObject>()
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnect = Runnable { connect() }
    private val approvalPoll = object : Runnable {
        override fun run() {
            syncPendingApprovals()
            reconnectHandler.postDelayed(this, 5_000)
        }
    }
    private val doneResetRunnables = mutableMapOf<String, Runnable>()
    private val sessionExpiry = object : Runnable {
        override fun run() {
            if (pruneSessions()) {
                sessions.values.maxByOrNull(::eventTimeMs)?.let(::renderEvent)
                    ?: renderIdleCompanion()
            }
            if (aggregateMood() == "idle") renderIdleCompanion()
            reconnectHandler.postDelayed(this, SESSION_SWEEP_INTERVAL_MS)
        }
    }
    private var careSyncing = false
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PET_CHANGED -> loadSelectedPet()
                ACTION_GAME_CHANGED -> renderIdleCompanion()
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        ContextCompat.registerReceiver(this, settingsReceiver, IntentFilter().apply {
            addAction(ACTION_PET_CHANGED)
            addAction(ACTION_GAME_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ApprovalInbox.all(this).forEach { approval -> pendingApprovals[approval.optString("requestId")] = approval }
        reconnectHandler.postDelayed(sessionExpiry, SESSION_SWEEP_INTERVAL_MS)
        reconnectHandler.postDelayed(approvalPoll, 1_000)
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
    override fun onDestroy() { reconnectHandler.removeCallbacksAndMessages(null); doneResetRunnables.clear(); client?.close(); unregisterReceiver(settingsReceiver); overlay?.let { windowManager.removeView(it) }; overlay = null; publishActiveAgentCount(0); super.onDestroy() }
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
        spriteSizePx = spriteSize
        val multi = getSharedPreferences("relay", MODE_PRIVATE).getBoolean("multi_agent_bubble", true)
        val bubbleHeight = if (multi) 112 else 62
        bubbleBaseHeight = bubbleHeight
        bubbleExpanded = false
        concurrentAgents = 0
        var lastTapAt = 0L
        val tapHandler = Handler(Looper.getMainLooper())
        var singleTap: Runnable? = null
        val params = WindowManager.LayoutParams(spriteSize + 56, spriteSize + bubbleHeight, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        overlayLayoutParams = params
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
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (lastTapAt != 0L && now - lastTapAt < android.view.ViewConfiguration.getDoubleTapTimeout()) {
                            singleTap?.let(tapHandler::removeCallbacks)
                            singleTap = null
                            lastTapAt = 0L
                            startActivity(Intent(this@PetOverlayService, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                            })
                        } else {
                            lastTapAt = now
                            singleTap = Runnable {
                                bubbleExpanded = !bubbleExpanded
                                updateBubbleSize()
                            }.also { tapHandler.postDelayed(it, android.view.ViewConfiguration.getDoubleTapTimeout().toLong()) }
                        }
                    }
                    saved.edit().putInt("x", params.x).putInt("y", params.y).apply(); true
                }
                else -> false
            }}
        }
        windowManager.addView(overlay, params)
        loadSelectedPet()
        renderIdleCompanion()
    }
    private fun updateBubbleSize() {
        val params = overlayLayoutParams ?: return
        val activeHeight = if (concurrentAgents > 1) (72 + concurrentAgents.coerceAtMost(3) * 44).coerceAtLeast(bubbleBaseHeight) else bubbleBaseHeight
        val height = if (bubbleExpanded) maxOf(220, activeHeight) else activeHeight
        bubble?.maxLines = if (bubbleExpanded) 12 else if (concurrentAgents > 1) 2 + concurrentAgents.coerceAtMost(3) * 2 else if (bubbleBaseHeight > 62) 4 else 2
        bubble?.layoutParams = bubble?.layoutParams?.apply { this.height = height - 8 }
        params.height = spriteSizePx + height
        overlay?.let { runCatching { windowManager.updateViewLayout(it, params) } }
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
            { setConnectionStatus("CONNECTED — live updates active"); syncCare { syncRequestHistory() } },
            { reason -> setConnectionStatus("DISCONNECTED — $reason"); reconnectHandler.removeCallbacks(reconnect); reconnectHandler.postDelayed(reconnect, 5_000) },
        )
        client?.connect()
    }
    private fun setConnectionStatus(status: String) {
        getSharedPreferences("relay", MODE_PRIVATE).edit().putString("connection_status", status).apply()
        sendBroadcast(Intent(ACTION_RELAY_STATUS).setPackage(packageName).putExtra(EXTRA_RELAY_STATUS, status))
        sendBroadcast(Intent(ACTION_APPROVALS_UPDATED).setPackage(packageName))
        if (::windowManager.isInitialized) getSystemService(NotificationManager::class.java).notify(7, notification())
    }
    private fun syncPendingApprovals() {
        val relay = client ?: return
        relay.fetchPendingApprovals { approvals, error ->
            if (approvals == null) {
                Handler(mainLooper).post {
                    getSharedPreferences("relay", MODE_PRIVATE).edit()
                        .putString("approval_poll_status", error ?: "Could not load approval requests")
                        .apply()
                    sendBroadcast(Intent(ACTION_APPROVALS_UPDATED).setPackage(packageName))
                }
                return@fetchPendingApprovals
            }
            Handler(mainLooper).post {
                getSharedPreferences("relay", MODE_PRIVATE).edit()
                    .putString("approval_poll_status", "Inbox reachable · checked just now")
                    .apply()
                val activeIds = approvals.map { it.optString("requestId") }.filter(String::isNotBlank).toSet()
                pendingApprovals.keys.filterNot(activeIds::contains).toList().forEach { id ->
                    pendingApprovals.remove(id)
                    ApprovalInbox.remove(this, id)
                    ApprovalNotifications.cancel(this, id)
                }
                approvals.forEach { approval ->
                    val id = approval.optString("requestId")
                    if (id.isNotBlank()) {
                        pendingApprovals[id] = JSONObject(approval.toString())
                        ApprovalInbox.put(this, approval)
                        ApprovalNotifications.show(this, approval)
                    }
                }
                if (pendingApprovals.isNotEmpty()) {
                    bubble?.text = "${pendingApprovals.size} approval request${if (pendingApprovals.size == 1) "" else "s"} · tap pet"
                    sprite?.setMood("waiting")
                } else if (bubble?.text?.contains("approval", ignoreCase = true) == true) {
                    sessions.values.maxByOrNull(::eventTimeMs)?.let(::renderEvent)
                        ?: renderIdleCompanion()
                }
                sendBroadcast(Intent(ACTION_APPROVALS_UPDATED).setPackage(packageName))
            }
        }
    }
    private fun updatePet(frame: JSONObject) = Handler(mainLooper).post {
        when (frame.optString("type")) {
            "approval_requested" -> {
                frame.optJSONObject("approval")?.let { approval ->
                    val id = approval.optString("requestId")
                    if (id.isNotBlank()) {
                        pendingApprovals[id] = JSONObject(approval.toString())
                        ApprovalInbox.put(this, approval)
                        ApprovalNotifications.show(this, approval)
                    }
                    bubble?.text = if (pendingApprovals.size > 1) "${pendingApprovals.size} approval requests · tap pet" else "${approval.optString("agentKind", "Agent")} needs approval · tap pet"
                    sprite?.setMood("waiting")
                    sendBroadcast(Intent(ACTION_APPROVALS_UPDATED).setPackage(packageName))
                }
                return@post
            }
            "approval_resolved" -> {
                val id = frame.optString("requestId")
                if (id.isNotBlank()) {
                    pendingApprovals.remove(id)
                    ApprovalInbox.remove(this, id)
                    ApprovalNotifications.cancel(this, id)
                    sendBroadcast(Intent(ACTION_APPROVALS_UPDATED).setPackage(packageName))
                    val expired = frame.optBoolean("expired") || frame.optBoolean("cancelled")
                    if (pendingApprovals.isNotEmpty()) {
                        bubble?.text = "${pendingApprovals.size} approval requests · tap pet"
                        sprite?.setMood("waiting")
                    } else {
                        bubble?.text = if (expired) "Timed out · approve in Codex" else "Approval answered · resuming"
                        sprite?.setMood(if (expired) "waiting" else "working")
                        reconnectHandler.postDelayed({
                            if (pendingApprovals.isEmpty()) sessions.values.maxByOrNull(::eventTimeMs)?.let(::renderEvent)
                                ?: renderIdleCompanion()
                        }, 2_000)
                    }
                }
                return@post
            }
        }
        if (frame.optString("type") == "care_delta") {
            syncCare()
            return@post
        }
        if (frame.optString("type") == "snapshot") {
            val snapshot = frame.optJSONArray("sessions") ?: return@post
            sessions.clear()
            for (i in 0 until snapshot.length()) snapshot.optJSONObject(i)?.let { e ->
                e.optString("sessionId").takeIf(String::isNotBlank)?.let {
                    if (moodFor(e) == "done" && eventAgeMs(e) > DONE_SESSION_IDLE_MS) e.put("_agentpetMood", "idle")
                    sessions[it] = e
                    scheduleDoneReset(e)
                }
            }
            pruneSessions()
            sessions.values.maxByOrNull(::eventTimeMs)?.let(::renderEvent) ?: renderIdleCompanion()
            return@post
        }
        val event = frame.optJSONObject("event") ?: return@post
        event.optString("sessionId").takeIf(String::isNotBlank)?.let { sessions[it] = event }
        scheduleDoneReset(event)
        val careChanged = MobilePetCare.recordEvent(this, event, frame.optString("id"))
        if (careChanged) {
            sendBroadcast(Intent(ACTION_CARE_UPDATED).setPackage(packageName))
            syncCare(forceSave = true)
        }
        pruneSessions()
        renderEvent(event)
    }
    private fun pruneSessions(): Boolean {
        val now = System.currentTimeMillis()
        val remove = sessions.filterValues { session ->
            val age = (now - eventTimeMs(session)).coerceAtLeast(0L)
            when (moodFor(session)) {
                // A quiet/stuck hook session must not permanently mask the
                // currently active agent in the pet or multi-agent bubble.
                "working", "waiting" -> age > ACTIVE_SESSION_STALE_MS
                else -> age > IDLE_SESSION_REMOVE_MS
            }
        }.keys
        remove.forEach(sessions::remove)
        var changed = remove.isNotEmpty()
        sessions.values.forEach { session ->
            if (moodFor(session) == "done" && eventAgeMs(session) > DONE_SESSION_IDLE_MS) {
                session.put("_agentpetMood", "idle")
                changed = true
            }
        }
        return changed
    }
    private fun syncRequestHistory() {
        val relay = client ?: return
        val after = MobilePetCare.careCheckpoint(this)
        val collected = mutableListOf<JSONObject>()
        fun fetchPage(before: Long) {
            relay.fetchHistory(after, before) { events ->
                if (events == null) return@fetchHistory
                collected.addAll(events)
                if (events.size == 200) {
                    val oldest = events.minOfOrNull { it.optLong("storedAt", Long.MAX_VALUE) } ?: Long.MIN_VALUE
                    if (oldest > after && oldest < before) {
                        fetchPage(oldest)
                        return@fetchHistory
                    }
                }
                Handler(mainLooper).post {
                    val changed = collected.fold(false) { anyChanged, event ->
                        MobilePetCare.recordRequest(this, event, event.optString("id")) || anyChanged
                    }
                    if (changed) sendBroadcast(Intent(ACTION_CARE_UPDATED).setPackage(packageName))
                    // Advance only after every page was retrieved successfully.
                    syncCare(forceSave = true)
                }
            }
        }
        fetchPage(System.currentTimeMillis() + 1)
    }
    private fun syncCare(forceSave: Boolean = false, onComplete: (() -> Unit)? = null) {
        if (careSyncing) {
            reconnectHandler.postDelayed({ syncCare(forceSave, onComplete) }, 1_000)
            return
        }
        val relay = client ?: return
        careSyncing = true
        relay.fetchAndroidCare { payload ->
            Handler(mainLooper).post {
                if (payload == null) { careSyncing = false; onComplete?.invoke(); return@post }
                val remoteVersion = payload.optInt("version", 0)
                val remoteCare = payload.optJSONObject("care") ?: JSONObject()
                if (remoteVersion > MobilePetCare.cloudVersion(this)) {
                    MobilePetCare.importJson(this, remoteCare)
                    MobilePetCare.setCloudVersion(this, remoteVersion)
                }
                if (remoteCare.optInt("total_queries", 0) > 0) MobilePetCare.setCareCheckpoint(this, payload.optLong("updatedAt", 0L))
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
                    onComplete?.invoke()
                    return@post
                }
                relay.consumeAndroidCare(remoteVersion, MobilePetCare.exportJson(this), ids) { version ->
                    Handler(mainLooper).post {
                        careSyncing = false
                        if (version != null) {
                            MobilePetCare.setCloudVersion(this, version.optInt("version", remoteVersion))
                            MobilePetCare.setCareCheckpoint(this, version.optLong("updatedAt", System.currentTimeMillis()))
                            sendBroadcast(Intent(ACTION_CARE_UPDATED).setPackage(packageName))
                            onComplete?.invoke()
                        } else reconnectHandler.postDelayed({ syncCare(forceSave, onComplete) }, 2_000)
                    }
                }
            }
        }
    }
    private fun moodFor(event: JSONObject): String {
        event.optString("_agentpetMood").takeIf { it.isNotBlank() }?.let { return it }
        val name = event.optString("eventName", "idle")
        return when (name.lowercase().replace("_", "").replace(".", "")) {
            "stop", "done", "sessionend", "agentstop", "afteragent", "turncomplete", "turncompleted",
            "postcascaderesponse", "postcascaderesponsewithtranscript", "sessionidle", "agentend", "sessionshutdown" -> "done"
            "permissionrequest", "notification", "waiting", "approvalrequired" -> "waiting"
            "pretooluse", "posttooluse", "userpromptsubmit", "userpromptsubmitted" -> "working"
            // Desktop treats a registered agent as an active animation until
            // its first terminal event. Keep the Android sprite in that mood.
            "sessionstart", "agentspawn", "registered" -> "working"
            else -> "working"
        }
    }
    private fun eventTimeMs(event: JSONObject): Long {
        val raw = event.opt("timestamp")
        val numeric = (raw as? Number)?.toLong() ?: 0L
        if (numeric > 0L) return if (numeric < 10_000_000_000L) numeric * 1_000 else numeric
        val string = raw as? String ?: return event.optLong("storedAt", 0L)
        string.toLongOrNull()?.takeIf { it > 0L }?.let { return if (it < 10_000_000_000L) it * 1_000 else it }
        return runCatching { java.time.Instant.parse(string).toEpochMilli() }.getOrDefault(event.optLong("storedAt", 0L))
    }
    private fun eventAgeMs(event: JSONObject): Long = (System.currentTimeMillis() - eventTimeMs(event)).coerceAtLeast(0L)
    private fun scheduleDoneReset(event: JSONObject) {
        val sessionId = event.optString("sessionId").takeIf(String::isNotBlank) ?: return
        doneResetRunnables.remove(sessionId)?.let(reconnectHandler::removeCallbacks)
        if (moodFor(event) != "done") return
        val completedAt = eventTimeMs(event)
        val settle = Runnable {
            doneResetRunnables.remove(sessionId)
            val latest = sessions[sessionId] ?: return@Runnable
            if (moodFor(latest) != "done" || eventTimeMs(latest) != completedAt) return@Runnable
            latest.put("_agentpetMood", "idle")
            pruneSessions()
            sessions.values.maxByOrNull(::eventTimeMs)?.let(::renderEvent) ?: renderIdleCompanion()
        }
        doneResetRunnables[sessionId] = settle
        reconnectHandler.postDelayed(settle, (DONE_SESSION_IDLE_MS - eventAgeMs(event)).coerceAtLeast(0L))
    }
    /** Match desktop behavior: any still-working session keeps the pet animated,
     * even if a newer event from another agent has already completed. */
    private fun aggregateMood(): String = when {
        sessions.values.any { moodFor(it) == "working" } -> "working"
        sessions.values.any { moodFor(it) == "waiting" } -> "waiting"
        sessions.values.any { moodFor(it) == "done" } -> "done"
        else -> "idle"
    }
    private fun renderEvent(event: JSONObject) {
        val active = sessions.values.filter { moodFor(it) == "working" || moodFor(it) == "waiting" }.sortedByDescending(::eventTimeMs)
        publishActiveAgentCount(active.size)
        if (pendingApprovals.isNotEmpty()) {
            sprite?.setMood("waiting")
            bubble?.text = "${pendingApprovals.size} approval request${if (pendingApprovals.size == 1) "" else "s"} · tap pet"
            return
        }
        if (aggregateMood() == "idle" && pendingApprovals.isEmpty()) {
            renderIdleCompanion()
            return
        }
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        val mood = moodFor(event)
        sprite?.setMood(aggregateMood())
        if (active.size > 1) {
            val showDetails = prefs.getBoolean("multi_agent_bubble", true)
            concurrentAgents = if (showDetails) active.size else 0
            updateBubbleSize()
            val summary = SpannableStringBuilder("${active.size} agents active")
            if (showDetails) {
                active.take(3).forEach { session ->
                    val kind = session.optString("agentKind", "Agent").replaceFirstChar(Char::uppercaseChar)
                    val shortId = session.optString("sessionId").takeLast(4)
                    val state = if (moodFor(session) == "waiting") "needs input" else "working"
                    summary.append("\n").append(bubbleLine(session, moodFor(session), "$kind #$shortId · $state"))
                }
                if (active.size > 3) summary.append("\n+${active.size - 3} more")
            } else summary.append(" · enable Multi-agent bubble for details")
            bubble?.text = summary
            return
        }
        if (concurrentAgents != 0) { concurrentAgents = 0; updateBubbleSize() }
        val reactive = if (prefs.getBoolean("reactive_bubbles", true)) ActivityPhrases.message(event, prefs.getString("activity_theme", "chef") ?: "chef") else null
        val message = prefs.getString("message_$mood", "")?.trim().orEmpty().ifBlank {
            if (mood == "idle") "Ready to help" else event.optString("message").trim().ifBlank { reactive.orEmpty() }
        }.ifBlank {
            when (mood) { "waiting" -> "I need your input"; "done" -> "All done!"; "idle" -> "Ready to help"; else -> "Working on it…" }
        }
        if (!prefs.getBoolean("multi_agent_bubble", true)) { bubble?.text = bubbleLine(event, mood, message); return }
        val minState = prefs.getString("bubble_min_state", "all")
        var rows = sessions.values.filter { candidate ->
            val state = moodFor(candidate)
            when (minState) { "working" -> state == "working"; "working_waiting" -> state == "working" || state == "waiting"; "done_above" -> state in setOf("working", "waiting", "done"); else -> true }
        }
        rows = rows.sortedBy(::eventTimeMs)
        if (prefs.getString("bubble_grouping", "byKind") == "byKind") rows = rows.groupBy { it.optString("agentKind") }.values.mapNotNull { group -> group.maxByOrNull(::eventTimeMs) }.sortedBy(::eventTimeMs)
        rows = rows.takeLast(prefs.getInt("bubble_max", 5).coerceIn(1, 10))
        val mode = prefs.getString("bubble_mode", "carousel")
        val visible = when (mode) { "carousel" -> if (rows.isEmpty()) rows else listOf(rows[(System.currentTimeMillis() / 3000 % rows.size).toInt()]); "compact" -> rows.take(2); else -> rows }
        val lines = visible.map { row ->
            val state = moodFor(row)
            val custom = prefs.getString("message_$state", "")?.trim().orEmpty()
            val detail = if (state == "idle") "Ready to help" else custom.takeIf { it.isNotBlank() }
                ?: row.optString("message").trim().takeIf { it.isNotEmpty() }
                ?: (if (prefs.getBoolean("reactive_bubbles", true)) ActivityPhrases.message(row, prefs.getString("activity_theme", "chef") ?: "chef") else null)
                ?: state
            bubbleLine(row, state, detail)
        }.toMutableList()
        if (mode == "compact" && rows.size > 2) lines += "+${rows.size - 2} more active"
        bubble?.text = if (lines.isEmpty()) bubbleLine(event, mood, message) else SpannableStringBuilder().apply {
            lines.forEachIndexed { index, line -> if (index > 0) append("\n"); append(line) }
        }
    }

    private fun renderIdleCompanion() {
        if (pendingApprovals.isNotEmpty() || aggregateMood() != "idle") return
        publishActiveAgentCount(0)
        if (concurrentAgents != 0) { concurrentAgents = 0; updateBubbleSize() }
        val companion = MobilePetGame.state(this)
        sprite?.setMood(companion.idleMood)
        bubble?.text = "${companion.personality.title} · ${companion.activity}"
    }

    private fun publishActiveAgentCount(count: Int) {
        val prefs = getSharedPreferences("relay", MODE_PRIVATE)
        if (prefs.getInt("active_agent_count", 0) == count) return
        prefs.edit().putInt("active_agent_count", count).apply()
        sendBroadcast(Intent(ACTION_AGENTS_UPDATED).setPackage(packageName))
    }

    private fun bubbleLine(event: JSONObject, mood: String, message: String): CharSequence {
        val kind = event.optString("agentKind", "unknown").lowercase()
        val project = event.optString("project").trim().trimEnd('/', '\\')
            .substringAfterLast('/').substringAfterLast('\\').ifBlank { "Agent session" }
        // An object-replacement character gives ImageSpan a real glyph slot;
        // using a leading whitespace character can be collapsed/clipped by
        // TextView layout and made the agent marks appear to be missing.
        val line = SpannableStringBuilder("\uFFFC  $project · $message")
        val icon = AgentLogos.bitmap(this, kind, (bubble?.textSize ?: 14f).toInt().coerceAtLeast(14))
        if (icon != null) {
            val iconSize = (bubble?.textSize ?: 14f).toInt().coerceAtLeast(14)
            val drawable = BitmapDrawable(resources, icon).apply { setBounds(0, 0, iconSize, iconSize) }
            line.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return line
    }
}
