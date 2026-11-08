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

package com.paulvers.auroravpn.service

import android.content.Context
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import java.io.File
import java.net.InetAddress

/**
 * Blocked networks (ASN) for the Countries & networks firewall. Holds the CIDR
 * prefixes announced by the AS numbers the user blocked, looked up from RIPEstat
 * and refreshed weekly by the ASN refresh worker (full flavor). This object is
 * in the main source set and does no networking itself: the worker computes the
 * prefixes and hands them over with [setPrefixes]. Until that runs the list is
 * empty and [isBlocked] is false, so country blocking works without it.
 *
 * Users block a handful of networks, so a linear scan over their prefixes is
 * cheap and avoids pulling in the native IP trie.
 */
object AsnRulesManager {

    private const val CACHE_FILE = "blocked_asn_prefixes.txt"

    @Volatile private var prefixes: List<IPAddress> = emptyList()

    fun setPrefixes(list: List<IPAddress>) {
        prefixes = list
    }

    /** Set the blocked prefixes from CIDR strings, dropping any that don't parse. */
    fun setFromCidrs(cidrs: List<String>) {
        prefixes = cidrs.mapNotNull { IPAddressString(it.trim()).address }
    }

    fun clear() {
        prefixes = emptyList()
    }

    /** Load the last fetched prefixes from disk, so a blocked network holds at
     *  start before the refresh worker runs. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun loadFromCache(context: Context) {
        try {
            val f = File(context.filesDir, CACHE_FILE)
            if (f.exists()) {
                prefixes = f.readLines().mapNotNull { IPAddressString(it.trim()).address }
            }
        } catch (e: Exception) {
            // leave whatever is loaded; the worker will refresh
        }
    }

    /** Persist the CIDR strings for [loadFromCache]. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun saveCache(context: Context, cidrs: List<String>) {
        try {
            File(context.filesDir, CACHE_FILE).writeText(cidrs.joinToString("\n"))
        } catch (e: Exception) {
            // best effort
        }
    }

    fun isEmpty(): Boolean = prefixes.isEmpty()

    /** True if [inet] falls inside any blocked network's prefix. */
    fun isBlocked(inet: InetAddress?): Boolean {
        val snapshot = prefixes
        val addr = inet?.hostAddress?.let { IPAddressString(it).address }
        return addr != null &&
            snapshot.isNotEmpty() &&
            snapshot.any { it.isIPv4 == addr.isIPv4 && it.contains(addr) }
    }
}
