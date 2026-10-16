package se.racelink.studio

import android.app.job.JobParameters
import android.app.job.JobService

/**
 * Flushes an outstanding publish while the app is closed, so a result captured during an outage
 * still reaches the skier's phone. Read-only with respect to capture: it only talks to the network
 * and the publish journal. Rescheduled with backoff only while the marker stays pending.
 */
internal class PublishJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            val settings = PublishConfigStore(applicationContext)
            val journal = PublishStateStore(applicationContext)
            val store = TrainingStore(applicationContext)
            val runner = PublishRetryRunner(Publisher(settings, journal, HttpPublishTransport()), settings, journal) { store.read() }
            jobFinished(params, runner.run())
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
