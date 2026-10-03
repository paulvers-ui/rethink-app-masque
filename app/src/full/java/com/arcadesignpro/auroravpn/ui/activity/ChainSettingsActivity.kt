package com.arcadesignpro.auroravpn.ui.activity

import Logger
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.arcadesignpro.auroravpn.R
import com.arcadesignpro.auroravpn.data.AppConfig
import com.arcadesignpro.auroravpn.databinding.ActivityChainBinding
import com.arcadesignpro.auroravpn.service.ChainArgs
import com.arcadesignpro.auroravpn.service.ChainManager
import com.arcadesignpro.auroravpn.service.ChainRouting
import com.arcadesignpro.auroravpn.service.PersistentState
import com.arcadesignpro.auroravpn.service.UsqueManager
import com.arcadesignpro.auroravpn.util.Themes
import com.arcadesignpro.auroravpn.util.Utilities.showToastUiCentered
import com.arcadesignpro.auroravpn.util.handleFrostEffectIfNeeded
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Setup + control screen for the nested WARP1 -> wg0 -> WARP2 chain.
 *
 * One card per hop, laid out like the simple WARP section of the proxy screen:
 *   - WARP1 / WARP2: register, SNI (Save / Reset), the identity file editor
 *     (config.json / config_exit.json, Reload / Save) and the hop's libusque.so
 *     flags (Reset / Reload / Save) with the value the next start will use.
 *   - wg0: the wg0.conf editor (Import / Reload / Save) and its --wg-* flags.
 * Each hop has an on/off switch; the chain connects only with all three
 * configured and on. The status card holds the master switch (connect /
 * disconnect), the MTU each tunnel will use and the exit-IP check; below are
 * the full command (see [ChainArgs]) and the verbose log (chain_debug.txt).
 *
 * Any saved change restarts a running chain so it takes effect at once.
 */
// One small handler per card/button keeps the hops independent; the catches
// guard content-resolver / share-intent calls that can throw anything and must
// only show a toast.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount")
class ChainSettingsActivity : AppCompatActivity(R.layout.activity_chain) {
    private val b by viewBinding(ActivityChainBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private var busy = false
    private var restarting = false
    // Guards against the programmatic isChecked updates in applyStatus()
    // re-entering the switch listeners.
    private var updatingSwitches = false
    private var pulse: ObjectAnimator? = null

    private val warp1 by lazy { WarpCard(ChainArgs.Hop.WARP1) }
    private val warp2 by lazy { WarpCard(ChainArgs.Hop.WARP2) }
    private val argsEditors by lazy { ChainArgs.Hop.entries.map { ArgsEditor(it) } }

    private val importWgLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            importWgFrom(uri)
        }

    /** The views and settings of one WARP card; WARP1 and WARP2 share all the code. */
    private inner class WarpCard(val hop: ChainArgs.Hop) {
        val isExit = hop == ChainArgs.Hop.WARP2
        val configName = if (isExit) ChainManager.EXIT_CONFIG else ChainManager.WARP1_CONFIG
        val defaultSni = if (isExit) ChainArgs.DEFAULT_WARP2_SNI else UsqueManager.DEFAULT_WARP_SNI
        @StringRes val registerLabel = if (isExit) R.string.chain_register2_btn else R.string.chain_register1_btn
        val dot: ImageView get() = if (isExit) b.warp2Dot else b.warp1Dot
        val status: TextView get() = if (isExit) b.warp2Status else b.warp1Status
        val switch: SwitchMaterial get() = if (isExit) b.warp2Switch else b.warp1Switch
        val registerBtn: MaterialButton get() = if (isExit) b.warp2RegisterBtn else b.warp1RegisterBtn
        val sniEdit: TextInputEditText get() = if (isExit) b.warp2SniEdit else b.warp1SniEdit
        val sniSave: MaterialButton get() = if (isExit) b.warp2SniSaveBtn else b.warp1SniSaveBtn
        val sniReset: MaterialButton get() = if (isExit) b.warp2SniResetBtn else b.warp1SniResetBtn
        val configEdit: TextInputEditText get() = if (isExit) b.warp2ConfigEdit else b.warp1ConfigEdit
        val configReload: MaterialButton get() = if (isExit) b.warp2ConfigReloadBtn else b.warp1ConfigReloadBtn
        val configSave: MaterialButton get() = if (isExit) b.warp2ConfigSaveBtn else b.warp1ConfigSaveBtn

        var sni: String
            get() = if (isExit) persistentState.chainWarp2Sni else persistentState.chainWarp1Sni
            set(v) { if (isExit) persistentState.chainWarp2Sni = v else persistentState.chainWarp1Sni = v }
    }

