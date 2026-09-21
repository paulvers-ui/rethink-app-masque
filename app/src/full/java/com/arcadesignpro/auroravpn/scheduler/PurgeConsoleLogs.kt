package com.arcadesignpro.auroravpn.scheduler

import Logger
import Logger.LOG_BATCH_LOGGER
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arcadesignpro.auroravpn.database.ConsoleLogRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class PurgeConsoleLogs(val context: Context, workerParameters: WorkerParameters) :
    CoroutineWorker(context, workerParameters), KoinComponent {

    private val consoleLogRepository by inject<ConsoleLogRepository>()
    companion object {
        const val MAX_TIME: Long = 3 // max time in hours to keep the console logs
    }
    override suspend fun doWork(): Result {
        // delete logs which are older than MAX_TIME hrs
        val threshold = TimeUnit.HOURS.toMillis(MAX_TIME)
        val currTime = System.currentTimeMillis()
        val time = currTime - threshold

        consoleLogRepository.deleteOldLogs(time)
        val startTime = consoleLogRepository.consoleLogStartTimestamp
        val lapsedTime = currTime - startTime
        // The level picked in Logs > App logs (VERBOSE by default) is no longer forced back to
        // ERROR here -- that check fired after 3 *minutes*, not hours -- since deleting logs
        // older than MAX_TIME hrs above already keeps the console log bounded.
        Logger.v(LOG_BATCH_LOGGER, "purged console logs older than $MAX_TIME hrs, current time: $currTime, start time: $startTime, lapsed time: $lapsedTime")
        return Result.success()
    }

}
