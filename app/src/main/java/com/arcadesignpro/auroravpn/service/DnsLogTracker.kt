/*
 * Copyright 2020 RethinkDNS and its authors
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

package com.arcadesignpro.auroravpn.service

import Logger
import Logger.LOG_TAG_VPN
import android.content.Context
import android.os.SystemClock
import com.arcadesignpro.auroravpn.R
import com.arcadesignpro.auroravpn.database.DnsLog
import com.arcadesignpro.auroravpn.database.DnsLogRepository
import com.arcadesignpro.auroravpn.net.doh.Transaction
import com.arcadesignpro.auroravpn.util.AndroidUidConfig
import com.arcadesignpro.auroravpn.util.Constants.Companion.EMPTY_PACKAGE_NAME
import com.arcadesignpro.auroravpn.util.Constants.Companion.INVALID_UID
import com.arcadesignpro.auroravpn.util.Constants.Companion.UNSPECIFIED_IP_IPV4
import com.arcadesignpro.auroravpn.util.Constants.Companion.UNSPECIFIED_IP_IPV6
import com.arcadesignpro.auroravpn.util.ResourceRecordTypes
import com.arcadesignpro.auroravpn.util.UIUtils.fetchFavIcon
import com.arcadesignpro.auroravpn.util.Utilities.getCountryCode
import com.arcadesignpro.auroravpn.util.Utilities.getFlag
import com.arcadesignpro.auroravpn.util.Utilities.makeAddressPair
import com.arcadesignpro.auroravpn.util.Constants.Companion.UID_EVERYBODY
import com.arcadesignpro.auroravpn.util.DnsSecGuard
import com.arcadesignpro.auroravpn.util.DnsSecGuard.isSuspect
import com.arcadesignpro.auroravpn.util.DnsSecGuard.isPoisonSuspect
import com.arcadesignpro.auroravpn.util.Utilities.normalizeIp
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.DNSSummary
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class DnsLogTracker
internal constructor(
    private val dnsLogRepository: DnsLogRepository,
    private val persistentState: PersistentState,
    private val context: Context
) {

    companion object {
        const val DNS_LEAK_TEST = "dnsleaktest"
        const val ECH = "ech."

        // Some apps like firefox, instagram do not respect ttls
        // add a reasonable grace period to account for that
        // for eg: https://support.mozilla.org/en-US/questions/1213045
        val DNS_TTL_GRACE_SEC = TimeUnit.MINUTES.toSeconds(5L)
        private const val RDATA_MAX_LENGTH = 100
        private const val EMPTY_RESPONSE = "--"
        private const val START_RESPONSE = "START"
    }

    private val vpnStateMap = HashMap<Transaction.Status, BraveVPNService.State>()

    init {
        vpnStateMap[Transaction.Status.COMPLETE] = BraveVPNService.State.WORKING
        vpnStateMap[Transaction.Status.SEND_FAIL] = BraveVPNService.State.NO_INTERNET
        vpnStateMap[Transaction.Status.NO_RESPONSE] = BraveVPNService.State.DNS_SERVER_DOWN
        vpnStateMap[Transaction.Status.TRANSPORT_ERROR] = BraveVPNService.State.DNS_SERVER_DOWN
        vpnStateMap[Transaction.Status.BAD_QUERY] = BraveVPNService.State.DNS_ERROR
        vpnStateMap[Transaction.Status.CLIENT_ERROR] = BraveVPNService.State.DNS_ERROR
        vpnStateMap[Transaction.Status.BAD_RESPONSE] = BraveVPNService.State.DNS_ERROR
        vpnStateMap[Transaction.Status.INTERNAL_ERROR] = BraveVPNService.State.APP_ERROR
    }

    fun processOnResponse(summary: DNSSummary, rethinkUid: Int): Transaction {
        val latencyMs = (TimeUnit.SECONDS.toMillis(1L) * summary.latency).toLong()
        val nowMs = SystemClock.elapsedRealtime()
        val queryTimeMs = nowMs - latencyMs
        var uid = INVALID_UID

        try {
            uid = if (summary.uid == Backend.UidSelf) {
                rethinkUid
            } else if (summary.uid == Backend.UidSystem) {
                AndroidUidConfig.SYSTEM.uid // 1000
            } else {
                summary.uid.toInt()
            }
        } catch (_: NumberFormatException) {
            Logger.w(LOG_TAG_VPN, "onQuery: invalid uid: ${summary.uid}, using default uid: $uid")
        }

        val transaction = Transaction()
        // remove the trailing dot from the qName if present, it causes discrepancies in the
        // stats summary page when qname is used along domain from ConnectionTracker
        transaction.qName = summary.qName.dropLastWhile { it == '.' }
        transaction.type = summary.qType
        transaction.uid = uid
        transaction.id = summary.id
        transaction.queryTime = queryTimeMs
        transaction.transportType = Transaction.TransportType.getType(summary.type)
        transaction.response = summary.rData ?: ""
        transaction.responseCode = summary.rCode
        transaction.ttl = summary.rTtl
        transaction.latency = latencyMs
        transaction.serverName = summary.server ?: ""
        transaction.status = Transaction.Status.fromId(summary.status)
        transaction.responseCalendar = Calendar.getInstance()
        transaction.blocklist = summary.blocklists ?: ""
        transaction.relayName = summary.rpid ?: ""
        transaction.proxyId = summary.pid ?: ""
        transaction.msg = summary.msg ?: ""
        transaction.upstreamBlock = summary.upstreamBlocks
        transaction.region = summary.region
        transaction.isCached = summary.cached
        transaction.dnssecOk = summary.`do`
        transaction.dnssecValid = summary.ad
        transaction.blockedTarget = summary.blockedTarget
        return transaction
    }

    suspend fun makeDnsLogObj(transaction: Transaction): DnsLog {
        val dnsLog = DnsLog()

        dnsLog.uid = transaction.uid
        dnsLog.blockLists = transaction.blocklist
        dnsLog.resolverId = transaction.id
        dnsLog.proxyId = transaction.proxyId
        dnsLog.relayIP = transaction.relayName
        dnsLog.dnsType = transaction.transportType.ordinal
        dnsLog.latency = transaction.latency
        dnsLog.queryStr = transaction.qName
        dnsLog.responseTime = transaction.latency
        dnsLog.serverIP = transaction.serverName
        dnsLog.status = transaction.status.name
        dnsLog.time = transaction.responseCalendar.timeInMillis
        dnsLog.ttl = transaction.ttl
        dnsLog.msg = transaction.msg
        dnsLog.upstreamBlock = transaction.upstreamBlock
        dnsLog.region = transaction.region
        dnsLog.isCached = transaction.isCached
        dnsLog.dnssecOk = transaction.dnssecOk
        dnsLog.dnssecValid = transaction.dnssecValid
        dnsLog.blockedTarget = transaction.blockedTarget
        val typeName = ResourceRecordTypes.getTypeName(transaction.type.toInt())
        if (typeName == ResourceRecordTypes.UNKNOWN) {
            dnsLog.typeName = transaction.type.toString()
        } else {
            dnsLog.typeName = typeName.desc
        }

        dnsLog.resolver = transaction.serverName

        // mark the query as blocked if the transaction id is Dnsx.BlockAll, no need to check
        // for blocklist as it is already marked as blocked
        if (transaction.id == Backend.BlockAll) {
            // TODO: rdata should be either empty / 0.0.0.0 / ::0 / -- for block all
            if (transaction.response.isNotEmpty() && transaction.response != UNSPECIFIED_IP_IPV4 && transaction.response != UNSPECIFIED_IP_IPV6 && transaction.response != EMPTY_RESPONSE) {
                Logger.w(
                    LOG_TAG_VPN,
                    "id is BlockAll, but rdata is not empty: ${transaction.response} for ${transaction.qName}"
                )
            }
            dnsLog.isBlocked = true
        }

        if (transaction.status === Transaction.Status.COMPLETE) {

            if (ResourceRecordTypes.mayContainIP(transaction.type.toInt())) {
                val addresses = transaction.response.split(",").toTypedArray()
                val destination = normalizeIp(addresses.getOrNull(0))

                if (destination != null) {
                    val countryCode: String? = getCountryCode(destination, context)
                    // addresses cannot be empty if destination is not null
                    dnsLog.response = makeAddressPair(countryCode, addresses[0])

                    dnsLog.responseIps =
                        addresses.joinToString(separator = ",") {
                            val addr = normalizeIp(it)
                            makeAddressPair(getCountryCode(addr, context), it)
                        }
                    dnsLog.isBlocked =
                        destination.hostAddress == UNSPECIFIED_IP_IPV4 ||
                                destination.hostAddress == UNSPECIFIED_IP_IPV6

                    dnsLog.flag = getFlagIfPresent(countryCode)
                } else {
                    // no ip address found
                    dnsLog.flag =
                        context.getString(R.string.unicode_question_sign) // white question mark
                    // add the response if it is not empty, in case of HTTP SVCB records, the
                    // ip address is empty but the response is not
                    if (transaction.response.isNotEmpty()) {
                        dnsLog.response = transaction.response.take(RDATA_MAX_LENGTH)
                    }
                    // there is no empty response, instead -- is added as response from go,
                    // there are cases where the response so check for empty response as well
                    if ((transaction.response == EMPTY_RESPONSE) && (transaction.blocklist.isNotEmpty() || transaction.upstreamBlock)) {
                        dnsLog.isBlocked = true
                    }
                }
            } else {
                // make sure we don't log too much data
                dnsLog.response = transaction.response.take(RDATA_MAX_LENGTH)
                dnsLog.flag = context.getString(R.string.unicode_check_sign) // green check mark
            }
        } else if (transaction.status == Transaction.Status.START) {
            if (transaction.response.isNotEmpty()) {
                dnsLog.response = transaction.response.take(RDATA_MAX_LENGTH)
            } else {
                dnsLog.response = transaction.status.name
            }
            dnsLog.flag = context.getString(R.string.unicode_start_sign) // start sign
        } else {
            // error
            dnsLog.response = transaction.status.name
            dnsLog.flag = context.getString(R.string.unicode_warning_sign) // Warning sign
        }

        if (persistentState.fetchFavIcon) {
            io { fetchFavIcon(context, dnsLog) }
        }

        // fetch appName and packageName from uid
        if (transaction.uid != INVALID_UID) {
            val appNames = FirewallManager.getAppNamesByUid(transaction.uid)
            val appCount = appNames.count()
            if (appCount >= 1) {
                dnsLog.appName = if (appCount >= 2) {
                    context.getString(
                        R.string.ctbs_app_other_apps,
                        appNames[0],
                        appCount.minus(1).toString()
                    )
                } else {
                    appNames[0]
                }
                val pkgName = FirewallManager.getPackageNameByAppName(appNames[0])
                dnsLog.packageName = pkgName ?: EMPTY_PACKAGE_NAME
            } else {
                dnsLog.appName = context.getString(R.string.network_log_app_name_unnamed, transaction.uid.toString())
                dnsLog.packageName = EMPTY_PACKAGE_NAME
            }
        } else {
            dnsLog.appName = context.getString(R.string.network_log_app_name_unknown)
            dnsLog.packageName = EMPTY_PACKAGE_NAME
        }
        // ── DNSSEC enforcement & DNS-poison heuristics ────────────────────────────
        // Runs after isBlocked is resolved so already-blocked queries are not re-examined.
        if (transaction.status == Transaction.Status.COMPLETE && !dnsLog.isBlocked) {
            val guard = DnsSecGuard.validate(
                qName       = dnsLog.queryStr,
                dnssecOk    = dnsLog.dnssecOk,
                dnssecValid = dnsLog.dnssecValid,
                responseIps = transaction.response
            )
            if (guard.isSuspect()) {
                val guardTag = when (guard) {
                    is DnsSecGuard.CheckResult.PoisonSuspect -> "WARN:poison-suspect"
                    is DnsSecGuard.CheckResult.BogonIp       -> "WARN:bogon-ip"
                    is DnsSecGuard.CheckResult.DnssecMissing -> "WARN:dnssec-unvalidated"
                    else -> ""
                }
                dnsLog.msg = if (dnsLog.msg.isNotEmpty()) "${dnsLog.msg} | $guardTag" else guardTag

                // Enforcement: only fires when "Enable DNSSEC" is on in Home > Configure > DNS.
                // We deliberately only block BogonIp / PoisonSuspect, never DnssecMissing on its
                // own — dnssecValid (the AD bit) is false for the huge majority of domains simply
                // because most zones aren't DNSSEC-signed, so treating "missing" as blockable
                // would break normal browsing. A resolved bogon address, on the other hand, is
                // never a legitimate public answer.
                val shouldEnforce = persistentState.enableDnssecValidation &&
                    (guard is DnsSecGuard.CheckResult.BogonIp || guard.isPoisonSuspect())
                if (shouldEnforce) {
                    dnsLog.isBlocked = true
                    if (dnsLog.blockedTarget.isEmpty()) {
                        dnsLog.blockedTarget = "DNSSEC enforcement"
                    }
                    dnsLog.msg = if (dnsLog.msg.isNotEmpty()) {
                        "${dnsLog.msg} | BLOCKED:dnssec-enforcement"
                    } else {
                        "BLOCKED:dnssec-enforcement"
                    }

                    // Marking the DnsLog row as blocked only affects the log/UI — by the time
                    // this callback runs, the answer has already gone back to the querying app
                    // (see onQuery/onResponse in the DNS path). Real enforcement therefore means
                    // adding a universal (UID_EVERYBODY) IP-block rule for the offending
                    // address(es) so any subsequent connection attempt to them is stopped at
                    // flow() — the same mechanism RULE2D (block-universal-ip) already relies on.
                    val bogonAddrs = when (guard) {
                        is DnsSecGuard.CheckResult.BogonIp -> guard.bogonAddresses
                        is DnsSecGuard.CheckResult.PoisonSuspect -> guard.bogonAddresses
                        else -> emptyList()
                    }
                    bogonAddrs.forEach { addr ->
                        try {
                            val ipAddress = IPAddressString(addr).address
                            if (ipAddress != null) {
                                IpRulesManager.addIpRule(
                                    UID_EVERYBODY,
                                    ipAddress,
                                    null,
                                    IpRulesManager.IpRuleStatus.BLOCK,
                                    proxyId = "",
                                    proxyCC = ""
                                )
                            }
                        } catch (e: Exception) {
                            Logger.w(LOG_TAG_VPN, "dnssec enforcement: failed to add ip rule for $addr: ${e.message}")
                        }
                    }
                }

                if (guard.isPoisonSuspect()) {
                    Logger.crash(
                        LOG_TAG_VPN,
                        "dns-poison suspect: ${dnsLog.queryStr} -> ${transaction.response} (enforced=$shouldEnforce)",
                        null
                    )
                }
            }
        }

        return dnsLog
    }

    private fun getFlagIfPresent(hostAddress: String?): String {
        if (hostAddress == null) {
            return context.getString(R.string.unicode_warning_sign)
        }
        return getFlag(hostAddress)
    }

    suspend fun insertBatch(logs: List<*>) {
        @Suppress("UNCHECKED_CAST")
        val dnsLogs = (logs as? List<DnsLog>) ?: return
        dnsLogRepository.insertBatch(dnsLogs)
    }

    fun updateVpnConnectionState(transaction: Transaction?) {
        if (transaction == null) return

        // Update the connection state.  If the transaction succeeded, then the connection is
        // working.
        // If the transaction failed, then the connection is not working.
        // commented the code for reporting good or bad network.
        // Connection state will be unknown if the transaction is blocked locally in that case,
        // transaction status will be set as complete. So introduced check while
        // setting the connection state.
        if (transaction.status === Transaction.Status.COMPLETE) {
            // skip updating the connection state if the transaction was resolved locally.
            // locally resolved transaction has no server name, indicating it was blocked
            // by a local rule—either a firewall rule or the local DNS blocklist.

            if (isLocallyResolved(transaction)) return

            VpnController.onConnectionStateChanged(BraveVPNService.State.WORKING)
            // only update the server name if it is not empty as its only used to show ech
            VpnController.onServerNameUpdated(transaction.serverName)
        } else {
            val vpnState = vpnStateMap[transaction.status] ?: BraveVPNService.State.FAILING
            VpnController.onConnectionStateChanged(vpnState)
        }
    }

    private fun isLocallyResolved(transaction: Transaction?): Boolean {
        if (transaction == null) return false

        return transaction.serverName.isEmpty()
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
