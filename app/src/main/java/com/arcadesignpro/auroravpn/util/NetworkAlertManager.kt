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
package com.arcadesignpro.auroravpn.util

import Logger
import Logger.LOG_TAG_VPN
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import com.arcadesignpro.auroravpn.service.PersistentState
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Fires a short audible alert whenever the tunnel/proxy/network status changes in a way
 * the user cares about. Built for a "fail loud, not silent" use-case: a status label
 * quietly flipping to "inactive" (or staying "Protected" in red) is not enough when
 * someone's safety may depend on knowing immediately.
 *
 * The sound itself is picked from the device's *notification* sound library (not alarm/
 * ringtone) via [buildPickerIntent]. Playback, however, still uses [AudioAttributes.
 * USAGE_ALARM] on a plain [MediaPlayer] -- the same stream real alarm-clock apps use --
 * rather than actually posting it as a notification: alarm-stream audio uses the device's
 * alarm volume and, on stock Android, plays through ringer silent/vibrate mode the same
 * way a wake-up alarm does. This intentionally avoids the separate "Do Not Disturb access"
 * permission grant (Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) -- that flow
 * requires the user to leave the app, and NotificationChannel.setBypassDnd() only works
 * after it's granted. If a device's DND config still suppresses the alarm stream (some OEM
 * "Total Silence" modes do), the fix is a system Settings change by the user, not something
 * this app can force from within its own sandbox.
 */
object NetworkAlertManager : KoinComponent {

    enum class Kind {
        TUNNEL_DOWN,
        TUNNEL_UP,
        // Reserved for a proxy health watchdog. Not wired on this base -- there is no
        // usque health checkpoint here to report from -- but kept so the Kind space and
        // the shared cooldown stay stable if one is added later.
        PROXY_UNHEALTHY,
        PROXY_HEALTHY,
        INTERFACE_SWITCH
    }

    private val persistentState by inject<PersistentState>()

    // Global (not per-Kind) debounce/cooldown. A single real-world failure -- e.g. the
    // network drops -- routinely trips several independent detectors within the same
    // second (interface-switch, tunnel-state, proxy-watchdog, UI red/green status all
    // observe the same underlying event from different angles). Per-Kind debouncing let
    // each of those fire its own sound in the same second, which is what "sounds crazy".
    // One shared cooldown across every Kind means a cluster of related detections from
    // one event collapses into a single alert.
    private const val COOLDOWN_MS = 8_000L
    @Volatile private var lastFiredAtMs = 0L
    @Volatile private var currentPlayer: MediaPlayer? = null

    @Synchronized
    fun fire(context: Context, kind: Kind) {
        if (!persistentState.networkAlertsEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastFiredAtMs < COOLDOWN_MS) return
        lastFiredAtMs = now

        Logger.i(LOG_TAG_VPN, "NetworkAlertManager: firing alert for $kind")
        playAlertSound(context.applicationContext)
    }

    // Separate from fire()'s cooldown: this reflects what's actually visible on-screen
    // right now (e.g. HomeScreenFragment's red/green protection-level label), so it
    // shares the same Kind space and the same global cooldown, but tracks its own
    // up/down edge independently of BraveVPNService.State transitions -- this is what
    // catches cases like "connected with SOCKS5/WireGuard but shown in red" that aren't
    // represented by any single State value.
    @Volatile private var lastUiWasBad: Boolean? = null

    @Synchronized
    fun reportUiStatus(context: Context, isBad: Boolean) {
        val prev = lastUiWasBad
        lastUiWasBad = isBad
        if (prev == null || prev == isBad) return // first observation, or no real change
        fire(context, if (isBad) Kind.TUNNEL_DOWN else Kind.TUNNEL_UP)
    }

    @Synchronized
    private fun playAlertSound(context: Context) {
        // Don't stack a second sound on top of one still playing (can happen if two
        // callers race past the cooldown check at nearly the same instant).
        if (currentPlayer?.isPlaying == true) return

        try {
            val uri = resolveSoundUri(context)
            if (uri == null) {
                Logger.w(LOG_TAG_VPN, "NetworkAlertManager: no alert sound available, skipping")
                return
            }

            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(context, uri)
            mp.setOnCompletionListener { player ->
                runCatching { player.release() }
                if (currentPlayer === player) currentPlayer = null
            }
            mp.setOnErrorListener { player, what, extra ->
                Logger.w(LOG_TAG_VPN, "NetworkAlertManager: playback error what=$what extra=$extra")
                runCatching { player.release() }
                if (currentPlayer === player) currentPlayer = null
                true
            }
            mp.prepare()
            currentPlayer = mp
            mp.start()
        } catch (e: java.io.IOException) {
            // setDataSource/prepare on a missing or revoked content Uri.
            logPlaybackFailure(e)
        } catch (e: IllegalStateException) {
            // MediaPlayer used out of order, or released underneath us.
            logPlaybackFailure(e)
        } catch (e: IllegalArgumentException) {
            // Malformed Uri handed to setDataSource.
            logPlaybackFailure(e)
        } catch (e: SecurityException) {
            // No read permission for the chosen sound (grant revoked since it was picked).
            logPlaybackFailure(e)
        }
    }

    // A failed alert sound must never take down the caller: this is best-effort
    // signalling, not something the tunnel's correctness depends on.
    private fun logPlaybackFailure(e: Exception) {
        Logger.w(LOG_TAG_VPN, "NetworkAlertManager: failed to play alert: ${e.message ?: ""}")
    }

    private fun resolveSoundUri(context: Context): Uri? {
        val stored = persistentState.networkAlertSoundUri
        if (stored.isNotEmpty()) {
            return runCatching { Uri.parse(stored) }.getOrNull()
        }
        return RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /** Builds the system sound-picker intent (notification sounds), pre-selecting the current choice, if any. */
    fun buildPickerIntent(): Intent {
        val existing = persistentState.networkAlertSoundUri
        val existingUri = if (existing.isNotEmpty()) runCatching { Uri.parse(existing) }.getOrNull() else null
        return Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            )
            if (existingUri != null) {
                putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
            }
        }
    }

    /** Human-readable title for the currently selected alert sound, for the settings row. */
    fun currentSoundTitle(context: Context): String {
        val uri = resolveSoundUri(context) ?: return "None"
        return runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull() ?: "Default notification sound"
    }

    /** Persists the user's picker result. Pass null to reset to the device default. */
    fun onSoundPicked(uri: Uri?) {
        persistentState.networkAlertSoundUri = uri?.toString() ?: ""
    }
}
