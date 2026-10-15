package com.nuvio.app.features.notifications

import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class EpisodeReleaseNotificationWorker(
    appContext: android.content.Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {

    override suspend fun doWork(): Result {
        runCatching {
            EpisodeReleaseNotificationPlatform.deliverDueAndScheduleNext(applicationContext)
        }
        return Result.success()
    }
}
