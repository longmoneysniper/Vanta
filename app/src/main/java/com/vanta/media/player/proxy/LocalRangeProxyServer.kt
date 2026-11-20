package com.vanta.media.player.proxy

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalRangeProxyServer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var port: Int = 0
    private val registeredUris = ConcurrentHashMap<String, Uri>()

    fun startIfNeeded(): Int {
        if (serverSocket != null && !serverSocket!!.isClosed) return port
        val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        port = socket.localPort

        scope.launch {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch { handleClient(client) }
                } catch (_: Exception) {
                }
            }
        }
        return port
    }

    fun registerSafUri(uri: Uri): String {
        val activePort = startIfNeeded()
        val token = UUID.randomUUID().toString()
        registeredUris[token] = uri
        return "http://127.0.0.1:$activePort/stream/$token"
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            val reader = socket.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1) ?: return
            val token = path.substringAfterLast("/")
            val uri = registeredUris[token] ?: return

            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
            }

            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return
            pfd.use { descriptor ->
                val totalLength = descriptor.statSize
                var start = 0L
                var end = totalLength - 1

                if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                    val parts = rangeHeader.removePrefix("bytes=").split("-")
                    start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
                    end = parts.getOrNull(1)?.toLongOrNull() ?: (totalLength - 1)
                }

                val contentLength = (end - start + 1).coerceAtLeast(0L)
                val out = BufferedOutputStream(socket.getOutputStream())
                val status = if (rangeHeader != null) "206 Partial Content" else "200 OK"

                val headers = buildString {
                    append("HTTP/1.1 $status\r\n")
                    append("Content-Type: video/x-matroska\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Content-Length: $contentLength\r\n")
                    if (rangeHeader != null) {
                        append("Content-Range: bytes $start-$end/$totalLength\r\n")
                    }
                    append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray())

                context.contentResolver.openInputStream(uri)?.use { rawIn ->
                    val input = BufferedInputStream(rawIn)
                    input.skip(start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        remaining -= read
                    }
                }
                out.flush()
            }
        }
    }
}
