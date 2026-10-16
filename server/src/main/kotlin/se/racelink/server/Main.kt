package se.racelink.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration

fun main() {
    val config = ServerConfig.fromEnv()
    if (config.publishToken == ServerConfig.DEV_TOKEN) {
        System.err.println("RACELINK: using the default dev publish token; set RACELINK_PUBLISH_TOKEN before any real event")
    }
    val store = ProjectionStore(dataFile = System.getenv("RACELINK_DATA_FILE")
        ?.takeIf { it.isNotBlank() }?.let(java.nio.file.Path::of))
    store.purge(config.retentionHours)
    if (config.retentionHours > 0) {
        // Sweep on a timer as well as per request, so a page nobody opens still expires.
        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(Duration.ofMinutes(15).toMillis())
                val purged = store.purge(config.retentionHours)
                if (purged > 0) println("RACELINK: purged $purged expired session(s)")
            }
        }
    } else {
        System.err.println("RACELINK: retention is disabled (RACELINK_RETENTION_HOURS=0); sessions are kept until deleted")
    }
    println("RACELINK: retention=${config.retentionHours}h, namePolicy=${config.namePolicy}")
    if (config.adminPassword == null) {
        println(
            "RACELINK: the link index at / is served to this machine only until the tablet publishes a " +
                "session password; set RACELINK_ADMIN_PASSWORD to open it from any device regardless",
        )
    } else {
        println("RACELINK: the link index at / asks for the session password from the tablet, or RACELINK_ADMIN_PASSWORD")
    }
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module(config, store) }.start(wait = true)
}
