package it.poc.codexlimits

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context

object RefreshScheduler {
    private const val JOB_ID = 27065125
    private const val INTERVAL_MS = 60L * 60L * 1000L

    fun schedule(context: Context) {
        if (!SecureAuthStore.hasCredentials(context)) return

        val scheduler = context.getSystemService(JobScheduler::class.java)
        val job = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, WidexRefreshJobService::class.java)
        )
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(INTERVAL_MS)
            .build()

        scheduler.schedule(job)
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}