    /** One hop's libusque.so flag editor (WARP1, wg0 or WARP2). */
    private inner class ArgsEditor(val hop: ChainArgs.Hop) {
        val edit: TextInputEditText get() = when (hop) {
            ChainArgs.Hop.WARP1 -> b.warp1ArgsEdit
            ChainArgs.Hop.WG -> b.wgArgsEdit
            ChainArgs.Hop.WARP2 -> b.warp2ArgsEdit
        }
        val effective: TextView get() = when (hop) {
            ChainArgs.Hop.WARP1 -> b.warp1ArgsEffective
            ChainArgs.Hop.WG -> b.wgArgsEffective
            ChainArgs.Hop.WARP2 -> b.warp2ArgsEffective
        }
        val reset: MaterialButton get() = when (hop) {
            ChainArgs.Hop.WARP1 -> b.warp1ArgsResetBtn
            ChainArgs.Hop.WG -> b.wgArgsResetBtn
            ChainArgs.Hop.WARP2 -> b.warp2ArgsResetBtn
        }
        val reload: MaterialButton get() = when (hop) {
            ChainArgs.Hop.WARP1 -> b.warp1ArgsReloadBtn
            ChainArgs.Hop.WG -> b.wgArgsReloadBtn
            ChainArgs.Hop.WARP2 -> b.warp2ArgsReloadBtn
        }
        val save: MaterialButton get() = when (hop) {
            ChainArgs.Hop.WARP1 -> b.warp1ArgsSaveBtn
            ChainArgs.Hop.WG -> b.wgArgsSaveBtn
            ChainArgs.Hop.WARP2 -> b.warp2ArgsSaveBtn
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        handleFrostEffectIfNeeded(persistentState.theme)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightNavigationBars = false
        window.isNavigationBarContrastEnforced = false

        wireStatusCard()
        wireWarpCard(warp1)
        wireWarpCard(warp2)
        wireWgCard()
        argsEditors.forEach { wireArgsEditor(it) }
        wireAdvanced(b.warp1AdvancedHeader, b.warp1AdvancedArrow, b.warp1AdvancedBody)
        wireAdvanced(b.wgAdvancedHeader, b.wgAdvancedArrow, b.wgAdvancedBody)
        wireAdvanced(b.warp2AdvancedHeader, b.warp2AdvancedArrow, b.warp2AdvancedBody)
        wireLog()

        loadEditorsFromDisk()
        refreshLog()
        if (savedInstanceState == null) animateCardsIn()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatus()
    }

    override fun onDestroy() {
        pulse?.cancel()
        super.onDestroy()
    }

    private fun Context.isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    // ── wiring ────────────────────────────────────────────────────────────────
    private fun wireStatusCard() {
        b.chainConnectBtn.setOnClickListener { toggleChain() }
        b.chainExitCheckBtn.setOnClickListener { checkExitIp() }
        // The master switch connects / disconnects; applyStatus() keeps it in
        // step with the live chain.
        b.chainMasterSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            if (isChecked) connectChain() else disconnectChain()
        }
    }

    private fun wireWarpCard(card: WarpCard) {
        card.registerBtn.setOnClickListener { doRegister(card) }
        card.switch.setOnCheckedChangeListener { _, isChecked -> onHopSwitch(card.hop, isChecked) }
        card.sniSave.setOnClickListener { saveSni(card) }
        card.sniReset.setOnClickListener { resetSni(card) }
        card.configReload.setOnClickListener {
            card.configEdit.setText(ChainManager.readFile(this, card.configName))
            toast(getString(R.string.chain_config_reloaded, card.configName))
        }
        card.configSave.setOnClickListener { saveWarpConfig(card) }
    }

