package se.racelink.studio

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

internal fun hasNewCompletedResult(before: Training, after: Training): Boolean {
    if (before.sessionId != after.sessionId) return false
    val known = before.attempts.map { it.id }.toSet()
    return after.attempts.any { it.id !in known && it.complete && it.durationMs != null }
}

internal class ResultSound(private val context: Context) {
    private var tone: ToneGenerator? = null
    private val handler = Handler(Looper.getMainLooper())
    private val release = Runnable { tone?.release(); tone = null }
    fun play(): Boolean {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) == 0 ||
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return false
        close()
        return runCatching {
            tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            val started = tone!!.startTone(ToneGenerator.TONE_PROP_ACK, 180)
            handler.postDelayed(release, 250)
            started
        }.getOrElse { close(); false }
    }
    fun close() { handler.removeCallbacks(release); release.run() }
}
