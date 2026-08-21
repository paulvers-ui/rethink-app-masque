package com.celzero.bravedns.iab

import Logger
import Logger.LOG_IAB
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent

class SubscriptionCheckWorker(
    val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    companion object {
        const val WORK_NAME = "SubscriptionCheckWorker"
        private const val MAX_REINITIATE_ATTEMPTS = 3
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                initiate()
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$WORK_NAME; failed: ${e.message ?: "unknown error"}")
                Result.retry()
            }
        }
    }

    private fun initiate() {
        // implement check for stripe subscription
    }
}
