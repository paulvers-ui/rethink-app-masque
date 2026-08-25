/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.creatore.rethinkfork.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.creatore.rethinkfork.database.AppInfoRepository
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// @RunWith is required: ApplicationProvider.getApplicationContext() needs Robolectric's
// Android shadow environment; without it the call throws IllegalStateException
// ("No instrumentation registered!").
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class TempAllowExpiryWorkerTest {

    private lateinit var context: Context

    // Declared as a class-level val, same fix pattern already applied in FirewallManagerTest.
    // TempAllowExpiryWorker.db is `by inject<AppInfoRepository>()` on a companion object —
    // Koin's inject() delegate is `lazy`, so it resolves ONCE per JVM and is cached for the
    // rest of the test run, regardless of how many times startKoin()/stopKoin() cycle.
    // Creating a fresh local `mockk<AppInfoRepository>()` inside each @Test method (the
    // previous approach) meant only whichever test happened to run FIRST actually controlled
    // `db` — every other test's stub was silently ignored, since re-injecting doesn't
    // re-resolve an already-initialized lazy. That's what caused this test to intermittently
    // see the OTHER test's expiry value and enqueue work instead of cancelling it (or vice
    // versa), depending on JUnit's method execution order.
    // Reusing the same mock instance across all test methods and re-stubbing its answers
    // sidesteps the caching entirely, since `db` only needs to resolve to this instance once.
    private val mockRepo: AppInfoRepository = mockk(relaxed = true)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        clearMocks(mockRepo, answers = false, recordedCalls = true, childMocks = false)

        // Stop any Koin instance (possibly started by Application.onCreate or a prior test)
        try { stopKoin() } catch (_: Exception) { /* ignore if not running */ }
        startKoin { modules(module { single<AppInfoRepository> { mockRepo } }) }

        // Initialize WorkManager for testing. WorkManagerTestInitHelper.initializeTestWorkManager
        // stores the instance in a static field, so a second call within the same JVM (i.e. the
        // second test method in this class) throws IllegalStateException "already initialized".
        // We catch that and fall through — the existing instance is reused.
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
        } catch (_: IllegalStateException) {
            // Already initialised by a previous test method in this class — that is fine.
        }
        // Always cancel any work left over from a previous test method so each test starts
        // with a clean WorkManager queue, regardless of execution order.
        try {
            WorkManager.getInstance(context).cancelAllWork().result.get()
        } catch (_: Exception) { /* ignore */ }
    }

    @After
    fun tearDown() {
        try { stopKoin() } catch (_: Exception) { /* ignore if not running */ }
        unmockkAll()
    }

    @Test
    fun `scheduleNext cancels unique work when repo returns null`() {
        every { mockRepo.getNearestTempAllowExpiryBlocking(any()) } returns null

        TempAllowExpiryWorker.scheduleNext(context)

        // Verify: cancelUniqueWork was called — no work should remain enqueued
        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork("fw_temp_allow_expiry").get()
        assertTrue(
            "Expected no enqueued work after cancel, got: $infos",
            infos.isEmpty() || infos.all { it.state == WorkInfo.State.CANCELLED }
        )
    }

    @Test
    fun `scheduleNext enqueues work when repo returns future expiry`() {
        val now = System.currentTimeMillis()
        every { mockRepo.getNearestTempAllowExpiryBlocking(any()) } returns (now + 60_000L)

        TempAllowExpiryWorker.scheduleNext(context)

        // Verify: enqueueUniqueWork was called — work should be ENQUEUED
        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork("fw_temp_allow_expiry").get()
        assertTrue("Expected exactly one enqueued work request", infos.size == 1)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
    }
}
