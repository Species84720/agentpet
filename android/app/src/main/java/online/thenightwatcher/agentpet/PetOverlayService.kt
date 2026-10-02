package online.thenightwatcher.agentpet

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.TextView
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/** Messenger-style, user-draggable overlay. It contains no credentials in its UI. */
class PetOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var pet: TextView? = null
    private var client: RelayClient? = null
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private val reconnect = Runnable { connect() }
    private var x = 0; private var y = 180
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(7, notification())
        if (pet == null) showPet()
        connect()
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { reconnectHandler.removeCallbacksAndMessages(null); client?.close(); pet?.let { windowManager.removeView(it) }; pet = null; super.onDestroy() }
    private fun notification(): Notification {
        val channel = NotificationChannel("agentpet", "AgentPet companion", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(this, "agentpet").setSmallIcon(android.R.drawable.presence_online).setContentTitle("AgentPet is watching").setContentText("Tap the app to configure your floating companion.").build()
    }
    private fun showPet() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val saved = getSharedPreferences("overlay", MODE_PRIVATE); x = saved.getInt("x", x); y = saved.getInt("y", y)
        val params = WindowManager.LayoutParams(132, 132, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }
        pet = TextView(this).apply {
            text = "🐾\nidle"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(70, 100, 86)); elevation = 12f
            var downX = 0f; var downY = 0f; var baseX = 0; var baseY = 0
            setOnTouchListener { _, event -> when (event.action) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; baseX = params.x; baseY = params.y; true }
                MotionEvent.ACTION_MOVE -> { params.x = baseX + (event.rawX - downX).toInt(); params.y = baseY + (event.rawY - downY).toInt(); windowManager.updateViewLayout(this, params); true }
                MotionEvent.ACTION_UP -> { saved.edit().putInt("x", params.x).putInt("y", params.y).apply(); true }
                else -> false
            }}
        }
        windowManager.addView(pet, params)
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
        pet?.text = "🐾\n$mood"
        pet?.setBackgroundColor(when (mood) { "waiting" -> Color.rgb(180, 105, 45); "done" -> Color.rgb(76, 135, 98); else -> Color.rgb(62, 90, 160) })
    }
}