    private fun wireWgCard() {
        b.wgSwitch.setOnCheckedChangeListener { _, isChecked -> onHopSwitch(ChainArgs.Hop.WG, isChecked) }
        b.wgImportBtn.setOnClickListener { importWgLauncher.launch("*/*") }
        b.wgReloadBtn.setOnClickListener {
            b.wgConfigEdit.setText(ChainManager.readFile(this, ChainManager.WG_CONFIG))
            toast(getString(R.string.chain_config_reloaded, ChainManager.WG_CONFIG))
        }
        b.wgSaveBtn.setOnClickListener { saveWgFromEditor() }
    }

    private fun wireArgsEditor(e: ArgsEditor) {
        e.reload.setOnClickListener {
            e.edit.setText(ChainArgs.hopArgs(persistentState, e.hop))
            refreshCommand()
            toast(getString(R.string.chain_args_reloaded))
        }
        e.reset.setOnClickListener {
            ChainArgs.writeHopArgs(persistentState, e.hop, "")
            e.edit.setText(ChainArgs.hopArgs(persistentState, e.hop))
            toast(getString(R.string.chain_args_reset_done))
            onSettingsChanged()
        }
        e.save.setOnClickListener { saveArgs(e) }
    }

    private fun wireLog() {
        b.chainLogRefreshBtn.setOnClickListener { refreshLog() }
        b.chainLogClearBtn.setOnClickListener { ChainManager.clearDebugLog(this); refreshLog() }
        b.chainLogShareBtn.setOnClickListener { shareLog() }
    }

    private fun loadEditorsFromDisk() {
        for (card in listOf(warp1, warp2)) {
            card.sniEdit.setText(card.sni)
            card.configEdit.setText(ChainManager.readFile(this, card.configName))
        }
        b.wgConfigEdit.setText(ChainManager.readFile(this, ChainManager.WG_CONFIG))
        argsEditors.forEach { it.edit.setText(ChainArgs.hopArgs(persistentState, it.hop)) }
    }

    // ── per-hop on/off ────────────────────────────────────────────────────────
    private fun hopConfigured(hop: ChainArgs.Hop): Boolean = when (hop) {
        ChainArgs.Hop.WARP1 -> ChainManager.warp1Registered(this)
        ChainArgs.Hop.WG -> ChainManager.wgLoaded(this)
        ChainArgs.Hop.WARP2 -> ChainManager.warp2Registered(this)
    }

    private fun hopOn(hop: ChainArgs.Hop): Boolean = when (hop) {
        ChainArgs.Hop.WARP1 -> persistentState.chainWarp1On
        ChainArgs.Hop.WG -> persistentState.chainWgOn
        ChainArgs.Hop.WARP2 -> persistentState.chainWarp2On
    }

    private fun setHopOn(hop: ChainArgs.Hop, on: Boolean) {
        when (hop) {
            ChainArgs.Hop.WARP1 -> persistentState.chainWarp1On = on
            ChainArgs.Hop.WG -> persistentState.chainWgOn = on
            ChainArgs.Hop.WARP2 -> persistentState.chainWarp2On = on
        }
    }

    private fun allHopsOn(): Boolean = ChainArgs.Hop.entries.all { hopOn(it) }

    private fun onHopSwitch(hop: ChainArgs.Hop, isChecked: Boolean) {
        if (updatingSwitches) return
        if (isChecked && !hopConfigured(hop)) {
            // Can't turn on a hop that isn't set up; bounce it back off.
            val need = if (hop == ChainArgs.Hop.WG) R.string.chain_need_wg_first else R.string.chain_need_register_first
            toast(getString(need))
            refreshAllStatus()
            return
        }
        setHopOn(hop, isChecked)
        refreshAllStatus()
        if (!isChecked) {
            toast(getString(R.string.chain_hop_turned_off))
            // A chain with a hop turned off must not keep carrying the VPN.
            // isRunning() may probe the port, so not on Main.
            lifecycleScope.launch {
                if (withContext(Dispatchers.IO) { ChainManager.isRunning() }) disconnectChain()
            }
        }
    }

