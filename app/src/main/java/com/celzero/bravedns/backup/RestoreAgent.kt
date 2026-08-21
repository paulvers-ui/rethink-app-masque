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
package com.celzero.bravedns.backup

import Logger
import Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.net.Uri
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celzero.bravedns.backup.BackupHelper.Companion.DATA_BUILDER_RESTORE_URI
import com.celzero.bravedns.backup.BackupHelper.Companion.METADATA_FILENAME
import com.celzero.bravedns.backup.BackupHelper.Companion.SHARED_PREFS_BACKUP_FILE_NAME
import com.celzero.bravedns.backup.BackupHelper.Companion.VERSION
import com.celzero.bravedns.backup.BackupHelper.Companion.deleteResidue
import com.celzero.bravedns.backup.BackupHelper.Companion.getTempDir
import com.celzero.bravedns.backup.BackupHelper.Companion.startVpn
import com.celzero.bravedns.backup.BackupHelper.Companion.stopVpn
import com.celzero.bravedns.backup.BackupHelper.Companion.unzip
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.LogDatabase
import com.celzero.bravedns.database.WgConfigFilesRepository
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.RemoteFileTagUtil
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.deleteRecursive
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream

// SECURITY (VULN, Insecure Deserialization / CWE-502): see notes in BackupAgent.
// The .rbk shared-pref blob is now a strict JSON envelope prefixed with the magic
// "RTHK_PREFS_V2_JSON\n". Any other content (including the legacy ObjectStream
// magic 0xACED) is refused without ever instantiating arbitrary classes.
private const val RTHK_PREFS_MAGIC = "RTHK_PREFS_V2_JSON\n"
private const val RTHK_PREFS_MAX_BYTES = 8 * 1024 * 1024 // 8 MiB hard cap

private fun isLegacyObjectStream(bytes: ByteArray): Boolean {
    // java ObjectStream STREAM_MAGIC is 0xACED followed by version 0x0005.
    return bytes.size >= 2 && (bytes[0].toInt() and 0xff) == 0xac && (bytes[1].toInt() and 0xff) == 0xed
}

private fun readPrefsBackupBytes(prefsBackupFile: java.io.File): ByteArray? {
    if (!prefsBackupFile.exists()) return null
    val len = prefsBackupFile.length()
    if (len <= 0L || len > RTHK_PREFS_MAX_BYTES) return null
    return FileInputStream(prefsBackupFile).use { it.readBytes() }
}

private fun parsePrefsJsonEnvelope(bytes: ByteArray): org.json.JSONObject? {
    val magic = RTHK_PREFS_MAGIC.toByteArray(Charsets.UTF_8)
    if (bytes.size < magic.size) return null
    for (i in magic.indices) if (bytes[i] != magic[i]) return null
    val jsonText = String(bytes, magic.size, bytes.size - magic.size, Charsets.UTF_8)
    return try {
        val obj = org.json.JSONObject(jsonText)
        if (obj.optString("format") != "RTHK_PREFS_JSON") null else obj
    } catch (_: Exception) { null }
}

class RestoreAgent(val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams), KoinComponent {

    private val logDatabase by inject<LogDatabase>()
    private val appDatabase by inject<AppDatabase>()
    private val persistentState by inject<PersistentState>()
    private val wgConfigFilesRepository by inject<WgConfigFilesRepository>()

    companion object {
        const val TAG = "RestoreAgent"
    }

