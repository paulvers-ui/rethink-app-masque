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
package com.arcadesignpro.auroravpn.util

import Logger
import Logger.LOG_TAG_APP
import android.content.Context
import android.os.Looper
import com.arcadesignpro.auroravpn.scheduler.EnhancedBugReport
import com.arcadesignpro.auroravpn.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.lang.ref.WeakReference
import kotlin.system.exitProcess

/**
 * Global uncaught exception handler.
 *
 * CARRIER-GRADE HARDENING (tests-lightver):
 *   In this fork the app runs on always-on / block-without-VPN devices where a
 *   process death means the whole subscriber loses connectivity ("state
 *   blackout"). A single malformed DNS answer, a Firestack JNI callback that
 *   raises, or an unexpected coroutine failure on a worker thread would
 *   previously bring down the entire process because the default Android
 *   handler calls Process.killProcess().
 *
 *   Policy:
 *     - MAIN THREAD or java.lang.Error (OOM / StackOverflow / LinkageError):
 *         propagate to the platform default handler. Swallowing an Error would
 *         leave the JVM in an unrecoverable state; a hard-locked main thread
 *         cannot serve VpnService callbacks anyway, so a fast restart via
 *         START_STICKY is preferable.
 *     - ANY OTHER background thread throwing a plain Exception:
 *         log + report + SWALLOW. The offending thread has already terminated
 *         by the time this handler returns; the VPN foreground service and its
 *         sibling threads keep running so the always-on tunnel survives the
 *         "death packet".
 *
 *   Do NOT weaken this behaviour without understanding the uptime requirement.
 */
class GlobalExceptionHandler private constructor(
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    contextRef: Context?
) : Thread.UncaughtExceptionHandler, KoinComponent {

    private val contextRef: WeakReference<Context>? = contextRef?.let { WeakReference(it) }
    private val persistentState by inject<PersistentState>()
    companion object {
        private var instance: GlobalExceptionHandler? = null

        fun initialize(ctx: Context) {
            if (instance != null) {
                Logger.w(LOG_TAG_APP, "err-handler already initialized")
                return
            }

            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            instance = GlobalExceptionHandler(defaultHandler, ctx.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(instance)

            Logger.i(LOG_TAG_APP, "err-handler initialized (carrier-grade mode)")
        }

        fun getInstance(): GlobalExceptionHandler? = instance
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val isMainThread = try {
            Looper.getMainLooper().thread === thread
        } catch (_: Throwable) {
            // if we cannot even query the main looper, default to "unsafe to swallow"
            true
        }
        // Error subclasses (OOM, StackOverflow, LinkageError, ...) leave the JVM in
        // an undefined state — never swallow them.
        val isFatalError = throwable is Error

        try {
            val exception = Logger.throwableToException(throwable)
            Logger.e(
                LOG_TAG_APP,
                "uncaught in thread='${thread.name}' main=$isMainThread fatal=$isFatalError",
                exception
            )
            reportToFirebase(exception)
            logExceptionContext(thread, exception)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err while handling uncaught exception", e)
        }

        if (isMainThread || isFatalError) {
            // Let the platform tear the process down cleanly; Android will restart
            // the VpnService via START_STICKY.
            try {
                defaultHandler?.uncaughtException(thread, throwable) ?: run {
                    Logger.e(LOG_TAG_APP, "no default exception handler; terminating")
                    exitProcess(1)
                }
            } catch (t: Throwable) {
                Logger.e(LOG_TAG_APP, "default handler itself threw; forcing exit", null)
                exitProcess(1)
            }
        } else {
            // Non-main thread, non-Error: keep the process alive so the VPN tunnel
            // and firewall stay up. The offending thread has already died.
            Logger.w(
                LOG_TAG_APP,
                "AUDIT: swallowed background-thread crash to preserve VPN uptime " +
                    "(thread='${thread.name}', ${throwable.javaClass.simpleName}: ${throwable.message})"
            )
        }
    }

    /**
     * Report the uncaught exception to Firebase
     */
    private fun reportToFirebase(exception: Exception) {
        try {
            FirebaseErrorReporting.recordException(exception)
        } catch (e: Exception) {
            // Firebase might not be available in all build variants (e.g., fdroid)
            Logger.w(LOG_TAG_APP, "crashlytics reporting not available: ${e.message}")
        }
    }

    /**
     * Log additional context information about the exception
     */
    private fun logExceptionContext(thread: Thread, exception: Throwable) {
        try {
            val stackTrace = exception.stackTraceToString()
            @Suppress("DEPRECATION")
            val threadInfo = "Thread: ${thread.name} (ID: ${thread.id}, State: ${thread.state})"

            val stringBuilder = StringBuilder()
            stringBuilder.appendLine("---Uncaught Exception ${thread.name}---")
            stringBuilder.appendLine("Exception Type: ${exception.javaClass.name}")
            stringBuilder.appendLine("Exception Message: ${exception.message}")
            stringBuilder.appendLine(threadInfo)
            stringBuilder.appendLine("Stack Trace:")
            stringBuilder.appendLine(stackTrace)
            stringBuilder.appendLine("--------------------------------------------")
            val msg = stringBuilder.toString()

            // Log cause chain if available
            var cause = exception.cause
            var causeLevel = 1
            while (cause != null && causeLevel <= 5) { // Limit to 5 levels to avoid infinite loops
                Logger.e(LOG_TAG_APP, "caused by (level $causeLevel): ${cause.javaClass.name}: ${cause.message}")
                cause = cause.cause
                causeLevel++
            }
            val ex = Logger.throwableToException(exception)
            Logger.crash(LOG_TAG_APP, msg, ex)

            // Try to write logs to file with context
            writeLogsToFileWithFallback(msg)

            // Record a marker so the next launch can offer to send this report.
            // Firebase is the only other notification path here and it does not
            // exist in the fdroid flavor, so without this a crash on a
            // F-Droid/GitHub build is invisible to both user and maintainer.
            contextRef?.get()?.let { ctx -> CrashReportStore.save(ctx, msg) }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err while logging exception context", e)
        }
    }

    /**
     * Attempt to write logs to file with fallback options when context is unavailable
     */
    private fun writeLogsToFileWithFallback(msg: String) {
        try {
            // First try: get context from WeakReference
            val context = contextRef?.get()
            if (context != null) {
                val token = persistentState.firebaseUserToken
                EnhancedBugReport.writeLogsToFile(context, token,msg)
                Logger.i(LOG_TAG_APP, "crash logs written to file successfully")
                return
            }

            // Fallback: log warning and ensure the crash info is at least logged
            Logger.w(LOG_TAG_APP, "context is null or has been garbage collected during crash handling")
            Logger.w(LOG_TAG_APP, "attempting to preserve crash info in system logs")

            // Additional fallback: try to write to standard error as last resort
            try {
                System.err.println("=== CRITICAL CRASH INFO (Context Unavailable) ===")
                System.err.println(msg)
                System.err.println("=== END CRITICAL CRASH INFO ===")
            } catch (e: Exception) {
                Logger.e(LOG_TAG_APP, "failed to write crash info to stderr", e)
            }

        } catch (e: Exception) {
            Logger.e(LOG_TAG_APP, "err in writeLogsToFileWithFallback", e)
        }
    }
}
