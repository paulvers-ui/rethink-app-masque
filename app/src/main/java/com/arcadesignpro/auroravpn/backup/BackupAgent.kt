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
package com.arcadesignpro.auroravpn.backup

import Logger
import Logger.LOG_TAG_BACKUP_RESTORE
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.SystemClock
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.CREATED_TIME
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.DATA_BUILDER_BACKUP_URI
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.METADATA_FILENAME
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.PACKAGE_NAME
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.SHARED_PREFS_BACKUP_FILE_NAME
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.TEMP_ZIP_FILE_NAME
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.VERSION
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.deleteResidue
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.getFileNameFromPath
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.getRethinkDatabase
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.getTempDir
import com.arcadesignpro.auroravpn.backup.BackupHelper.Companion.startVpn
import com.arcadesignpro.auroravpn.database.AppDatabase
import com.arcadesignpro.auroravpn.service.PersistentState
import com.arcadesignpro.auroravpn.util.Utilities
import com.arcadesignpro.auroravpn.util.Utilities.copyWithStream
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ref:
// https://gavingt.medium.com/refactoring-my-backup-and-restore-feature-to-comply-with-scoped-storage-e2b6c792c3b
class BackupAgent(val context: Context, workerParams: WorkerParameters) :
    Worker(context, workerParams), KoinComponent {

    var filesPathToZip: MutableList<String> = ArrayList()
    private val persistentState by inject<PersistentState>()

    companion object {
        const val TAG = "BackupExport"

        /**
         * Exhaustive set of SharedPreferences keys that constitute the firewall configuration.
         *
         * Only these keys are included in a firewall-only backup. DNS server settings,
         * WireGuard credentials, proxy settings, analytics toggles, and UI-only state are
         * deliberately excluded so that restoring a backup cannot silently reconfigure
         * the user's DNS or VPN transport.
         */
        val FIREWALL_PREF_KEYS: Set<String> = setOf(
            // Operation mode (DNS-only / DNS+Firewall)
            "brave_mode",
            // Universal firewall rules
            "block_udp_traffic_other_than_dns",
            "block_unknown_connections",
            "block_http_connections",
            "block_metered_connections",
            "universal_lockdown",
            "block_new_app",
            "disallow_dns_bypass",
            "background_mode",           // block apps in background
            "screen_state",              // block when device locked
            "block_non_ip_dns_responses",
            "block_icmp",
            // Firewall bypass control
            "allow_bypass",
            // Firewall bubble overlay feature
            "pref_firewall_bubble_enabled",
            // Local blocklist (used by the firewall for DNS-level blocking)
            "enable_local_list",
            "local_block_list_stamp",
            "local_block_list_downloaded_time",
            "local_block_list_count",
            // App version — needed by the restore path to validate compatibility
            "app_version"
        )

        /**
         * WireGuard-owned tables. A backup must not carry WireGuard configuration, so these
         * are emptied in the temporary database copy before it is zipped.
         */
        val WG_TABLES: List<String> =
            listOf("WgConfigFiles", "ProxyApplicationMapping")
    }

    override fun doWork(): Result {
        val backupFileUri = inputData.getString(DATA_BUILDER_BACKUP_URI)?.toUri()
        if (backupFileUri == null) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "backup file uri is null, return failure")
            return Result.failure()
        }

        Logger.d(LOG_TAG_BACKUP_RESTORE, "begin backup process with file uri: $backupFileUri")
        val isBackupSucceed = startBackupProcess(backupFileUri)

        Logger.i(
            LOG_TAG_BACKUP_RESTORE,
            "completed backup process, is backup successful? $isBackupSucceed"
        )
        // always restart VPN whether backup succeeded or failed — the tunnel was
        // stopped before backup started and must come back up regardless of outcome
        startVpn(context)
        if (isBackupSucceed) {
            return Result.success()
        }
        return Result.failure()
    }

    private fun startBackupProcess(backupFileUri: Uri): Boolean {
        var processCompleted: Boolean
        try {
            val tempDir = getTempDir(context)

            val prefsBackupFile = File(tempDir, SHARED_PREFS_BACKUP_FILE_NAME)

            Logger.d(
                    LOG_TAG_BACKUP_RESTORE,
                    "backup process, temp file dir: ${tempDir.path}, prefs backup file: ${prefsBackupFile.path}"
                )
            processCompleted = saveSharedPreferencesToFile(context, prefsBackupFile)

            if (processCompleted) {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "shared pref backup is added to the temp dir")
            } else {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to add shared pref to temp backup dir, return failure"
                )
                return false
            }

            processCompleted = saveDatabasesToFile(tempDir.path)

            if (processCompleted) {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "database backup is added to the temp dir")
            } else {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "failed to add database to temp backup dir, return failure"
                )
                return false
            }

            processCompleted = createMetaData(tempDir)

            if (processCompleted) {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "metadata is added to the temp dir")
            } else {
                Logger.w(LOG_TAG_BACKUP_RESTORE, "failed to create metadata file, return failure")
                return false
            }

            return zipAndCopyToDestination(tempDir, backupFileUri)
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception during backup process, reason? ${e.message}",
                e
            )
            return false
        } finally {
            for (filePath in filesPathToZip) {
                val file = File(filePath)
                deleteResidue(file)
            }
            filesPathToZip.clear()
        }
    }


    private fun createMetaData(backupDir: File): Boolean {
        Logger.d(LOG_TAG_BACKUP_RESTORE, "creating meta data file, path: ${backupDir.path}")
        // check if the file exists already, if yes, delete it
        val file = File(backupDir, METADATA_FILENAME)
        if (file.exists()) {
            Logger.d(LOG_TAG_BACKUP_RESTORE, "metadata file exists, deleting it")
            file.delete()
            filesPathToZip.remove(file.absolutePath)
        }
        val metadata = backupMetadata()
        try {
            val metadataFile = File(backupDir, METADATA_FILENAME)
            metadataFile.writer().use {
                writer -> writer.write(metadata)
                writer.flush()
            }
            // add the metadata file to the list of files to be zipped
            filesPathToZip.add(metadataFile.absolutePath)
            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "exception while creating meta data file, ${e.message}",
                e
            )
            return false
        }
    }

    private fun backupMetadata(): String {
        return "$VERSION:${persistentState.appVersion}|$PACKAGE_NAME:${context.packageName}|$CREATED_TIME:${SystemClock.elapsedRealtime()}"
    }

    private fun zipAndCopyToDestination(tempDir: File, destUri: Uri): Boolean {
        val bZipSucceeded: Boolean = zip(filesPathToZip, tempDir.path)

        Logger.i(
            LOG_TAG_BACKUP_RESTORE,
            "backup zip completed, is success? $bZipSucceeded, proceed to copy $destUri"
        )

        if (bZipSucceeded) {
            val tempZipFile = File(tempDir, TEMP_ZIP_FILE_NAME)
            val zipFileUri: Uri = Uri.fromFile(tempZipFile)
            val inputStream: InputStream =
                context.contentResolver.openInputStream(zipFileUri) ?: return false
            val outputStream: OutputStream =
                context.contentResolver.openOutputStream(destUri) ?: run {
                    inputStream.close()
                    return false
                }

            // we are passing the streams instead of actual files because we do not have
            // write access to the destination dir.
            // copyWithStream closes both streams via use{} internally.
            val copySucceeded: Boolean = copyWithStream(inputStream, outputStream)
            return if (copySucceeded) {
                Logger.i(
                    LOG_TAG_BACKUP_RESTORE,
                    "Copy completed, delete the temp dir ${tempZipFile.path}"
                )
                deleteResidue(tempZipFile)
                true
            } else {
                Logger.w(
                    LOG_TAG_BACKUP_RESTORE,
                    "copy failed to destination dir, path: ${zipFileUri.path}"
                )
                false
            }
        } else {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "backup zip failed, do not proceed")
            return false
        }
    }

    /**
     * Firewall-config backup: only include [AppDatabase.DATABASE_NAME] (and its WAL/SHM
     * siblings). That database holds AppInfo (per-app firewall rules), CustomIp, and
     * CustomDomain — the tables that represent the user's firewall state.
     *
     * The log database is intentionally excluded: it contains ephemeral connection-tracking
     * data that is not part of the firewall configuration.
     *
     * WireGuard tables are wiped from the copied database (see [stripWireguardFromDbCopy]),
     * so a backup never contains WireGuard configuration.
     */
    private fun saveDatabasesToFile(path: String): Boolean {
        // Checkpoint the live database before copying so that any WAL frames that are
        // only in memory (committed but not yet flushed to the .db file) are written to
        // disk and folded into the main database file. Without this, recently-changed
        // firewall rules may be absent from the backup copy.
        val livePath = context.getDatabasePath(AppDatabase.DATABASE_NAME).absolutePath
        try {
            val liveDb = SQLiteDatabase.openDatabase(
                livePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            liveDb.use { it.execSQL("PRAGMA wal_checkpoint(FULL)") }
            Logger.i(LOG_TAG_BACKUP_RESTORE, "wal checkpoint on live db succeeded")
        } catch (e: Exception) {
            // Non-fatal: the WAL/SHM siblings are still included in the copy, so a
            // consistent restore remains possible. Log and continue.
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "wal checkpoint on live db failed (non-fatal), reason? ${e.message}"
            )
        }

        val files = getRethinkDatabase(context)?.listFiles() ?: return false

        for (f in files) {
            Logger.d(
                LOG_TAG_BACKUP_RESTORE,
                "file ${f.name} found in database dir (${f.absolutePath})"
            )

            // Firewall-only policy: skip every file that does not belong to the main
            // app database (bravedns.db). WAL and SHM siblings of bravedns.db are kept
            // because they are required for a consistent Room restore.
            if (!f.name.startsWith(AppDatabase.DATABASE_NAME)) {
                Logger.d(
                    LOG_TAG_BACKUP_RESTORE,
                    "firewall-only backup: skipping non-firewall db file: ${f.name}"
                )
                continue
            }

            val databaseFile =
                backUpFile(f.absolutePath, constructDbFileName(path, f.name)) ?: return false
            Logger.i(LOG_TAG_BACKUP_RESTORE, "file ${databaseFile.name} added to backup dir")
            filesPathToZip.add(databaseFile.absolutePath)
        }

        // the copied db still carries the WireGuard tables; a backup must never contain
        // wireguard configs (names, file paths, hops, per-app proxy mappings), so wipe
        // them from the copy before it is zipped.
        return stripWireguardFromDbCopy(constructDbFileName(path, AppDatabase.DATABASE_NAME))
    }

    /**
     * Removes every WireGuard-related row from the temporary database copy that is about to
     * be zipped into the .rbk file. Only the copy is touched — the live database is never
     * modified. If the copy cannot be sanitised, the backup fails rather than shipping
     * WireGuard configuration.
     */
    private fun stripWireguardFromDbCopy(dbCopyPath: String): Boolean {
        val dbCopy = File(dbCopyPath)
        if (!dbCopy.exists()) {
            Logger.w(LOG_TAG_BACKUP_RESTORE, "db copy missing at $dbCopyPath, cannot strip wg")
            return false
        }
        var db: SQLiteDatabase? = null
        try {
            db = SQLiteDatabase.openDatabase(dbCopyPath, null, SQLiteDatabase.OPEN_READWRITE)
            for (table in WG_TABLES) {
                try {
                    db.execSQL("DELETE FROM $table")
                    Logger.i(LOG_TAG_BACKUP_RESTORE, "wg-free backup: cleared table $table")
                } catch (e: Exception) {
                    // table may not exist on older schemas; that is fine
                    Logger.d(
                        LOG_TAG_BACKUP_RESTORE,
                        "wg-free backup: skip table $table, reason? ${e.message}"
                    )
                }
            }
            // fold the deletes into the main db file so the zipped wal/shm cannot resurrect them
            try {
                db.execSQL("PRAGMA wal_checkpoint(TRUNCATE)")
            } catch (e: Exception) {
                Logger.d(LOG_TAG_BACKUP_RESTORE, "wal checkpoint failed, reason? ${e.message}")
            }
            return true
        } catch (e: Exception) {
            Logger.crash(
                LOG_TAG_BACKUP_RESTORE,
                "failed to strip wireguard data from backup db, reason? ${e.message}",
                e
            )
            return false
        } finally {
            try {
                db?.close()
            } catch (ignored: Exception) {
                // no-op
            }
        }
    }

    private fun constructDbFileName(path: String, fileName: String): String {
        return path + File.separator + fileName
    }

    // SECURITY (VULN, Insecure Deserialization / CWE-502): The previous implementation
    // serialized SharedPreferences via java.io.ObjectOutputStream and the matching
    // restore path used java.io.ObjectInputStream on a user-supplied .rbk file. That is
    // a classic ACE primitive: a crafted backup whose embedded class graph triggers any
    // gadget chain available on the classpath (Android framework, Glide, Gson, OkHttp,
    // Koin, etc.) executes attacker-controlled code in the Rethink process — which
    // holds VPN, accessibility-style network visibility, and EncryptedFile master keys.
    //
    // Fix: write a strict JSON envelope with a magic header. Only primitive scalar prefs
    // (Boolean/Int/Long/Float/String/Set<String>) are exported, matching what
    // SharedPreferences supports. The restore side parses with org.json (no class
    // instantiation), validates types per key, and rejects any old-format binary blob.
    //
    // Firewall-only policy: only the pref keys listed in FIREWALL_PREF_KEYS are exported.
    // DNS settings, WireGuard configs, proxy settings, and app-update state are excluded
    // so that restoring a backup cannot silently override the user's DNS/VPN configuration.
    private fun saveSharedPreferencesToFile(context: Context, prefFile: File): Boolean {
        Logger.i(LOG_TAG_BACKUP_RESTORE, "begin shared pref copy, file path:${prefFile.path}")
        val sharedPrefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

        try {
            val entries = org.json.JSONArray()
            for ((k, v) in sharedPrefs.all) {
                if (k == null) continue
                // Firewall-only filter: skip any pref that is not part of the firewall config.
                if (k !in FIREWALL_PREF_KEYS) {
                    Logger.d(LOG_TAG_BACKUP_RESTORE, "firewall-only backup: skipping pref '$k'")
                    continue
                }
                val item = org.json.JSONObject()
                item.put("k", k)
                when (v) {
                    is Boolean -> { item.put("t", "bool"); item.put("v", v) }
                    is Int -> { item.put("t", "int"); item.put("v", v) }
                    is Long -> { item.put("t", "long"); item.put("v", v) }
                    is Float -> { item.put("t", "float"); item.put("v", v.toDouble()) }
                    is String -> { item.put("t", "string"); item.put("v", v) }
                    is Set<*> -> {
                        val arr = org.json.JSONArray()
                        for (s in v) {
                            if (s is String) arr.put(s) else continue
                        }
                        item.put("t", "stringset")
                        item.put("v", arr)
                    }
                    null -> continue
                    else -> {
                        Logger.w(LOG_TAG_BACKUP_RESTORE, "AUDIT: skipping non-scalar pref '$k' of type ${v.javaClass.name}")
                        continue
                    }
                }
                entries.put(item)
            }
            val envelope = org.json.JSONObject()
            envelope.put("format", "RTHK_PREFS_JSON")
            envelope.put("version", 2)
            envelope.put("entries", entries)

            FileOutputStream(prefFile).use { fos ->
                fos.write("RTHK_PREFS_V2_JSON\n".toByteArray(Charsets.UTF_8))
                fos.write(envelope.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
            }
        } catch (e: Exception) {
            Logger.crash(LOG_TAG_BACKUP_RESTORE, "exception during shared pref backup, ${e.message}", e)
            return false
        }
        filesPathToZip.add(prefFile.absolutePath)
        return true
    }

    private fun backUpFile(backupFilePath: String?, destFilePath: String?): File? {
        if (backupFilePath == null || destFilePath == null) {
            Logger.w(
                LOG_TAG_BACKUP_RESTORE,
                "invalid backup info during db backup, file: $backupFilePath, destination: $destFilePath"
            )
            return null
        }
        val isCopySuccess = Utilities.copy(backupFilePath, destFilePath)
        if (isCopySuccess) return File(destFilePath)

        return null
    }

    private fun zip(files: List<String>, zipDirectory: String): Boolean {
        val outputFileName = zipDirectory + File.separator + TEMP_ZIP_FILE_NAME
        Logger.d(LOG_TAG_BACKUP_RESTORE, "files: $files, output: $outputFileName")
        return try {
            val bufferSize = 80000
            val data = ByteArray(bufferSize)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFileName))).use { out ->
                for (file in files) {
                    // SQLite may checkpoint and remove WAL/SHM files after stripWireguardFromDbCopy
                    // opens the DB copy; these files are optional for a valid restore
                    if (!java.io.File(file).exists()) {
                        Logger.w(LOG_TAG_BACKUP_RESTORE, "skipping missing optional db file during zip: $file")
                        continue
                    }
                    BufferedInputStream(FileInputStream(file), bufferSize).use { origin ->
                        out.putNextEntry(ZipEntry(getFileNameFromPath(file)))
                        var count: Int
                        while (origin.read(data, 0, bufferSize).also { count = it } != -1) {
                            out.write(data, 0, count)
                        }
                    }
                    Logger.d(LOG_TAG_BACKUP_RESTORE, "$file added to zip, path: $file")
                }
            }
            Logger.i(LOG_TAG_BACKUP_RESTORE, "$files added to zip")
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_BACKUP_RESTORE, "error while adding files to zip dir, ${e.message}", e)
            false
        }
    }
}
