/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.activity

import Logger
import Logger.LOG_TAG_PROXY
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.RotateAnimation
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.ProxyEndpoint
import com.celzero.bravedns.database.ProxyEndpoint.Companion.DEFAULT_PROXY_TYPE
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.DialogSetProxyBinding
import com.celzero.bravedns.databinding.FragmentProxyConfigureBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.TcpProxyHelper
import com.celzero.bravedns.service.UsqueManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.openUrl
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.delay
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.isValidPort
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RouterStats
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit


class ProxySettingsActivity : AppCompatActivity(R.layout.fragment_proxy_configure) {
    private val b by viewBinding(FragmentProxyConfigureBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val eventLogger by inject<EventLogger>()
    private lateinit var animation: Animation

    private var isWarpStarting = false

    companion object {
        private const val REFRESH_TIMEOUT: Long = 4000
        private const val ANIMATION_DURATION = 750L
        private const val ANIMATION_REPEAT_COUNT = -1
        private const val ANIMATION_PIVOT_VALUE = 0.5f
        private const val ANIMATION_START_DEGREE = 0.0f
        private const val ANIMATION_END_DEGREE = 360.0f
        // Reserved ID for the WARP SOCKS5 proxy row. Negative so Room's auto-increment
        // (which starts at 1) never collides with the user's custom proxy entries.
        private const val WARP_PROXY_ID = -1
        // How long to wait, and how often to re-poll, for UsqueManager.isRunning() to settle
        // after enabling WARP before trusting it in updateWarpUi() (see the io{} block below).
        private const val WARP_SETTLE_TIMEOUT_MS = 3000L
        private const val WARP_SETTLE_POLL_MS = 200L
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        initAnimation()
        initView()
        initClickListeners()
        // Do NOT call displayWarpUi() here; onResume() always runs next and calls it,
        // so calling it in onCreate causes a redundant double-render that can flicker.
    }

    private fun initAnimation() {
        animation =
            RotateAnimation(
                ANIMATION_START_DEGREE,
                ANIMATION_END_DEGREE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE,
                Animation.RELATIVE_TO_SELF,
                ANIMATION_PIVOT_VALUE
            )
        animation.repeatCount = ANIMATION_REPEAT_COUNT
        animation.duration = ANIMATION_DURATION
    }

    override fun onResume() {
        super.onResume()
        handleProxyUi()
        displayWireguardUi()
        // If WARP was enabled but the process died (app killed, VPN restarted),
        // attempt a silent restart so the switch shows the correct state.
        if (persistentState.usqueEnabled && !UsqueManager.isRunning()) {
            // Process reference lost (e.g. after navigation) — check if port is still alive
            // before doing a full restart. If the proxy is already listening, just refresh UI.
            //
            // Show the switch in the user's *intended* state (ON + disabled) immediately so
            // they never see it flash to OFF while the background probe is in-flight.
            b.settingsActivityWarpSwitch.setOnCheckedChangeListener(null)
            b.settingsActivityWarpSwitch.isChecked = true   // intent = ON; do NOT show OFF while probing
            b.settingsActivityWarpSwitch.isEnabled = false
            isWarpStarting = true
            io {
                // Fast port probe first (300ms timeout) — avoids unnecessary restart
                val portAlive = UsqueManager.isPortAlive()
                if (portAlive) {
                    // Port is already listening; proxy is running, just lost the JVM reference.
                    // Reattach it so isRunning() stays accurate.
                    UsqueManager.reattachIfPortAlive(this@ProxySettingsActivity)
                } else {
                    val started = UsqueManager.startSocksProxy(this@ProxySettingsActivity)
                    if (!started) {
                        // Sprint 21: do NOT permanently disable on a single failed restart —
                        // the watchdog / death-callback will retry. Logging only.
                        Logger.w(LOG_TAG_PROXY, "usque: onResume restart failed; watchdog will retry")
                    }
                }
                uiCtx {
                    isWarpStarting = false
                    updateWarpUi()
                }
            }
        } else {
            // Always refresh the WARP switch on resume so the visual state stays
            // consistent with the actual running state.
            if (!isWarpStarting) updateWarpUi()
        }
    }

    private fun initView() {
        b.settingsActivityHttpProxyProgress.visibility = View.GONE
        b.settingsWireguardTitle.text = getString(R.string.lbl_wireguard).lowercase()
        b.otherTitle.text = getString(R.string.category_name_others).lowercase()

        b.rpnTitle.visibility = View.GONE
        b.settingsActivityRpnContainer.visibility = View.GONE

        displayHttpProxyUi()
        displaySocks5Ui()
    }

    private fun initClickListeners() {

        b.settingsActivityRpnContainer.setOnClickListener { }

        b.wgRefresh.setOnClickListener { refresh() }

        b.settingsActivitySocks5Rl.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = !b.settingsActivitySocks5Switch.isChecked
        }

        b.settingsActivitySocks5Switch.setOnCheckedChangeListener {
            _: CompoundButton,
            checked: Boolean ->
            if (!checked) {
                appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
                b.settingsActivitySocks5Desc.text =
                    getString(R.string.settings_socks_forwarding_default_desc)
                logEvent("Custom SOCKS5 Proxy disabled", "disabled custom SOCKS5 proxy")
                return@setOnCheckedChangeListener
            }

            if (appConfig.getBraveMode().isDnsMode()) {
                b.settingsActivitySocks5Switch.isChecked = false
                return@setOnCheckedChangeListener
            }

            if (!appConfig.canEnableSocks5Proxy()) {
                val s = persistentState.proxyProvider.lowercase().replaceFirstChar(Char::titlecase)
                showToastUiCentered(
                    this,
                    getString(R.string.settings_socks5_disabled_error, s),
                    Toast.LENGTH_SHORT
                )
                b.settingsActivitySocks5Switch.isChecked = false
                return@setOnCheckedChangeListener
            }
            io {
                val endpoint = appConfig.getSocks5ProxyDetails()
                if (endpoint == null) {
                    uiCtx {
                        showToastUiCentered(
                            this,
                            getString(R.string.blocklist_update_check_failure),
                            Toast.LENGTH_SHORT
                        )
                    }
                    return@io
                }
                val packageName = endpoint.proxyAppName
                val app = FirewallManager.getAppInfoByPackage(packageName)?.appName ?: ""
                val appNames: MutableList<String> = ArrayList()
                appNames.add(getString(R.string.settings_app_list_default_app))
                appNames.addAll(
                    FirewallManager.getAllAppNamesSortedByVpnPermission(this@ProxySettingsActivity)
                )
                uiCtx { showSocks5ProxyDialog(endpoint, appNames, app) }
            }
        }

        // ===== WARP TUNNEL SECTION =====
        b.settingsActivityWarpRegisterBtn.setOnClickListener { showWarpRegistrationDialog() }

        // ===== WireGuard -> WARP(SOCKS5) double hop =====
        b.settingsActivityWgS5HopSwitch.isChecked = persistentState.autoHopWgIntoWarp
        b.settingsActivityWgS5HopSwitch.setOnCheckedChangeListener { _, enabled ->
            persistentState.autoHopWgIntoWarp = enabled
            WgHopManager.hlog("ui: double-hop switch -> $enabled")
            logEvent("wg-socks5 double hop", "enabled=$enabled")
            io {
                val report = WgHopManager.setDoubleHopForAllConfigs(enabled)
                uiCtx {
                    showToastUiCentered(
                        this@ProxySettingsActivity,
                        if (enabled) "double hop: $report" else "double hop off: $report",
                        Toast.LENGTH_LONG
                    )
                }
            }
        }

        // SNI editor: prefill, save, reset, and live-restart WARP if it's running.
        b.settingsActivityWarpSniEdit.setText(persistentState.warpSpoofedSni)

        b.settingsActivityWarpSniSaveBtn.setOnClickListener {
            val raw = b.settingsActivityWarpSniEdit.text?.toString()?.trim().orEmpty()
            val value = if (raw.isEmpty()) "cloudflare.com" else raw.take(20)
            val previous = persistentState.warpSpoofedSni
            persistentState.warpSpoofedSni = value
            b.settingsActivityWarpSniEdit.setText(value)

            if (value == previous) {
                showToastUiCentered(this, "SNI unchanged: $value", Toast.LENGTH_SHORT)
                return@setOnClickListener
            }
            showToastUiCentered(this, "SNI saved: $value", Toast.LENGTH_SHORT)
            restartWarpForSniChange("WARP restarted with new SNI")
        }

        b.settingsActivityWarpSniResetBtn.setOnClickListener {
            val default = "cloudflare.com"
            val previous = persistentState.warpSpoofedSni
            persistentState.warpSpoofedSni = default
            b.settingsActivityWarpSniEdit.setText(default)

            if (default == previous) {
                showToastUiCentered(this, "Already at default: $default", Toast.LENGTH_SHORT)
                return@setOnClickListener
            }
            showToastUiCentered(this, "SNI reset to $default", Toast.LENGTH_SHORT)
            restartWarpForSniChange("WARP restarted with default SNI")
        }

        // config.json editor: Reload pulls the current file from disk;
        // Save validates the payload and atomically overwrites, then live-
        // restarts WARP if it's already running so the new config takes
        // effect without the user toggling the switch.
        b.settingsActivityWarpConfigReloadBtn.setOnClickListener {
            loadWarpConfigIntoEditor()
            showToastUiCentered(this, "config.json reloaded", Toast.LENGTH_SHORT)
        }

        b.settingsActivityWarpConfigSaveBtn.setOnClickListener {
            val text = b.settingsActivityWarpConfigEdit.text?.toString().orEmpty()
            if (text.isBlank()) {
                showToastUiCentered(this, "config.json is empty", Toast.LENGTH_SHORT)
                return@setOnClickListener
            }
            val ok = UsqueManager.writeConfig(this, text)
            if (!ok) {
                showToastUiCentered(this, "Invalid config.json — not saved", Toast.LENGTH_LONG)
                return@setOnClickListener
            }
            showToastUiCentered(this, "config.json saved", Toast.LENGTH_SHORT)
            restartWarpForSniChange("WARP restarted with new config")
        }

        // libusque.so args editor: Reload pulls the currently-effective arg
        // string (override or default template); Reset clears the override
        // so the default template is used again; Save validates and stores
        // the override then live-restarts WARP if it is running.
        b.settingsActivityWarpArgsReloadBtn.setOnClickListener {
            loadWarpArgsIntoEditor()
            showToastUiCentered(this, "arguments reloaded", Toast.LENGTH_SHORT)
        }

        b.settingsActivityWarpArgsResetBtn.setOnClickListener {
            UsqueManager.writeSocksArgs(this, "")
            loadWarpArgsIntoEditor()
            showToastUiCentered(this, "arguments reset to default", Toast.LENGTH_SHORT)
            restartWarpForSniChange("WARP restarted with default arguments")
        }

        b.settingsActivityWarpArgsSaveBtn.setOnClickListener {
            val text = b.settingsActivityWarpArgsEdit.text?.toString().orEmpty()
            val ok = UsqueManager.writeSocksArgs(this, text)
            if (!ok) {
                showToastUiCentered(
                    this,
                    "Invalid arguments — must include {config}",
                    Toast.LENGTH_LONG
                )
                return@setOnClickListener
            }
            loadWarpArgsIntoEditor()
            showToastUiCentered(this, "arguments saved", Toast.LENGTH_SHORT)
            restartWarpForSniChange("WARP restarted with new arguments")
        }

        // Switch listener is attached (and re-attached safely) in updateWarpUi()
        // ===== END WARP SECTION =====
        b.settingsActivityWireguardContainer.setOnClickListener { openWireguardActivity() }
        b.settingsActivityWireguardImg.setOnClickListener { openWireguardActivity() }

        b.settingsActivityHttpProxyContainer.setOnClickListener {
            b.settingsActivityHttpProxySwitch.isChecked =
                !b.settingsActivityHttpProxySwitch.isChecked
        }

        b.settingsActivityHttpProxySwitch.setOnCheckedChangeListener {
            _: CompoundButton,
            checked: Boolean ->
            if (!checked) {
                appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
                b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
                return@setOnCheckedChangeListener
            }

            if (appConfig.getBraveMode().isDnsMode()) {
                b.settingsActivityHttpProxySwitch.isChecked = false
                return@setOnCheckedChangeListener
            }

            if (!appConfig.canEnableHttpProxy()) {
                val s = persistentState.proxyProvider.lowercase().replaceFirstChar(Char::titlecase)
                showToastUiCentered(
                    this,
                    getString(R.string.settings_https_disabled_error, s),
                    Toast.LENGTH_SHORT
                )
                b.settingsActivityHttpProxySwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            io {
                val endpoint = try {
                    appConfig.getHttpProxyDetails()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "err fetching HTTP proxy details: ${e.message}", e)
                    null
                }
                if (endpoint == null) {
                    uiCtx {
                        showToastUiCentered(
                            this,
                            getString(R.string.blocklist_update_check_failure),
                            Toast.LENGTH_SHORT
                        )
                    }
                    return@io
                }
                val packageName = endpoint.proxyAppName
                val app = FirewallManager.getAppInfoByPackage(packageName)
                val appNames: MutableList<String> = ArrayList()
                appNames.add(getString(R.string.settings_app_list_default_app))
                appNames.addAll(
                    FirewallManager.getAllAppNamesSortedByVpnPermission(this@ProxySettingsActivity)
                )
                uiCtx { showHttpProxyDialog(endpoint, appNames, app?.appName) }
            }
        }
    }

