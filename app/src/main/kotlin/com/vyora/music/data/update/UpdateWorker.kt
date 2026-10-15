package com.vyora.music.data.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.vyora.music.data.settings.SettingsRepository
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Looks for a new release about twice a day, only on a working connection. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SettingsRepository.init(applicationContext)
        if (!SettingsRepository.current.autoUpdateCheck) return@withContext Result.success()
        if (UpdateManager.checkInBackground(applicationContext)) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "vyora-update-check"
        private const val INTERVAL_HOURS = 12L

        fun schedule(context: Context, enabled: Boolean) {
            val wm = runCatching { WorkManager.getInstance(context.applicationContext) }.getOrNull() ?: return
            if (!enabled) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
