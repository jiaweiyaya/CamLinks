package com.jiaweiya.camlinks.logic.network

import android.util.Log
import com.jiaweiya.camlinks.util.CertificateHelper
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress

class TlsCamServer(
    port: Int = 8443,
    private val onCommandReceived: (action: String, json: JSONObject) -> Unit
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "TlsCamServer"
    }

    init {
        isReuseAddr = true
        val sslContext = CertificateHelper.createServerSslContext()
        setWebSocketFactory(DefaultSSLWebSocketServerFactory(sslContext))
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.i(TAG, "Client connected: " + conn.remoteSocketAddress)
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        Log.i(TAG, "Client disconnected: " + conn.remoteSocketAddress)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val json = JSONObject(message)
            val action = json.optString("action")
            onCommandReceived(action, json)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse command", e)
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "Server error", ex)
    }

    override fun onStart() {
        Log.i(TAG, "TlsCamServer started on port: " + port)
    }

    fun broadcastState(stateJson: String) {
        broadcast(stateJson)
    }

    fun broadcastBinary(data: ByteArray) {
        broadcast(data)
    }
}