    override suspend fun doWork(): Result {
        val restoreUri = inputData.getString(DATA_BUILDER_RESTORE_URI)?.toUri()
        if (restoreUri == null) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "restore uri is null, return failure")
            return Result.failure()
        }

        Logger.d(LOG_TAG_BACKUP_RESTORE, "begin restore process with file uri: $restoreUri")
        val result = startRestore(restoreUri)

        Logger.i(LOG_TAG_BACKUP_RESTORE, "completed restore process, is successful? $result")
        return if (result) {
            // Bug fix: restart VPN after a successful restore so DNS resolves immediately;
            // previously stopVpn() was called at the start of restore but startVpn() was
            // never called on the success path, leaving the tunnel permanently down.
            // Drop WireGuard rows whose encrypted config file is missing on disk.
            // Restore does not carry over the wireguard/ directory, so any pre-existing
            // rows without a matching file would surface as "invalid config" in the UI.
            pruneOrphanWireguardConfigs()
            startVpn(context)
            Result.success()
        } else {
            Result.failure()
        }
    }

    private suspend fun startRestore(importUri: Uri): Boolean {
        var inputStream: InputStream? = null
        stopVpn(context)
        try {
            val tempDir = getTempDir(context)
            inputStream = context.contentResolver.openInputStream(importUri)

            Logger.d(LOG_TAG_BACKUP_RESTORE, "restore process, temp file dir: ${tempDir.path}")
            // unzip the backup files to tempDir
            if (!unzip(inputStream, tempDir.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to unzip the uri to temp dir $importUri, ${tempDir.path}, return failure"
                )
                return false
            } else {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "restore process, unzipped the files to temp dir")
                // proceed
            }

            if (!validateMetadata(tempDir.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "invalid meta-data or metadata not found. maybe earlier version backup"
                )
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "metadata file validation complete")
                // no-op; proceed
            }

            // copy SharedPreferences file to its directory,
            // if shared pref copy is succeeds then proceed to database restore else
            // return failed
            if (!restoreSharedPreferencesFromFile(tempDir.path)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore shared pref, return failure")
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "shared pref restored to the temp dir")
                // proceed
            }

            // Copy new database file into its final directory
            if (!restoreDatabaseFile(tempDir)) {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to restore database, return failure")
                return false
            } else {
                Logger.i(LOG_TAG_BACKUP_RESTORE, "database restored to the temp dir")
                // proceed
            }

            // open log database if its not open
            handleDatabaseInit()

            // copy the blocklist file from assets to the remote blocklist folder
            moveRemoteBlocklistFileFromAsset()

            // update app version after the restore process
            updateLatestVersion()

            // clean up the temp directory
            deleteRecursive(tempDir)

            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception during restore process, reason? ${e.message}",
                e
            )
            return false
        } finally {
            inputStream?.close()
        }
    }

    private suspend fun moveRemoteBlocklistFileFromAsset() {
        // already there is a remote blocklist file available
        if (
            persistentState.remoteBlocklistTimestamp >
            Constants.PACKAGED_REMOTE_FILETAG_TIMESTAMP
        ) {
            RethinkBlocklistManager.readJson(
                context,
                RethinkBlocklistManager.DownloadType.REMOTE,
                persistentState.remoteBlocklistTimestamp
            )
            return
        }

        RemoteFileTagUtil.moveFileToLocalDir(context.applicationContext, persistentState)
    }

    private fun handleDatabaseInit() {
        // get writable database for logs
        if (!logDatabase.isOpen) {
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "log database is not open, perform writableDatabase operation"
            )
            logDatabase.openHelper.writableDatabase
        } else {
            // no-op
        }

        // get writable database for app
        if (!appDatabase.isOpen) {
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "app database is not open, perform writableDatabase operation"
            )
            appDatabase.openHelper.writableDatabase
        } else {
            // no-op
        }
    }

    // Restore database file stored at tempDir/nameOfFileToRestore.
    private fun restoreDatabaseFile(tempDir: File): Boolean {
        checkPoint()

        Logger.d(LOG_TAG_BACKUP_RESTORE, "begin restore database to temp dir: ${tempDir.path}")

        val files = tempDir.listFiles()
        if (files == null) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "files to restore is empty, path: ${tempDir.path}")
            return false
        }

        Logger.d(
            LOG_TAG_BACKUP_RESTORE,
            "List of files in backup folder: ${files.size}, path: ${tempDir.path}"
        )
        for (file in files) {
            val currentDbFile = File(context.getDatabasePath(file.name).path)
            // Firewall-only restore: only restore files that belong to the main app database.
            // LogDatabase is excluded because (a) it was not backed up in a firewall-only
            // backup and (b) restoring connection logs from an older backup would produce
            // confusing stale entries that are not representative of the current state.
            if (!file.name.startsWith(AppDatabase.DATABASE_NAME)) {
                Logger.d(
                    LOG_TAG_BACKUP_RESTORE,
                    "firewall-only restore: skipping non-firewall db file: ${file.name}"
                )
                continue
            }
            if (!Utilities.copy(file.path, currentDbFile.path)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "restore process, failure copying database file: ${file.path} to ${currentDbFile.path}"
                )
                return false
            }
            Logger.i(
                LOG_TAG_BACKUP_RESTORE,
                "database file: ${file.name} backed up from ${file.path} to ${currentDbFile.path}"
            )
        }

        deleteResidue(tempDir)
        return true
    }


    private fun updateLatestVersion() {
        if (isNewVersion()) {
            persistentState.appVersion = getLatestVersion()
            Logger.i(LOG_TAG_BACKUP_RESTORE, "app version updated to ${persistentState.appVersion}")
        } else {
            Logger.i(LOG_TAG_BACKUP_RESTORE, "no need to update app version")
        }
    }

    private fun isNewVersion(): Boolean {
        val versionStored = persistentState.appVersion
        val version = getLatestVersion()
        return (version != 0 && version != versionStored)
    }

    @Suppress("DEPRECATION")
    private fun getLatestVersion(): Int {
        val pInfo: PackageInfo? =
            Utilities.getPackageMetadata(context.packageManager, context.packageName)
        return pInfo?.versionCode ?: 0
    }

    private fun checkPoint() {
        Logger.i(LOG_TAG_BACKUP_RESTORE, "database checkpoint() during restore process")
        appDatabase.checkPoint()
        logDatabase.checkPoint()
        return
    }

    private fun validateMetadata(tempDirectory: String?): Boolean {
        // TODO: revisit this after v055 release
        if (isMetadataCompatible(tempDirectory)) {
            return true
        } else {
            // proceed with META_DATA_FILE validation
        }

        val file = File(tempDirectory, METADATA_FILENAME)
        var stream: InputStream? = null
        return try {
            stream = file.inputStream()
            val metadata = stream.bufferedReader().use { it.readText() }
            isVersionSupported(metadata)
        } catch (ex: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "err while restoring metadata, reason? ${ex.message}",
                ex
            )
            false
        } finally {
            try {
                stream?.close()
            } catch (ex: IOException) {
                Logger.e(
                    LOG_TAG_BACKUP_RESTORE,
                    "err while restoring metadata, reason? ${ex.message}",
                    ex
                )
            }
        }
    }


    private fun isMetadataCompatible(tempDirectory: String?): Boolean {

        val minVersionSupported = 24

        val prefsBackupFile = File(tempDirectory, SHARED_PREFS_BACKUP_FILE_NAME)
        val bytes = readPrefsBackupBytes(prefsBackupFile) ?: run {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "AUDIT: prefs backup missing or too large")
            return false
        }
        if (isLegacyObjectStream(bytes)) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "AUDIT (CWE-502): refusing legacy ObjectStream prefs backup; export a new backup with this build."
            )
            return false
        }
        val envelope = parsePrefsJsonEnvelope(bytes) ?: run {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "AUDIT (CWE-502): prefs backup has unexpected format; refusing.")
            return false
        }
        return try {
            val entries = envelope.optJSONArray("entries") ?: return false
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val key = item.optString("k", "")
                val type = item.optString("t", "")
                if (key == PersistentState.APP_VERSION && type == "int") {
                    val appVersion = item.optInt("v", 0)
                    if (appVersion >= minVersionSupported) {
                        Logger.w(
                            LOG_TAG_BACKUP_RESTORE,
                            "app version $appVersion >= min $minVersionSupported, proceed with restore"
                        )
                        return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Logger.crash(LOG_TAG_BACKUP_RESTORE, "AUDIT: error parsing prefs envelope, ${e.message}", e)
            false
        }
    }

    private fun isVersionSupported(metadata: String): Boolean {
        try {
            val minVersionSupported = 24

            if (!metadata.contains(VERSION)) return false

            val versionDetails = metadata.split("|")
            if (versionDetails[0].isEmpty()) return false

            val version = versionDetails[0].split(":")[1].toIntOrNull() ?: 0

            // backup version should be equal to minVersionSupported (prior to that version
            // there is only one database), so do not consider the backups prior to that
            return version >= minVersionSupported && persistentState.appVersion >= version
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "error while reading metadata, reason? ${e.message}",
                e
            )
            return false
        }
    }

    private fun restoreSharedPreferencesFromFile(tempDirectory: String?): Boolean {
        val prefsBackupFile = File(tempDirectory, SHARED_PREFS_BACKUP_FILE_NAME)
        val currentSharedPreferences: SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(context)

        Logger.d(LOG_TAG_BACKUP_RESTORE, "shared pref file path: ${prefsBackupFile.path}")
        try {
            val bytes = readPrefsBackupBytes(prefsBackupFile) ?: run {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "AUDIT: prefs backup missing or too large")
                return false
            }
            if (isLegacyObjectStream(bytes)) {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "AUDIT (CWE-502): refusing legacy ObjectStream prefs backup; export a new backup with this build."
                )
                return false
            }
            val envelope = parsePrefsJsonEnvelope(bytes) ?: run {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "AUDIT (CWE-502): prefs backup has unexpected format; refusing.")
                return false
            }

            val entries = envelope.optJSONArray("entries") ?: return false
            val prefsEditor = currentSharedPreferences.edit()
            prefsEditor.clear()
            for (i in 0 until entries.length()) {
                val item = entries.optJSONObject(i) ?: continue
                val key = item.optString("k", "")
                if (key.isEmpty()) continue
                when (item.optString("t", "")) {
                    "bool" -> prefsEditor.putBoolean(key, item.optBoolean("v", false))
                    "int" -> prefsEditor.putInt(key, item.optInt("v", 0))
                    "long" -> prefsEditor.putLong(key, item.optLong("v", 0L))
                    "float" -> prefsEditor.putFloat(key, item.optDouble("v", 0.0).toFloat())
                    "string" -> prefsEditor.putString(key, item.optString("v", ""))
                    "stringset" -> {
                        val arr = item.optJSONArray("v") ?: continue
                        val set = LinkedHashSet<String>(arr.length())
                        for (j in 0 until arr.length()) {
                            val s = arr.optString(j, null) ?: continue
                            set.add(s)
                        }
                        prefsEditor.putStringSet(key, set)
                    }
                    else -> {
                        Logger.w(LOG_TAG_BACKUP_RESTORE, "AUDIT: unknown pref type for key='$key', skipping")
                    }
                }
            }
            prefsEditor.apply()
            Logger.i(LOG_TAG_BACKUP_RESTORE, "completed restore of ${entries.length()} shared pref entries")
            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception while restoring shared pref, reason? ${e.message}",
                e
            )
            return false
        } finally {
            deleteResidue(prefsBackupFile)
        }
    }

    private suspend fun pruneOrphanWireguardConfigs() {
        try {
            val rows = wgConfigFilesRepository.getWgConfigs()
            var pruned = 0
            rows.forEach { row ->
                val readable = try {
                    EncryptedFileManager.readWireguardConfig(context, row.configPath) != null
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_BACKUP_RESTORE, "prune: read failed id=${row.id} name=${row.name}: ${e.message}")
                    false
                }
                if (!readable) {
                    Logger.i(LOG_TAG_BACKUP_RESTORE, "prune: dropping orphan wg id=${row.id} name=${row.name}")
                    wgConfigFilesRepository.deleteConfig(row.id)
                    pruned++
                }
            }
            Logger.i(LOG_TAG_BACKUP_RESTORE, "prune: removed $pruned orphan wg configs")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "prune: failed: ${e.message}")
        }
    }
}
