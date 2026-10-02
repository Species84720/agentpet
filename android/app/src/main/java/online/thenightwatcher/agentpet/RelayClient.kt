package online.thenightwatcher.agentpet

import okhttp3.*
import org.json.JSONObject
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
    fun fetchHistory(onComplete: (List<JSONObject>?) -> Unit) {
        val request = Request.Builder().url(endpoint.removeSuffix("/") + "/v1/events?limit=50")
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
}
