package com.arcadesignpro.auroravpn.ui.activity

import Logger
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.arcadesignpro.auroravpn.R
import com.arcadesignpro.auroravpn.databinding.ActivityChainBinding
import com.arcadesignpro.auroravpn.service.ChainManager
import com.arcadesignpro.auroravpn.service.PersistentState
import com.arcadesignpro.auroravpn.util.Themes
import com.arcadesignpro.auroravpn.util.Utilities.showToastUiCentered
import com.arcadesignpro.auroravpn.util.handleFrostEffectIfNeeded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Standalone setup + test screen for the nested WARP1 -> wg0 -> WARP2 chain.
 *
 * Deliberately modular: each of the four steps (register WARP1, load wg0.conf,
 * register WARP2, connect) is an independent action so the chain can be built
 * and debugged one process at a time, with a live verbose log underneath.
 *
 * It only drives [ChainManager]; it does not touch the single-hop WARP UI in
 * [ProxySettingsActivity] or the VPN service wiring. Wiring the chain SOCKS
 * endpoint into the live tunnel is a separate, later step; here the goal is to
 * stand each hop up and prove it over the log + the end-to-end liveness probe.
 */
class ChainSettingsActivity : AppCompatActivity(R.layout.activity_chain) {
    private val b by viewBinding(ActivityChainBinding::bind)
    private val persistentState by inject<PersistentState>()

    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        handleFrostEffectIfNeeded(persistentState.theme)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightNavigationBars = false
        window.isNavigationBarContrastEnforced = false

        wireClicks()
        loadEditorsFromDisk()
        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStatus()
    }

    private fun Context.isDarkThemeOn(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun wireClicks() {
        b.warp1RegisterBtn.setOnClickListener { doRegister(ChainManager.WARP1_CONFIG) }
        b.warp2RegisterBtn.setOnClickListener { doRegister(ChainManager.EXIT_CONFIG) }

        b.wgReloadBtn.setOnClickListener {
            b.wgConfigEdit.setText(ChainManager.readFile(this, ChainManager.WG_CONFIG))
            toast(getString(R.string.chain_config_reload))
        }
        b.wgSaveBtn.setOnClickListener {
            val text = b.wgConfigEdit.text?.toString().orEmpty()
            val ok = ChainManager.writeWgConfig(this, text)
            toast(getString(if (ok) R.string.chain_wg_loaded else R.string.chain_wg_invalid))
            refreshAllStatus()
        }

        b.chainConnectBtn.setOnClickListener { toggleChain() }

        b.chainLogRefreshBtn.setOnClickListener { refreshLog() }
        b.chainLogClearBtn.setOnClickListener {
            ChainManager.clearDebugLog(this); refreshLog()
        }
        b.chainLogShareBtn.setOnClickListener { shareLog() }
    }

    private fun loadEditorsFromDisk() {
        b.wgConfigEdit.setText(ChainManager.readFile(this, ChainManager.WG_CONFIG))
    }

    // ── step 1 / 3 ────────────────────────────────────────────────────────────
    private fun doRegister(configName: String) {
        if (busy) return
        busy = true
        val isExit = configName == ChainManager.EXIT_CONFIG
        val statusView = if (isExit) b.warp2Status else b.warp1Status
        val btn = if (isExit) b.warp2RegisterBtn else b.warp1RegisterBtn
        statusView.text = getString(R.string.chain_registering)
        btn.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { ChainManager.registerWarp(this@ChainSettingsActivity, configName) }
            busy = false
            btn.isEnabled = true
            toast(getString(if (ok) R.string.chain_registered_ok else R.string.chain_register_failed))
            refreshAllStatus()
            refreshLog()
        }
    }

    // ── step 4 ────────────────────────────────────────────────────────────────
    private fun toggleChain() {
        if (busy) return
        if (ChainManager.isRunning()) {
            ChainManager.stopChain()
            persistentState.chainEnabled = false
            refreshAllStatus()
            refreshLog()
            return
        }
        if (!ChainManager.chainReady(this)) {
            toast(getString(R.string.chain_not_ready))
            return
        }
        busy = true
        b.chainConnectBtn.isEnabled = false
        b.chainStatusText.text = getString(R.string.chain_connecting)
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) { ChainManager.startChain(this@ChainSettingsActivity) }
            // Confirm the whole chain actually carries traffic, not just that the
            // port opened (a half-up chain can bind the port then stall).
            val live = started && withContext(Dispatchers.IO) { ChainManager.probeChainLiveness() }
            busy = false
            if (live) {
                persistentState.chainEnabled = true
                toast(getString(R.string.chain_connected))
            } else {
                ChainManager.stopChain()
                persistentState.chainEnabled = false
                toast(getString(R.string.chain_connect_failed))
            }
            refreshAllStatus()
            refreshLog()
        }
    }

    // ── status / dots ─────────────────────────────────────────────────────────
    private fun refreshAllStatus() {
        val warp1 = ChainManager.warp1Registered(this)
        val wg = ChainManager.wgLoaded(this)
        val warp2 = ChainManager.warp2Registered(this)
        val running = ChainManager.isRunning()

        dot(b.warp1Dot, warp1)
        b.warp1Status.text = getString(if (warp1) R.string.chain_registered_ok else R.string.chain_not_registered)
        b.warp1RegisterBtn.setText(if (warp1) R.string.chain_reregister_btn else R.string.chain_register1_btn)

        dot(b.wgDot, wg)
        b.wgStatus.text = getString(if (wg) R.string.chain_wg_loaded else R.string.chain_wg_missing)

        dot(b.warp2Dot, warp2)
        b.warp2Status.text = getString(if (warp2) R.string.chain_registered_ok else R.string.chain_not_registered)
        b.warp2RegisterBtn.setText(if (warp2) R.string.chain_reregister_btn else R.string.chain_register2_btn)

        val ready = warp1 && wg && warp2
        b.chainConnectBtn.isEnabled = ready && !busy
        b.chainConnectBtn.setText(if (running) R.string.chain_disconnect else R.string.chain_connect)

        b.chainMasterSwitch.isEnabled = false // reflects state only; connect via the button
        b.chainMasterSwitch.isChecked = running

        dot(b.chainStatusDot, running)
        b.chainStatusText.text = getString(
            when {
                running -> R.string.chain_status_connected
                ready -> R.string.chain_status_ready
                else -> R.string.chain_status_incomplete
            }
        )
    }

    private fun dot(view: android.widget.ImageView, good: Boolean) {
        view.setImageResource(if (good) R.drawable.dot_green else R.drawable.dot_white)
    }

    // ── verbose log ───────────────────────────────────────────────────────────
    private fun refreshLog() {
        val text = ChainManager.readDebugLog(this)
        // Show the tail; the file is capped at 2 MB but can still be long.
        b.chainLogView.text = if (text.length > 20000) text.takeLast(20000) else text
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
}