    // ── SNI / identity files / flags ──────────────────────────────────────────
    private fun saveSni(card: WarpCard) {
        val value = card.sniEdit.text?.toString()?.trim().orEmpty().ifEmpty { card.defaultSni }
        if (!ChainArgs.isValidSni(value)) {
            toast(getString(R.string.chain_sni_invalid))
            return
        }
        card.sniEdit.setText(value)
        if (value == card.sni) {
            toast(getString(R.string.chain_sni_unchanged, value))
            return
        }
        card.sni = value
        flashSaved(card.sniSave, R.string.chain_save)
        toast(getString(R.string.chain_sni_saved, value))
        onSettingsChanged()
    }

    private fun resetSni(card: WarpCard) {
        card.sniEdit.setText(card.defaultSni)
        if (card.sni == card.defaultSni) {
            toast(getString(R.string.chain_sni_unchanged, card.defaultSni))
            return
        }
        card.sni = card.defaultSni
        toast(getString(R.string.chain_sni_reset_done, card.defaultSni))
        onSettingsChanged()
    }

    private fun saveWarpConfig(card: WarpCard) {
        val text = card.configEdit.text?.toString().orEmpty()
        if (!ChainManager.writeConfigJson(this, card.configName, text)) {
            toast(getString(R.string.chain_config_invalid, card.configName))
            refreshLog()
            return
        }
        flashSaved(card.configSave, R.string.chain_save)
        toast(getString(R.string.chain_config_saved, card.configName))
        onSettingsChanged()
    }

    private fun saveArgs(e: ArgsEditor) {
        val check = ChainArgs.writeHopArgs(persistentState, e.hop, e.edit.text?.toString().orEmpty())
        val error = when (check) {
            ChainArgs.ArgsCheck.CORE_FLAG -> R.string.chain_args_err_core
            ChainArgs.ArgsCheck.WRONG_HOP_FLAG -> R.string.chain_args_err_hop
            ChainArgs.ArgsCheck.SUBCOMMAND -> R.string.chain_args_err_sub
            ChainArgs.ArgsCheck.SAVED, ChainArgs.ArgsCheck.CLEARED -> null
        }
        if (error != null) {
            toast(getString(error))
            return
        }
        // Normalized (or back to the default after a clear).
        e.edit.setText(ChainArgs.hopArgs(persistentState, e.hop))
        flashSaved(e.save, R.string.chain_save)
        toast(getString(if (check == ChainArgs.ArgsCheck.CLEARED) R.string.chain_args_reset_done else R.string.chain_args_saved))
        onSettingsChanged()
    }

    /** After any saved setting: refresh what the next start runs, and apply it now if connected. */
    private fun onSettingsChanged() {
        refreshAllStatus()
        restartIfRunning()
    }

    // ── wg0.conf (paste or import) ────────────────────────────────────────────
    private fun saveWgFromEditor() {
        val text = b.wgConfigEdit.text?.toString().orEmpty()
        val ok = ChainManager.writeWgConfig(this, text)
        toast(getString(if (ok) R.string.chain_wg_loaded else R.string.chain_wg_invalid))
        if (ok) {
            flashSaved(b.wgSaveBtn, R.string.chain_save)
            onSettingsChanged()
        } else {
            refreshAllStatus()
        }
    }

