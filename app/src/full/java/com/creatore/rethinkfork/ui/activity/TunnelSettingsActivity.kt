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
package com.creatore.rethinkfork.ui.activity

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import by.kirich1409.viewbindingdelegate.viewBinding
import com.creatore.rethinkfork.R
import com.creatore.rethinkfork.data.AppConfig
import com.creatore.rethinkfork.database.EventSource
import com.creatore.rethinkfork.database.EventType
import com.creatore.rethinkfork.database.Severity
import com.creatore.rethinkfork.databinding.ActivityTunnelSettingsBinding
import com.creatore.rethinkfork.service.EventLogger
import com.creatore.rethinkfork.service.PersistentState
import com.creatore.rethinkfork.service.VpnController
import com.creatore.rethinkfork.ui.dialog.NetworkReachabilityDialog
import com.creatore.rethinkfork.util.Constants
import com.creatore.rethinkfork.util.InternetProtocol
import com.creatore.rethinkfork.util.Themes
import com.creatore.rethinkfork.util.UIUtils
import com.creatore.rethinkfork.util.Utilities
import com.creatore.rethinkfork.util.Utilities.isAtleastQ
import com.creatore.rethinkfork.util.Utilities.showToastUiCentered
import com.creatore.rethinkfork.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.koin.android.ext.android.inject
import java.util.concurrent.TimeUnit

class TunnelSettingsActivity : AppCompatActivity(R.layout.activity_tunnel_settings) {
    private val b by viewBinding(ActivityTunnelSettingsBinding::bind)
    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()
    private val eventLogger by inject<EventLogger>()

    companion object {
        // Time conversion constants
        private const val SECONDS_PER_MINUTE = 60
        private const val SECONDS_PER_HOUR = 3600

        // Network policy indices
        private const val POLICY_AUTO = 0
        private const val POLICY_SENSITIVE = 1
        private const val POLICY_RELAXED = 2
        private const val POLICY_FIXED = 3

        // IP protocol dialog positions
        private const val IP_DIALOG_POS_IPV4 = 0
        private const val IP_DIALOG_POS_IPV6 = 1
        private const val IP_DIALOG_POS_ALWAYS_V46 = 2
        private const val IP_DIALOG_POS_V46 = 3

        // Alpha values for UI elements
        private const val ALPHA_ENABLED = 1f
        private const val ALPHA_DISABLED = 0.5f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        //setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        initView()
        setupClickListeners()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onResume() {
        super.onResume()
        handleLockdownModeIfNeeded()
    }

    private fun initView() {
        // show ping ips
        b.settingsActivityPingIpsBtn.visibility = if (persistentState.connectivityChecks) View.VISIBLE else View.GONE
        // for protocol translation, enable only on DNS/DNS+Firewall mode
        if (appConfig.getBraveMode().isDnsActive()) {
            b.settingsActivityPtransSwitch.isChecked = persistentState.protocolTranslationType
        } else {
            persistentState.protocolTranslationType = false
            b.settingsActivityPtransSwitch.isChecked = false
        }

        displayInternetProtocolUi()
        showNwPolicyDescription(persistentState.vpnBuilderPolicy)
        
        // If Fixed policy is selected, disable IP version settings
        if (persistentState.vpnBuilderPolicy == POLICY_FIXED) {
            b.settingsActivityIpRl.isEnabled = false
        }
    }


    private fun setupClickListeners() {
        b.settingsActivityVpnLockdownDesc.setOnClickListener { UIUtils.openVpnProfile(this) }

        b.settingsActivityIpRl.setOnClickListener {
            if (persistentState.vpnBuilderPolicy == POLICY_FIXED) return@setOnClickListener

            enableAfterDelay(TimeUnit.SECONDS.toMillis(1L), b.settingsActivityIpRl)
            showIpDialog()
        }

        b.settingsActivityPtransRl.setOnClickListener {
            b.settingsActivityPtransSwitch.isChecked = !b.settingsActivityPtransSwitch.isChecked
        }

        b.settingsActivityPtransSwitch.setOnCheckedChangeListener { _, isSelected ->
            if (appConfig.getBraveMode().isDnsActive()) {
                persistentState.protocolTranslationType = isSelected
            } else {
                b.settingsActivityPtransSwitch.isChecked = false
                showToastUiCentered(
                    this,
                    getString(R.string.settings_protocol_translation_dns_inactive),
                    Toast.LENGTH_SHORT
                )
            }
            logEvent(
                "protocol translation",
                "Protocol translation set to: $isSelected"
            )
        }

        b.settingsActivityDefaultDnsRl.setOnClickListener { showDefaultDnsDialog() }

        b.settingsVpnProcessPolicyRl.setOnClickListener { showTunNetworkPolicyDialog() }

        b.settingsActivityConnectivityChecksRl.setOnClickListener {
            showConnectivityChecksOptionsDialog()
        }

        b.settingsActivityConnectivityChecksImg.setOnClickListener {
            showConnectivityChecksOptionsDialog()
        }

        b.settingsActivityPingIpsBtn.setOnClickListener {
            if (!VpnController.hasTunnel()) {
                showToastUiCentered(
                    this,
                    getString(R.string.settings_socks5_vpn_disabled_error),
                    Toast.LENGTH_SHORT
                )
                return@setOnClickListener
            }
            showNwReachabilityCheckDialog()
        }

        // Custom LAN IPs for VPN
        b.settingsCustomLanIpHeading.text = getString(R.string.custom_lan_ip_title)
        b.settingsCustomLanIpDesc.text = getString(R.string.custom_lan_ip_desc)
        b.settingsCustomLanIpRl.setOnClickListener {
            openCustomLanIpDialog()
        }
    }

    private fun openCustomLanIpDialog() {
        try {
            var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
            if (Themes.isFrostTheme(themeId)) {
                themeId = R.style.App_Dialog_NoDim
            }
            val dialog = com.creatore.rethinkfork.ui.dialog.CustomLanIpDialog(
                this,
                persistentState,
                themeId
            )
            dialog.setCanceledOnTouchOutside(true)
            dialog.show()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "err opening CustomLanIpDialog: ${e.message}", e)
            showToastUiCentered(
                this,
                getString(R.string.custom_lan_ip_open_error),
                Toast.LENGTH_LONG
            )
        }
    }

