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
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.WgIncludeAppsAdapter
import com.celzero.bravedns.adapter.WgPeersAdapter
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ActivityWgDetailBinding
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.WireguardManager
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_OTHER_WG_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_ACTIVE
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_VPN_NOT_FULL
import com.celzero.bravedns.service.WireguardManager.ERR_CODE_WG_INVALID
import com.celzero.bravedns.service.WireguardManager.INVALID_CONF_ID
import com.celzero.bravedns.service.WireguardManager.WG_UPTIME_THRESHOLD
import com.celzero.bravedns.ui.activity.NetworkLogsActivity.Companion.RULES_SEARCH_ID_WIREGUARD
import com.celzero.bravedns.ui.dialog.WgAddPeerDialog
import com.celzero.bravedns.ui.dialog.WgIncludeAppsDialog
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.TunnelExporter
import androidx.activity.result.contract.ActivityResultContracts
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import com.celzero.bravedns.viewmodel.ProxyAppsMappingViewModel
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.bravedns.wireguard.util.ErrorMessages
import com.celzero.firestack.backend.RouterStats
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.getValue

class WgConfigDetailActivity : AppCompatActivity(R.layout.activity_wg_detail) {
    private val b by viewBinding(ActivityWgDetailBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

    private val mappingViewModel: ProxyAppsMappingViewModel by viewModel()

    private var wgPeersAdapter: WgPeersAdapter? = null
    private var layoutManager: LinearLayoutManager? = null

    private var configId: Int = INVALID_CONF_ID
    private var wgInterface: WgInterface? = null
    private val peers: MutableList<Peer> = mutableListOf()
    private var wgType: WgType = WgType.DEFAULT
    private var triedConfigRefresh: Boolean = false

    companion object {
        private const val CLIPBOARD_PUBLIC_KEY_LBL = "Public Key"
        const val INTENT_EXTRA_WG_TYPE = "WIREGUARD_TUNNEL_TYPE"
    }

    enum class WgType(val value: Int) {
        DEFAULT(0),
        ONE_WG(1);

        fun isOneWg() = this == ONE_WG

        fun isDefault() = this == DEFAULT

        companion object {
            fun fromInt(value: Int): WgType {
                return entries.firstOrNull { it.value == value }
                    ?: throw IllegalArgumentException("Invalid WgType value: $value")
            }
        }
    }

    private val downloadConfigLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri == null) return@registerForActivityResult
            val cfg = WireguardManager.getConfigById(configId)
            if (cfg == null) {
                Utilities.showToastUiCentered(this, getString(R.string.wireguard_download_failure), Toast.LENGTH_LONG)
                return@registerForActivityResult
            }
            lifecycleScope.launch {
                val result = TunnelExporter.exportConfig(contentResolver, uri, cfg)
                val msg = if (result.isSuccess) R.string.wireguard_download_success else R.string.wireguard_download_failure
                Utilities.showToastUiCentered(this@WgConfigDetailActivity, getString(msg), Toast.LENGTH_LONG)
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        configId = intent.getIntExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, INVALID_CONF_ID)
        wgType = WgType.fromInt(intent.getIntExtra(INTENT_EXTRA_WG_TYPE, WgType.DEFAULT.value))
    }

    override fun onResume() {
        super.onResume()
        init()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        val vpnActive = VpnController.hasTunnel()

        b.editBtn.text = getString(R.string.rt_edit_dialog_positive).uppercase()
        b.deleteBtn.text = getString(R.string.lbl_delete).uppercase()

        b.catchAllTitleTv.text =
            getString(
                R.string.two_argument_space,
                getString(R.string.catch_all_wg_dialog_title),
                getString(R.string.symbol_lightening)
            )

        if (wgType.isDefault()) {
            b.wgHeaderTv.text = getString(R.string.lbl_advanced).replaceFirstChar(Char::titlecase)
            b.catchAllRl.visibility = View.VISIBLE
            b.oneWgInfoTv.visibility = View.GONE
        } else if (wgType.isOneWg()) {
            b.wgHeaderTv.text =
                getString(R.string.rt_list_simple_btn_txt).replaceFirstChar(Char::titlecase)
            b.catchAllRl.visibility = View.GONE
            b.oneWgInfoTv.visibility = View.VISIBLE
            b.applicationsBtn.isEnabled = false
            b.applicationsBtnX.isEnabled = false
            b.applicationsBtn.text = getString(R.string.one_wg_apps_added)
            b.appsLabel.text = "All apps"
        } else {
            // invalid wireguard type, finish the activity
            finish()
            return
        }
        val config = WireguardManager.getConfigById(configId)
        val mapping = WireguardManager.getConfigFilesById(configId)

        if (config == null) {
            // Configs may not yet be loaded (e.g. cold-start race, or immediately
            // after import). Try refreshing once before declaring the config invalid.
            if (!triedConfigRefresh) {
                triedConfigRefresh = true
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { WireguardManager.load(forceRefresh = true) }
                    if (WireguardManager.getConfigById(configId) != null) {
                        init()
                    } else {
                        showInvalidConfigDialog()
                    }
                }
            } else {
                showInvalidConfigDialog()
            }
            return
        }

        if (mapping != null) {
            // if catch all is enabled, disable the add apps button
            b.catchAllCheck.isChecked = mapping.isCatchAll
            if (mapping.isCatchAll) {
                b.applicationsBtn.isEnabled = false
                b.applicationsBtn.text = getString(R.string.routing_remaining_apps)
            }
        }

        if (shouldObserveAppsCount()) {
            handleAppsCount()
        } else {
            b.applicationsBtn.isEnabled = false
        }
        if (vpnActive) {
            io { updateStatusUi(config.getId()) }
        } else {
            b.statusText.text = getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
        }
        prefillConfig(config)
    }

    private suspend fun updateStatusUi(id: Int) {
        val config = WireguardManager.getConfigFilesById(id)
        val cid = ID_WG_BASE + id
        if (config?.isActive == true) {
            val statusPair = VpnController.getProxyStatusById(cid)
            val stats = VpnController.getProxyStats(cid)
            val ps = UIUtils.ProxyStatus.entries.find { it.id == statusPair.first }
            val dnsStatusId = if (persistentState.splitDns) {
                VpnController.getDnsStatus(cid)
            } else {
                null
            }
            uiCtx {
                if (dnsStatusId != null && isDnsError(dnsStatusId)) {
                    // check for dns failure cases and update the UI
                    b.statusText.text = getString(R.string.status_failing)
                        .replaceFirstChar(Char::titlecase)
                    b.interfaceDetailCard.strokeWidth = 2
                    b.interfaceDetailCard.strokeColor = fetchColor(this, R.attr.chipTextNegative)
                    return@uiCtx
                } else if (statusPair.first != null) {
                    val handshakeTime = getHandshakeTime(stats).toString()
                    val statusText = getStatusText(ps, handshakeTime, stats, statusPair.second)
                    b.statusText.text = statusText
                } else {
                    if (statusPair.second.isEmpty()) {
                        b.statusText.text =
                            getString(R.string.status_waiting).replaceFirstChar(Char::titlecase)
                    } else {
                        val txt =
                            getString(R.string.status_waiting).replaceFirstChar(Char::titlecase) + " (${statusPair.second})"
                        b.statusText.text = txt
                    }
                }
                val strokeColor = getStrokeColorForStatus(ps, stats)
                b.interfaceDetailCard.strokeWidth = 2
                b.interfaceDetailCard.strokeColor = fetchColor(this, strokeColor)
            }
        } else {
            uiCtx {
                b.interfaceDetailCard.strokeWidth = 0
                b.statusText.text =
                    getString(R.string.lbl_disabled).replaceFirstChar(Char::titlecase)
            }
        }
    }

    private fun isDnsError(statusId: Long?): Boolean {
        if (statusId == null) return true

        val s = Transaction.Status.fromId(statusId)
        return s == Transaction.Status.BAD_QUERY || s == Transaction.Status.BAD_RESPONSE || s == Transaction.Status.NO_RESPONSE || s == Transaction.Status.SEND_FAIL || s == Transaction.Status.CLIENT_ERROR || s == Transaction.Status.INTERNAL_ERROR || s == Transaction.Status.TRANSPORT_ERROR
    }

    private fun getStatusText(
        status: UIUtils.ProxyStatus?,
        handshakeTime: String? = null,
        stats: RouterStats?,
        errMsg: String? = null
    ): String {
        if (status == null) {
            val txt = if (!errMsg.isNullOrEmpty()) {
                getString(R.string.status_waiting) + " ($errMsg)"
            } else {
                getString(R.string.status_waiting)
            }
            return txt.replaceFirstChar(Char::titlecase)
        }

        // no need to check for lastOk/since for paused wg
        if (status == UIUtils.ProxyStatus.TPU) {
            return getString(UIUtils.getProxyStatusStringRes(status.id))
                .replaceFirstChar(Char::titlecase)
        }

        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        if (now - since > WG_UPTIME_THRESHOLD && lastOk == 0L) {
            return getString(R.string.status_failing).replaceFirstChar(Char::titlecase)
        }

        val baseText = getString(UIUtils.getProxyStatusStringRes(status.id))
            .replaceFirstChar(Char::titlecase)

        return if (stats?.lastOK != 0L && handshakeTime != null) {
            getString(R.string.about_version_install_source, baseText, handshakeTime)
        } else {
            baseText
        }
    }

    private fun getHandshakeTime(stats: RouterStats?): CharSequence {
        if (stats == null) {
            return ""
        }
        if (stats.lastOK == 0L) {
            return ""
        }
        val now = System.currentTimeMillis()
        // returns a string describing 'time' as a time relative to 'now'
        return DateUtils.getRelativeTimeSpanString(
            stats.lastOK,
            now,
            DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun getStrokeColorForStatus(status: UIUtils.ProxyStatus?, stats: RouterStats?): Int {
        val now = System.currentTimeMillis()
        val lastOk = stats?.lastOK ?: 0L
        val since = stats?.since ?: 0L
        val isFailing = now - since > WG_UPTIME_THRESHOLD && lastOk == 0L
        return when (status) {
            UIUtils.ProxyStatus.TOK -> if (isFailing) R.attr.chipTextNeutral else R.attr.accentGood
            UIUtils.ProxyStatus.TUP, UIUtils.ProxyStatus.TZZ, UIUtils.ProxyStatus.TNT -> R.attr.chipTextNeutral
            else -> R.attr.chipTextNegative // TKO, TEND
        }
    }

    private fun showInvalidConfigDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.lbl_wireguard))
        builder.setMessage(getString(R.string.config_invalid_desc))
        builder.setCancelable(false)
        builder.setPositiveButton(getString(R.string.fapps_info_dialog_positive_btn)) { _, _ ->
            finish()
        }
        builder.setNeutralButton(getString(R.string.lbl_delete)) { _, _ ->
            WireguardManager.deleteConfig(configId)
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun shouldObserveAppsCount(): Boolean {
        return !wgType.isOneWg() && !b.catchAllCheck.isChecked
    }

    private fun prefillConfig(config: Config) {
        wgInterface = config.getInterface()
        peers.clear()
        peers.addAll(config.getPeers() ?: emptyList())
        if (wgInterface == null) {
            return
        }
        b.configNameText.visibility = View.VISIBLE
        b.configNameText.text = config.getName()
        b.configIdText.text =
            getString(R.string.single_argument_parenthesis, config.getId().toString())

        if (wgType.isOneWg()) {
            b.dnsServersLabel.visibility = View.VISIBLE
            b.dnsServersText.visibility = View.VISIBLE
            var dns = wgInterface?.dnsServers?.joinToString { it.hostAddress?.toString() ?: "" }
            val searchDomains = wgInterface?.dnsSearchDomains?.joinToString { it }
            dns = if (!searchDomains.isNullOrEmpty()) "$dns,$searchDomains" else dns
            b.dnsServersText.text = dns
        } else {
            b.publicKeyLabel.visibility = View.VISIBLE
            b.publicKeyText.visibility = View.VISIBLE
            b.publicKeyText.text = wgInterface?.getKeyPair()?.getPublicKey()?.base64().tos()
            b.dnsServersLabel.visibility = View.GONE
            b.dnsServersText.visibility = View.GONE
        }

        prefillConfigCard(config)
        setPeersAdapter()
    }

    private fun prefillConfigCard(config: Config) {
        val iface = config.getInterface() ?: return
        b.wgConfigNameText.setText(config.getName())
        var dns = iface.dnsServers.joinToString { it.hostAddress?.toString() ?: "" }
        val searchDomains = iface.dnsSearchDomains.joinToString { it }
        if (searchDomains.isNotEmpty()) dns = "$dns,$searchDomains"
        b.wgConfigDnsText.setText(dns)
        if (iface.mtu.isPresent) {
            b.wgConfigMtuText.setText(iface.mtu.get().toString())
        }
    }

    private fun handleAppsCount() {
        val id = ID_WG_BASE + configId
        b.applicationsBtn.isEnabled = true
        mappingViewModel.getAppCountById(id).observe(this) {
            if (it == 0) {
                b.applicationsBtn.setTextColor(fetchColor(this, R.attr.accentBad))
            } else {
                b.applicationsBtn.setTextColor(fetchColor(this, R.attr.accentGood))
            }
            b.applicationsBtn.text = getString(R.string.add_remove_apps, it.toString())
            b.appsLabel.text = "Apps ($it)"
        }
    }

    private fun setupClickListeners() {
        b.editBtn.setOnClickListener {
            val intent = Intent(this, WgConfigEditorActivity::class.java)
            intent.putExtra(WgConfigEditorActivity.INTENT_EXTRA_WG_ID, configId)
            intent.putExtra(INTENT_EXTRA_WG_TYPE, wgType.value)
            this.startActivity(intent)
        }

        b.addPeerFab.setOnClickListener { openAddPeerDialog() }

        b.applicationsBtn.setOnClickListener {
            val proxyName = WireguardManager.getConfigName(configId)
            openAppsDialog(proxyName)
        }

        b.downloadBtn.setOnClickListener {
            val name = WireguardManager.getConfigName(configId).ifBlank { "wg-$configId" }
            try {
                downloadConfigLauncher.launch("$name.conf")
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "download wg config failed: ${e.message}", e)
                Utilities.showToastUiCentered(this, getString(R.string.wireguard_download_failure), Toast.LENGTH_LONG)
            }
        }

        b.deleteBtn.setOnClickListener { showDeleteInterfaceDialog() }

        /*b.newConfLayout.setOnClickListener {
            b.newConfProgressBar.visibility = View.VISIBLE
            io { createConfigOrShowErrorLayout() }
        }*/

        b.publicKeyLabel.setOnClickListener {
            UIUtils.clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }

        b.publicKeyText.setOnClickListener {
            UIUtils.clipboardCopy(this, b.publicKeyText.text.toString(), CLIPBOARD_PUBLIC_KEY_LBL)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.public_key_copy_toast_msg),
                Toast.LENGTH_SHORT
            )
        }

        b.catchAllRl.setOnClickListener {
            b.catchAllCheck.isChecked = !b.catchAllCheck.isChecked
            updateCatchAll(b.catchAllCheck.isChecked)
        }

        b.catchAllCheck.setOnClickListener { updateCatchAll(b.catchAllCheck.isChecked) }

        b.logsBtn.setOnClickListener {
            startActivity(ID_WG_BASE + configId)
        }

        b.wgConfigSaveBtn.setOnClickListener {
            saveConfigCard()
        }
    }

    private fun startActivity(searchParam: String?) {
        val intent = Intent(this, NetworkLogsActivity::class.java)
        val query = RULES_SEARCH_ID_WIREGUARD + searchParam
        intent.putExtra(Constants.SEARCH_QUERY, query)
        startActivity(intent)
    }

    private fun updateCatchAll(enabled: Boolean) {
        io {
            if (!VpnController.hasTunnel()) {
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        ERR_CODE_VPN_NOT_ACTIVE + getString(R.string.settings_socks5_vpn_disabled_error),
                        Toast.LENGTH_LONG
                    )
                    b.catchAllCheck.isChecked = !enabled
                }
                return@io
            }

            if (!WireguardManager.canEnableProxy()) {
                Logger.i(
                    LOG_TAG_PROXY,
                    "not in DNS+Firewall mode, cannot enable WireGuard"
                )
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        this,
                        ERR_CODE_VPN_NOT_FULL + getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return@io
            }

            if (WireguardManager.oneWireGuardEnabled()) {
                // this should not happen, ui is disabled if one wireGuard is enabled
                Logger.w(LOG_TAG_PROXY, "one wireGuard is already enabled")
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        this,
                        ERR_CODE_OTHER_WG_ACTIVE + getString(
                            R.string.wireguard_enabled_failure
                        ),
                        Toast.LENGTH_LONG
                    )
                }
                return@io
            }


            val config = WireguardManager.getConfigFilesById(configId)
            if (config == null) {
                Logger.e(LOG_TAG_PROXY, "updateCatchAll: config not found for $configId")
                uiCtx {
                    // reset the check box
                    b.catchAllCheck.isChecked = false
                    Utilities.showToastUiCentered(
                        this,
                        ERR_CODE_WG_INVALID + getString(R.string.wireguard_enabled_failure),
                        Toast.LENGTH_LONG
                    )
                }
                return@io
            }

            WireguardManager.updateCatchAllConfig(configId, enabled)
            logEvent(
                "WireGuard Catch All apps",
                "User ${if (enabled) "enabled" else "disabled"} catch all apps for WireGuard config with id $configId"
            )
            uiCtx {
                b.applicationsBtn.isEnabled = !enabled
                if (enabled) {
                    b.applicationsBtn.text = getString(R.string.routing_remaining_apps)
                } else {
                    handleAppsCount()
                }
            }
        }
    }

    private fun openAppsDialog(proxyName: String) {
        val proxyId = ID_WG_BASE + configId
        val appsAdapter = WgIncludeAppsAdapter(this, proxyId, proxyName)
        mappingViewModel.apps.observe(this) { appsAdapter.submitData(lifecycle, it) }
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val includeAppsDialog =
            WgIncludeAppsDialog(this, appsAdapter, mappingViewModel, themeId, proxyId, proxyName)
        includeAppsDialog.setCanceledOnTouchOutside(false)
        includeAppsDialog.show()
    }

    private fun showDeleteInterfaceDialog() {
        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        val delText =
            getString(
                R.string.two_argument_space,
                getString(R.string.config_delete_dialog_title),
                getString(R.string.lbl_wireguard)
            )
        builder.setTitle(delText)
        builder.setMessage(getString(R.string.config_delete_dialog_desc))
        builder.setCancelable(true)
        builder.setPositiveButton(delText) { _, _ ->
            io {
                WireguardManager.deleteConfig(configId)
                uiCtx {
                    Utilities.showToastUiCentered(
                        this,
                        getString(R.string.config_add_success_toast),
                        Toast.LENGTH_SHORT
                    )
                    finish()
                }
                logEvent("Delete WireGuard config", "User deleted WireGuard config with id $configId")
            }
        }

        builder.setNegativeButton(this.getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }
        val dialog = builder.create()
        dialog.show()
    }

    private fun openAddPeerDialog() {
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val addPeerDialog = WgAddPeerDialog(this, themeId, configId, null)
        addPeerDialog.setCanceledOnTouchOutside(false)
        addPeerDialog.show()
        addPeerDialog.setOnDismissListener {
            if (wgPeersAdapter != null) {
                wgPeersAdapter?.dataChanged()
            } else {
                setPeersAdapter()
            }
        }
    }

    private fun saveConfigCard() {
        val name = b.wgConfigNameText.text.toString().trim()
        val dns = b.wgConfigDnsText.text.toString().trim().ifEmpty { "1.1.1.1" }
        // "0" means not set (Optional.empty); empty field = use default
        val mtu = b.wgConfigMtuText.text.toString().trim().ifEmpty { "0" }
        val currentIface = wgInterface ?: return
        io {
            try {
                val builder = WgInterface.Builder()
                    .parsePrivateKey(currentIface.getKeyPair().getPrivateKey().base64().tos())
                    .parseAddresses(currentIface.getAddresses().joinToString { it.toString() })
                    .parseListenPort(
                        if (currentIface.listenPort.isPresent) currentIface.listenPort.get().toString() else "0"
                    )
                    .parseDnsServers(dns)
                    .parseMtu(mtu)
                // preserve amnezia props if present
                currentIface.getJc().ifPresent { builder.setJc(it) }
                currentIface.getJmin().ifPresent { builder.setJmin(it) }
                currentIface.getJmax().ifPresent { builder.setJmax(it) }
                currentIface.getS1().ifPresent { builder.setS1(it) }
                currentIface.getS2().ifPresent { builder.setS2(it) }
                currentIface.getH1().ifPresent { builder.setH1(it) }
                currentIface.getH2().ifPresent { builder.setH2(it) }
                currentIface.getH3().ifPresent { builder.setH3(it) }
                currentIface.getH4().ifPresent { builder.setH4(it) }
                val newIface = builder.build()
                val updated = WireguardManager.addOrUpdateInterface(configId, name, newIface)
                if (updated != null) {
                    wgInterface = updated.getInterface()
                    uiCtx {
                        b.configNameText.text = updated.getName()
                        Utilities.showToastUiCentered(
                            this,
                            getString(R.string.config_add_success_toast),
                            Toast.LENGTH_SHORT
                        )
                    }
                    logEvent("Config card saved", "Updated name/dns/mtu for configId: $configId")
                }
            } catch (e: Throwable) {
                val error = ErrorMessages[this@WgConfigDetailActivity, e]
                uiCtx {
                    Utilities.showToastUiCentered(this, error, Toast.LENGTH_LONG)
                }
            }
        }
    }

    private fun setPeersAdapter() {
        layoutManager = LinearLayoutManager(this)
        b.peersList.layoutManager = layoutManager
        val themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        wgPeersAdapter = WgPeersAdapter(this, themeId, configId, peers)
        b.peersList.adapter = wgPeersAdapter
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        if (isFinishing || isDestroyed) return
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }


    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.PROXY_SWITCH, Severity.LOW, msg, EventSource.UI, false, details)
    }
}
