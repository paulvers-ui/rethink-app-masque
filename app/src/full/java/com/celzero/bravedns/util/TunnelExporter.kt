/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */
package com.celzero.bravedns.util

import Logger
import Logger.LOG_TAG_PROXY
import android.content.ContentResolver
import android.net.Uri
import com.celzero.bravedns.wireguard.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes WireGuard [Config]s to user-picked SAF Uris as plain .conf text
 * (single) or a .zip archive of .conf files (bulk).
 *
 * Single Responsibility: file serialization only. It does not touch the
 * database, the VPN, or any UI.
 */
object TunnelExporter {

    /** Write one config to [uri] as `[Interface]/[Peer]` text. */
    suspend fun exportConfig(
        contentResolver: ContentResolver,
        uri: Uri,
        config: Config
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = contentResolver.openOutputStream(uri, "wt")
                ?: error("cannot open output stream for $uri")
            out.use { OutputStreamWriter(it, Charsets.UTF_8).use { w -> w.write(config.toWgQuickString()) } }
            Logger.i(LOG_TAG_PROXY, "exportConfig: wrote ${config.getName()} to $uri")
        }.onFailure { Logger.e(LOG_TAG_PROXY, "exportConfig: failed: ${it.message}", it as? Exception) }
    }

    /** Write all supplied configs to [uri] as a .zip of `<name>.conf` entries. */
    suspend fun exportConfigsZip(
        contentResolver: ContentResolver,
        uri: Uri,
        configs: List<Config>
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val out = contentResolver.openOutputStream(uri, "wt")
                ?: error("cannot open output stream for $uri")
            var count = 0
            out.use { rawOut ->
                ZipOutputStream(rawOut).use { zip ->
                    configs.forEach { cfg ->
                        val safeName = cfg.getName().ifBlank { "wg-${cfg.getId()}" }
                            .replace(Regex("[^A-Za-z0-9._-]"), "_")
                        zip.putNextEntry(ZipEntry("$safeName.conf"))
                        zip.write(cfg.toWgQuickString().toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                        count++
                    }
                }
            }
            Logger.i(LOG_TAG_PROXY, "exportConfigsZip: wrote $count configs to $uri")
            count
        }.onFailure { Logger.e(LOG_TAG_PROXY, "exportConfigsZip: failed: ${it.message}", it as? Exception) }
    }
}
