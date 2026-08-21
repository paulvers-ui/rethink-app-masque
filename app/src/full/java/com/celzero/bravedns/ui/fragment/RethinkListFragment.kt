/*
 * Copyright 2022 RethinkDNS and its authors
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
package com.celzero.bravedns.ui.fragment

import Logger
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.RethinkEndpointAdapter
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.databinding.FragmentRethinkListBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity.Companion.UID
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.RethinkEndpointViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class RethinkListFragment : Fragment(R.layout.fragment_rethink_list) {
    private val b by viewBinding(FragmentRethinkListBinding::bind)

    private val appConfig by inject<AppConfig>()
    private val persistentState by inject<PersistentState>()

    // rethink doh ui elements
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recyclerAdapter: RethinkEndpointAdapter? = null
    private val viewModel: RethinkEndpointViewModel by viewModel()

    private var uid: Int = Constants.MISSING_UID

    companion object {
        fun newInstance() = RethinkListFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val bundle = this.arguments
        uid = bundle?.getInt(UID, Constants.MISSING_UID) ?: Constants.MISSING_UID
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        initClickListeners()
    }

    private fun showBlocklistVersionUi() {
        if (getDownloadTimeStamp() == INIT_TIME_MS) {
            b.dohFabAddServerIcon.visibility = View.GONE
            b.lbVersion.visibility = View.GONE
            return
        }

        b.lbVersion.text =
            getString(
                R.string.settings_local_blocklist_version,
                Utilities.convertLongToTime(getDownloadTimeStamp(), Constants.TIME_FORMAT_2)
            )
    }

    private fun getDownloadTimeStamp(): Long {
        return persistentState.remoteBlocklistTimestamp
    }

    private fun initView() {
        showBlocklistVersionUi()
        updateMaxSwitchUi()

        layoutManager = LinearLayoutManager(requireContext())
        b.recyclerDohConnections.layoutManager = layoutManager

        recyclerAdapter = RethinkEndpointAdapter(requireContext(), get())
        viewModel.setFilter(uid)
        viewModel.rethinkEndpointList.observe(viewLifecycleOwner) {
            recyclerAdapter!!.submitData(viewLifecycleOwner.lifecycle, it)
        }
        b.recyclerDohConnections.adapter = recyclerAdapter
    }

    private fun updateMaxSwitchUi() {
        ui {
            var endpointUrl: String? = null
            ioCtx { endpointUrl = appConfig.getRethinkPlusEndpoint()?.url }
            updateRethinkRadioUi(isMax = endpointUrl?.contains(MAX_ENDPOINT) == true)
        }
    }

    private fun initClickListeners() {
        // see CustomIpFragment#setupClickListeners#bringToFront()
        b.dohFabAddServerIcon.bringToFront()
        b.dohFabAddServerIcon.setOnClickListener {
            val intent = Intent(requireContext(), ConfigureRethinkBasicActivity::class.java)
            intent.putExtra(
                RETHINK_BLOCKLIST_TYPE,
                RethinkBlocklistManager.RethinkBlocklistType.REMOTE
            )
            requireContext().startActivity(intent)
        }

        b.radioMax.setOnCheckedChangeListener(null)
        b.radioMax.setOnClickListener {
            if (b.radioMax.isChecked) {
                io { appConfig.switchRethinkDnsToMax() }
                updateRethinkRadioUi(isMax = true)
            }
        }

        b.radioSky.setOnCheckedChangeListener(null)
        b.radioSky.setOnClickListener {
            if (b.radioSky.isChecked) {
                io { appConfig.switchRethinkDnsToSky() }
                updateRethinkRadioUi(isMax = false)
            }
        }
    }

    private fun updateRethinkRadioUi(isMax: Boolean) {
        if (isMax) {
            b.radioMax.isChecked = true
            b.radioSky.isChecked = false
            b.frlDesc.text = getString(R.string.rethink_max_desc)
        } else {
            b.radioSky.isChecked = true
            b.radioMax.isChecked = false
            b.frlDesc.text = getString(R.string.rethink_sky_desc)
        }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}
