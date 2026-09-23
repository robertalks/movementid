package com.movementid.app.backup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.movementid.app.settings.SettingsRepository
import java.util.concurrent.TimeUnit

/**
 * Runs the backup in the background.
 *
 * WorkManager rather than a coroutine in the ViewModel, because the whole point is that it
 * happens without the user thinking about it: it survives the app being closed, waits for the
 * network condition to be met, and retries on failure. A backup that only runs while the user
 * watches is a manual backup with extra steps.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        if (!settings.getBackupEnabled() || !settings.backupConfigured()) {
            return Result.success()
        }

        return when (val outcome = BackupManager(applicationContext).runBackup()) {
            is BackupOutcome.Uploaded -> {
                Log.i(TAG, "Backed up ${outcome.fileName}")
                Result.success()
            }

            is BackupOutcome.NothingToDo -> Result.success()

            is BackupOutcome.SavedLocally -> {
                // The archive is safe on disk; ask WorkManager to try the upload again later
                // rather than treating a flaky connection as a lost backup.
                Log.w(TAG, "Upload deferred: ${outcome.reason}")
                Result.retry()
            }

            is BackupOutcome.Failed -> {
                Log.w(TAG, "Backup failed: ${outcome.reason}")
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "MovementID/Backup"
        private const val PERIODIC = "movementid-backup-periodic"
        private const val ONE_OFF = "movementid-backup-now"

        private fun constraints(wifiOnly: Boolean) = Constraints.Builder()
            .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        /**
         * Daily safety net. Called on app start and whenever backup settings change, so the
         * schedule always reflects the current WiFi-only choice.
         */
        fun schedule(context: Context) {
            val settings = SettingsRepository(context)
            val manager = WorkManager.getInstance(context)

            if (!settings.getBackupEnabled()) {
                manager.cancelUniqueWork(PERIODIC)
                return
            }

            val request = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints(settings.getBackupWifiOnly()))
                .build()

            // REPLACE, so a change to the WiFi-only setting takes effect rather than leaving
            // the old constraint in place.
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        /** Nudged after a save, so a new movement isn't left unprotected until tomorrow. */
        fun backupSoon(context: Context) {
            val settings = SettingsRepository(context)
            if (!settings.getBackupEnabled() || !settings.backupConfigured()) return

            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(constraints(settings.getBackupWifiOnly()))
                .setInitialDelay(5, TimeUnit.MINUTES)   // batch a burst of edits into one upload
                .build()

            // KEEP: several saves in a row should produce one backup, not one each.
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.KEEP, request)
        }

        /** The user pressing "Back up now" — no delay, and no WiFi constraint. */
        fun backupNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(ONE_OFF, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
