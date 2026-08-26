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
package com.creatore.rethinkfork.ui

import Logger
import Logger.LOG_TAG_UI
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.creatore.rethinkfork.R
import com.creatore.rethinkfork.scheduler.BugReportZipper
import com.creatore.rethinkfork.scheduler.EnhancedBugReport
import com.creatore.rethinkfork.util.CrashReportStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

/**
 * On launch, if the previous run died from an uncaught exception, tell the user
 * and offer to hand the crash report off somewhere useful.
 *
 * Uses a share chooser (ACTION_SEND) rather than a hardcoded mailto. The
 * about_mail_to string is upstream's address (hello@celzero.com) -- routing this
 * fork's crashes into the upstream maintainers' inbox would be both useless to
 * them and rude, so the destination is left to the user: their own mail app, an
 * issue tracker, a messaging app, wherever.
 */
object CrashReportPrompt {

    private const val TAG = "CrashPrompt"

    // Guards against re-showing within a single process: onResume fires on every
    // return to the foreground, and the marker is only cleared once the user
    // actually answers the dialog.
    @Volatile private var shownThisProcess = false

    /**
     * Shows the prompt if a crash was recorded and we have not already asked in
     * this process. Safe to call from onResume. Never throws -- a failure here
     * must not take down the activity that is trying to report a crash.
     */
    fun maybeShow(activity: Activity) {
        try {
            if (shownThisProcess) return
            if (activity.isFinishing || activity.isDestroyed) return

            val summary = CrashReportStore.pending(activity) ?: return
            shownThisProcess = true

            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.crash_report_title)
                .setMessage(activity.getString(R.string.crash_report_desc, firstLines(summary)))
                .setCancelable(true)
                .setPositiveButton(R.string.crash_report_send) { d, _ ->
                    CrashReportStore.clear(activity)
                    d.dismiss()
                    share(activity, summary)
                }
                .setNegativeButton(R.string.crash_report_dismiss) { d, _ ->
                    // Clear on explicit dismiss so the user is not nagged about a
                    // crash they have already chosen to ignore.
                    CrashReportStore.clear(activity)
                    d.dismiss()
                }
                .show()
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG could not show crash prompt: ${e.message}")
        }
    }

    /** The head of the trace is what identifies the crash; the rest goes in the share body. */
    private fun firstLines(summary: String, max: Int = 6): String {
        return summary.lineSequence().take(max).joinToString("\n").trim()
    }

    private fun share(activity: Activity, summary: String) {
        try {
            val uris = arrayListOf<Uri>()

            // Attach the existing bug-report zip and tombstone when present -- they
            // carry far more context than the marker alone. Both are optional: a
            // text-only report is still worth sending, so a missing file must not
            // block the share.
            attachIfPresent(activity, File(BugReportZipper.getZipFileName(activity.filesDir)))?.let { uris.add(it) }
            try {
                EnhancedBugReport.getTombstoneZipFile(activity)?.let { tomb ->
                    attachIfPresent(activity, tomb)?.let { uris.add(it) }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG no tombstone file: ${e.message}")
            }

            val body = buildString {
                appendLine(activity.getString(R.string.crash_report_share_intro))
                appendLine()
                appendLine("app: ${activity.packageName}")
                appendLine("android: ${android.os.Build.VERSION.SDK_INT}")
                appendLine("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("abi: ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
                appendLine()
                append(summary)
            }

            val intent =
                if (uris.isEmpty()) {
                    Intent(Intent.ACTION_SEND).apply { type = "text/plain" }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "text/plain"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                        // Some receivers read clipData rather than EXTRA_STREAM; set both
                        // and grant read permission or the attachment arrives unreadable.
                        clipData = ClipData.newUri(activity.contentResolver, "crash", uris[0]).also { cd ->
                            for (i in 1 until uris.size) cd.addItem(ClipData.Item(uris[i]))
                        }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }

            intent.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.crash_report_share_subject))
            intent.putExtra(Intent.EXTRA_TEXT, body)

            activity.startActivity(
                Intent.createChooser(intent, activity.getString(R.string.crash_report_send))
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG failed to share crash report: ${e.message}", e)
        }
    }

    private fun attachIfPresent(activity: Activity, file: File): Uri? {
        return try {
            if (!file.isFile || !file.exists() || file.length() == 0L) return null
            FileProvider.getUriForFile(
                activity.applicationContext,
                BugReportZipper.FILE_PROVIDER_NAME,
                file
            )
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG could not attach ${file.name}: ${e.message}")
            null
        }
    }
}