    private fun importWgFrom(uri: Uri) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (e: Exception) {
                    Logger.e(Logger.LOG_TAG_PROXY, "chain wg import read failed: ${e.message}", e)
                    null
                }
            }
            if (text.isNullOrBlank()) {
                toast(getString(R.string.chain_wg_import_failed))
                return@launch
            }
            b.wgConfigEdit.setText(text)
            val ok = ChainManager.writeWgConfig(this@ChainSettingsActivity, text)
            toast(getString(if (ok) R.string.chain_wg_imported else R.string.chain_wg_invalid))
            if (ok) onSettingsChanged() else refreshAllStatus()
            refreshLog()
        }
    }

    // ── register a WARP identity ──────────────────────────────────────────────
    private fun doRegister(card: WarpCard) {
        if (busy) return
        setBusy(true)
        card.status.text = getString(R.string.chain_registering)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { ChainManager.registerWarp(this@ChainSettingsActivity, card.configName) }
            setBusy(false)
            toast(getString(if (ok) R.string.chain_registered_ok else R.string.chain_register_failed))
            if (ok) card.configEdit.setText(ChainManager.readFile(this@ChainSettingsActivity, card.configName))
            refreshAllStatus()
            refreshLog()
        }
    }

    // ── connect / disconnect / restart ────────────────────────────────────────
    private fun toggleChain() {
        if (busy) return
        lifecycleScope.launch {
            if (withContext(Dispatchers.IO) { ChainManager.isRunning() }) disconnectChain() else connectChain()
        }
    }

    private fun connectChain() {
        if (busy) return
        if (!ChainManager.chainReady(this)) {
            toast(getString(R.string.chain_not_ready)); refreshAllStatus(); return
        }
        if (!allHopsOn()) {
            toast(getString(R.string.chain_need_hops_on)); refreshAllStatus(); return
        }
        setBusy(true)
        lifecycleScope.launch {
            val live = startAndRoute()
            setBusy(false)
            toast(getString(if (live) R.string.chain_connected else R.string.chain_connect_failed))
            refreshAllStatus(); refreshLog()
        }
    }

    /**
     * Starts the chain, waits for real traffic through all three hops, then
     * points the VPN tunnel at it. On any failure (also when the screen is left
     * mid-connect) nothing is left running that the tunnel does not use.
     */
    private suspend fun startAndRoute(): Boolean {
        val ctx = this@ChainSettingsActivity
        var live = false
        try {
            val started = withContext(Dispatchers.IO) { ChainManager.startChain(ctx) }
            // Only report success if the whole chain actually carries traffic
            // (a half-up chain can bind the port then stall).
            live = started && withContext(Dispatchers.IO) { ChainManager.awaitChainLiveness(ctx) }
            if (live) {
                // Point the VPN tunnel (every other app) at the chain's SOCKS5.
                live = withContext(NonCancellable) { ChainRouting.routeThroughChain(ctx, appConfig, persistentState) }
            }
        } finally {
            if (!live) {
                withContext(NonCancellable) {
                    ChainRouting.leaveChain(ctx, appConfig, persistentState)
                    withContext(Dispatchers.IO) { ChainManager.stopChain() }
                }
            }
        }
        return live
    }

    // Moves the VPN tunnel off the chain first (back to simple WARP if it was on,
    // else no custom proxy), then stops the chain, so traffic never targets a dead port.
    private fun disconnectChain() {
        if (busy) return
        setBusy(true)
        lifecycleScope.launch {
            val ctx = this@ChainSettingsActivity
            withContext(NonCancellable) {
                ChainRouting.leaveChain(ctx, appConfig, persistentState)
                withContext(Dispatchers.IO) { ChainManager.stopChain() }
            }
            setBusy(false)
            refreshAllStatus(); refreshLog()
        }
    }

    /**
     * Applies saved settings to a running chain: stop, start with the new
     * command, wait for traffic. The VPN keeps pointing at :40001 meanwhile,
     * so apps only see a short gap; if the new settings do not come up, the
     * chain is disconnected like a failed connect.
     */
    private fun restartIfRunning() {
        if (busy) return
        lifecycleScope.launch {
            if (!withContext(Dispatchers.IO) { ChainManager.isRunning() }) return@launch
            restarting = true
            setBusy(true)
            withContext(Dispatchers.IO) { ChainManager.stopChain() }
            val live = startAndRoute()
            restarting = false
            setBusy(false)
            toast(getString(if (live) R.string.chain_restart_ok else R.string.chain_restart_failed))
            refreshAllStatus(); refreshLog()
        }
    }

    // ── verify the exit IP ────────────────────────────────────────────────────
    /**
     * Asks Cloudflare what it sees through the chain (WARP2 exit) and, if it is
     * running, through simple WARP (WARP1's identity, from the phone), and shows
     * which local SOCKS5 the VPN tunnel itself is set to. Read-only: it starts
     * nothing and changes no proxy setting.
     */
    private fun checkExitIp() {
        if (busy) return
        setBusy(true)
        b.chainExitResult.text = getString(R.string.chain_exit_checking)
        lifecycleScope.launch {
            val ctx = this@ChainSettingsActivity
            // isRunning() may probe the port (reattached chain), so not on Main.
            if (!withContext(Dispatchers.IO) { ChainManager.isRunning() }) {
                setBusy(false)
                b.chainExitResult.text = getString(R.string.chain_exit_need_connect)
                refreshAllStatus()
                return@launch
            }
            val chain = async { ChainManager.fetchExitTrace(ctx, ChainManager.SOCKS_PORT) }
            val warp = async {
                val warpUp = withContext(Dispatchers.IO) { UsqueManager.isPortAlive() }
                if (warpUp) ChainManager.fetchExitTrace(ctx, UsqueManager.SOCKS_PORT) else null
            }
            val vpnRoute = describeVpnRoute()
            val text = renderExitCheck(chain.await(), warp.await(), vpnRoute)
            setBusy(false)
            b.chainExitResult.text = text
            refreshAllStatus(); refreshLog()
        }
    }

    /** Which local SOCKS5 the VPN tunnel (every other app) is configured to use. */
    private suspend fun describeVpnRoute(): String {
        val ep = if (appConfig.isCustomSocks5Enabled()) {
            withContext(Dispatchers.IO) { appConfig.getSocks5ProxyDetails() }
        } else {
            null
        }
        if (ep == null) return getString(R.string.chain_exit_vpn_no_socks)
        val local = ep.proxyIP == ChainManager.SOCKS_HOST
        val label = when {
            local && ep.proxyPort == ChainManager.SOCKS_PORT -> R.string.chain_exit_vpn_is_chain
            local && ep.proxyPort == UsqueManager.SOCKS_PORT -> R.string.chain_exit_vpn_is_warp
            else -> R.string.chain_exit_vpn_is_other
        }
        return getString(R.string.chain_exit_vpn_route, "${ep.proxyIP}:${ep.proxyPort}", getString(label))
    }

    private fun renderExitCheck(
        chain: ChainManager.ExitTrace,
        warp: ChainManager.ExitTrace?,
        vpnRoute: String,
    ): String {
        val lines = mutableListOf(
            getString(R.string.chain_exit_label_chain, ChainManager.SOCKS_PORT),
            traceLine(chain),
        )
        if (warp != null) {
            lines += getString(R.string.chain_exit_label_warp, UsqueManager.SOCKS_PORT)
            lines += traceLine(warp)
        }
        lines += ""
        lines += getString(exitVerdict(chain, warp))
        lines += vpnRoute
        return lines.joinToString("\n")
    }

    private fun traceLine(t: ChainManager.ExitTrace): String = when (t) {
        is ChainManager.ExitTrace.Ok -> getString(R.string.chain_exit_trace_ok, t.ip, t.loc, t.colo, t.warp)
        is ChainManager.ExitTrace.Failed -> getString(R.string.chain_exit_trace_failed, t.reason)
    }

    // colo (the Cloudflare data center) is the strongest signal: WARP2 reaches
    // Cloudflare from the wg0 server, simple WARP / WARP1 from the phone.
    @StringRes
    private fun exitVerdict(chain: ChainManager.ExitTrace, warp: ChainManager.ExitTrace?): Int {
        val c = chain as? ChainManager.ExitTrace.Ok ?: return R.string.chain_exit_verdict_chain_failed
        val w = warp as? ChainManager.ExitTrace.Ok ?: return R.string.chain_exit_verdict_no_compare
        return when {
            c.ip == w.ip -> R.string.chain_exit_verdict_same_ip
            c.colo != w.colo -> R.string.chain_exit_verdict_different
            else -> R.string.chain_exit_verdict_same_colo
        }
    }

    // ── status / dots / switches ──────────────────────────────────────────────
    // isRunning() may probe the SOCKS port (a reattached chain), so not on Main.
    private fun refreshAllStatus() {
        lifecycleScope.launch {
            val running = withContext(Dispatchers.IO) { ChainManager.isRunning() }
            applyStatus(running)
        }
    }

    private fun applyStatus(running: Boolean) {
        val ready = ChainManager.chainReady(this)
        val canConnect = ready && allHopsOn()
        updatingSwitches = true

        applyWarpCard(warp1)
        applyWarpCard(warp2)
        val wg = hopConfigured(ChainArgs.Hop.WG)
        dot(b.wgDot, wg, hopOn(ChainArgs.Hop.WG))
        b.wgStatus.text = getString(if (wg) R.string.chain_wg_loaded else R.string.chain_wg_missing)
        armSwitch(b.wgSwitch, ChainArgs.Hop.WG)

        b.chainConnectBtn.isEnabled = !busy && (running || canConnect)
        b.chainConnectBtn.setText(if (running) R.string.chain_disconnect else R.string.chain_connect)
        b.chainExitCheckBtn.isEnabled = running && !busy
        b.chainMasterSwitch.isEnabled = !busy && (running || canConnect)
        b.chainMasterSwitch.isChecked = running

        b.chainStatusDot.setImageResource(
            when {
                busy -> R.drawable.dot_yellow
                running -> R.drawable.dot_green
                else -> R.drawable.dot_white
            }
        )
        b.chainStatusText.text = getString(statusText(running, ready))
        updatingSwitches = false
        refreshCommand()
    }

    @StringRes
    private fun statusText(running: Boolean, ready: Boolean): Int = when {
        restarting -> R.string.chain_status_restarting
        busy && !running -> R.string.chain_connecting
        running -> R.string.chain_status_connected
        ready && !allHopsOn() -> R.string.chain_status_hop_off
        ready -> R.string.chain_status_ready
        else -> R.string.chain_status_incomplete
    }

    private fun applyWarpCard(card: WarpCard) {
        val registered = hopConfigured(card.hop)
        dot(card.dot, registered, hopOn(card.hop))
        if (!(busy && card.status.text.toString() == getString(R.string.chain_registering))) {
            card.status.text = getString(if (registered) R.string.chain_registered_ok else R.string.chain_not_registered)
        }
        card.registerBtn.setText(if (registered) R.string.chain_reregister_btn else card.registerLabel)
        card.registerBtn.isEnabled = !busy
        armSwitch(card.switch, card.hop)
    }

    // A hop shows on only when it is set up and switched on.
    private fun armSwitch(sw: SwitchMaterial, hop: ChainArgs.Hop) {
        sw.isEnabled = !busy
        sw.isChecked = hopConfigured(hop) && hopOn(hop)
    }

    // Green: ready and on. Yellow: ready but switched off. White: not set up yet.
    private fun dot(view: ImageView, configured: Boolean, on: Boolean) {
        view.setImageResource(
            when {
                configured && on -> R.drawable.dot_green
                configured -> R.drawable.dot_yellow
                else -> R.drawable.dot_white
            }
        )
    }

    /** The per-hop "effective" lines, the full command and the MTU plan. */
    private fun refreshCommand() {
        argsEditors.forEach { it.effective.text = ChainArgs.effectiveHopForDisplay(this, persistentState, it.hop) }
        b.chainCmdCore.text = ChainArgs.CORE_TEMPLATE
        b.chainCmdFull.text = "libusque.so " + ChainArgs.effectiveForDisplay(this, persistentState)
        val plan = ChainArgs.mtuPlan(this, persistentState)
        b.chainMtuSummary.text = getString(
            R.string.chain_mtu_summary,
            plan.warp1,
            plan.wg?.toString() ?: getString(R.string.chain_mtu_no_wg),
            plan.warp2,
            getString(if (plan.warp2OverQuic) R.string.chain_transport_quic else R.string.chain_transport_h2),
        )
    }

    // ── animations ────────────────────────────────────────────────────────────
    // Busy: progress bar on, status dot pulsing; buttons and switches are
    // disabled by applyStatus() until it ends.
    private fun setBusy(on: Boolean) {
        busy = on
        b.chainProgress.visibility = if (on) View.VISIBLE else View.GONE
        pulse?.cancel()
        pulse = null
        b.chainStatusDot.alpha = 1f
        if (on) {
            pulse = ObjectAnimator.ofFloat(b.chainStatusDot, View.ALPHA, 1f, PULSE_MIN_ALPHA).apply {
                duration = PULSE_MS
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
        refreshAllStatus()
    }

    // Cards slide up and fade in one after another when the screen opens.
    private fun animateCardsIn() {
        val slide = CARD_SLIDE_DP * resources.displayMetrics.density
        listOf(b.chainStatusCard, b.warp1Card, b.wgCard, b.warp2Card, b.chainCmdCard, b.chainLogCard)
            .forEachIndexed { i, card ->
                card.alpha = 0f
                card.translationY = slide
                card.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(i * CARD_STAGGER_MS)
                    .setDuration(CARD_ANIM_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
    }

    // "Advanced" rows expand / collapse their section; the arrow turns with it.
    private fun wireAdvanced(header: View, arrow: ImageView, body: View) {
        header.setOnClickListener {
            val expand = body.visibility != View.VISIBLE
            (body.parent as? ViewGroup)?.let {
                TransitionManager.beginDelayedTransition(it, AutoTransition().setDuration(EXPAND_ANIM_MS))
            }
            body.visibility = if (expand) View.VISIBLE else View.GONE
            arrow.animate().rotation(if (expand) ARROW_EXPANDED_DEG else 0f).setDuration(EXPAND_ANIM_MS).start()
        }
    }

    // A Save button briefly reads "Saved ✓" with a small bounce, then goes back.
    private fun flashSaved(btn: MaterialButton, @StringRes label: Int) {
        btn.setText(R.string.chain_saved_tick)
        btn.animate().scaleX(BOUNCE_SCALE).scaleY(BOUNCE_SCALE).setDuration(BOUNCE_MS)
            .withEndAction { btn.animate().scaleX(1f).scaleY(1f).setDuration(BOUNCE_MS).start() }
            .start()
        btn.postDelayed({ btn.setText(label) }, SAVED_FLASH_MS)
    }

    // ── verbose log ───────────────────────────────────────────────────────────
    private fun refreshLog() {
        val text = ChainManager.readDebugLog(this)
        b.chainLogView.text = if (text.length > LOG_VIEW_MAX_CHARS) text.takeLast(LOG_VIEW_MAX_CHARS) else text
    }

    private fun shareLog() {
        try {
            val f = ChainManager.getDebugLogFile(this)
            if (!f.exists()) { toast("No log yet"); return }
            val uri = FileProvider.getUriForFile(this, "${packageName}.provider", f)
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(android.content.Intent.createChooser(send, getString(R.string.chain_log_share)))
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_UI, "chain log share failed: ${e.message}", e)
            toast("Share failed: ${e.message}")
        }
    }

    private fun toast(msg: String) = showToastUiCentered(this, msg, Toast.LENGTH_SHORT)

    companion object {
        // Tail of chain_debug.txt shown on screen; the full file is shareable.
        private const val LOG_VIEW_MAX_CHARS = 20_000

        private const val PULSE_MS = 600L
        private const val PULSE_MIN_ALPHA = 0.25f
        private const val CARD_SLIDE_DP = 24f
        private const val CARD_STAGGER_MS = 60L
        private const val CARD_ANIM_MS = 320L
        private const val EXPAND_ANIM_MS = 200L
        private const val ARROW_EXPANDED_DEG = 180f
        private const val BOUNCE_SCALE = 1.06f
        private const val BOUNCE_MS = 110L
        private const val SAVED_FLASH_MS = 1_200L
    }
}
