/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.arcadesignpro.auroravpn.ui.bottomsheet

import Logger
import Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.arcadesignpro.auroravpn.R
import com.arcadesignpro.auroravpn.backup.BackupAgent
import com.arcadesignpro.auroravpn.backup.BackupHelper
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.BACKUP_FILE_EXTN
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.DATA_BUILDER_BACKUP_URI
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.DATA_BUILDER_RESTORE_URI
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.INTENT_RESTART_APP
import com.arcadesignpro.auroravpn.backup.RestoreAgent
import com.arcadesignpro.auroravpn.databinding.ActivityBackupRestoreBinding
import com.arcadesignpro.auroravpn.service.PersistentState
import com.arcadesignpro.auroravpn.util.BackupText
import com.arcadesignpro.auroravpn.util.TextTransferDialogs
import com.arcadesignpro.auroravpn.util.Themes
import com.arcadesignpro.auroravpn.util.TransferResult
import com.arcadesignpro.auroravpn.util.Utilities
import com.arcadesignpro.auroravpn.util.Utilities.delay
import com.arcadesignpro.auroravpn.util.Utilities.isAtleastQ
import com.arcadesignpro.auroravpn.util.useTransparentNoDimBackground
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

class BackupRestoreBottomSheet : BottomSheetDialogFragment() {
    // Set while a "copy as text" backup is running: the worker writes the .rbk
    // here (app-private cache), and the success handler turns it into text.
    private var pendingTextExport: File? = null

    private companion object TextTransfer {
        // App-private; never visible to other apps, cleared after each use.
        const val TEXT_TRANSFER_DIR = "text-transfer"
    }

    private var _binding: ActivityBackupRestoreBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val b
        get() = _binding!!

