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
import android.content.Context
import com.celzero.bravedns.R
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.service.VpnController
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class BackupHelper {

    companion object {
        // MIME type is used for unknown binary files (if stored locally)
        const val INTENT_TYPE_OCTET = "application/octet-stream"

        // google drive will have its own mimetypes changed to "x-zip"
        const val INTENT_TYPE_XZIP = "application/x-zip"

        // file name for the backup file
        const val BACKUP_FILE_NAME = "Rethink_"

        // date format for backup  file name
        const val BACKUP_FILE_NAME_DATETIME = "yyMMddHHmmss"

        // backup file extension
        const val BACKUP_FILE_EXTN = ".rbk"

        // backup shared pref file name
        const val SHARED_PREFS_BACKUP_FILE_NAME = "shared_prefs_backup.txt"

        // metadata file name
        const val METADATA_FILENAME = "rethink_backup.txt"

        // data builder uri string for restore worker
        const val DATA_BUILDER_RESTORE_URI = "restoreFileUri"

        // data builder uri string for backup worker
        const val DATA_BUILDER_BACKUP_URI = "backupFileUri"

        // temp zip  file name
        const val TEMP_ZIP_FILE_NAME = "temp.rbk"

        // intent scheme
        const val INTENT_SCHEME = "content"

        // restart app after database restore
        const val INTENT_RESTART_APP = "restartApp"

        // wireguard dir to backup the wireguard configs
        const val BACKUP_WG_DIR = "wireguard"

        // while restoring the wireguard config, the temp dir to store the wireguard config
        const val TEMP_WG_DIR = "temp_wireguard"

        // metadata constants
        // version
        const val VERSION = "version"
        // package name
        const val PACKAGE_NAME = "package"
        // time when the backup  is taken
        const val CREATED_TIME = "createdTs"

        fun getTempDir(context: Context): File {
            // temp dir (files/Rethink/)
            val backupDirectoryPath: String =
                context.filesDir.absolutePath +
                    File.separator +
                    context.getString(R.string.app_name)
            val file = File(backupDirectoryPath)
            if (!file.exists()) {
                file.mkdir()
            }

            return file
        }

        fun getRethinkDatabase(context: Context): File? {
            val path =
                (context.getDatabasePath(AppDatabase.DATABASE_NAME).parentFile?.path
                    ?: return null) + File.separator
            return File(path)
        }

        fun stopVpn(context: Context) {
            Logger.i(Logger.LOG_TAG_BACKUP_RESTORE, "calling vpn stop from backup helper")
            VpnController.stop("bkup", context)
        }

        fun startVpn(context: Context) {
            Logger.i(Logger.LOG_TAG_BACKUP_RESTORE, "calling vpn start from backup helper")
            VpnController.start(context)
        }

        fun deleteResidue(backupFile: File) {
            if (backupFile.exists()) {
                backupFile.delete()
            }
        }

        // SECURITY (VULN, Zip Slip / CWE-22): A malicious .rbk archive can contain
        // entries whose names include path-traversal segments (e.g. "../../foo") or
        // absolute paths. Concatenating ZipEntry.name onto the destination directory
        // without validating the resolved path lets the archive overwrite arbitrary
        // files inside the app's private storage (databases, encrypted WireGuard
        // configs, shared prefs, native libs cache). The fix below:
        //   1. Rejects entries that are directories, absolute paths, or contain ".."
        //      / null bytes.
        //   2. Resolves the would-be destination via File.canonicalPath and rejects
        //      it if the resolved path does not start with the canonical target
        //      directory.
        //   3. Caps total uncompressed size and per-entry size to mitigate zip-bomb
        //      style DoS during the restore worker.
        fun unzip(inputStream: InputStream?, path: String): Boolean {
            val targetDir = File(path)
            if (!targetDir.exists()) targetDir.mkdirs()
            val canonicalTarget: String
            try {
                canonicalTarget = targetDir.canonicalPath + File.separator
            } catch (e: Exception) {
                Logger.e(Logger.LOG_TAG_BACKUP_RESTORE, "AUDIT: cannot canonicalize unzip target: ${e.message}", e)
                return false
            }

            // Hard limits to bound work done by the restore worker on a hostile zip.
            val maxEntrySize = 256L * 1024L * 1024L // 256 MiB per entry
            val maxTotalSize = 1024L * 1024L * 1024L // 1 GiB total

            var zis: ZipInputStream? = null
            try {
                zis = ZipInputStream(BufferedInputStream(inputStream))
                var ze: ZipEntry? = zis.nextEntry
                var totalRead = 0L
                val buffer = ByteArray(8192)
                while (ze != null) {
                    val name = ze.name
                    if (ze.isDirectory) {
                        zis.closeEntry()
                        ze = zis.nextEntry
                        continue
                    }
                    if (name.isEmpty() ||
                        name.contains("..") ||
                        name.contains('\u0000') ||
                        name.startsWith("/") ||
                        name.startsWith("\\") ||
                        File(name).isAbsolute
                    ) {
                        Logger.w(
                            Logger.LOG_TAG_BACKUP_RESTORE,
                            "AUDIT (Zip Slip): refusing zip entry with unsafe name: '$name'"
                        )
                        return false
                    }

                    val outFile = File(targetDir, name)
                    val canonicalOut: String = try {
                        outFile.canonicalPath
                    } catch (e: Exception) {
                        Logger.e(Logger.LOG_TAG_BACKUP_RESTORE, "AUDIT (Zip Slip): canonicalize failed for '$name'", e)
                        return false
                    }
                    if (!canonicalOut.startsWith(canonicalTarget)) {
                        Logger.w(
                            Logger.LOG_TAG_BACKUP_RESTORE,
                            "AUDIT (Zip Slip): refusing entry escaping target dir; entry='$name' resolved='$canonicalOut'"
                        )
                        return false
                    }

                    // Ensure parent dirs (still inside target) exist.
                    outFile.parentFile?.let { p ->
                        val cp = try { p.canonicalPath } catch (_: Exception) { return false }
                        if (!cp.startsWith(canonicalTarget) && cp + File.separator != canonicalTarget) {
                            Logger.w(Logger.LOG_TAG_BACKUP_RESTORE, "AUDIT (Zip Slip): parent escapes target: $cp")
                            return false
                        }
                        if (!p.exists()) p.mkdirs()
                    }

                    var entryWritten = 0L
                    FileOutputStream(outFile).use { fout ->
                        var count = zis.read(buffer)
                        while (count != -1) {
                            entryWritten += count
                            totalRead += count
                            if (entryWritten > maxEntrySize || totalRead > maxTotalSize) {
                                Logger.w(
                                    Logger.LOG_TAG_BACKUP_RESTORE,
                                    "AUDIT (Zip Bomb): size cap exceeded; entry='$name' entryBytes=$entryWritten totalBytes=$totalRead"
                                )
                                return false
                            }
                            fout.write(buffer, 0, count)
                            count = zis.read(buffer)
                        }
                    }
                    zis.closeEntry()
                    ze = zis.nextEntry
                }
            } catch (e: Exception) {
                Logger.e(Logger.LOG_TAG_BACKUP_RESTORE, "AUDIT: unzip error: ${e.message}", e)
                return false
            } finally {
                try { zis?.close() } catch (_: Exception) { /* no-op */ }
            }
            return true
        }

        fun getFileNameFromPath(file: String): String {
            return file.substring(file.lastIndexOf("/") + 1)
        }
    }
}
