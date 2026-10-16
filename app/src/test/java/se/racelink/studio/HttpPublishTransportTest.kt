package se.racelink.studio

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real HttpURLConnection transport against a loopback server. `com.sun.net.httpserver`
 * is not on the Android compile classpath, so the request is served from a plain ServerSocket.
 */
class HttpPublishTransportTest {
    /** Serves exactly one HTTP request and records what the client sent. */
    private class OneShotServer(
        private val status: Int,
        private val reason: String,
        private val responseBody: String = "",
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 10_000 }
        val port: Int get() = socket.localPort
        val requestLine = AtomicReference<String>()
        val authorization = AtomicReference<String>()
        val contentType = AtomicReference<String>()
        val receivedBody = AtomicReference<String>()

        init {
            Thread {
                runCatching {
                    socket.accept().use { client ->
                        val input = client.getInputStream()
                        val headerBytes = ByteArrayOutputStream()
                        var match = 0
                        while (true) {
                            val b = input.read()
                            if (b == -1) break
                            headerBytes.write(b)
                            match = when {
                                match == 1 && b == '\n'.code -> 2
                                match == 2 && b == '\r'.code -> 3
                                match == 3 && b == '\n'.code -> 4
                                b == '\r'.code -> 1
                                else -> 0
                            }
                            if (match == 4) break
                        }
                        val lines = headerBytes.toByteArray().toString(Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
                        requestLine.set(lines.firstOrNull().orEmpty())
                        val headers = lines.drop(1)
                            .mapNotNull { it.split(":", limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0].trim().lowercase() to p[1].trim() } }
                            .toMap()
                        authorization.set(headers["authorization"])
                        contentType.set(headers["content-type"])
                        val length = headers["content-length"]?.toIntOrNull() ?: 0
                        val body = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(body, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                        receivedBody.set(body.toString(Charsets.UTF_8))

                        val payload = responseBody.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                        client.getOutputStream().apply {
                            write(head.toByteArray(Charsets.ISO_8859_1)); write(payload); flush()
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() { runCatching { socket.close() } }
    }

    @Test
    fun postsAuthorizedJsonAndParsesTheAck() {
        OneShotServer(202, "Accepted", """{"sessionId":"s","revision":3,"accepted":true}""").use { server ->
            val outcome = HttpPublishTransport().publish("http://127.0.0.1:${server.port}/api/v1/publish", "secret", """{"revision":3}""")

            assertEquals(PublishOutcome.Delivered(3, true), outcome)
            assertTrue(server.requestLine.get()!!.startsWith("POST /api/v1/publish"))
            assertEquals("Bearer secret", server.authorization.get())
            assertTrue(server.contentType.get()!!.contains("application/json"))
            assertEquals("""{"revision":3}""", server.receivedBody.get())
        }
    }

    @Test
    fun unauthorizedReplyBecomesAFailureWithAHint() {
        OneShotServer(401, "Unauthorized").use { server ->
            val outcome = HttpPublishTransport().publish("http://127.0.0.1:${server.port}/api/v1/publish", "wrong", "{}")
            assertTrue((outcome as PublishOutcome.Failed).message.contains("Åtkomst nekad"))
        }
    }

    @Test
    fun unreachableServerIsAFailureRatherThanAThrow() {
        val outcome = HttpPublishTransport(timeoutMs = 400).publish("http://127.0.0.1:1/api/v1/publish", "t", "{}")
        assertTrue(outcome is PublishOutcome.Failed)
    }
}