    // ===== WARP METHODS =====

    private fun showWarpRegistrationDialog() {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.warp_register_button)
            .setMessage("Register device with Cloudflare WARP?")
            .setPositiveButton("Register") { dialog, _ -> dialog.dismiss(); registerWarp() }
            .setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
            .setCancelable(true)
            .create()
            .show()
    }

    private fun registerWarp() {
        val progressDialog = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(R.string.warp_registering)
            .setMessage(R.string.warp_registering)
            .setCancelable(false)
            .create()
        progressDialog.show()
        io {
            val registered = UsqueManager.registerWithWarp(this@ProxySettingsActivity)
            uiCtx {
                progressDialog.dismiss()
                // No post-register modal: surface the outcome via a toast and
                // let the proxy row reflect the new state.
                val msg =
                    if (registered) getString(R.string.warp_registered_ok)
                    else getString(R.string.warp_register_failed)
                showToastUiCentered(
                    this@ProxySettingsActivity,
                    msg,
                    Toast.LENGTH_SHORT
                )
                updateWarpUi()
            }
        }
    }

    private fun displayWarpUi() {
        updateWarpUi()
    }

    private fun updateWarpUi() {
        // Guard: if a start is already in-flight, don't clobber the switch state.
        // The coroutine will call updateWarpUi() again once it completes.
        if (isWarpStarting) return

        val isRegistered = UsqueManager.isRegistered(this)
        val isConnected = persistentState.usqueEnabled && UsqueManager.isRunning()

        b.settingsActivityWarpDesc.text = when {
            isConnected  -> getString(R.string.warp_status_active)
            isRegistered -> getString(R.string.warp_status_registered)
            else         -> getString(R.string.warp_status_unregistered)
        }

        // Register button: only shown when not yet registered
        b.settingsActivityWarpRegisterBtn.visibility =
            if (isRegistered) View.GONE else View.VISIBLE

        // config.json editor row: only meaningful once registered (a config
        // file exists on disk). Populate the field the first time the row
        // becomes visible so we don't clobber in-progress edits on refresh.
        val wasConfigRowVisible = b.settingsActivityWarpConfigRow.isVisible
        b.settingsActivityWarpConfigRow.visibility =
            if (isRegistered) View.VISIBLE else View.GONE
        if (isRegistered && (!wasConfigRowVisible ||
                b.settingsActivityWarpConfigEdit.text.isNullOrEmpty())) {
            loadWarpConfigIntoEditor()
        }

        // Args editor: shown once registered. Populate the field on first
        // reveal or when it is empty so we do not clobber in-progress edits.
        val wasArgsRowVisible = b.settingsActivityWarpArgsRow.isVisible
        b.settingsActivityWarpArgsRow.visibility =
            if (isRegistered) View.VISIBLE else View.GONE
        if (isRegistered && (!wasArgsRowVisible ||
                b.settingsActivityWarpArgsEdit.text.isNullOrEmpty())) {
            loadWarpArgsIntoEditor()
        }

        // Switch row: only shown when registered
        val wasSwitchRowVisible = b.settingsActivityWarpSwitchRow.isVisible
        b.settingsActivityWarpSwitchRow.visibility =
            if (isRegistered) View.VISIBLE else View.GONE

        // Update switch state without triggering the listener
        b.settingsActivityWarpSwitch.setOnCheckedChangeListener(null)
        b.settingsActivityWarpSwitch.isChecked = isConnected
        // Bug (original): on the very first enable, the ON/OFF slide animation didn't play.
        // Root cause: settingsActivityWarpSwitchRow starts as View.GONE (fragment_proxy_configure.xml),
        // so the switch's drawable never gets a real draw pass until the row first becomes
        // VISIBLE. Forcing a drawable sync right at that GONE -> VISIBLE transition fixed it.
        //
        // Bug (regression this introduced): ProxySettingsActivity is a real Activity, so every
        // time the user leaves and comes back, a brand-new instance is created and the switch
        // row is GONE by default again - this branch then fires on every single re-entry, not
        // just the true first-ever registration. Calling jumpDrawablesToCurrentState()
        // synchronously, in the same frame isChecked was just set, could freeze the switch on a
        // stale pre-change (OFF) drawable frame before that state change had actually been laid
        // out/drawn - so it displayed OFF even though isChecked and the real WARP connection
        // were both genuinely ON. Deferring the jump to the next frame (post{}), after the
        // checked-state change and the row's own layout pass have settled, fixes that: it syncs
        // to the state that's actually on screen instead of the one mid-transition.
        if (isRegistered && !wasSwitchRowVisible) {
            b.settingsActivityWarpSwitch.post { b.settingsActivityWarpSwitch.jumpDrawablesToCurrentState() }
        }
        b.settingsActivityWarpSwitch.isEnabled = true
        b.settingsActivityWarpSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!UsqueManager.isRegistered(this)) {
                    b.settingsActivityWarpSwitch.isChecked = false
                    showWarpRegistrationDialog()
                    return@setOnCheckedChangeListener
                }
                // Defer disabling the switch to the next frame instead of doing it inline in
                // the listener. Disabling it synchronously here forces an immediate
                // disabled-state redraw that cuts off the native checked-state slide animation
                // mid-flight, which is the other half of the "animation doesn't play" symptom.
                b.settingsActivityWarpSwitch.post { b.settingsActivityWarpSwitch.isEnabled = false }
                val warpProxyName = getString(R.string.warp_tunnel_title)
                isWarpStarting = true
                io {
                    val started = UsqueManager.startSocksProxy(this@ProxySettingsActivity)
                    if (started) {
                        // Set usqueEnabled = true BEFORE updateCustomSocks5Proxy so that any
                        // reactive observer triggered by the DB write sees the correct flag.
                        // Previously this was reversed, causing the observer to read
                        // usqueEnabled=false and flip the switch back to OFF (double-tap bug).
                        persistentState.usqueEnabled = true
                        val warpProxy = ProxyEndpoint(
                            WARP_PROXY_ID,
                            warpProxyName,
                            ProxyManager.ProxyMode.SOCKS5.value,
                            ProxyEndpoint.DEFAULT_PROXY_TYPE,
                            /* appName */ "",
                            UsqueManager.SOCKS_HOST,
                            UsqueManager.SOCKS_PORT,
                            /* userName */ "",
                            /* password */ "",
                            isSelected = true,
                            isCustom = true,
                            isUDP = false,
                            modifiedDataTime = 0L,
                            latency = 0
                        )
                        appConfig.updateCustomSocks5Proxy(warpProxy)
                        // Bug: on the very first enable, the switch flipped back to OFF even
                        // though WARP genuinely connected a moment later - it also stayed wrong
                        // until a second tap re-checked it. updateCustomSocks5Proxy() can trigger
                        // BraveVPNService to rebuild the tunnel around the new proxy, which can
                        // briefly cycle the usque process before things settle. Calling
                        // updateWarpUi() immediately could land in that gap and read
                        // isRunning()=false, then nothing re-checked it afterwards. Give it a
                        // short window to settle and re-confirm before trusting it in the UI.
                        var waitedMs = 0L
                        while (!UsqueManager.isRunning() && waitedMs < WARP_SETTLE_TIMEOUT_MS) {
                            delay(WARP_SETTLE_POLL_MS)
                            waitedMs += WARP_SETTLE_POLL_MS
                        }
                    }
                    uiCtx {
                        isWarpStarting = false
                        if (started) {
                            updateWarpUi()
                        } else {
                            b.settingsActivityWarpSwitch.isChecked = false
                            b.settingsActivityWarpSwitch.isEnabled = true
                            showToastUiCentered(
                                this@ProxySettingsActivity,
                                getString(R.string.warp_start_failed),
                                Toast.LENGTH_SHORT
                            )
                        }
                    }
                }
            } else {
                UsqueManager.stopSocksProxy()
                appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
                persistentState.usqueEnabled = false
                updateWarpUi()
            }
        }
    }

        /**
     * If WARP is currently running, stop the SOCKS proxy, briefly wait for the port
     * to free, and start it again so the new SNI value takes effect immediately.
     * No-op when WARP is not running. Always refreshes the WARP status row.
     */
    private fun restartWarpForSniChange(successMsg: String) {
        if (!(persistentState.usqueEnabled && UsqueManager.isRunning())) {
            updateWarpUi()
            return
        }
        io {
            UsqueManager.stopSocksProxy()
            kotlinx.coroutines.delay(500)
            val started = UsqueManager.startSocksProxy(this@ProxySettingsActivity)
            uiCtx {
                showToastUiCentered(
                    this@ProxySettingsActivity,
                    if (started) successMsg
                    else "WARP restart failed — toggle the switch manually",
                    Toast.LENGTH_SHORT
                )
                updateWarpUi()
            }
        }
    }

    /** Reads config.json off disk and puts its raw text into the editor. */
    private fun loadWarpConfigIntoEditor() {
        val text = UsqueManager.readConfig(this)
        b.settingsActivityWarpConfigEdit.setText(text)
    }

    /** Pulls the currently-effective libusque.so arg string (user override
     *  if saved, otherwise the default template with {config}/{sni} tokens
     *  intact) and puts it into the editor. Also refreshes the read-only
     *  "effective args" line beneath the editor so advanced users can see
     *  the fully-substituted argv that will be handed to libusque on the
     *  next start. */
    private fun loadWarpArgsIntoEditor() {
        val text = UsqueManager.currentSocksArgsForEditor()
        b.settingsActivityWarpArgsEdit.setText(text)
        b.settingsActivityWarpArgsEffective.text =
            UsqueManager.effectiveSocksArgsForDisplay(this)
    }


    // ===== END WARP METHODS =====

    private fun refresh() {
        b.wgRefresh.isEnabled = false
        b.wgRefresh.animation = animation
        b.wgRefresh.startAnimation(animation)
        io { VpnController.refreshOrPauseOrResumeOrReAddProxies() }
        delay(REFRESH_TIMEOUT, lifecycleScope) {
            b.wgRefresh.isEnabled = true
            b.wgRefresh.clearAnimation()
            showToastUiCentered(this, getString(R.string.dc_refresh_toast), Toast.LENGTH_SHORT)
        }
    }
    private fun openWireguardActivity() {
        val intent = Intent(this, WgMainActivity::class.java)
        startActivity(intent)
    }

    private fun displayHttpProxyUi() {
        val isCustomHttpProxyEnabled = appConfig.isCustomHttpProxyEnabled()
        b.settingsActivityHttpProxyContainer.visibility = View.VISIBLE
        b.settingsActivityHttpProxySwitch.isChecked = isCustomHttpProxyEnabled

        if (!appConfig.isCustomHttpProxyEnabled()) return

        io {
            val endpoint = try {
                appConfig.getHttpProxyDetails()
            } catch (e: Exception) {
                Logger.e(
                    LOG_TAG_PROXY,
                    "Error fetching HTTP proxy details in displayHttpProxyUi: ${e.message}",
                    e
                )
                null
            }
            if (endpoint == null) {
                uiCtx {
                    showToastUiCentered(
                        this,
                        getString(R.string.blocklist_update_check_failure),
                        Toast.LENGTH_SHORT
                    )
                }
                return@io
            }
            val m = ProxyManager.ProxyMode.get(endpoint.proxyMode) ?: return@io
            if (!m.isCustomHttp()) return@io
            uiCtx {
                b.settingsActivityHttpProxyContainer.visibility = View.VISIBLE
                if (b.settingsActivityHttpProxySwitch.isChecked) {
                    b.settingsActivityHttpProxyDesc.text =
                        getString(R.string.settings_http_proxy_desc, endpoint.proxyIP)
                }
            }
        }
    }

    private fun displayWireguardUi() {
        val activeWgs = WireguardManager.getActiveConfigs()
        if (activeWgs.isEmpty()) {
            b.settingsActivityWireguardDesc.text = getString(R.string.wireguard_description)
            return
        }
        io {
            var wgStatus = ""
            activeWgs.forEach {
                val id = ProxyManager.ID_WG_BASE + it.getId()
                val statusPair = VpnController.getProxyStatusById(id)
                val stats = VpnController.getProxyStats(id)
                val dnsStatusId = VpnController.getDnsStatus(id)

                val statusText =
                    if (statusPair.first == Backend.TPU) {
                        getString(
                                UIUtils.getProxyStatusStringRes(UIUtils.ProxyStatus.TPU.id)
                            )
                            .replaceFirstChar(Char::titlecase)
                    } else if (dnsStatusId != null && isDnsError(dnsStatusId)) {
                        getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
                    } else {
                        getProxyStatusText(statusPair, stats)
                    }

                wgStatus +=
                    getString(R.string.ci_ip_label, it.getName(), statusText.padStart(1, ' ')) +
                        "\n"
                Logger.d(LOG_TAG_PROXY, "current proxy status for $id: $statusText")
            }
            wgStatus = wgStatus.trimEnd()
            uiCtx { b.settingsActivityWireguardDesc.text = wgStatus }
        }
    }

    private fun isDnsError(statusId: Long?): Boolean {
        if (statusId == null) return true
        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY ||
            s == Transaction.Status.BAD_RESPONSE ||
            s == Transaction.Status.NO_RESPONSE ||
            s == Transaction.Status.SEND_FAIL ||
            s == Transaction.Status.CLIENT_ERROR ||
            s == Transaction.Status.INTERNAL_ERROR ||
            s == Transaction.Status.TRANSPORT_ERROR
    }

    private fun getProxyStatusText(
        statusPair: Pair<Long?, String>,
        stats: RouterStats?
    ): String {
        val status = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
        return getStatusText(status, stats, statusPair.second)
    }

    private fun getStatusText(
        status: UIUtils.ProxyStatus?,
        stats: RouterStats?,
        errMsg: String?
    ): String {
        if (status == null) {
            val txt =
                if (errMsg != null && errMsg.isNotEmpty()) {
                    getString(R.string.status_waiting) + " ($errMsg)"
                } else {
                    getString(R.string.status_waiting)
                }
            return txt.replaceFirstChar(Char::titlecase)
        }

        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        if (now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
            return getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
        }

        val baseText =
            getString(UIUtils.getProxyStatusStringRes(status.id)).replaceFirstChar(Char::titlecase)

        val handshakeTime =
            if (stats != null && stats.lastOK > 0L) {
                DateUtils.getRelativeTimeSpanString(
                        stats.lastOK,
                        now,
                        DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.FORMAT_ABBREV_RELATIVE
                    )
                    .toString()
            } else {
                null
            }

        return if (stats?.lastOK != 0L && handshakeTime != null) {
            getString(R.string.about_version_install_source, baseText, handshakeTime)
        } else {
            baseText
        }
    }

    private fun displaySocks5Ui() {
        val isCustomSocks5Enabled = appConfig.isCustomSocks5Enabled()
        b.settingsActivitySocks5Progress.visibility = View.GONE
        b.settingsActivitySocks5Switch.isChecked = isCustomSocks5Enabled
        if (!isCustomSocks5Enabled) return

        io {
            val endpoint: ProxyEndpoint? = appConfig.getSocks5ProxyDetails()
            if (endpoint == null) {
                uiCtx {
                    showToastUiCentered(
                        this,
                        getString(R.string.blocklist_update_check_failure),
                        Toast.LENGTH_SHORT
                    )
                }
                return@io
            }
            val m = ProxyManager.ProxyMode.get(endpoint.proxyMode) ?: return@io
            if (!m.isCustomSocks5()) return@io

            if (
                endpoint.proxyAppName.isNullOrBlank() ||
                    endpoint.proxyAppName.equals(
                        getString(R.string.settings_app_list_default_app)
                    )
            ) {
                uiCtx {
                    b.settingsActivitySocks5Desc.text =
                        getString(
                            R.string.settings_socks_forwarding_desc_no_app,
                            endpoint.proxyIP,
                            endpoint.proxyPort.toString()
                        )
                }
            } else {
                val app = FirewallManager.getAppInfoByPackage(endpoint.proxyAppName!!)
                uiCtx {
                    b.settingsActivitySocks5Desc.text =
                        if (app == null) {
                            getString(
                                R.string.settings_socks_forwarding_desc_no_app,
                                endpoint.proxyIP,
                                endpoint.proxyPort.toString()
                            )
                        } else {
                            getString(
                                R.string.settings_socks_forwarding_desc,
                                endpoint.proxyIP,
                                endpoint.proxyPort.toString(),
                                app.appName
                            )
                        }
                }
            }
        }
    }

    private fun showSocks5ProxyDialog(
        endpoint: ProxyEndpoint,
        appNames: List<String>,
        appName: String
    ) {
        val dialogBinding = DialogSetProxyBinding.inflate(layoutInflater)
        val builder =
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(false)
        dialog.window?.attributes = lp

        val headerTxt: TextView = dialogBinding.dialogProxyHeader
        val headerDesc: TextView = dialogBinding.dialogProxyHeaderDesc
        val lockdownDesc: TextView = dialogBinding.dialogProxyHeaderLockdownDesc
        val applyURLBtn = dialogBinding.dialogProxyApplyBtn
        val cancelURLBtn = dialogBinding.dialogProxyCancelBtn
        val appNameSpinner: Spinner = dialogBinding.dialogProxySpinnerAppname
        val ipAddressEditText: EditText = dialogBinding.dialogProxyEditIp
        val portEditText: EditText = dialogBinding.dialogProxyEditPort
        val errorTxt: TextView = dialogBinding.dialogProxyErrorText
        val userNameEditText: EditText = dialogBinding.dialogProxyEditUsername
        val passwordEditText: EditText = dialogBinding.dialogProxyEditPassword
        val udpBlockLayout: LinearLayout = dialogBinding.dialogProxyUdpHeader
        val udpBlockCheckBox: CheckBox = dialogBinding.dialogProxyUdpCheck
        val excludeAppLayout: LinearLayout = dialogBinding.dialogProxyExcludeAppsHeader
        val excludeAppCheckBox: CheckBox = dialogBinding.dialogProxyExcludeAppsCheck

        headerDesc.visibility = View.GONE
        udpBlockCheckBox.isChecked = persistentState.getUdpBlocked()
        excludeAppCheckBox.isChecked = !persistentState.excludeAppsInProxy
        excludeAppCheckBox.isEnabled = !VpnController.isVpnLockdown()
        lockdownDesc.visibility = if (VpnController.isVpnLockdown()) View.VISIBLE else View.GONE
        if (VpnController.isVpnLockdown()) excludeAppCheckBox.alpha = 0.5f

        val proxySpinnerAdapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appNames)
        appNameSpinner.adapter = proxySpinnerAdapter

        if (!endpoint.proxyIP.isNullOrBlank()) {
            ipAddressEditText.setText(endpoint.proxyIP, TextView.BufferType.EDITABLE)
            portEditText.setText(endpoint.proxyPort.toString(), TextView.BufferType.EDITABLE)
            userNameEditText.setText(endpoint.userName.toString(), TextView.BufferType.EDITABLE)
            if (
                !endpoint.proxyAppName.isNullOrBlank() &&
                    endpoint.proxyAppName != getString(R.string.settings_app_list_default_app)
            ) {
                var position = 0
                for ((i, item) in appNames.withIndex()) {
                    if (item == appName) position = i
                }
                appNameSpinner.setSelection(position)
            }
        } else {
            ipAddressEditText.setText(Constants.SOCKS_DEFAULT_IP, TextView.BufferType.EDITABLE)
            portEditText.setText(
                Constants.SOCKS_DEFAULT_PORT.toString(),
                TextView.BufferType.EDITABLE
            )
        }

        headerTxt.text = getString(R.string.settings_dns_proxy_dialog_header)
        headerDesc.text = getString(R.string.settings_dns_proxy_dialog_app_desc)

        lockdownDesc.setOnClickListener {
            dialog.dismiss()
            UIUtils.openVpnProfile(this)
        }

        excludeAppLayout.setOnClickListener {
            excludeAppCheckBox.isChecked = !excludeAppCheckBox.isChecked
            logEvent(
                "loopback proxy forwarder apps SOCKS5 Proxy toggled",
                "loopback proxy forwarder apps in SOCKS5 proxy: ${excludeAppCheckBox.isChecked}"
            )
        }

        udpBlockLayout.setOnClickListener {
            udpBlockCheckBox.isChecked = !udpBlockCheckBox.isChecked
            logEvent(
                "UDP Block in SOCKS5 Proxy toggled",
                "UDP block in SOCKS5 proxy: ${udpBlockCheckBox.isChecked}"
            )
        }

        applyURLBtn.setOnClickListener {
            var port: Int? = 0
            var isValid: Boolean
            var isIPValid = true
            var isUDPBlock = false
            val ip: String = ipAddressEditText.text.toString()

            if (ip.isBlank()) {
                isIPValid = false
                errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                errorTxt.visibility = View.VISIBLE
            }

            try {
                port = portEditText.text.toString().toInt()
                isValid =
                    if (Utilities.isLanIpv4(ip)) Utilities.isValidLocalPort(port)
                    else isValidPort(port)
                if (!isValid) {
                    errorTxt.text = getString(R.string.settings_http_proxy_error_text1)
                }
            } catch (e: NumberFormatException) {
                Logger.w(LOG_TAG_PROXY, "err: ${e.message}", e)
                errorTxt.text = getString(R.string.settings_http_proxy_error_text2)
                isValid = false
            }

            if (udpBlockCheckBox.isChecked) isUDPBlock = true
            persistentState.excludeAppsInProxy = !excludeAppCheckBox.isChecked

            val userName: String = userNameEditText.text.toString()
            val password: String = passwordEditText.text.toString()
            if (isValid && isIPValid) {
                persistentState.setUdpBlocked(udpBlockCheckBox.isChecked)
                val app = appNameSpinner.selectedItem.toString()
                insertSocks5Endpoint(endpoint.id, ip, port, app, userName, password, isUDPBlock)
                b.settingsActivitySocks5Desc.text =
                    if (app == getString(R.string.settings_app_list_default_app)) {
                        getString(
                            R.string.settings_socks_forwarding_desc_no_app,
                            ip,
                            port.toString()
                        )
                    } else {
                        getString(R.string.settings_socks_forwarding_desc, ip, port.toString(), app)
                    }
                logEvent(
                    "Custom SOCKS5 Proxy set",
                    "custom SOCKS5 proxy to $ip:$port, app: $app"
                )
                dialog.dismiss()
            }
        }

        cancelURLBtn.setOnClickListener {
            b.settingsActivitySocks5Switch.isChecked = false
            appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
            b.settingsActivitySocks5Desc.text =
                getString(R.string.settings_socks_forwarding_default_desc)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun enableTcpProxy() {
        io { TcpProxyHelper.enable() }
    }

    private fun handleProxyUi() {
        val canEnableProxy = appConfig.canEnableProxy()

        if (canEnableProxy) {
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
            b.settingsActivityWireguardContainer.alpha = 1f
            b.settingsActivitySocks5Rl.alpha = 1f
            b.settingsActivityHttpProxyContainer.alpha = 1f
            b.wgRefresh.visibility = View.VISIBLE
        } else {
            b.settingsActivityWireguardContainer.alpha = 0.5f
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
            b.settingsActivitySocks5Rl.alpha = 0.5f
            b.settingsActivityHttpProxyContainer.alpha = 0.5f
            b.wgRefresh.visibility = View.GONE
        }

        b.settingsActivityWireguardImg.isEnabled = canEnableProxy
        b.settingsActivityWireguardContainer.isEnabled = canEnableProxy
        b.settingsActivitySocks5Switch.isEnabled = canEnableProxy
        b.settingsActivityHttpProxySwitch.isEnabled = canEnableProxy
    }

    private fun showHttpProxyDialog(
        endpoint: ProxyEndpoint,
        appNames: List<String>,
        appName: String?
    ) {
        val defaultHost = "http://127.0.0.1:8118"
        var host: String
        val dialogBinding = DialogSetProxyBinding.inflate(layoutInflater)
        val builder =
            MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        val headerTxt: TextView = dialogBinding.dialogProxyHeader
        val headerDesc: TextView = dialogBinding.dialogProxyHeaderDesc
        val lockdownDesc: TextView = dialogBinding.dialogProxyHeaderLockdownDesc
        val applyURLBtn = dialogBinding.dialogProxyApplyBtn
        val cancelURLBtn = dialogBinding.dialogProxyCancelBtn
        val appNameSpinner: Spinner = dialogBinding.dialogProxySpinnerAppname
        val ipAddressEditText: EditText = dialogBinding.dialogProxyEditIp
        val portLl: LinearLayout = dialogBinding.dialogProxyPortHeader
        val portEditText: EditText = dialogBinding.dialogProxyEditPort
        val errorTxt: TextView = dialogBinding.dialogProxyErrorText
        val userNameLl: LinearLayout = dialogBinding.dialogProxyUsernameHeader
        val passwordLl: LinearLayout = dialogBinding.dialogProxyPasswordHeader
        val udpBlockLl: LinearLayout = dialogBinding.dialogProxyUdpHeader
        val excludeAppLayout: LinearLayout = dialogBinding.dialogProxyExcludeAppsHeader
        val excludeAppCheckBox: CheckBox = dialogBinding.dialogProxyExcludeAppsCheck

        udpBlockLl.visibility = View.GONE
        portLl.visibility = View.GONE
        userNameLl.visibility = View.GONE
        passwordLl.visibility = View.GONE
        excludeAppCheckBox.isChecked = !persistentState.excludeAppsInProxy
        excludeAppCheckBox.isEnabled = !VpnController.isVpnLockdown()
        if (VpnController.isVpnLockdown()) excludeAppCheckBox.alpha = 0.5f

        lockdownDesc.setOnClickListener {
            dialog.dismiss()
            UIUtils.openVpnProfile(this)
        }
        lockdownDesc.visibility = if (VpnController.isVpnLockdown()) View.VISIBLE else View.GONE

        excludeAppLayout.setOnClickListener {
            excludeAppCheckBox.isChecked = !excludeAppCheckBox.isChecked
            logEvent(
                "loopback proxy forwarder apps in HTTP Proxy toggled",
                "loopback proxy forwarder apps in HTTP proxy: ${excludeAppCheckBox.isChecked}"
            )
        }

        val proxySpinnerAdapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, appNames)
        appNameSpinner.adapter = proxySpinnerAdapter

        if (!endpoint.proxyIP.isNullOrBlank()) {
            ipAddressEditText.setText(endpoint.proxyIP, TextView.BufferType.EDITABLE)
            portEditText.setText(endpoint.proxyPort.toString(), TextView.BufferType.EDITABLE)
            if (
                !endpoint.proxyAppName.isNullOrBlank() &&
                    endpoint.proxyAppName != getString(R.string.settings_app_list_default_app)
            ) {
                var position = 0
                for ((i, item) in appNames.withIndex()) {
                    if (item == appName) position = i
                }
                appNameSpinner.setSelection(position)
            }
        } else {
            ipAddressEditText.setText(defaultHost, TextView.BufferType.EDITABLE)
        }

        headerTxt.text = getString(R.string.http_proxy_dialog_heading)
        headerDesc.text = getString(R.string.http_proxy_dialog_desc)

        applyURLBtn.setOnClickListener {
            host = ipAddressEditText.text.toString()
            var isHostValid = true

            if (host.isBlank()) {
                isHostValid = false
                errorTxt.text = getString(R.string.settings_http_proxy_error_text3)
                errorTxt.visibility = View.VISIBLE
            }

            if (isHostValid) {
                errorTxt.visibility = View.INVISIBLE
                insertHttpProxyEndpointDB(
                    endpoint.id,
                    host,
                    appNameSpinner.selectedItem.toString()
                )
                dialog.dismiss()
                persistentState.excludeAppsInProxy = !excludeAppCheckBox.isChecked
                showToastUiCentered(
                    this,
                    getString(R.string.settings_http_proxy_toast_success),
                    Toast.LENGTH_SHORT
                )
                if (b.settingsActivityHttpProxySwitch.isChecked) {
                    b.settingsActivityHttpProxyDesc.text =
                        getString(R.string.settings_http_proxy_desc, host)
                }
                logEvent(
                    "Custom HTTP Proxy set",
                    "custom HTTP proxy to $host, app: ${appNameSpinner.selectedItem}"
                )
            }
        }

        cancelURLBtn.setOnClickListener {
            dialog.dismiss()
            appConfig.removeProxy(AppConfig.ProxyType.HTTP, AppConfig.ProxyProvider.CUSTOM)
            b.settingsActivityHttpProxyDesc.text = getString(R.string.settings_https_desc)
            b.settingsActivityHttpProxySwitch.isChecked = false
        }
    }

    private fun insertSocks5Endpoint(
        id: Int,
        ip: String,
        port: Int?,
        appName: String,
        userName: String,
        password: String,
        isUDPBlock: Boolean
    ) {
        b.settingsActivitySocks5Switch.isEnabled = false
        b.settingsActivitySocks5Switch.visibility = View.GONE
        b.settingsActivitySocks5Progress.visibility = View.VISIBLE
        delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
            b.settingsActivitySocks5Switch.isEnabled = true
            b.settingsActivitySocks5Progress.visibility = View.GONE
            b.settingsActivitySocks5Switch.visibility = View.VISIBLE
        }
        io {
            val proxyName = ProxyManager.ProxyMode.SOCKS5.name
            val mode = ProxyManager.ProxyMode.SOCKS5
            val appPackage =
                if (appName == getString(R.string.settings_app_list_default_app)) ""
                else FirewallManager.getPackageNameByAppName(appName) ?: ""
            val proxyEndpoint =
                constructProxy(id, proxyName, mode, appPackage, ip, port ?: 0, userName, password, isUDPBlock)
            if (proxyEndpoint != null) {
                appConfig.updateCustomSocks5Proxy(proxyEndpoint)
            }
        }
    }

    private fun insertHttpProxyEndpointDB(id: Int, ip: String, appName: String) {
        b.settingsActivityHttpProxySwitch.isEnabled = false
        b.settingsActivityHttpProxySwitch.visibility = View.GONE
        b.settingsActivityHttpProxyProgress.visibility = View.VISIBLE
        delay(TimeUnit.SECONDS.toMillis(1L), lifecycleScope) {
            b.settingsActivityHttpProxySwitch.isEnabled = true
            b.settingsActivityHttpProxyProgress.visibility = View.GONE
            b.settingsActivityHttpProxySwitch.visibility = View.VISIBLE
        }
        io {
            val proxyName = Constants.HTTP
            val mode = ProxyManager.ProxyMode.HTTP
            val packageName =
                if (appName == getString(R.string.settings_app_list_default_app)) ""
                else FirewallManager.getPackageNameByAppName(appName) ?: ""
            val proxyEndpoint =
                constructProxy(id, proxyName, mode, packageName, ip, 0, "", "", false)
            if (proxyEndpoint != null) {
                appConfig.updateCustomHttpProxy(proxyEndpoint)
            }
        }
    }

    private fun constructProxy(
        id: Int,
        name: String,
        mode: ProxyManager.ProxyMode,
        appName: String,
        ip: String?,
        port: Int,
        userName: String,
        password: String,
        isUdp: Boolean
    ): ProxyEndpoint? {
        if (ip.isNullOrEmpty()) {
            Logger.w(LOG_TAG_PROXY, "cannot construct proxy with values ip: $ip, port: $port")
            return null
        }
        if (mode == ProxyManager.ProxyMode.SOCKS5 && !isValidPort(port)) {
            Logger.w(LOG_TAG_PROXY, "cannot construct proxy with values ip: $ip, port: $port")
            return null
        }
        return ProxyEndpoint(
            id,
            name,
            mode.value,
            proxyType = DEFAULT_PROXY_TYPE,
            appName,
            ip,
            port,
            userName,
            password,
            isSelected = true,
            isCustom = true,
            isUDP = isUdp,
            modifiedDataTime = 0L,
            latency = 0
        )
    }

    private fun showHopLogDialog(title: String, body: String) {
        MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(title)
            .setMessage(body.ifBlank { "empty" })
            .setPositiveButton(R.string.dns_info_positive) { d, _ -> d.dismiss() }
            .setNegativeButton("clear log") { d, _ ->
                WgHopManager.clearHopLog()
                d.dismiss()
            }
            .show()
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
