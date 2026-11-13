package com.screensense.service

import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Minimal loopback-only HTTP server bound to 127.0.0.1 (not remotely accessible).
 *
 * Zepp Side Service on the same device sends POST /speak with JSON
 * {"text": "..."} and this server relays it to ScreenSenseService TTS.
 *
 * No external dependencies: uses java.net.ServerSocket + a coroutine
 * accept loop.  One request at a time, designed for infrequent
 * assistant-message speak triggers, not high throughput.
 */
class TtsHttpServer(
    private val port: Int,
    private val onSpeak: (String) -> Unit
) {
    companion object {
        private const val TAG = "TtsHttpServer"
        private const val LOCALHOST = "127.0.0.1"
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    var actualPort: Int = 0
        private set

    /**
     * Start the loopback HTTP server on the configured port.
     * actualPort will be set to the bound port (may differ if port 0 was used).
     */
    fun start() {
        if (serverJob != null) return

        serverJob = scope.launch {
            try {
                val addr = InetAddress.getByName(LOCALHOST)
                ServerSocket(port, 1, addr).use { ss ->
                    serverSocket = ss
                    actualPort = ss.localPort
                    Log.d(TAG, "HTTP server listening on 127.0.0.1:$actualPort")

                    while (isActive) {
                        val client: Socket
                        try {
                            client = ss.accept()
                        } catch (e: Exception) {
                            if (!isActive) break
                            Log.w(TAG, "Accept failed", e)
                            continue
                        }
                        handleClient(client)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "HTTP server failed", e)
                }
            } finally {
                serverSocket = null
            }
        }
    }

    /**
     * Stop the server and release the port.
     */
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // ignore
        }
        serverSocket = null
    }

    private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
                val requestLine = reader.readLine() ?: return@withContext
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@withContext

                val method = parts[0]
                val path = parts[1]

                // Read headers to find Content-Length
                var contentLength = 0
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                    if (header.startsWith("Content-Length:", true)) {
                        contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }

                var responseBody: String
                var statusLine: String

                if (method == "POST" && path == "/speak") {
                    val body = if (contentLength > 0) {
                        val chars = CharArray(contentLength)
                        reader.read(chars, 0, contentLength)
                        String(chars)
                    } else ""

                    try {
                        val json = JSONObject(body)
                        val text = json.optString("text", "")
                        if (text.isNotBlank()) {
                            onSpeak(text)
                            Log.d(TAG, "Speech requested from HTTP: ${text.length} chars")
                            statusLine = "HTTP/1.1 200 OK"
                            responseBody = """{"status":"ok"}"""
                        } else {
                            statusLine = "HTTP/1.1 400 Bad Request"
                            responseBody = """{"error":"text field is empty or missing"}"""
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Bad speak request", e)
                        statusLine = "HTTP/1.1 400 Bad Request"
                        responseBody = """{"error":"invalid JSON"}"""
                    }
                } else {
                    statusLine = "HTTP/1.1 404 Not Found"
                    responseBody = """{"error":"not found"}"""
                }

                val response = """
                    $statusLine
                    Content-Type: application/json; charset=utf-8
                    Content-Length: ${responseBody.toByteArray().size}
                    Connection: close

                    $responseBody
                """.trimIndent()

                socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
                socket.getOutputStream().flush()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling HTTP client", e)
        }
    }
}