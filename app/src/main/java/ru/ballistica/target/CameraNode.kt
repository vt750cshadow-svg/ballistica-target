package ru.ballistica.target

import android.content.Context
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Роль «камера»: отдаёт JPEG по HTTP.
 *
 *   GET /shot.jpg   — сделать снимок и вернуть его
 *   GET /info       — состояние
 */
object CameraNode {

    @Volatile private var capture: ImageCapture? = null
    @Volatile private var ctx: Context? = null
    @Volatile var shots = 0
        private set
    @Volatile var resolution = "—"
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var running = false
        private set
    var port = 8080

    fun attach(context: Context, cap: ImageCapture) {
        ctx = context.applicationContext
        capture = cap
    }

    suspend fun takeJpeg(): ByteArray {
        val cap = capture ?: throw IllegalStateException("камера не готова")
        val context = ctx ?: throw IllegalStateException("нет контекста")
        return suspendCancellableCoroutine { cont ->
            cap.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val buf = image.planes[0].buffer
                            val bytes = ByteArray(buf.remaining())
                            buf.get(bytes)
                            resolution = "${image.width} × ${image.height}"
                            shots++
                            image.close()
                            cont.resume(bytes)
                        } catch (e: Exception) {
                            image.close()
                            cont.resumeWithException(e)
                        }
                    }

                    override fun onError(exc: ImageCaptureException) {
                        lastError = exc.message
                        cont.resumeWithException(exc)
                    }
                }
            )
        }
    }

    fun startServer(p: Int = 8080) {
        if (running) return
        port = p
        running = true
        thread(isDaemon = true, name = "camera-http") {
            var server: ServerSocket? = null
            try {
                server = ServerSocket(port)
                while (running) {
                    val sock = try {
                        server.accept()
                    } catch (e: SocketException) {
                        break
                    }
                    thread(isDaemon = true) { handle(sock) }
                }
            } catch (e: Exception) {
                lastError = e.message
            } finally {
                try { server?.close() } catch (_: Exception) { }
                running = false
            }
        }
    }

    fun stopServer() {
        running = false
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 30000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            val path = if (parts.size > 1) parts[1] else "/"
            val out = BufferedOutputStream(sock.getOutputStream())

            when {
                path.startsWith("/shot.jpg") -> {
                    val jpeg = try {
                        runBlocking { takeJpeg() }
                    } catch (e: Exception) {
                        lastError = e.message
                        null
                    }
                    if (jpeg == null) {
                        send(out, 500, "application/json", "{\"error\":\"${lastError ?: "снимок не вышел"}\"}".toByteArray())
                    } else {
                        send(out, 200, "image/jpeg", jpeg)
                    }
                }
                path.startsWith("/info") -> {
                    val js = "{\"shots\":$shots,\"resolution\":\"$resolution\",\"error\":${if (lastError == null) "null" else "\"$lastError\""}}"
                    send(out, 200, "application/json", js.toByteArray())
                }
                else -> {
                    send(out, 404, "text/plain", "нет такого пути".toByteArray())
                }
            }
            out.flush()
            sock.close()
        } catch (_: Exception) {
            try { sock.close() } catch (_: Exception) { }
        }
    }

    private fun send(out: BufferedOutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
    }
}
