package se.racelink.studio

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Schedules a network-constrained background retry for an outstanding publish, so a result captured
 * during an outage can still reach the phone after the app is closed. Uses the platform
 * JobScheduler (always available at minSdk 26, so no extra dependency); one job id coalesces
 * repeated failures so the exponential backoff is not reset on every commit.
 *
 * The job is **expedited** on API 31+, because a plain no-deadline job is deferrable and the
 * platform may leave it queued long after its constraints are met (observed sitting runnable for
 * minutes) — which would make the closed-app retry useless. Expedited and persisted are mutually
 * exclusive, so the job does not survive a reboot; that is fine, because the publisher also sends
 * the current snapshot on every app start. On API < 31 this is an ordinary deferrable job.
 */
internal object PublishScheduler {
    private const val TAG = "RaceLinkPublish"
    private const val JOB_ID = 0x52414C31 // "RAL1"

    fun enqueue(context: Context) {
        // Guard so an unusual host (e.g. a preview) cannot crash the editor, but always report the
        // outcome: a silently dropped retry would be worse than the missing opt-in publish.
        try {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler == null) {
                Log.w(TAG, "no JobScheduler service on this device")
                return
            }
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, PublishJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setExpedited(true) }
                .build()
            Log.i(TAG, "scheduled retry job id=$JOB_ID result=${scheduler.schedule(job)}")
        } catch (t: Throwable) {
            Log.w(TAG, "could not schedule retry job", t)
        }
    }
}
