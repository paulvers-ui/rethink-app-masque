/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.paulvers.auroravpn.customdownloader

import Logger
import Logger.LOG_TAG_DOWNLOAD
import android.content.Context
import com.paulvers.auroravpn.service.AsnRulesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Looks up the CIDR prefixes a network (ASN) announces, from RIPEstat, for the
 * Countries & networks firewall. The blocked-network lookup ([AsnRulesManager])
 * matches a destination IP against these prefixes. Refreshed when the user edits
 * the list and weekly (AsnRefreshWorker); the result is cached to disk so a
 * blocked network still holds after a restart, before the next fetch.
 */
object RipeStatDownloader {

    private const val BASE_URL = "https://stat.ripe.net/"
    private const val MAX_ASNS = 64
    private const val MAX_PREFIXES = 20000
    private const val MAX_ASN = 4294967295L

    /** The CIDR prefixes announced by each ASN, as strings. */
    suspend fun fetchPrefixes(asns: List<Long>): List<String> = withContext(Dispatchers.IO) {
        if (asns.isEmpty()) return@withContext emptyList()
        val api =
            Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(RetrofitManager.okHttpClient(false))
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(IRipeStatDownload::class.java)
        val out = mutableListOf<String>()
        for (asn in asns.take(MAX_ASNS)) {
            out += fetchOne(api, asn)
            if (out.size >= MAX_PREFIXES) break
        }
        Logger.i(LOG_TAG_DOWNLOAD, "ripestat: ${asns.size} ASN(s) -> ${out.size} prefixes")
        out.take(MAX_PREFIXES)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchOne(api: IRipeStatDownload, asn: Long): List<String> {
        return try {
            val resp = api.announcedPrefixes("AS$asn")
            val arr =
                resp?.takeIf { it.isSuccessful }?.body()
                    ?.getAsJsonObject("data")
                    ?.getAsJsonArray("prefixes")
            if (arr == null) {
                Logger.w(LOG_TAG_DOWNLOAD, "ripestat: AS$asn failed: ${resp?.code()}")
                emptyList()
            } else {
                arr.mapNotNull { it.asJsonObject?.get("prefix")?.asString }
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_DOWNLOAD, "ripestat: AS$asn error: ${e.message}")
            emptyList()
        }
    }

    /** Fetch for the ASNs in [blockedAsnsCsv] and update the matcher and the cache. */
    suspend fun refreshFromSettings(context: Context, blockedAsnsCsv: String) {
        val asns =
            blockedAsnsCsv
                .split(",")
                .mapNotNull { it.trim().removePrefix("AS").removePrefix("as").toLongOrNull() }
                .filter { it in 1..MAX_ASN }
                .distinct()
        if (asns.isEmpty()) {
            AsnRulesManager.clear()
            AsnRulesManager.saveCache(context, emptyList())
            return
        }
        val cidrs = fetchPrefixes(asns)
        // keep the last known-good set when a fetch comes back empty (offline, rate-limited)
        if (cidrs.isEmpty()) return
        AsnRulesManager.setFromCidrs(cidrs)
        AsnRulesManager.saveCache(context, cidrs)
    }
}
