/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.customdownloader

import Logger
import com.celzero.bravedns.util.Constants
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import retrofit2.Retrofit
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class RetrofitManager {

    companion object {
        // Bug fix: original values were 1 min connect / 20 min read / 5 min write.
        // A 20-minute read timeout is dangerously high for a DNS-privacy app —
        // it masks stalled connections and produced the "read: 106751d …" log anomaly.
        // New values match the Go-side firestack timeouts and give clear, fast failures.
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS    = 30L
        private const val WRITE_TIMEOUT_SECONDS   = 30L

        enum class OkHttpDnsType {
            DEFAULT,      // Quad9  (malware-blocking, DNSSEC-validating)
            CLOUDFLARE,   // 1.1.1.1 (privacy-first)
            GOOGLE,       // 8.8.8.8 (broad reach)
            SYSTEM_DNS,   // OS resolver (last resort)
            FALLBACK_DNS  // give up — let OkHttp use its platform default
        }

        fun getBlocklistBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.DOWNLOAD_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun getTcpProxyBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.TCP_PROXY_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun getIpInfoBaseBuilder(isRinRActive: Boolean): Retrofit.Builder {
            return Retrofit.Builder()
                .baseUrl(Constants.IP_INFO_BASE_URL)
                .client(okHttpClient(isRinRActive))
        }

        fun okHttpClient(isRinRActive: Boolean): OkHttpClient {
            val b = OkHttpClient.Builder()
            b.connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            b.readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            b.writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            b.retryOnConnectionFailure(true)
            // Only override DNS when routing via VPN (RinR mode); otherwise let the
            // system resolver handle it — replacing it unconditionally creates a
            // chicken-and-egg problem when the VPN is not yet up.
            if (isRinRActive) {
                val bootstrap = b.build()
                customDns(bootstrap)?.let { b.dns(it) }
            }
            return b.build()
        }

        /**
         * Build a DoH-backed [Dns] implementation for OkHttp, trying providers in
         * priority order and falling back to the next on any error.
         *
         * Bug fix: the original code used `forEach { return … }` which always
         * returned after the *first* iteration (Quad9), making Cloudflare, Google,
         * and the system resolver dead code.  We now use `firstNotNullOfOrNull` so
         * each provider is tried in sequence and only skipped on exception.
         */
        private fun customDns(bootstrapClient: OkHttpClient): Dns? {
            return enumValues<OkHttpDnsType>().firstNotNullOfOrNull { type ->
                try {
                    when (type) {
                        OkHttpDnsType.DEFAULT -> {
                            // Quad9 — DNSSEC-validating, malware-blocking
                            DnsOverHttps.Builder()
                                .client(bootstrapClient)
                                .url("https://dns.quad9.net/dns-query".toHttpUrl())
                                .bootstrapDnsHosts(
                                    getByIp("9.9.9.9"),
                                    getByIp("149.112.112.112"),
                                    getByIp("2620:fe::9"),
                                    getByIp("2620:fe::fe")
                                )
                                .includeIPv6(true)
                                .build()
                        }
                        OkHttpDnsType.CLOUDFLARE -> {
                            // Cloudflare 1.1.1.1 — privacy-first DoH
                            DnsOverHttps.Builder()
                                .client(bootstrapClient)
                                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                                .bootstrapDnsHosts(
                                    getByIp("1.1.1.1"),
                                    getByIp("1.0.0.1"),
                                    getByIp("2606:4700:4700::1111"),
                                    getByIp("2606:4700:4700::1001")
                                )
                                .includeIPv6(true)
                                .build()
                        }
                        OkHttpDnsType.GOOGLE -> {
                            // Google 8.8.8.8 — broad reach, last DoH fallback
                            DnsOverHttps.Builder()
                                .client(bootstrapClient)
                                .url("https://dns.google/dns-query".toHttpUrl())
                                .bootstrapDnsHosts(
                                    getByIp("8.8.8.8"),
                                    getByIp("8.8.4.4"),
                                    getByIp("2001:4860:4860::8888"),
                                    getByIp("2001:4860:4860::8844")
                                )
                                .includeIPv6(true)
                                .build()
                        }
                        OkHttpDnsType.SYSTEM_DNS -> {
                            // Fall back to the OS resolver — no DoH, but still works
                            Dns.SYSTEM
                        }
                        OkHttpDnsType.FALLBACK_DNS -> {
                            // Nothing left to try; return null so OkHttp uses its
                            // platform default (same as SYSTEM_DNS in practice).
                            null
                        }
                    }
                } catch (e: Exception) {
                    Logger.crash(
                        Logger.LOG_TAG_DOWNLOAD,
                        "customDns: provider $type failed, trying next: ${e.message}",
                        e
                    )
                    null // continue to next provider
                }
            }
        }

        private fun getByIp(ip: String): InetAddress {
            return try {
                InetAddress.getByName(ip)
            } catch (e: Exception) {
                Logger.e(Logger.LOG_TAG_DOWNLOAD, "getByIp: bad literal '$ip': ${e.message}", e)
                throw e
            }
        }
    }
}
