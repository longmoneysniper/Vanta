package com.vanta.media.player.companion

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.vanta.media.player.engine.VantaMpvController
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LanCompanionServer @Inject constructor(
    private val mpvController: VantaMpvController
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var companionUrl: String? = null

    fun start(): String {
        companionUrl?.let { return it }
        val socket = ServerSocket(0)
        serverSocket = socket
        val ip = getLocalWifiIp() ?: "127.0.0.1"
        val url = "http://$ip:${socket.localPort}"
        companionUrl = url

        scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleHttp(client) }
                } catch (_: Exception) {
                }
            }
        }
        return url
    }

    fun generateQrBitmap(url: String, sizePx: Int = 360): Bitmap {
        val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bmp
    }

    private fun handleHttp(client: Socket) {
        client.use { socket ->
            val reader = socket.getInputStream().bufferedReader()
            val req = reader.readLine() ?: return
            val path = req.split(" ").getOrNull(1) ?: "/"

            when {
                path.startsWith("/cmd/playpause") -> mpvController.togglePlayPause()
                path.startsWith("/cmd/rw") -> mpvController.seekRelative(-15)
                path.startsWith("/cmd/ff") -> mpvController.seekRelative(30)
                path.startsWith("/cmd/nextchapter") -> mpvController.skipChapter(1)
                path.startsWith("/cmd/sub?url=") -> {
                    val sub = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                    if (sub.isNotBlank()) mpvController.addExternalSubtitle(sub)
                }
            }

            val html = """
                <!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                <style>body{background:#050507;color:#f8fafc;font-family:sans-serif;text-align:center;padding:24px}
                button{background:#ff8a1f;color:#050507;border:none;padding:14px 20px;margin:8px;border-radius:14px;font-weight:700;font-size:15px}
                input{padding:12px;border-radius:12px;border:1px solid #334155;background:#0e0f14;color:#fff;width:85%}</style></head>
                <body><h2>VANTA LAN REMOTE</h2>
                <div><button onclick="fetch('/cmd/rw')">-15s</button>
                <button onclick="fetch('/cmd/playpause')">Play / Pause</button>
                <button onclick="fetch('/cmd/ff')">+30s</button></div>
                <div><button onclick="fetch('/cmd/nextchapter')">Skip Chapter</button></div><br>
                <input id="sub" placeholder="Paste .srt / .ass subtitle URL">
                <button onclick="fetch('/cmd/sub?url='+encodeURIComponent(document.getElementById('sub').value))">Load Sub</button>
                </body></html>
            """.trimIndent()

            val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.toByteArray().size}\r\nConnection: close\r\n\r\n$html"
            socket.getOutputStream().write(resp.toByteArray())
            socket.getOutputStream().flush()
        }
    }

    private fun getLocalWifiIp(): String? {
        return NetworkInterface.getNetworkInterfaces()?.toList()
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    }
}
