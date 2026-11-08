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
package com.paulvers.auroravpn.ui.activity

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowInsetsControllerCompat
import by.kirich1409.viewbindingdelegate.viewBinding
import com.paulvers.auroravpn.R
import com.paulvers.auroravpn.databinding.ActivityCountriesNetworksBinding
import com.paulvers.auroravpn.databinding.ItemCnEntryBinding
import com.paulvers.auroravpn.scheduler.AsnRefreshWorker
import com.paulvers.auroravpn.service.PersistentState
import com.paulvers.auroravpn.util.Themes
import com.paulvers.auroravpn.util.Utilities
import com.paulvers.auroravpn.util.handleFrostEffectIfNeeded
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale
import org.koin.android.ext.android.inject

// The Countries & networks firewall screen, ported from the Windows app: block
// outgoing connections to a country (or, in allowlist mode, allow only some) and
// to a network (ASN). The lists live in PersistentState as comma-separated
// values; the firewall reads them live (BraveVPNService.geoBlocked).
@Suppress("TooManyFunctions")
class CountriesNetworksActivity :
    AppCompatActivity(R.layout.activity_countries_networks) {

    private val b by viewBinding(ActivityCountriesNetworksBinding::bind)
    private val persistentState by inject<PersistentState>()

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)
        handleFrostEffectIfNeeded(persistentState.theme)
        if (Utilities.isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }
        init()
    }

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    private fun init() {
        b.cnModeToggle.check(
            if (persistentState.blockedCountriesAllowlist) b.cnModeAllow.id else b.cnModeBlock.id
        )
        b.cnModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            persistentState.blockedCountriesAllowlist = checkedId == b.cnModeAllow.id
            renderCountries()
        }
        b.cnChooseCountries.setOnClickListener { chooseCountriesDialog() }
        b.cnBlockNetwork.setOnClickListener { blockNetworkDialog() }
        renderCountries()
        renderAsns()
    }

    // ---------- countries ----------

    private fun countrySet(): MutableSet<String> =
        persistentState.blockedCountries
            .split(",")
            .map { it.trim().uppercase() }
            .filter { it.length == 2 }
            .toMutableSet()

    private fun saveCountries(set: Set<String>) {
        persistentState.blockedCountries = set.sorted().joinToString(",")
    }

    private fun renderCountries() {
        val set = countrySet()
        b.cnCountryList.removeAllViews()
        if (set.isEmpty()) {
            b.cnCountryList.addView(emptyRow(getString(R.string.cn_no_countries)))
            return
        }
        val verb = getString(
            if (persistentState.blockedCountriesAllowlist) R.string.cn_allowed else R.string.cn_blocked
        )
        set.sortedBy { nameOf(it) }.forEach { cc ->
            val row = ItemCnEntryBinding.inflate(layoutInflater, b.cnCountryList, false)
            row.cnItemTitle.text = getString(R.string.cn_country_title, flagOf(cc), nameOf(cc))
            row.cnItemSub.text = getString(R.string.cn_country_sub, cc, verb)
            row.cnItemDelete.setOnClickListener {
                val s = countrySet()
                s.remove(cc)
                saveCountries(s)
                renderCountries()
            }
            b.cnCountryList.addView(row.root)
        }
    }

    private fun chooseCountriesDialog() {
        // every ISO country, by display name; those already blocked are checked
        val codes = Locale.getISOCountries().toList().sortedBy { nameOf(it) }
        val labels = codes.map { "${flagOf(it)}  ${nameOf(it)}" }.toTypedArray()
        val chosen = countrySet()
        val checked = BooleanArray(codes.size) { chosen.contains(codes[it]) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cn_choose_countries)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.lbl_save) { _, _ ->
                val set = mutableSetOf<String>()
                codes.forEachIndexed { i, cc -> if (checked[i]) set.add(cc) }
                saveCountries(set)
                renderCountries()
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    // ---------- networks (ASN) ----------

    private fun asnList(): MutableList<Long> =
        persistentState.blockedAsns
            .split(",")
            .mapNotNull { it.trim().removePrefix("AS").removePrefix("as").toLongOrNull() }
            .filter { it in 1..MAX_ASN }
            .distinct()
            .toMutableList()

    private fun saveAsns(list: List<Long>) {
        persistentState.blockedAsns = list.sorted().joinToString(",")
        // look the networks' prefixes up from RIPEstat (or clear them) now
        AsnRefreshWorker.enqueueNow(this)
    }

    private fun renderAsns() {
        val list = asnList()
        b.cnAsnList.removeAllViews()
        if (list.isEmpty()) {
            b.cnAsnList.addView(emptyRow(getString(R.string.cn_no_networks)))
            return
        }
        list.sorted().forEach { asn ->
            val row = ItemCnEntryBinding.inflate(layoutInflater, b.cnAsnList, false)
            row.cnItemTitle.text = "AS$asn"
            row.cnItemSub.text = getString(R.string.cn_network_sub)
            row.cnItemDelete.setOnClickListener {
                val l = asnList()
                l.remove(asn)
                saveAsns(l)
                renderAsns()
            }
            b.cnAsnList.addView(row.root)
        }
    }

    private fun blockNetworkDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = getString(R.string.cn_block_network_hint)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.cn_block_network)
            .setMessage(R.string.cn_block_network_msg)
            .setView(input)
            .setPositiveButton(R.string.lbl_add) { _, _ ->
                val asn = input.text.toString().trim().removePrefix("AS").removePrefix("as").toLongOrNull()
                if (asn == null || asn !in 1..MAX_ASN) {
                    Utilities.showToastUiCentered(this, getString(R.string.cn_bad_asn), android.widget.Toast.LENGTH_SHORT)
                    return@setPositiveButton
                }
                val l = asnList()
                if (!l.contains(asn)) l.add(asn)
                saveAsns(l)
                renderAsns()
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    // ---------- helpers ----------

    private fun emptyRow(text: String): android.view.View {
        val row = ItemCnEntryBinding.inflate(layoutInflater, b.cnCountryList, false)
        row.cnItemTitle.text = text
        row.cnItemSub.text = ""
        row.cnItemDelete.visibility = android.view.View.GONE
        return row.root
    }

    private fun nameOf(cc: String): String {
        val n = Locale("", cc).displayCountry
        return if (n.isBlank() || n == cc) cc else n
    }

    private fun flagOf(cc: String): String {
        val valid = cc.length == 2 && cc[0].uppercaseChar() in 'A'..'Z' && cc[1].uppercaseChar() in 'A'..'Z'
        val base = FLAG_BASE - 'A'.code
        return if (valid) {
            String(Character.toChars(base + cc[0].uppercaseChar().code)) +
                String(Character.toChars(base + cc[1].uppercaseChar().code))
        } else {
            ""
        }
    }

    companion object {
        // the first regional-indicator code point (🇦), for building flag emoji
        private const val FLAG_BASE = 0x1F1E6
        // the largest valid 32-bit AS number
        private const val MAX_ASN = 4294967295L
    }
}
