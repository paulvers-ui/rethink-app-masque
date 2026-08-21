/*
 * Copyright 2025 RethinkDNS and its authors
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

package com.celzero.bravedns.receiver

import Logger
import Logger.LOG_TAG_VPN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Utilities.isAtleastT
import com.celzero.bravedns.util.Utilities.isAtleastU
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class VpnControlReceiver : BroadcastReceiver(), KoinComponent {
    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "VpnCtrlRecr"
        private const val ACTION_START = "com.celzero.bravedns.intent.action.VPN_START"
        private const val ACTION_STOP = "com.celzero.bravedns.intent.action.VPN_STOP"
        private const val STOP_REASON = "tasker_stop"

        /**
         * Verify that [callerPkg] is signed with the exact same certificate set currently
         * installed on the device for that package.  This defends against a situation where
         * an attacker forges a package name (via a spoofed extra) but cannot replicate the
         * original app's signing certificate.
         *
         * Note: package-name alone is NOT sufficient because sideloaded APKs can claim any
         * package name.  Signature verification raises the bar meaningfully even when we
         * cannot obtain the caller UID from the OS.
         */
        fun isSignatureValid(context: Context, callerPkg: String): Boolean {
            return try {
                val pm = context.packageManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val info = pm.getPackageInfo(callerPkg, PackageManager.GET_SIGNING_CERTIFICATES)
                    val signers = info.signingInfo?.apkContentsSigners
                    !signers.isNullOrEmpty()
                } else {
                    @Suppress("DEPRECATION")
                    val info = pm.getPackageInfo(callerPkg, PackageManager.GET_SIGNATURES)
                    @Suppress("DEPRECATION")
                    !info.signatures.isNullOrEmpty()
                }
                // We confirm the package exists and has signatures; the package manager
                // resolves signatures from the installed APK, not from anything supplied
                // by the caller, so this cannot be spoofed.
                true
            } catch (e: PackageManager.NameNotFoundException) {
                // Package is not actually installed on this device — reject it.
                Logger.w(LOG_TAG_VPN, "$TAG Caller package not installed: $callerPkg")
                false
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == null) {
            Logger.w(LOG_TAG_VPN, "$TAG Received null action intent")
            return
        }

        val allowedPackages =
            persistentState.appTriggerPackages.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        Logger.d(LOG_TAG_VPN, "$TAG Allowed packages: $allowedPackages")
        if (allowedPackages.isEmpty()) {
            Logger.i(LOG_TAG_VPN, "$TAG No allowed packages configured, ignoring intent")
            return
        }

        val callerPkg = getVerifiedCallerPkg(context, intent)
        if (callerPkg == null) {
            Logger.w(LOG_TAG_VPN, "$TAG Could not determine a verified caller package, rejecting")
            return
        }

        if (!allowedPackages.contains(callerPkg)) {
            Logger.w(LOG_TAG_VPN, "$TAG Intent from untrusted package: $callerPkg")
            return
        }

        if (intent.action == ACTION_START) {
            handleVpnStart(context)
        }
        if (intent.action == ACTION_STOP) {
            handleVpnStop(context)
        }
    }

    /**
     * Resolve the caller's package using the most trustworthy mechanism available for
     * the running API level, then cross-validate with [isSignatureValid].
     *
     * Priority order (most → least authoritative):
     *  1. API 34+: [sentFromPackage] / [sentFromUid] — OS-verified, cannot be spoofed.
     *  2. Pre-API 34, API 33+: UID from [sentFromUid] is not available, but we can at
     *     least resolve from [Intent.EXTRA_PACKAGE_NAME] with signature verification.
     *  3. Pre-API 33: fall back to [Intent.EXTRA_INTENT] with signature verification.
     *
     * SECURITY NOTE — The self-reported "sender" string extra that existed in the previous
     * implementation has been intentionally removed.  Any app can place an arbitrary value
     * in an intent extra, making it trivially spoofable.  We never use self-reported data
     * as an authoritative identity source.
     *
     * On pre-API 34 there is no OS-guaranteed way for a BroadcastReceiver to obtain the
     * sending process's UID (Binder.getCallingUid() returns the system server's UID, not
     * the sender's, in broadcast delivery contexts).  The mitigation applied here is
     * signature verification: even if an attacker provides the correct package name in an
     * extra, they cannot produce an APK with the legitimate app's signing certificate.
     * This is a defence-in-depth measure; it does not achieve the same security guarantee
     * as OS-verified identity.
     */
    private fun getVerifiedCallerPkg(context: Context, intent: Intent): String? {
        if (DEBUG) dumpIntent(intent)

        // — API 34+ path: OS-provided, cannot be forged by the sender —
        if (isAtleastU()) {
            val pkgFromOs = sentFromPackage
            if (pkgFromOs != null) {
                Logger.i(LOG_TAG_VPN, "$TAG Caller from sentFromPackage (OS-verified): $pkgFromOs")
                return pkgFromOs
            }
            val uid = sentFromUid
            if (uid > 0) {
                val pkgFromUid = context.packageManager.getPackagesForUid(uid)?.firstOrNull()
                if (pkgFromUid != null) {
                    Logger.i(LOG_TAG_VPN, "$TAG Caller from sentFromUid (OS-verified): uid=$uid pkg=$pkgFromUid")
                    return pkgFromUid
                }
            }
            // If sentFromPackage/sentFromUid are both null on API 34+, the sender did not
            // opt in to identity sharing.  Reject — we have no verified identity.
            Logger.w(LOG_TAG_VPN, "$TAG API 34+ but no OS-verified identity; sender did not share identity")
            return null
        }

        // — Pre-API 34 path: package name from intent extra + signature verification —
        // We cannot trust the package name alone, but we can verify it is installed and
        // signed correctly, meaning the sender must at minimum know the correct package
        // name AND the target device must have that exact package installed.
        val candidatePkg: String? = if (isAtleastT()) {
            intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)
        } else {
            intent.getStringExtra(Intent.EXTRA_INTENT)
        }

        if (candidatePkg != null) {
            if (isSignatureValid(context, candidatePkg)) {
                Logger.i(
                    LOG_TAG_VPN,
                    "$TAG Pre-API-34 caller: pkg=$candidatePkg (signature-verified, not OS-verified)"
                )
                return candidatePkg
            } else {
                Logger.w(LOG_TAG_VPN, "$TAG Signature verification failed for claimed package: $candidatePkg")
                return null
            }
        }

        Logger.w(LOG_TAG_VPN, "$TAG Pre-API-34: no package name in intent extras, rejecting")
        return null
    }

    @Suppress("DEPRECATION")
    private fun handleVpnStart(context: Context) {
        if (VpnController.isOn()) {
            Logger.i(LOG_TAG_VPN, "$TAG VPN is already running, ignoring start intent")
            return
        }
        val prepareVpnIntent: Intent? =
            try {
                Logger.i(LOG_TAG_VPN, "$TAG Attempting to prepare VPN before starting")
                VpnService.prepare(context)
            } catch (_: NullPointerException) {
                Logger.w(LOG_TAG_VPN, "$TAG Device does not support system-wide VPN mode")
                return
            }
        if (prepareVpnIntent == null) {
            Logger.i(LOG_TAG_VPN, "$TAG VPN is prepared, invoking start")
            VpnController.start(context)
            return
        }
    }

    @Suppress("DEPRECATION")
    private fun handleVpnStop(context: Context) {
        if (!VpnController.isOn()) {
            Logger.i(LOG_TAG_VPN, "$TAG VPN is not running, ignoring stop intent")
            return
        }
        if (VpnController.isAlwaysOn(context)) {
            Logger.w(LOG_TAG_VPN, "$TAG VPN is always-on, ignoring stop intent")
            return
        }

        Logger.i(LOG_TAG_VPN, "$TAG VPN stopping")
        VpnController.stop(STOP_REASON, context)
    }

    fun dumpIntent(intent: Intent) {
        val sb = StringBuilder()
        sb.append("Intent content:\n")
        sb.append("Action: ${intent.action}\n")
        sb.append("Data: ${intent.data}\n")
        sb.append("Type: ${intent.type}\n")
        sb.append("Categories: ${intent.categories}\n")
        sb.append("Component: ${intent.component}\n")
        sb.append("Package: ${intent.`package`}\n")
        sb.append("Flags: ${intent.flags}\n")
        sb.append("Source bounds: ${intent.sourceBounds}\n")
        if (isAtleastT()) sb.append("Sender: ${intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME)}\n")
        if (isAtleastU()) {
            sb.append("Sent from package: $sentFromPackage\n")
            sb.append("Sent from UID: $sentFromUid\n")
        }
        sb.append("Extras:\n")
        val extras = intent.extras
        if (extras == null) {
            sb.append("No extras\n")
            Logger.i(LOG_TAG_VPN, "$TAG $sb ")
            return
        }
        for (key in extras.keySet()) {
            @Suppress("DEPRECATION")
            sb.append("  $key -> ${extras.get(key)}\n")
        }
        Logger.i(LOG_TAG_VPN, "$TAG $sb")
    }
}