    private fun showDefaultDnsDialog() {
        /*if (RpnProxyManager.isRpnEnabled()) {
            showToastUiCentered(
                this,
                getString(R.string.fallback_rplus_toast),
                Toast.LENGTH_SHORT
            )
            return
        }*/

        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_default_dns_heading))
        val items = Constants.DEFAULT_DNS_LIST.map { it.name }.toTypedArray()
        // get the index of the default dns url
        // if the default dns url is not in the list, then select the first item
        val checkedItem =
            Constants.DEFAULT_DNS_LIST.firstOrNull { it.url == persistentState.defaultDnsUrl }
                ?.let { Constants.DEFAULT_DNS_LIST.indexOf(it) } ?: 0
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, pos ->
            dialog.dismiss()
            // update the default dns url
            persistentState.defaultDnsUrl = Constants.DEFAULT_DNS_LIST[pos].url
            logEvent(
                "default dns changed",
                "Default DNS changed to: ${Constants.DEFAULT_DNS_LIST[pos].name}"
            )
        }
        val dialog = alertBuilder.create()
        dialog.show()
    }

    data class NetworkPolicyOption(val title: String, val description: String)
    private fun showTunNetworkPolicyDialog() {
        val conservativeTxt = getString(R.string.two_argument_space, getString(R.string.vpn_policy_fixed), getString(R.string.lbl_experimental))
        val options = listOf(
            NetworkPolicyOption(getString(R.string.settings_ip_text_ipv46), getString(R.string.vpn_policy_auto_desc)),
            NetworkPolicyOption(getString(R.string.vpn_policy_sensitive), getString(R.string.vpn_policy_sensitive_desc)),
            NetworkPolicyOption(getString(R.string.vpn_policy_relaxed), getString(R.string.vpn_policy_relaxed_desc)),
            NetworkPolicyOption(conservativeTxt, getString(R.string.vpn_policy_fixed_desc))
        )
        var currentSelection = persistentState.vpnBuilderPolicy
        val adapter = object : ArrayAdapter<NetworkPolicyOption>(
            this, R.layout.item_network_policy, R.id.policyTitle, options
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val titleView = view.findViewById<AppCompatTextView>(R.id.policyTitle)
                val descView = view.findViewById<AppCompatTextView>(R.id.policyDesc)
                val radio = view.findViewById<AppCompatRadioButton>(R.id.radioButton)

                val item = getItem(position)
                titleView.text = item?.title
                descView.text = item?.description
                radio.isChecked = position == currentSelection

                return view
            }
        }

        val builder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
            .setTitle(getString(R.string.vpn_policy_title))
            .setAdapter(adapter) { _, which ->
                currentSelection = which
                if (currentSelection == POLICY_FIXED) {
                    // enable experimental settings prompt
                    persistentState.enableStabilityDependentSettings(this)
                }
                saveNetworkPolicy(which)
                adapter.notifyDataSetChanged()
            }

        val dialog = builder.create()
        dialog.show()
    }

    private fun saveNetworkPolicy(which: Int) {
        persistentState.vpnBuilderPolicy = which
        showNwPolicyDescription(which)

        // If Fixed policy is selected (index 3), enable jumbo packets and set IPv4 & IPv6
        if (which == POLICY_FIXED) {
            // Set IP version to IPv4 & IPv6 (ALWAYSv46)
            persistentState.internetProtocolType = InternetProtocol.ALWAYSv46.id
            b.settingsActivityIpRl.isEnabled = false
            displayInternetProtocolUi()
        } else {
            b.settingsActivityIpRl.isEnabled = true
        }
        logEvent(
            "vpn builder network policy changed",
            "VPN builder network policy changed to index: $which"
        )
    }

    private fun showNwPolicyDescription(which: Int) {
        when (which) {
            POLICY_AUTO -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.settings_ip_text_ipv46) }
            POLICY_SENSITIVE -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.vpn_policy_sensitive) }
            POLICY_RELAXED -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.vpn_policy_relaxed) }
            POLICY_FIXED -> { b.settingsVpnNwPolicyDesc.text = getString(R.string.vpn_policy_fixed) }
        }
    }

    private fun showNwReachabilityCheckDialog() {
        var themeId = Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme)
        if (Themes.isFrostTheme(themeId)) {
            themeId = R.style.App_Dialog_NoDim
        }
        val nwReachabilityDialog = NetworkReachabilityDialog(this, persistentState, themeId)
        nwReachabilityDialog.setCanceledOnTouchOutside(true)
        nwReachabilityDialog.show()
    }

    private fun displayInternetProtocolUi() {
        b.settingsActivityIpRl.isEnabled = true
        when (persistentState.internetProtocolType) {
            InternetProtocol.IPv4.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE
            }
            InternetProtocol.IPv6.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv6)
                    )
                b.settingsActivityPtransRl.visibility = View.VISIBLE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE
            }
            InternetProtocol.IPv46.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv46)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.VISIBLE
                if (persistentState.connectivityChecks) {
                    b.settingsActivityPingIpsBtn.visibility = View.VISIBLE
                } else {
                    b.settingsActivityPingIpsBtn.visibility = View.GONE
                }
            }
            InternetProtocol.ALWAYSv46.id -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4) + " & " + getString(R.string.settings_ip_text_ipv6)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE
            }
            else -> {
                b.genSettingsIpDesc.text =
                    getString(
                        R.string.settings_selected_ip_desc,
                        getString(R.string.settings_ip_text_ipv4)
                    )
                b.settingsActivityPtransRl.visibility = View.GONE
                b.settingsActivityConnectivityChecksRl.visibility = View.GONE
                b.settingsActivityPingIpsBtn.visibility = View.GONE
            }
        }
    }

    private fun showIpDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_ip_dialog_title))
        val alwaysv46Txt = getString(R.string.settings_ip_text_ipv4) + " & " + getString(R.string.settings_ip_text_ipv6) + " " + getString(R.string.lbl_experimental)
        val items =
            arrayOf(
                getString(R.string.settings_ip_dialog_ipv4),
                getString(R.string.settings_ip_dialog_ipv6),
                alwaysv46Txt,
                getString(R.string.settings_ip_dialog_ipv46),
            )
        val chosenProtocol = persistentState.internetProtocolType
        val checkedItem = when (chosenProtocol) {
            InternetProtocol.ALWAYSv46.id -> {
                IP_DIALOG_POS_ALWAYS_V46 // alwaysV46 is at pos 2
            }
            InternetProtocol.IPv46.id -> {
                IP_DIALOG_POS_V46 // ipv46 is at pos 3
            }
            else -> {
                when (chosenProtocol) {
                    InternetProtocol.IPv4.id -> IP_DIALOG_POS_IPV4
                    InternetProtocol.IPv6.id -> IP_DIALOG_POS_IPV6
                    else -> IP_DIALOG_POS_IPV4
                }
            }
        }
        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            val selectedItem = when (which) {
                IP_DIALOG_POS_V46 -> {
                    InternetProtocol.IPv46.id // ipv46 is at pos 3
                }
                IP_DIALOG_POS_ALWAYS_V46 -> {
                    InternetProtocol.ALWAYSv46.id // alwaysV46 is at pos 2
                }
                else -> {
                    which
                }
            }
            // return if already selected item is same as current item
            if (persistentState.internetProtocolType == selectedItem) {
                return@setSingleChoiceItems
            }

            val protocolType = InternetProtocol.getInternetProtocol(selectedItem)
            persistentState.internetProtocolType = protocolType.id

            // Enable experimental-dependent settings for IPv6, IPv46, and ALWAYSv46 (experimental protocols)
            if (protocolType.id == InternetProtocol.IPv6.id ||
                protocolType.id == InternetProtocol.IPv46.id ||
                protocolType.id == InternetProtocol.ALWAYSv46.id) {
                persistentState.enableStabilityDependentSettings(this)
            }

            displayInternetProtocolUi()
            logEvent(
                "internet protocol changed",
                "Internet protocol changed to: ${protocolType.name}"
            )
        }
        alertBuilder.create().show()
    }

    private fun showConnectivityChecksOptionsDialog() {
        val alertBuilder = MaterialAlertDialogBuilder(this, R.style.App_Dialog_NoDim)
        alertBuilder.setTitle(getString(R.string.settings_connectivity_checks))
        val items = arrayOf(
            getString(R.string.settings_app_list_default_app),
            getString(R.string.settings_ip_text_ipv46),
            getString(R.string.lbl_manual)
        )
        val type = persistentState.performAutoNetworkConnectivityChecks
        val enabled = persistentState.connectivityChecks
        val checkedItem = if (!enabled) {
            0 // none
        } else {
            when (type) {
                true -> 1 // auto
                false -> 2 // manual
            }
        }

        alertBuilder.setSingleChoiceItems(items, checkedItem) { dialog, which ->
            dialog.dismiss()
            when (which) {
                0 -> {
                    // none
                    persistentState.performAutoNetworkConnectivityChecks = true
                    persistentState.connectivityChecks = false
                    b.settingsActivityPingIpsBtn.visibility = View.GONE
                }
                1 -> {
                    // auto
                    persistentState.performAutoNetworkConnectivityChecks = true
                    persistentState.connectivityChecks = true
                    b.settingsActivityPingIpsBtn.visibility = View.VISIBLE
                }
                2 -> {
                    // manual
                    persistentState.performAutoNetworkConnectivityChecks = false
                    persistentState.connectivityChecks = true
                    b.settingsActivityPingIpsBtn.visibility = View.VISIBLE
                }
            }
            logEvent(
                "connectivity checks changed",
                "Connectivity checks changed to option index: $which"
            )
        }
        alertBuilder.create().show()
    }

    private fun handleLockdownModeIfNeeded() {
        val isLockdown = VpnController.isVpnLockdown()
        if (isLockdown) {
            b.settingsActivityVpnLockdownDesc.visibility = View.VISIBLE
        } else {
            b.settingsActivityVpnLockdownDesc.visibility = View.GONE
        }
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.TUN_ESTABLISHED, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun enableAfterDelay(ms: Long, vararg views: View) {
        for (v in views) v.isEnabled = false

        Utilities.delay(ms, lifecycleScope) { for (v in views) v.isEnabled = true }
    }
}
