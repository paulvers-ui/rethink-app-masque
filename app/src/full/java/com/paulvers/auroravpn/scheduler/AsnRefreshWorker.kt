/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.paulvers.auroravpn.scheduler

import Logger
import Logger.LOG_TAG_DOWNLOAD
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
import com.paulvers.auroravpn.customdownloader.RipeStatDownloader
import com.paulvers.auroravpn.service.PersistentState
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Refreshes the blocked-network (ASN) prefixes for the Countries & networks
 * firewall from RIPEstat: when the user edits the list, at app start, and
 * weekly. The result feeds AsnRulesManager and is cached to disk.
 */
class AsnRefreshWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params), KoinComponent {

    private val persistentState by inject<PersistentState>()

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            RipeStatDownloader.refreshFromSettings(applicationContext, persistentState.blockedAsns)
            Result.success()
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DOWNLOAD, "asn refresh failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val ONE_TIME_TAG = "AsnRefreshNow"
        private const val PERIODIC_TAG = "AsnRefreshWeekly"
        private const val REFRESH_INTERVAL_DAYS = 7L

        private val networkConstraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Refresh now (e.g. the list changed or the app started). */
        fun enqueueNow(context: Context) {
            val req =
                OneTimeWorkRequestBuilder<AsnRefreshWorker>()
                    .setConstraints(networkConstraints)
                    .addTag(ONE_TIME_TAG)
                    .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(ONE_TIME_TAG, ExistingWorkPolicy.REPLACE, req)
        }

        /** Keep the prefixes fresh weekly. */
        fun schedulePeriodic(context: Context) {
            val req =
                PeriodicWorkRequestBuilder<AsnRefreshWorker>(REFRESH_INTERVAL_DAYS, TimeUnit.DAYS)
                    .setConstraints(networkConstraints)
                    .addTag(PERIODIC_TAG)
                    .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    PERIODIC_TAG,
                    ExistingPeriodicWorkPolicy.KEEP,
                    req
                )
        }
    }
}
