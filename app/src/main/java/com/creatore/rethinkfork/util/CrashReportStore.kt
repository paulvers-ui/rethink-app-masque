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

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
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
// Broad `catch` is the whole point of this class, not an oversight: every method
// here can run while the process is already dying, and a narrow catch that let an
// unanticipated Throwable escape would turn "failed to record a crash" into a
// second crash inside the crash handler. Suppressed deliberately, not narrowed.
@Suppress("TooGenericExceptionCaught")
object CrashReportStore {

    private const val CRASH_MARKER_FILE = "last_crash.txt"
    // Persists the timestamp (ApplicationExitInfo.getTimestamp()) of the last
    // native-crash exit whose report prompt was successfully shown, so
    // getHistoricalProcessExitReasons() -- which returns HISTORICAL data, not
    // just the most recent process death -- does not cause the same native
    // crash to be reported again on every subsequent launch.
    private const val NATIVE_CRASH_SEEN_FILE = "last_native_crash_seen.txt"
    private const val NATIVE_TRACE_FILE = "last_native_crash_trace.bin"
    // Only the single most recent exit reason is actually used (see
    // firstOrNull() below); asking for a few more than that is just headroom
    // in case an ANR or some other reason sits ahead of the native crash in
    // the returned list.
    private const val MAX_EXIT_REASONS_TO_INSPECT = 5

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
        } catch (_: Throwable) {
            // An unreadable/corrupt marker is indistinguishable from "no crash
            // recorded" here -- either way there is nothing to offer the user, so
            // there is nothing worth reporting about the failure itself.
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

    /**
     * A native crash (SIGABRT/SIGSEGV inside libgojni.so, for example) kills the
     * process at the OS level -- it never reaches GlobalExceptionHandler, which
     * only sees JVM Throwables. Without this, that whole class of crash is
     * completely invisible to the crash-report flow: the process just vanishes
     * and relaunches with no marker written at all. This is a real gap the
     * "About button crashes" investigation exposed once, and closes here for
     * every future native crash, not just that one.
     *
     * Uses ApplicationExitInfo (API 30+; this app's minSdk is 23, so this is a
     * best-effort enhancement, not something every install gets) to ask Android
     * directly why the process last died, rather than trying to detect this
     * indirectly. Returns a short description if a NEW native-crash exit is
     * found since the last time this was checked, or null otherwise --
     * including on API < 30, where the check is skipped entirely.
     */
    // Four independent guard clauses (SDK check, service lookup, exit-reason
    // lookup, dedup-against-last-seen) -- each is a precondition that ends the
    // function early on its own. Forcing this into a single-exit shape would
    // mean nesting all four behind one another, which reads worse, not better,
    // than the early returns it would replace.
    @Suppress("ReturnCount")
    fun pendingNativeCrash(ctx: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null

            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return null
            val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, MAX_EXIT_REASONS_TO_INSPECT)
            val nativeCrash = exits.firstOrNull {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_SIGNALED
            } ?: return null

            val seenFile = File(ctx.filesDir, NATIVE_CRASH_SEEN_FILE)
            val lastSeen = try {
                if (seenFile.exists()) seenFile.readText().trim().toLongOrNull() ?: 0L else 0L
            } catch (_: Throwable) {
                0L
            }
            if (nativeCrash.timestamp <= lastSeen) return null // already reported

            // Do not mark this exit as seen yet. The caller marks it only after
            // the report dialog successfully exists; otherwise a transient window
            // or activity failure would permanently hide the only report the user
            // can provide for this native crash.

            // Best effort: the raw trace is a tombstone protobuf on API 31+ (see
            // ApplicationExitInfo.getTraceInputStream() docs) and may be absent
            // entirely on API 30. Saved as-is, undecoded -- attaching the raw
            // bytes is still far more useful to whoever reads the report than
            // nothing, even without a protobuf schema to parse it with here.
            try {
                nativeCrash.traceInputStream?.use { input ->
                    File(ctx.filesDir, NATIVE_TRACE_FILE).outputStream().use { out ->
                        input.copyTo(out)
                    }
                }
            } catch (_: Throwable) {
                // No trace available -- the description text alone is still
                // worth surfacing.
            }

            "Native crash detected (process killed by the OS, description: " +
                "${nativeCrash.description ?: "none"}). This is almost always a " +
                "crash inside the native VPN engine (libgojni.so), not app code."
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Marks the newest native-like process exit as surfaced to the user.
     * Called only after CrashReportPrompt.show() succeeds. This keeps a
     * temporary UI failure from consuming the only native crash report.
     */
    fun markNativeCrashSeen(ctx: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val nativeCrash = am.getHistoricalProcessExitReasons(
                ctx.packageName,
                0,
                MAX_EXIT_REASONS_TO_INSPECT
            ).firstOrNull {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_SIGNALED
            } ?: return false
            File(ctx.filesDir, NATIVE_CRASH_SEEN_FILE)
                .writeText(nativeCrash.timestamp.toString())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** The raw native tombstone trace saved by pendingNativeCrash(), if any. */
    fun nativeTraceFile(ctx: Context): File? {
        val f = File(ctx.filesDir, NATIVE_TRACE_FILE)
        return if (f.exists() && f.length() > 0L) f else null
    }
}
