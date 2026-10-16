package se.racelink.studio

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

internal sealed interface PublishOutcome {
    /** The server now holds [revision]; [accepted] is false when it already had an equal or newer one. */
    data class Delivered(val revision: Long, val accepted: Boolean) : PublishOutcome
    data class Failed(val message: String) : PublishOutcome
}

internal interface PublishTransport {
    fun publish(endpoint: String, token: String, body: String): PublishOutcome

    /**
     * Asks the server to forget a session and its per-skier links. [PublishOutcome.Delivered] means the
     * pages are gone; a server that no longer had them counts as gone too, since that is the point.
     */
    fun withdraw(endpoint: String, token: String): PublishOutcome
}

/** Plain HttpURLConnection keeps the tablet free of an extra networking dependency. */
internal class HttpPublishTransport(private val timeoutMs: Int = 8_000) : PublishTransport {
    override fun publish(endpoint: String, token: String, body: String): PublishOutcome = try {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
        }
        connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        parseAck(code, text)
    } catch (e: Exception) {
        PublishOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }

    override fun withdraw(endpoint: String, token: String): PublishOutcome = try {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("Authorization", "Bearer $token")
        }
        val code = connection.responseCode
        connection.disconnect()
        when {
            code == 401 || code == 403 -> PublishOutcome.Failed("Åtkomst nekad ($code). Kontrollera publiceringstoken.")
            // A 404 is the outcome we asked for: nothing of the session is left on the server.
            code == 404 || code in 200..299 -> PublishOutcome.Delivered(revision = 0L, accepted = true)
            else -> PublishOutcome.Failed("Servern svarade HTTP $code.")
        }
    } catch (e: Exception) {
        PublishOutcome.Failed(e.message ?: e.javaClass.simpleName)
    }
}

internal fun parseAck(code: Int, body: String): PublishOutcome = when {
    code == 401 || code == 403 -> PublishOutcome.Failed("Åtkomst nekad ($code). Kontrollera publiceringstoken.")
    code !in 200..299 -> PublishOutcome.Failed("Servern svarade HTTP $code.")
    else -> PublishOutcome.Delivered(
        revision = runCatching { JSONObject(body).getLong("revision") }.getOrDefault(0L),
        accepted = runCatching { JSONObject(body).optBoolean("accepted", true) }.getOrDefault(true),
    )
}
