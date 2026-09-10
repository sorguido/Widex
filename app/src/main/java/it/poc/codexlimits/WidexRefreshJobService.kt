package it.poc.codexlimits

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors

class WidexRefreshJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onStartJob(params: JobParameters?): Boolean {
        if (params == null) return false

        executor.execute {
            try {
                UsageRepository.refresh(applicationContext)
                CodexWidgetProvider.updateAll(applicationContext)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
