package com.jiaweiya.camlinks.logic.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import android.content.Context
import android.net.wifi.WifiManager
import java.net.InetSocketAddress

class DeviceDiscoveryServer(private val context: Context, private val port: Int = 8444) {
    private var socket: DatagramSocket? = null
    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        stop()
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifiManager.createMulticastLock("CamLinksMulticastLock").apply {
            setReferenceCounted(true)
            acquire()
        }

        job = CoroutineScope(Dispatchers.IO).launch {
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(port))
                }
                val buffer = ByteArray(1024)
                while (isActive) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val message = String(packet.data, 0, packet.length)
                    if (message == "CAMLINKS_DISCOVER") {
                        val reply = "CAMLINKS_OFFER:8443".toByteArray()
                        val replyPacket = DatagramPacket(
                            reply,
                            reply.size,
                            packet.address,
                            packet.port
                        )
                        socket?.send(replyPacket)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        multicastLock = null
    }
}