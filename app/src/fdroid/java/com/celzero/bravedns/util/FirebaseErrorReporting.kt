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
package com.celzero.bravedns.util

import Logger.LOG_FIREBASE
import org.koin.core.component.KoinComponent

/**
 * Firebase Error Reporting stub for the fdroid variant.
 * Firebase/Crashlytics is not available in fdroid builds; all methods are no-ops.
 */
object FirebaseErrorReporting : KoinComponent {

    const val TOKEN_REGENERATION_PERIOD_DAYS: Long = 45
    const val TOKEN_LENGTH = 16

    /** Always false — Crashlytics is not available in the fdroid variant. */
    const val IS_AVAILABLE = false

    fun initialize() {
        Logger.i(LOG_FIREBASE, "crashlytics not available in fdroid variant")
    }

    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun setEnabled(enabled: Boolean) {
        Logger.i(LOG_FIREBASE, "crashlytics not available in fdroid variant")
    }

    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun log(msg: String) {
        // no-op: firebase not available in fdroid variant
    }

    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun recordException(throwable: Throwable) {
        // no-op: firebase not available in fdroid variant
    }

    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun setUserId(uid: String) {
        // no-op: firebase not available in fdroid variant
    }

    @Suppress("UnusedParameter", "UNUSED_PARAMETER")
    fun setCustomKey(key: String, value: String) {
        // no-op: firebase not available in fdroid variant
    }
}
