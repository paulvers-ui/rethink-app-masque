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

import android.content.Context
import java.io.File

/**
 * Persists a one-line-ish summary of the last uncaught crash so the app can
 * offer to send a report on the NEXT launch.
 *
 * Why this exists: GlobalExceptionHandler already writes full crash context to
 * the bug-report file, and calls reportToFirebase(). But Firebase/Crashlytics is
 * only a dependency of the `website` and `play` product flavors -- the `fdroid`
 * flavor has none, so reportToFirebase() catches its own NoClassDefFoundError
 * and gives up quietly. On an F-Droid/GitHub build, a crash therefore leaves no
 * signal the user ever sees, and no way for them to hand it to a maintainer.
 * That is exactly how an "About button crashes the app" report ends up with no
 * usable stack trace attached.
 *
 * Deliberately dependency-free (plain java.io, no Koin, no Logger, no coroutines):
 * every method here can run while the process is already dying from an uncaught
 * throwable, where any additional class-loading or injection could itself fail.
 * Nothing here throws -- a failure to record a crash must never become a second
 * crash inside the crash handler.
 */
object CrashReportStore {

    private const val CRASH_MARKER_FILE = "last_crash.txt"

    // A stack trace is the useful part and is rarely large, but a pathological
    // cause-chain could be. Cap it so the marker can never grow unbounded on a
    // crash-loop; the full detail is in the bug-report file regardless.
    private const val MAX_MARKER_CHARS = 64 * 1024

    private fun markerFile(ctx: Context): File = File(ctx.filesDir, CRASH_MARKER_FILE)

    /** Called from the uncaught-exception handler. Must never throw. */
    fun save(ctx: Context, summary: String) {
        try {
            val text =
                if (summary.length > MAX_MARKER_CHARS) {
                    summary.substring(0, MAX_MARKER_CHARS) + "\n[truncated]"
                } else {
                    summary
                }
            markerFile(ctx).writeText(text)
        } catch (t: Throwable) {
            // Last resort only. Do not use Logger here: this runs mid-crash and
            // Logger may itself depend on state that is already unusable.
            try {
                System.err.println("CrashReportStore: could not persist marker: ${t.message}")
            } catch (_: Throwable) {
                // give up silently -- never crash the crash handler
            }
        }
    }

    /** Returns the saved crash summary, or null if the last run exited cleanly. */
    fun pending(ctx: Context): String? {
        return try {
            val f = markerFile(ctx)
            if (!f.exists() || f.length() == 0L) null else f.readText()
        } catch (t: Throwable) {
            null
        }
    }

    /** Clears the marker so the user is not prompted about the same crash twice. */
    fun clear(ctx: Context) {
        try {
            markerFile(ctx).delete()
        } catch (_: Throwable) {
            // non-fatal: worst case the user sees the prompt once more
        }
    }
}
