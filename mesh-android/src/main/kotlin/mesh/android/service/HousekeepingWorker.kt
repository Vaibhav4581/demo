package mesh.android.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import mesh.node.MeshNode
import mesh.storage.MessageStore
import mesh.transport.Clock
import mesh.transport.SystemClock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager worker responsible for periodic maintenance:
 * 1. Purging expired messages and outbox records.
 * 2. Rotating deduplication Bloom filters to prevent long-term filter saturation.
 * 3. Ensuring [MeshService] remains running if it was killed by aggressive OS task management.
 */
class HousekeepingWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams), KoinComponent {

    companion object {
        const val WORK_NAME = "MeshHousekeepingWork"
        private const val REPEAT_INTERVAL_MINUTES = 15L

        /**
         * Enqueues periodic housekeeping to run every 15 minutes.
         */
        fun enqueuePeriodicHousekeeping(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<HousekeepingWorker>(
                REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        }
    }

    private val messageStore: MessageStore by inject()
    private val meshNode: MeshNode by inject()

    override suspend fun doWork(): Result {
        val nowMs = SystemClock.nowMs()

        // 1. Purge expired stored messages and outbox packets
        messageStore.purgeExpired(nowMs)

        // 2. Rotate dual Bloom filters to prevent saturation
        meshNode.router.dedupManager.rotatingBloom.rotate()

        // 3. Keep-alive check: if the mesh service was stopped or killed by the OS, restart it
        if (!MeshService.isServiceRunning) {
            MeshService.startService(applicationContext)
        }

        return Result.success()
    }
}
