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
package com.arcadesignpro.auroravpn.ui.fragment

import Logger
import Logger.LOG_TAG_UI
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.paging.filter
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.arcadesignpro.auroravpn.R
import com.arcadesignpro.auroravpn.adapter.RemoteSimpleViewAdapter
import com.arcadesignpro.auroravpn.data.AppConfig
import com.arcadesignpro.auroravpn.data.FileTag
import com.arcadesignpro.auroravpn.databinding.FragmentRethinkBlocklistBinding
import com.arcadesignpro.auroravpn.service.PersistentState
import com.arcadesignpro.auroravpn.service.RethinkBlocklistManager
import com.arcadesignpro.auroravpn.service.RethinkBlocklistManager.RethinkBlocklistType.Companion.getType
import com.arcadesignpro.auroravpn.service.RethinkBlocklistManager.getStamp
import com.arcadesignpro.auroravpn.service.RethinkBlocklistManager.getTagsFromStamp
import com.arcadesignpro.auroravpn.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_NAME
import com.arcadesignpro.auroravpn.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_TYPE
import com.arcadesignpro.auroravpn.ui.activity.ConfigureRethinkBasicActivity.Companion.RETHINK_BLOCKLIST_URL
import com.arcadesignpro.auroravpn.ui.bottomsheet.RethinkPlusFilterBottomSheet
import com.arcadesignpro.auroravpn.util.Constants
import com.arcadesignpro.auroravpn.util.Constants.Companion.DEAD_PACK
import com.arcadesignpro.auroravpn.util.Constants.Companion.DEFAULT_RDNS_REMOTE_DNS_NAMES
import com.arcadesignpro.auroravpn.util.Constants.Companion.MAX_ENDPOINT
import com.arcadesignpro.auroravpn.util.Constants.Companion.RETHINK_STAMP_VERSION
import com.arcadesignpro.auroravpn.util.CustomLinearLayoutManager
import com.arcadesignpro.auroravpn.util.UIUtils
import com.arcadesignpro.auroravpn.util.UIUtils.fetchToggleBtnColors
import com.arcadesignpro.auroravpn.util.Utilities.getRemoteBlocklistStamp
import com.arcadesignpro.auroravpn.util.Utilities.hasRemoteBlocklists
import com.arcadesignpro.auroravpn.util.Utilities.showToastUiCentered
import com.arcadesignpro.auroravpn.viewmodel.RemoteBlocklistPacksMapViewModel
import com.arcadesignpro.auroravpn.viewmodel.RethinkRemoteFileTagViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.regex.Pattern

