/*
 * Copyright 2026 AuroraVPN contributors
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
package com.arcadesignpro.auroravpn.service

import Logger
import Logger.LOG_TAG_DNS
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.arcadesignpro.auroravpn.database.EventSource
import com.arcadesignpro.auroravpn.database.EventType
import com.celzero.firestack.intra.DNSSECValidator
import com.celzero.firestack.intra.Intra
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drives the local DNSSEC validator in firestack (intra/dnssecv) and exposes
 * its health to the UI.
 *
 * The validator resolves from the root servers and checks every signature up
 * to the IANA root keys; it never trusts an upstream AD bit. The self-test
 * passes only if a signed zone validates AND a deliberately broken zone is
 * rejected, so "validating" is evidence, not an assumption.
 *
 * Privacy: self-test queries go out in plaintext on UDP/TCP 53 from the
 * device's real IP, outside DoH/WARP (sockets are protected from the VPN).
 * They only ever name the fixed probe zones, never user domains.
 */
object DnssecManager : KoinComponent {

    enum class Health { UNKNOWN, CHECKING, VALIDATING, DEGRADED, UNREACHABLE, BROKEN }

    data class State(
        val health: Health,
        val summary: String = "",
        val checkedAtElapsedMs: Long = 0L,
        val durationMs: Long = 0L
    )

    private const val TAG = "DnssecManager"
    private const val MIN_INTERVAL_MS = 10 * 60 * 1000L // don't re-probe more than every 10 min
    private const val SELF_TEST_TIMEOUT_MS = 15_000L

    private val _state = MutableLiveData(State(Health.UNKNOWN))
    val state: LiveData<State> = _state

    private val eventLogger by inject<EventLogger>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = Mutex()

    // Strong Java-side owners for every Go-backed object we hand across the
    // boundary (see the gomobile refnum note in app/build.gradle).
    @Volatile private var validator: DNSSECValidator? = null
    @Volatile private var controller: ProbeController? = null
    @Volatile private var lastRunElapsedMs = 0L

    /** Runs the self-test unless one ran recently; [force] skips the rate limit. */
    fun refresh(force: Boolean = false) {
        scope.launch { runSelfTest(force) }
    }

    /** Call from the VPN service when the underlying network changes. */
    fun onNetworkChanged() {
        try {
            validator?.onNetworkChanged()
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DNS, "$TAG: onNetworkChanged failed: ${e.message}")
        }
        lastRunElapsedMs = 0L // next refresh() re-probes on the new path
    }

    private fun runSelfTest(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastRunElapsedMs != 0L && now - lastRunElapsedMs < MIN_INTERVAL_MS) return
        // One self-test at a time; extra taps are dropped, not queued.
        if (!running.tryLock()) return
        try {
            _state.postValue(State(Health.CHECKING))
            val v = obtainValidator()
            val report = v.selfTest(SELF_TEST_TIMEOUT_MS)
            val health = mapHealth(report.health)
            lastRunElapsedMs = SystemClock.elapsedRealtime()
            _state.postValue(State(health, report.summary, lastRunElapsedMs, report.durationMs))

            if (health == Health.BROKEN) {
                // The validator accepted forged data. Surface loudly; never green.
                eventLogger.logCritical(
                    EventType.DNS_FAIL,
                    "DNSSEC self-test BROKEN",
                    EventSource.DNS,
                    "Validator accepted a deliberately broken zone: ${report.summary}"
                )
            }
            Logger.i(LOG_TAG_DNS, "$TAG: self-test ${report.summary} in ${report.durationMs}ms")
        } catch (e: Exception) {
            // Fail closed: an error is "unknown", never "validating".
            Logger.w(LOG_TAG_DNS, "$TAG: self-test error: ${e.message}")
            _state.postValue(State(Health.UNKNOWN, e.message ?: ""))
        } finally {
            running.unlock()
        }
    }

    private fun obtainValidator(): DNSSECValidator {
        // ProbeController(-1, null) protects each socket via
        // VpnService.protect() so queries bypass the tunnel and its DNS trap.
        // Re-passing it on every run keeps the protector current across
        // VPN service restarts (Go swaps it atomically).
        val ctl = ProbeController(-1L, null).also { controller = it }
        val existing = validator
        if (existing != null) {
            existing.setController(ctl)
            return existing
        }
        return Intra.newDNSSECValidator(ctl).also { validator = it }
    }

    private fun mapHealth(h: Int): Health =
        when (h) {
            Intra.DNSSECHealthValidating -> Health.VALIDATING
            Intra.DNSSECHealthDegraded -> Health.DEGRADED
            Intra.DNSSECHealthUnreachable -> Health.UNREACHABLE
            Intra.DNSSECHealthBroken -> Health.BROKEN
            else -> Health.UNKNOWN
        }
}
