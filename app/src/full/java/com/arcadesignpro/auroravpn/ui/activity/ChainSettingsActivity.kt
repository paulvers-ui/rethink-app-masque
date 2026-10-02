package com.arcadesignpro.auroravpn.ui.activity

import Logger
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Setup + test screen for the nested WARP1 -> wg0 -> WARP2 chain.
 *
 * Three per-hop switches reflect and gate readiness:
 *   - WARP1 switch: on once config.json is registered (register via its button).
 *   - WG switch:    on once wg0.conf is loaded (paste + Save, or Import .conf).
 *   - WARP2 switch: on once config_exit.json is registered.
 * With all three on, Connect starts `libusque.so chain`; the master switch then
 * reflects whether the chain process is actually carrying traffic.
 *
 * Each step is independent so the chain can be built and debugged one process at
 * a time, with a live verbose log (chain_debug.txt) underneath. This screen only
 * drives [ChainManager]; it does not modify the single-hop WARP UI or the VPN
 * service. Flipping a switch off for a running chain disconnects it.
 */
// One small handler per card/button keeps the steps independent; the two catches
// guard content-resolver / share-intent calls that can throw anything and must
// only show a toast.
@Suppress("TooManyFunctions", "TooGenericExceptionCaught", "ReturnCount")
class ChainSettingsActivity : AppCompatActivity(R.layout.activity_chain) {
    private val b by viewBinding(ActivityChainBinding::bind)
    private val persistentState by inject<PersistentState>()

    private var busy = false
    // Guards against the programmatic isChecked updates in refreshAllStatus()
    // re-entering the switch listeners.
    private var updatingSwitches = false

    private val importWgLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            importWgFrom(uri)
        }

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

        b.wgImportBtn.setOnClickListener { importWgLauncher.launch("*/*") }
        b.wgReloadBtn.setOnClickListener {
            b.wgConfigEdit.setText(ChainManager.readFile(this, ChainManager.WG_CONFIG))
            toast(getString(R.string.chain_config_reload))
        }
        b.wgSaveBtn.setOnClickListener { saveWgFromEditor() }

        b.chainConnectBtn.setOnClickListener { toggleChain() }

        b.chainLogRefreshBtn.setOnClickListener { refreshLog() }
        b.chainLogClearBtn.setOnClickListener { ChainManager.clearDebugLog(this); refreshLog() }
        b.chainLogShareBtn.setOnClickListener { shareLog() }

        // Per-hop switches: WARP1 / WARP2 gate on registration, WG gates on a
        // loaded config. Turning any off while the chain runs disconnects it.
        b.warp1Switch.setOnCheckedChangeListener { _, isChecked ->
            onHopSwitch(isChecked, registered = ChainManager.warp1Registered(this),
                needMsg = R.string.chain_need_register_first)
        }
        b.warp2Switch.setOnCheckedChangeListener { _, isChecked ->
            onHopSwitch(isChecked, registered = ChainManager.warp2Registered(this),
                needMsg = R.string.chain_need_register_first)
        }
        b.wgSwitch.setOnCheckedChangeListener { _, isChecked ->
            onHopSwitch(isChecked, registered = ChainManager.wgLoaded(this),
                needMsg = R.string.chain_need_wg_first)
        }
    }

    private fun onHopSwitch(isChecked: Boolean, registered: Boolean, needMsg: Int) {
        if (updatingSwitches) return
        if (isChecked && !registered) {
            // Can't arm a hop that isn't set up; bounce it back off.
            toast(getString(needMsg))
            refreshAllStatus()
            return
        }
        if (!isChecked && ChainManager.isRunning()) {
            // Disarming any hop tears the running chain down.
            ChainManager.stopChain()
            persistentState.chainEnabled = false
        }
        refreshAllStatus()
    }

    private fun loadEditorsFromDisk() {
        b.wgConfigEdit.setText(ChainManager.readFile(this, ChainManager.WG_CONFIG))
    }

    // ── step 2: wg0.conf (paste or import) ────────────────────────────────────
    private fun saveWgFromEditor() {
        val text = b.wgConfigEdit.text?.toString().orEmpty()
        val ok = ChainManager.writeWgConfig(this, text)
        toast(getString(if (ok) R.string.chain_wg_loaded else R.string.chain_wg_invalid))
        refreshAllStatus()
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
            refreshAllStatus()
            refreshLog()
        }
    }

    // ── step 1 / 3: register a WARP identity ──────────────────────────────────
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

    // ── step 4: connect ───────────────────────────────────────────────────────
    private fun toggleChain() {
        if (busy) return
        if (ChainManager.isRunning()) {
            ChainManager.stopChain()
            persistentState.chainEnabled = false
            refreshAllStatus(); refreshLog()
            return
        }
        if (!ChainManager.chainReady(this)) {
            toast(getString(R.string.chain_not_ready)); return
        }
        busy = true
        b.chainConnectBtn.isEnabled = false
        b.chainStatusText.text = getString(R.string.chain_connecting)
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) { ChainManager.startChain(this@ChainSettingsActivity) }
            // Only report success if the whole chain actually carries traffic
            // (a half-up chain can bind the port then stall).
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
            refreshAllStatus(); refreshLog()
        }
    }

    // ── status / dots / switches ──────────────────────────────────────────────
    private fun refreshAllStatus() {
        val warp1 = ChainManager.warp1Registered(this)
        val wg = ChainManager.wgLoaded(this)
        val warp2 = ChainManager.warp2Registered(this)
        val running = ChainManager.isRunning()
        val ready = warp1 && wg && warp2

        updatingSwitches = true

        dot(b.warp1Dot, warp1)
        b.warp1Status.text = getString(if (warp1) R.string.chain_registered_ok else R.string.chain_not_registered)
        b.warp1RegisterBtn.setText(if (warp1) R.string.chain_reregister_btn else R.string.chain_register1_btn)
        armSwitch(b.warp1Switch, enabled = warp1, on = warp1)

        dot(b.wgDot, wg)
        b.wgStatus.text = getString(if (wg) R.string.chain_wg_loaded else R.string.chain_wg_missing)
        armSwitch(b.wgSwitch, enabled = wg, on = wg)

        dot(b.warp2Dot, warp2)
        b.warp2Status.text = getString(if (warp2) R.string.chain_registered_ok else R.string.chain_not_registered)
        b.warp2RegisterBtn.setText(if (warp2) R.string.chain_reregister_btn else R.string.chain_register2_btn)
        armSwitch(b.warp2Switch, enabled = warp2, on = warp2)

        b.chainConnectBtn.isEnabled = ready && !busy
        b.chainConnectBtn.setText(if (running) R.string.chain_disconnect else R.string.chain_connect)

        b.chainMasterSwitch.isEnabled = false // reflects the live chain; connect via the button
        b.chainMasterSwitch.isChecked = running

        dot(b.chainStatusDot, running)
        b.chainStatusText.text = getString(
            when {
                running -> R.string.chain_status_connected
                ready -> R.string.chain_status_ready
                else -> R.string.chain_status_incomplete
            }
        )

        updatingSwitches = false
    }

    private fun armSwitch(sw: SwitchMaterial, enabled: Boolean, on: Boolean) {
        sw.isEnabled = enabled && !busy
        sw.isChecked = on
    }

    private fun dot(view: android.widget.ImageView, good: Boolean) {
        view.setImageResource(if (good) R.drawable.dot_green else R.drawable.dot_white)
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
    }
}