class RethinkBlocklistFragment :
    Fragment(R.layout.fragment_rethink_blocklist), SearchView.OnQueryTextListener {
    private val b by viewBinding(FragmentRethinkBlocklistBinding::bind)

    private val persistentState by inject<PersistentState>()
    private val appConfig by inject<AppConfig>()

    private var type: RethinkBlocklistManager.RethinkBlocklistType =
        RethinkBlocklistManager.RethinkBlocklistType.REMOTE
    private var remoteName: String = ""
    private var remoteUrl: String = ""

    private val filters = MutableLiveData<Filters>()

    private var remoteSimpleViewAdapter: RemoteSimpleViewAdapter? = null

    private val remoteFileTagViewModel: RethinkRemoteFileTagViewModel by viewModel()
    private val remoteBlocklistPacksMapViewModel: RemoteBlocklistPacksMapViewModel by viewModel()

    private var modifiedStamp: String = ""

    enum class BlocklistSelectionFilter(val id: Int) {
        ALL(0),
        SELECTED(1)
    }

    class Filters {
        var query: String = "%%"
        var filterSelected: BlocklistSelectionFilter = BlocklistSelectionFilter.ALL
        var subGroups: MutableSet<String> = mutableSetOf()
    }

    enum class BlocklistView(val tag: String) {
        PACKS("1"),
        ADVANCED("2");

        fun isSimple() = this == PACKS

        companion object {
            fun getTag(tag: String): BlocklistView {
                return if (tag == PACKS.tag) {
                    PACKS
                } else {
                    ADVANCED
                }
            }
        }
    }

    companion object {
        fun newInstance() = RethinkBlocklistFragment()

        private var selectedFileTags: MutableLiveData<MutableSet<Int>> = MutableLiveData()

        fun updateFileTagList(fileTags: Set<Int>) {
            selectedFileTags.postValue(fileTags.toMutableSet())
        }

        fun getSelectedFileTags(): Set<Int> {
            return selectedFileTags.value ?: emptySet()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val bundle = this.arguments
        type =
            getType(
                bundle?.getInt(
                    RETHINK_BLOCKLIST_TYPE,
                    RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal
                ) ?: RethinkBlocklistManager.RethinkBlocklistType.REMOTE.ordinal
            )
        remoteName = bundle?.getString(RETHINK_BLOCKLIST_NAME, "") ?: ""
        remoteUrl = bundle?.getString(RETHINK_BLOCKLIST_URL, "") ?: ""
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Logger.v(LOG_TAG_UI, "init Rethink blocklist fragment")
        init()
        initObservers()
        initClickListeners()
    }

    private fun initObservers() {
        selectedFileTags.observe(viewLifecycleOwner) {
            if (it == null) return@observe

            io { modifiedStamp = getStamp(it, type) }
        }

        filters.observe(viewLifecycleOwner) {
            if (it == null) return@observe

            remoteFileTagViewModel.setFilter(it)
        }
    }

    private fun init() {
        modifiedStamp = getStamp()

        b.lbBlocklistApplyBtn.text =
            getString(R.string.ct_ip_details, getString(R.string.lbl_apply), getString(R.string.rdns_plus))

        io {
            val flags = getTagsFromStamp(modifiedStamp, type)
            updateFileTagList(flags)
        }

        // update ui based on blocklist availability
        hasBlocklist()

        remakeFilterChipsUi()
    }

    private fun hasBlocklist() {
        go {
            uiCtx {
                val blocklistsExist = withContext(Dispatchers.IO) { hasBlocklists() }
                if (blocklistsExist) {
                    setListAdapter()
                    setSimpleAdapter()
                    showConfigureUi()
                    hideDownloadUi()
                    return@uiCtx
                }

                showDownloadUi()
                hideConfigureUi()
            }
        }
    }

    private fun hasBlocklists(): Boolean {
        return hasRemoteBlocklists(requireContext(), persistentState.remoteBlocklistTimestamp)
    }

    private fun showDownloadUi() {
        b.lbDownloadProgressRemote.visibility = View.VISIBLE
    }

    private fun showConfigureUi() {
        b.lbConfigureLayout.visibility = View.VISIBLE
    }

    private fun hideDownloadUi() {
        b.lbDownloadProgressRemote.visibility = View.GONE
    }

    private fun hideConfigureUi() {
        b.lbConfigureLayout.visibility = View.GONE
    }

    private fun isStampChanged(): Boolean {
        // no need to check on the stamp when the remote name is in the default list
        // eg., rec, sec, pec etc
        if (DEFAULT_RDNS_REMOTE_DNS_NAMES.contains(remoteName)) {
            return false
        }

        // user modified the blocklists
        return getStamp() != modifiedStamp
    }

    private fun initClickListeners() {
        b.lbBlocklistApplyBtn.setOnClickListener {
            // update rethink stamp
            setStamp(modifiedStamp)
            requireActivity().finish()
        }

        b.lbBlocklistCancelBtn.setOnClickListener {
            // close the activity associated with the fragment after reverting to old stamp
            io {
                val stamp = getStamp()
                val list = RethinkBlocklistManager.getTagsFromStamp(stamp, type)
                updateSelectedFileTags(list.toMutableSet())
                setStamp(stamp)
                Logger.i(LOG_TAG_UI, "revert to old stamp for blocklist type: ${type.name}, $stamp, $list")
                uiCtx {
                    requireActivity().finish()
                }
            }
        }

        b.lbAdvSearchFilterIcon.setOnClickListener { openFilterBottomSheet() }

        b.lbAdvSearchSv.setOnQueryTextListener(this)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            if (!isStampChanged()) {
                requireActivity().finish()
                return@addCallback
            }

            showApplyChangesDialog()
        }
    }

    private fun showApplyChangesDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
        builder.setTitle(getString(R.string.rt_dialog_title))
        builder.setMessage(getString(R.string.rt_dialog_message))
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.lbl_apply)) { _, _ ->
            setStamp(modifiedStamp)
            requireActivity().finish()
        }
        builder.setNeutralButton(getString(R.string.rt_dialog_neutral)) { _, _ ->
            // no-op
        }
        builder.setNegativeButton(getString(R.string.notif_dialog_pause_dialog_negative)) { _, _ ->
            requireActivity().finish()
        }
        builder.create().show()
    }

    private fun setStamp(stamp: String?) {
        Logger.i(LOG_TAG_UI, "set stamp for blocklist type: ${type.name} with $stamp")
        if (stamp == null) {
            Logger.i(LOG_TAG_UI, "stamp is null")
            return
        }

        io {
            val blocklistCount = getTagsFromStamp(stamp, type).size
            persistentState.setRemoteBlocklistCount(blocklistCount)
        }
    }

    private fun setListAdapter() {
        io {
            processSelectedFileTags(getStamp())
            uiCtx {
                // simple view only
            }
        }
    }

    private fun setupRecyclerScrollListener(recycler: RecyclerView) {
          val scrollListener =
              object : RecyclerView.OnScrollListener() {

                  override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                      super.onScrolled(recyclerView, dx, dy)

                      if (recyclerView.getChildAt(0)?.tag == null) return

                      val tag: String = recyclerView.getChildAt(0).tag as String
                      b.recyclerScrollHeaderSimple.visibility = View.VISIBLE
                      b.recyclerScrollHeaderSimple.text = tag
                  }

                  override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                      super.onScrollStateChanged(recyclerView, newState)
                      if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                          b.recyclerScrollHeaderSimple.visibility = View.GONE
                      }
                  }
              }
          recycler.addOnScrollListener(scrollListener)
      }
    private fun setSimpleAdapter() {
        setRemoteSimpleViewAdapter()
    }

    private suspend fun processSelectedFileTags(stamp: String) {
        val list = RethinkBlocklistManager.getTagsFromStamp(stamp, type)
        updateSelectedFileTags(list.toMutableSet())
    }

    private suspend fun updateSelectedFileTags(selectedTags: MutableSet<Int>) {
        // clear the residues if the selected tags are empty
        if (selectedTags.isEmpty()) {
            RethinkBlocklistManager.clearTagsSelectionRemote()
            return
        }

        RethinkBlocklistManager.clearTagsSelectionRemote()
        RethinkBlocklistManager.updateFiletagsRemote(selectedTags, 1 /* isSelected: true */)
        val list = RethinkBlocklistManager.getSelectedFileTagsRemote().toSet()
        updateFileTagList(list)
    }

    private fun getStamp(): String {
        return getRemoteBlocklistStamp(remoteUrl)
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        if (isRethinkStampSearch(query)) {
            return false
        }
        addQueryToFilters(query)
        return false
    }

    override fun onQueryTextChange(query: String): Boolean {
        if (isRethinkStampSearch(query)) {
            return false
        }
        addQueryToFilters(query)
        return false
    }

    private fun isRethinkStampSearch(t: String): Boolean {
        // do not proceed if rethinkdns.com is not available
        if (!t.contains(Constants.RETHINKDNS_DOMAIN)) return false

        val split = t.split("/")

        // split: https://max.rethinkdns.com/1:IAAgAA== [https:, , max.rethinkdns.com, 1:IAAgAA==]
        split.forEach {
            if (it.contains("$RETHINK_STAMP_VERSION:") && isBase64(it)) {
                io { processSelectedFileTags(it) }
                showToastUiCentered(requireContext(), "Blocklists restored", Toast.LENGTH_SHORT)
                return true
            }
        }

        return false
    }

    // ref: netflix/msl/util/Base64
    private fun isBase64(stamp: String): Boolean {
        val whitespaceRegex = "\\s"
        val pattern =
            Pattern.compile(
                "^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{4}|[A-Za-z0-9+/]{3}=|[A-Za-z0-9+/]{2}==)?$"
            )

        val versionSplit = stamp.split(":").getOrNull(1) ?: return false

        if (versionSplit.isEmpty()) return false

        val result = versionSplit.replace(whitespaceRegex, "")
        return pattern.matcher(result).matches()
    }

    fun filterObserver(): MutableLiveData<Filters> {
        return filters
    }

    private fun addQueryToFilters(query: String) {
        val a = filterObserver()
        if (a.value == null) {
            val temp = Filters()
            temp.query = formatQuery(query)
            filters.postValue(temp)
            return
        }

        // asserting, as there is a null check
        a.value!!.query = formatQuery(query)
        filters.postValue(a.value)
    }

    private fun formatQuery(q: String): String {
        return "%$q%"
    }

    private fun setRemoteSimpleViewAdapter() {
        remoteSimpleViewAdapter = RemoteSimpleViewAdapter(requireContext())
        val layoutManager = CustomLinearLayoutManager(requireContext())
        b.lbSimpleRecyclerPacks.layoutManager = layoutManager

        remoteBlocklistPacksMapViewModel.simpleTags.observe(viewLifecycleOwner) {
            val r = it.filter { it1 -> !it1.pack.contains(DEAD_PACK) && it1.pack.isNotEmpty() }
            remoteSimpleViewAdapter?.submitData(viewLifecycleOwner.lifecycle, r)
        }
        b.lbSimpleRecyclerPacks.adapter = remoteSimpleViewAdapter
        setupRecyclerScrollListener(b.lbSimpleRecyclerPacks)
    }

    private fun remakeFilterChipsUi() {
        b.filterChipGroup.removeAllViews()

        val all = makeChip(BlocklistSelectionFilter.ALL.id, getString(R.string.lbl_all), true)
        val selected =
            makeChip(
                BlocklistSelectionFilter.SELECTED.id,
                getString(R.string.rt_filter_parent_selected),
                false
            )

        b.filterChipGroup.addView(all)
        b.filterChipGroup.addView(selected)
    }

    private fun makeChip(id: Int, label: String, checked: Boolean): Chip {
        val chip = this.layoutInflater.inflate(R.layout.item_chip_filter, b.root, false) as Chip
        chip.tag = id
        chip.text = label
        chip.isChecked = checked

        chip.setOnCheckedChangeListener { button: CompoundButton, isSelected: Boolean ->
            if (isSelected) { // apply filter only when the CompoundButton is selected
                applyFilter(button.tag)
            }
        }

        return chip
    }

    private fun applyFilter(tag: Any) {
        val a = filterObserver().value ?: Filters()

        when (tag) {
            BlocklistSelectionFilter.ALL.id -> {
                a.filterSelected = BlocklistSelectionFilter.ALL
            }
            BlocklistSelectionFilter.SELECTED.id -> {
                a.filterSelected = BlocklistSelectionFilter.SELECTED
            }
        }
        filters.postValue(a)
    }

    private fun openFilterBottomSheet() {
        io {
            val bottomSheetFragment = RethinkPlusFilterBottomSheet.newInstance(this, getAllList())
            uiCtx { bottomSheetFragment.show(childFragmentManager, bottomSheetFragment.tag) }
        }
    }

    private suspend fun getAllList(): List<FileTag> {
        return remoteFileTagViewModel.allFileTags()
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }

    private fun go(f: suspend () -> Unit) {
        lifecycleScope.launch { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.Main) { f() } }
    }
}
