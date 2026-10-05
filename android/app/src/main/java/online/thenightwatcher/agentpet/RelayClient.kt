package online.thenightwatcher.agentpet

import okhttp3.*
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class RelayClient(
    private val endpoint: String,
    private val token: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onConnected: () -> Unit = {},
    private val onDisconnected: (String) -> Unit = {},
) {
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    private var manuallyClosed = false
    fun connect() {
        if (endpoint.isBlank() || token.isBlank()) { onDisconnected("Endpoint or device token is missing"); return }
        manuallyClosed = false
        val wsUrl = endpoint.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/live"
        socket = client.newWebSocket(Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onConnected()
            override fun onMessage(webSocket: WebSocket, text: String) { runCatching { onMessage(JSONObject(text)) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!manuallyClosed) onDisconnected(t.message ?: response?.message ?: "WebSocket connection failed")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (!manuallyClosed) onDisconnected(reason.ifBlank { "WebSocket closed ($code)" }) }
        })
    }
    fun close() { manuallyClosed = true; socket?.close(1000, "stopped"); client.dispatcher.executorService.shutdown() }
    fun clearLogs(onComplete: (Boolean) -> Unit) {
        val request = Request.Builder().url(endpoint.removeSuffix("/") + "/v1/logs")
            .delete().header("Authorization", "Bearer $token").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = onComplete(false)
            override fun onResponse(call: Call, response: Response) { response.use { onComplete(it.isSuccessful) } }
        })
    }
    fun fetchHistory(after: Long = 0L, before: Long = System.currentTimeMillis() + 1, onComplete: (List<JSONObject>?) -> Unit) {
        val request = Request.Builder().url(endpoint.removeSuffix("/") + "/v1/events?limit=200&after=$after&before=$before")
            .header("Authorization", "Bearer $token").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = onComplete(null)
            override fun onResponse(call: Call, response: Response) = response.use {
                val events = runCatching { JSONObject(it.body?.string() ?: "{}").optJSONArray("events") }
                    .getOrNull()?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }
                onComplete(events)
            }
        })
    }

    fun fetchAndroidCare(onComplete: (JSONObject?) -> Unit) {
        val request = Request.Builder().url(endpoint.removeSuffix("/") + "/v1/android-care")
            .header("Authorization", "Bearer $token").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = onComplete(null)
            override fun onResponse(call: Call, response: Response) = response.use {
                onComplete(if (it.isSuccessful) runCatching { JSONObject(it.body?.string() ?: "{}") }.getOrNull() else null)
            }
        })
    }

    fun consumeAndroidCare(version: Int, care: JSONObject, deltaIds: List<String>, onComplete: (JSONObject?) -> Unit) {
        val body = JSONObject().put("version", version).put("care", care).put("deltaIds", org.json.JSONArray(deltaIds))
        val request = Request.Builder().url(endpoint.removeSuffix("/") + "/v1/android-care/consume")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = onComplete(null)
            override fun onResponse(call: Call, response: Response) = response.use {
                val result = runCatching { JSONObject(it.body?.string() ?: "{}") }.getOrNull()
                onComplete(if (it.isSuccessful) result else null)
            }
        })
    }

    fun submitApprovalDecision(requestId: String, decision: String, onComplete: (JSONObject?) -> Unit) {
        val body = JSONObject().put("decision", decision)
        val request = Request.Builder().url(endpoint.removeSuffix("/") + "/v1/approvals/$requestId/decision")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer $token").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = onComplete(null)
            override fun onResponse(call: Call, response: Response) = response.use {
                val result = runCatching { JSONObject(it.body?.string() ?: "{}") }.getOrNull()
                onComplete(if (it.isSuccessful) result else null)
            }
        })
    }

    fun fetchPendingApprovals(onComplete: (List<JSONObject>?, String?) -> Unit) {
        val request = Request.Builder().url(endpoint.removeSuffix("/") + "/v1/approvals")
            .header("Authorization", "Bearer $token").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = onComplete(null, e.message ?: "Network request failed")
            override fun onResponse(call: Call, response: Response) = response.use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val reason = runCatching { JSONObject(body).optString("error") }.getOrNull().orEmpty()
                    onComplete(null, "HTTP ${it.code}${if (reason.isBlank()) "" else ": $reason"}")
                } else {
                    val approvals = runCatching { JSONObject(body).optJSONArray("approvals") }
                        .getOrNull()?.let { array -> (0 until array.length()).mapNotNull(array::optJSONObject) }
                    if (approvals == null) onComplete(null, "Relay returned an invalid approval list")
                    else onComplete(approvals, null)
                }
            }
        })
    }
}
