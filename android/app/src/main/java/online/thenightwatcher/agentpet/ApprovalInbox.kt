package online.thenightwatcher.agentpet

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Durable, small local cache of live approval requests so the inbox survives UI/process recreation. */
object ApprovalInbox {
    private const val PREFS = "relay"
    private const val KEY = "pending_approvals"

    @Synchronized
    fun all(context: Context): List<JSONObject> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val result = mutableListOf<JSONObject>()
        val source = runCatching { JSONArray(prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(JSONArray())
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            if (item.optLong("expiresAt", now + 1) > now) result += JSONObject(item.toString())
        }
        write(context, result)
        return result.sortedBy { it.optLong("createdAt") }
    }

    @Synchronized
    fun put(context: Context, approval: JSONObject) {
        val requests = all(context).associateBy { it.optString("requestId") }.toMutableMap()
        val id = approval.optString("requestId")
        if (id.isBlank()) return
        requests[id] = JSONObject(approval.toString())
        write(context, requests.values.toList())
    }

    @Synchronized
    fun remove(context: Context, requestId: String) {
        write(context, all(context).filterNot { it.optString("requestId") == requestId })
    }

    private fun write(context: Context, approvals: List<JSONObject>) {
        val array = JSONArray()
        approvals.forEach { array.put(it) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}