    val persistentState by inject<PersistentState>()

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ActivityBackupRestoreBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }
        init()
    }

    private fun init() {
        showVersion()
        b.brbsBackup.setOnClickListener { showBackupDialog() }

        b.brbsRestore.setOnClickListener { showRestoreDialog() }
    }

    private fun showVersion() {
        val version = getVersionName()
        b.brbsAppVersion.text =
            getString(R.string.about_version_install_source, version, getDownloadSource())
    }

    private fun getVersionName(): String {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(
                requireContext().packageManager,
                requireContext().packageName
            )
        return pInfo?.versionName ?: ""
    }

    private fun getDownloadSource(): String {
        if (Utilities.isFdroidFlavour()) return getString(R.string.build__flavor_fdroid)

        if (Utilities.isPlayStoreFlavour()) return getString(R.string.build__flavor_play_store)

        return getString(R.string.build__flavor_website)
    }

    private fun observeBackupWorker() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // observer for backup agent worker
        workManager.getWorkInfosByTagLiveData(BackupAgent.TAG).observe(viewLifecycleOwner) {
            workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe

            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "WorkManager state: ${workInfo.state} for ${BackupAgent.TAG}"
            )
            when (workInfo.state) {
                WorkInfo.State.SUCCEEDED -> {
                    if (pendingTextExport != null) showBackupAsText() else showBackupSuccessUi()
                    workManager.pruneWork()
                }
                WorkInfo.State.CANCELLED, WorkInfo.State.FAILED -> {
                    pendingTextExport = null
                    clearTextTransferDir()
                    showBackupFailureDialog()
                    workManager.pruneWork()
                    workManager.cancelAllWorkByTag(BackupAgent.TAG)
                }
                else -> {
                    // no-op
                }
            }
        }
    }

    private fun observeRestoreWorker() {
        val workManager = WorkManager.getInstance(requireContext().applicationContext)

        // observer for restore agent worker
        workManager.getWorkInfosByTagLiveData(RestoreAgent.TAG).observe(viewLifecycleOwner) {
            workInfoList ->
            val workInfo = workInfoList?.getOrNull(0) ?: return@observe
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "WorkManager state: ${workInfo.state} for ${RestoreAgent.TAG}"
            )
            if (WorkInfo.State.SUCCEEDED == workInfo.state) {
                clearTextTransferDir()
                showRestoreSuccessUi()
                workManager.pruneWork()
            } else if (
                WorkInfo.State.CANCELLED == workInfo.state ||
                    WorkInfo.State.FAILED == workInfo.state
            ) {
                clearTextTransferDir()
                showRestoreFailureDialog()
                workManager.pruneWork()
                workManager.cancelAllWorkByTag(RestoreAgent.TAG)
            } else { // state == blocked
                // no-op
            }
        }
    }

    private fun startRestoreProcess(fileUri: Uri?) {
        if (fileUri == null) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "uri received from activity result is null, cancel restore process"
            )
            showRestoreFailureDialog()
            return
        }

        Logger.i(LOG_TAG_BACKUP_RESTORE, "invoke worker to initiate the restore process")
        val data = Data.Builder()
        data.putString(DATA_BUILDER_RESTORE_URI, fileUri.toString())

        val importWorker =
            OneTimeWorkRequestBuilder<RestoreAgent>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(RestoreAgent.TAG)
                .build()
        WorkManager.getInstance(requireContext()).beginWith(importWorker).enqueue()
    }

    private fun startBackupProcess(backupUri: Uri?) {
        if (backupUri == null) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "uri received from activity result is null, cancel backup process"
            )
            showBackupFailureDialog()
            return
        }

        // stop vpn before beginning the backup process
        BackupHelper.stopVpn(requireContext())

        Logger.i(LOG_TAG_BACKUP_RESTORE, "invoke worker to initiate the backup process")
        val data = Data.Builder()

        data.putString(DATA_BUILDER_BACKUP_URI, backupUri.toString())
        val downloadWatcher =
            OneTimeWorkRequestBuilder<BackupAgent>()
                .setInputData(data.build())
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(BackupAgent.TAG)
                .build()
        WorkManager.getInstance(requireContext()).beginWith(downloadWatcher).enqueue()
    }

    // ── copy/paste as text (no file picker, no file left in shared storage) ──

    private fun textTransferDir(): File =
        File(requireContext().cacheDir, TEXT_TRANSFER_DIR).apply { mkdirs() }

    private fun clearTextTransferDir() {
        try {
            File(requireContext().applicationContext.cacheDir, TEXT_TRANSFER_DIR)
                .listFiles()?.forEach { it.delete() }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "text transfer cleanup failed: ${e.message}")
        }
    }

    /** Runs the normal backup into an app-private file, then shows it as text. */
    private fun backupAsText() {
        clearTextTransferDir() // leftovers from an interrupted run
        val file = File(textTransferDir(), "backup-${System.currentTimeMillis()}$BACKUP_FILE_EXTN")
        pendingTextExport = file
        startBackupProcess(Uri.fromFile(file))
    }

    private fun showBackupAsText() {
        val file = pendingTextExport ?: return
        pendingTextExport = null
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    val len = file.length()
                    if (len in 1..BackupText.MAX_BACKUP_BYTES.toLong()) BackupText.encode(file.readBytes()) else null
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_BACKUP_RESTORE, "backup as text: read failed: ${e.message}", e)
                    null
                } finally {
                    file.delete()
                }
            }
            if (!isAdded) return@launch
            if (text == null) {
                Utilities.showToastUiCentered(
                    ctx, getString(R.string.brbs_backup_text_too_large), Toast.LENGTH_LONG
                )
                return@launch
            }
            TextTransferDialogs.showExport(ctx, getString(R.string.brbs_backup_text_title), text)
        }
    }

    /** Validates pasted text fully before anything touches the restore pipeline. */
    private fun restoreFromText() {
        val ctx = context ?: return
        TextTransferDialogs.showImport(
            ctx,
            getString(R.string.brbs_restore_text_title),
            getString(R.string.brbs_restore_text_hint),
            BackupText.MAX_CLIPBOARD_CHARS
        ) { pasted ->
            when (val r = BackupText.decode(pasted)) {
                is TransferResult.Err -> TextTransferDialogs.message(ctx, r.error)
                is TransferResult.Ok -> {
                    clearTextTransferDir()
                    val file = File(textTransferDir(), "restore-${System.currentTimeMillis()}$BACKUP_FILE_EXTN")
                    try {
                        file.writeBytes(r.value)
                        observeRestoreWorker()
                        startRestoreProcess(Uri.fromFile(file))
                        null
                    } catch (e: Exception) {
                        Logger.e(LOG_TAG_BACKUP_RESTORE, "restore from text: write failed: ${e.message}", e)
                        file.delete()
                        getString(R.string.text_transfer_err_generic)
                    }
                }
            }
        }
    }

    private fun showBackupFailureDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.brbs_backup_dialog_failure_title)
        builder.setMessage(R.string.brbs_backup_dialog_failure_message)
        builder.setPositiveButton(getString(R.string.brbs_backup_dialog_failure_positive)) { _, _ ->
            backupAsText() // retry uses the text flow (no file picker)
            observeBackupWorker()
        }

        builder.setNegativeButton(getString(R.string.lbl_dismiss)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun showBackupSuccessUi() {
        Utilities.showToastUiCentered(
            requireContext(),
            getString(R.string.brbs_backup_complete_toast),
            Toast.LENGTH_SHORT
        )
    }

    private fun showRestoreSuccessUi() {
        Utilities.showToastUiCentered(
            requireContext(),
            getString(R.string.brbs_restore_complete_toast),
            Toast.LENGTH_LONG
        )
        delay(TimeUnit.MILLISECONDS.toMillis(1000), lifecycleScope) { restartApp(requireContext()) }
    }

    // do a force restart of app, sometimes the database which is restored is throws exception
    // with error code 1032 (DBMOVED), trying to change the moved database to writeable state
    // is also not helping but restarting the app does solve this issue.
    // https://issuetracker.google.com/issues/37047677
    // SQLITE_READONLY_DBMOVED error code indicates that a database cannot be modified because the
    // database file has been moved since it was opened
    private fun restartApp(context: Context) {
        val packageManager: PackageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent!!.component
        val mainIntent = Intent.makeRestartActivityTask(componentName)
        mainIntent.putExtra(INTENT_RESTART_APP, true)
        context.startActivity(mainIntent)
        Runtime.getRuntime().exit(0)
    }

    private fun showRestoreFailureDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.brbs_restore_dialog_failure_title)
        builder.setMessage(R.string.brbs_restore_dialog_failure_message)
        builder.setPositiveButton(getString(R.string.brbs_restore_dialog_failure_positive)) { _, _
            ->
            restoreFromText() // retry uses the text flow; it observes the worker itself
        }

        builder.setNegativeButton(getString(R.string.lbl_dismiss)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun showRestoreDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.brbs_restore_dialog_title)
        builder.setMessage(R.string.brbs_restore_dialog_message)
        // Text only: the app never opens the phone's files (no file picker).
        builder.setPositiveButton(getString(R.string.brbs_restore_text_btn)) { _, _ ->
            restoreFromText()
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun showBackupDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(R.string.brbs_backup_dialog_title)
        builder.setMessage(R.string.brbs_backup_dialog_message)
        // Text only: the backup becomes an AURORAVPN BACKUP block to copy.
        builder.setPositiveButton(getString(R.string.brbs_backup_text_btn)) { _, _ ->
            backupAsText()
            observeBackupWorker()
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }
}
