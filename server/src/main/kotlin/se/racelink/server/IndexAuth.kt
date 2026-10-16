package se.racelink.server

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The login sessions behind the link index. A browser that has typed the password gets back an opaque
 * random token in a cookie instead of the password itself: the password is never stored in the browser,
 * so a shared tablet does not leave it behind for the next person who opens the page, and "Logga ut"
 * can revoke the token here rather than trusting the client to forget it.
 *
 * In memory, like the projection store: a restart simply asks the coach to log in again, and nothing
 * about a login outlives the process.
 */
internal class IndexSessions(
    private val now: () -> Instant = Instant::now,
    /** A coaching evening with room to spare. The store is in memory, so a restart ends them anyway. */
    private val ttl: Duration = Duration.ofHours(12),
    private val random: SecureRandom = SecureRandom(),
) {
    private val issued = ConcurrentHashMap<String, Instant>()

    /** @return a fresh token, readable only by this server and the browser that receives it. */
    fun issue(): String {
        purge()
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = buildString(TOKEN_BYTES * 2) { bytes.forEach { append("%02x".format(it.toInt() and 0xFF)) } }
        issued[token] = now().plus(ttl)
        return token
    }

    /** @return true when [token] is a login this server handed out and has not expired or revoked. */
    fun isValid(token: String?): Boolean {
        val expiresAt = token?.let { issued[it] } ?: return false
        if (!now().isBefore(expiresAt)) {
            issued.remove(token)
            return false
        }
        return true
    }

    /** Makes one token useless immediately; an unknown token is simply nothing to do. */
    fun revoke(token: String?) {
        token?.let(issued::remove)
    }

    /** @return how many expired logins were dropped. */
    fun purge(): Int {
        val cutoff = now()
        val expired = issued.entries.filter { !cutoff.isBefore(it.value) }.map { it.key }
        expired.forEach(issued::remove)
        return expired.size
    }

    private companion object {
        /** 128 bits of entropy, which is what makes the cookie unguessable rather than secret. */
        const val TOKEN_BYTES = 16
    }
}

/**
 * A temporary refusal after repeated wrong passwords from the same address. The index password is a
 * shared secret a coach may well keep short and memorable, and the page is reachable from the venue
 * Wi-Fi, so an unthrottled form would make guessing it the cheapest way onto the page.
 *
 * A correct password is never refused (see the login route): waiting out a flood would only punish the
 * coach for someone else's guessing.
 */
internal class LoginThrottle(
    private val now: () -> Instant = Instant::now,
    private val limit: Int = 8,
    private val window: Duration = Duration.ofMinutes(5),
) {
    private class Failures(val count: Int, val first: Instant)

    private val byAddress = ConcurrentHashMap<String, Failures>()

    /** @return true when this address has already failed [limit] times inside the window. */
    fun blocked(address: String): Boolean {
        val failures = byAddress[address] ?: return false
        if (expired(failures)) {
            byAddress.remove(address)
            return false
        }
        return failures.count >= limit
    }

    fun recordFailure(address: String) {
        byAddress.compute(address) { _, existing ->
            if (existing == null || expired(existing)) Failures(1, now())
            else Failures(existing.count + 1, existing.first)
        }
    }

    /** Forgets an address: called when a password was accepted, so a typo does not count against it. */
    fun clear(address: String) {
        byAddress.remove(address)
    }

    /** @return how many addresses were forgotten. */
    fun purge(): Int {
        val stale = byAddress.entries.filter { expired(it.value) }.map { it.key }
        stale.forEach(byAddress::remove)
        return stale.size
    }

    private fun expired(failures: Failures) = !now().isBefore(failures.first.plus(window))
}
