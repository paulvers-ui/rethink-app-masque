/*
 * Copyright 2026 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.creatore.rethinkfork.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Counts how often BraveVPNService's go2ktBounded() fail-safe deadline fires for
 * flow/inflow/preflow.
 *
 * Why this exists: the deadline itself is a backstop, not a capacity fix. If it fires
 * occasionally under a real traffic spike, that is the system working as designed --
 * but if it fires *regularly*, that is a signal the dispatcher pool (or the device, or
 * the network) is genuinely under-provisioned for the load it is seeing, and needs real
 * capacity work, not just a wider safety margin. Without a counter, that signal is
 * invisible -- the fallback firing occasionally looks identical to it firing constantly
 * from inside a single bug report. This makes the difference visible in vpnStats() and
 * in the crash/bug report bundle.
 *
 * Deliberately simple: a ConcurrentHashMap<String, AtomicLong> keyed by call name
 * ("flow", "inflow", "preflow"). No persistence -- this is in-memory, per-process,
 * reset on every VPN (re)start, which is the right scope for "is the current session
 * under stress", not a long-term analytics store.
 */
object BridgeCallTelemetry {

    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun recordTimeout(who: String) {
        counts.getOrPut(who) { AtomicLong(0) }.incrementAndGet()
    }

    /** Snapshot of current counts, for diagnostics/stats dumps. Never throws. */
    fun snapshot(): Map<String, Long> {
        return try {
            counts.mapValues { it.value.get() }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun reset() {
        counts.clear()
    }
}
