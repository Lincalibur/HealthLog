package com.liamo.f1.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.liamo.f1.F1App
import com.liamo.f1.ble.ConnectFailed
import com.liamo.f1.ble.WatchService
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Periodic snapshot: connect, battery + steps, one short heart-rate sample, then the
 * experimental SpO2 reading and history sync, and disconnect. A failed connect backs
 * off 1, 5 then 15 minutes instead of retrying in a tight loop.
 */
class SnapshotWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as F1App
        // The live heart-rate service already holds the watch; don't interrupt it.
        if (WatchService.running.value) return Result.success()
        try {
            app.session.withConnection {
                app.ops.onConnected()
                app.ops.readSteps()
                app.ops.heartRateSample()
                if (app.settings.state.value.experimentalInSnapshot) {
                    app.ops.spotSpO2()
                    app.ops.syncHistory()
                }
            }
        } catch (e: ConnectFailed) {
            Schedules.retrySnapshot(applicationContext, app.store.state().connectFailures)
        }
        app.store.pruneExported()
        return Result.success()
    }
}

/** Phone -> GitHub. Every 6 hours on Wi-Fi, or straight away from the Sync button. */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as F1App
        val s = app.settings.state.value
        val token = app.settings.token
        if (!s.gitHubReady || token == null) {
            app.store.updateState { it.copy(lastExportError = "Add the GitHub token, owner and repo in Settings.") }
            return Result.failure()
        }
        return try {
            val result = GitHubSync(app.store, GitHubClient(s.owner, s.repo, token)).run()
            Result.success(workDataOf(KEY_MESSAGE to result.message))
        } catch (e: IOException) {
            // Offline or dropped mid-sync: nothing was marked exported; try again later.
            app.store.updateState { it.copy(lastExportError = "Network: ${e.message ?: e.javaClass.simpleName}") }
            Result.retry()
        } catch (e: Exception) {
            app.store.updateState { it.copy(lastExportError = e.message ?: e.javaClass.simpleName) }
            Result.failure()
        }
    }

    companion object {
        const val KEY_MESSAGE = "message"
    }
}

object Schedules {
    private const val SNAPSHOT = "snapshot"
    private const val SNAPSHOT_NOW = "snapshot-now"
    private const val SNAPSHOT_RETRY = "snapshot-retry"
    private const val EXPORT = "export"
    const val EXPORT_NOW = "export-now"

    private val BACKOFF_MINUTES = listOf(1L, 5L, 15L)

    fun apply(context: Context, on: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!on) {
            wm.cancelUniqueWork(SNAPSHOT)
            wm.cancelUniqueWork(EXPORT)
            return
        }
        wm.enqueueUniquePeriodicWork(
            SNAPSHOT, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SnapshotWorker>(2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build(),
        )
        wm.enqueueUniquePeriodicWork(
            EXPORT, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ExportWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build(),
        )
    }

    fun snapshotNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SNAPSHOT_NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SnapshotWorker>().build(),
        )
    }

    /** After the 1st, 2nd and 3rd failure in a row; then wait for the next periodic run. */
    fun retrySnapshot(context: Context, failures: Int) {
        val minutes = BACKOFF_MINUTES.getOrNull(failures - 1) ?: return
        WorkManager.getInstance(context).enqueueUniqueWork(
            SNAPSHOT_RETRY, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SnapshotWorker>().setInitialDelay(minutes, TimeUnit.MINUTES).build(),
        )
    }

    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            EXPORT_NOW, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ExportWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }
}
