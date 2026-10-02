package online.thenightwatcher.agentpet

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RelayClient(
    private val endpoint: String,
    private val token: String,
    private val onMessage: (JSONObject) -> Unit,
    private val onDisconnected: () -> Unit = {},
) {
    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    fun connect() {
        if (endpoint.isBlank() || token.isBlank()) return
        val wsUrl = endpoint.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/live"
        socket = client.newWebSocket(Request.Builder().url(wsUrl).header("Authorization", "Bearer $token").build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) { runCatching { onMessage(JSONObject(text)) } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnected()
            }
        })
    }
    fun close() { socket?.close(1000, "stopped"); client.dispatcher.executorService.shutdown() }
    fun clearLogs(onComplete: (Boolean) -> Unit) {
        val request = Request.Builder().url(endpoint.removeSuffix("/") + "/v1/logs")
            .delete().header("Authorization", "Bearer $token").build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) = onComplete(false)
            override fun onResponse(call: Call, response: Response) { response.use { onComplete(it.isSuccessful) } }
        })
    }
}
