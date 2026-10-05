package online.thenightwatcher.agentpet

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** System-tray alerts for approvals; tapping one opens the Inputs tab to review it. */
object ApprovalNotifications {
    private const val CHANNEL_ID = "approval_requests"
    private const val CHANNEL_NAME = "Approval requests"
    private const val PREFS = "approval_notifications"
    private const val NOTIFIED_IDS = "notified_ids"

    fun enabled(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun show(context: Context, approval: JSONObject) {
        val requestId = approval.optString("requestId").takeIf(String::isNotBlank) ?: return
        if (!enabled(context)) return

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(NOTIFIED_IDS, emptySet()).orEmpty()
        if (requestId in notified) return

        ensureChannel(context)
        val agent = approval.optString("agentKind", "Agent").ifBlank { "Agent" }
        val tool = approval.optString("toolName", "Action").ifBlank { "Action" }
        val project = approval.optString("project").substringAfterLast('/').ifBlank { "AgentPet" }
        val summary = approval.optString("summary").take(180)
        val detail = buildString {
            append("$tool · $project")
            if (summary.isNotBlank()) append("\n$summary")
            append("\nTap to review and respond.")
        }
        val openInputs = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_INPUTS, true)
            putExtra(MainActivity.EXTRA_APPROVAL_ID, requestId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context, requestId.hashCode(), openInputs,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("$agent needs approval")
            .setContentText("$tool · $project")
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter((approval.optLong("expiresAt") - System.currentTimeMillis()).coerceAtLeast(1_000))
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(requestId, 0, notification)
        prefs.edit().putStringSet(NOTIFIED_IDS, (notified + requestId).toSet()).apply()
    }

    fun cancel(context: Context, requestId: String) {
        if (requestId.isBlank()) return
        NotificationManagerCompat.from(context).cancel(requestId, 0)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(NOTIFIED_IDS, prefs.getStringSet(NOTIFIED_IDS, emptySet()).orEmpty() - requestId).apply()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when an AI agent is waiting for your approval"
                enableVibration(true)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
