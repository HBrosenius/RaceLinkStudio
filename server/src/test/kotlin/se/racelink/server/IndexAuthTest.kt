package se.racelink.server

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The index login and its brute-force guard, on their own clock so the timeouts can be waited out
 * without waiting.
 */
class IndexAuthTest {
    @Test
    fun aLoginStopsWorkingAfterItsTtl() {
        var clock = Instant.parse("2026-10-16T18:00:00Z")
        val logins = IndexSessions(now = { clock })
        val token = logins.issue()

        assertTrue(logins.isValid(token))
        clock = clock.plus(Duration.ofHours(11).plusMinutes(59))
        assertTrue(logins.isValid(token), "still inside the window")
        clock = clock.plus(Duration.ofMinutes(2))
        assertFalse(logins.isValid(token), "and out of it")

        assertFalse(logins.isValid(null), "a request with no cookie is not a login")
        assertFalse(logins.isValid(""), "nor is an empty one")
        assertFalse(logins.isValid("00000000000000000000000000000000"), "nor is a guess")
    }

    @Test
    fun aRevokedLoginIsUselessImmediatelyAndForgotten() {
        val logins = IndexSessions()
        val token = logins.issue()
        val other = logins.issue()

        logins.revoke(token)
        assertFalse(logins.isValid(token), "logging out ends that login here, not just in the browser")
        assertTrue(logins.isValid(other), "and leaves the coach's other device alone")
        logins.revoke("något-vi-aldrig-gav-ut")
        logins.revoke(null)
        assertEquals(0, logins.purge(), "an unknown token is nothing to do, and nothing leaks")
    }

    @Test
    fun issuedLoginsAreRandomEnoughToBeAPasswordInThemselves() {
        val logins = IndexSessions()
        val tokens = (1..64).map { logins.issue() }

        assertEquals(64, tokens.toSet().size, "no repeats")
        assertTrue(
            tokens.all { it.length == 32 && it.all { c -> c in "0123456789abcdef" } },
            "128 bits as hex, which is what makes the cookie unguessable rather than secret",
        )
    }

    @Test
    fun expiredLoginsAreSwept() {
        var clock = Instant.parse("2026-10-16T18:00:00Z")
        val logins = IndexSessions(now = { clock }, ttl = Duration.ofHours(1))
        val token = logins.issue()

        clock = clock.plus(Duration.ofHours(2))
        assertEquals(1, logins.purge(), "the sweep drops it")
        assertFalse(logins.isValid(token))
    }

    @Test
    fun theThrottleCountsFailuresPerAddressAndForgetsThemAfterAWhile() {
        var clock = Instant.parse("2026-10-16T18:00:00Z")
        val throttle = LoginThrottle(now = { clock }, limit = 3, window = Duration.ofMinutes(5))

        assertFalse(throttle.blocked("10.0.0.1"), "a first attempt is always answered")
        repeat(2) { throttle.recordFailure("10.0.0.1") }
        assertFalse(throttle.blocked("10.0.0.1"), "two typos are still answered")
        throttle.recordFailure("10.0.0.1")
        assertTrue(throttle.blocked("10.0.0.1"))
        assertFalse(throttle.blocked("10.0.0.2"), "one device guessing must not lock out another")

        clock = clock.plus(Duration.ofMinutes(6))
        assertFalse(throttle.blocked("10.0.0.1"), "the window has passed")

        repeat(3) { throttle.recordFailure("10.0.0.1") }
        assertTrue(throttle.blocked("10.0.0.1"), "a fresh window counts from the first new failure")
        throttle.clear("10.0.0.1")
        assertFalse(throttle.blocked("10.0.0.1"), "a right password clears the count")
    }
}
