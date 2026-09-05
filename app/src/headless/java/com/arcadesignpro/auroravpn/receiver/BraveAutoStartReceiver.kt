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
package com.arcadesignpro.auroravpn.receiver

import Logger
import Logger.LOG_TAG_VPN
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.arcadesignpro.auroravpn.service.VpnController

/**
 * Headless (carrier-device) auto-start.
 *
 * The headless flavour has no UI, so nobody can manually re-enable the tunnel after a
 * reboot, an OTA or an app update. Losing the resolver on such a device is a full
 * network blackout, therefore this receiver always attempts a restart when the tunnel
 * was active before the restart — there is deliberately no user-facing "auto start"
 * preference gate here (unlike the `full` flavour).
 *
 * It is declared `directBootAware` so the OS can deliver LOCKED_BOOT_COMPLETED, but the
 * actual start is deferred until the credential-encrypted storage is available: reading
 * SharedPreferences (which VpnController.state() does) before user unlock throws.
 */
class BraveAutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // credential-encrypted storage is not readable yet; ACTION_BOOT_COMPLETED
                // or ACTION_USER_UNLOCKED will follow and drive the actual start.
                Logger.i(LOG_TAG_VPN, "auto-start: locked boot, deferring until unlock")
                return
            }
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // fall through
            }
            else -> {
                Logger.w(LOG_TAG_VPN, "auto-start: unhandled broadcast ${intent.action}")
                return
            }
        }

        // the receiver runs on the main thread with a ~10s budget; everything below is
        // non-blocking (a prepare() check plus startForegroundService).
        try {
            if (!VpnController.state().activationRequested) {
                Logger.i(LOG_TAG_VPN, "auto-start: vpn was not active before ${intent.action}")
                return
            }
            if (VpnController.isAlwaysOn(context)) {
                // android brings up always-on VPNs by itself; racing it causes a
                // start/stop flap that can leave the tun down.
                Logger.i(LOG_TAG_VPN, "auto-start: always-on set, letting the OS start us")
                return
            }
            val prepareIntent =
                try {
                    VpnService.prepare(context)
                } catch (_: NullPointerException) {
                    Logger.w(LOG_TAG_VPN, "auto-start: device has no system-wide vpn support")
                    return
                }
            if (prepareIntent != null) {
                // consent missing: headless builds are platform-signed and normally
                // pre-consented, so this is an operator-visible misconfiguration.
                Logger.w(LOG_TAG_VPN, "auto-start: vpn consent missing, cannot self-start")
                return
            }
            Logger.i(LOG_TAG_VPN, "auto-start: starting vpn after ${intent.action}")
            VpnController.start(context, autoAttempt = true)
        } catch (e: Exception) {
            // never let a boot receiver crash the process: a crash loop at boot is the
            // one failure mode that is worse than a slow start.
            Logger.e(LOG_TAG_VPN, "auto-start failed: ${e.message}", e)
        }
    }
}
