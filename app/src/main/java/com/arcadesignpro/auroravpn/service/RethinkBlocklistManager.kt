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
package com.arcadesignpro.auroravpn.service

import Logger
import Logger.LOG_TAG_DNS
import Logger.LOG_TAG_VPN
import android.content.Context
import com.arcadesignpro.auroravpn.R
import com.arcadesignpro.auroravpn.data.FileTag
import com.arcadesignpro.auroravpn.data.FileTagDeserializer
import com.arcadesignpro.auroravpn.database.RemoteBlocklistPacksMap
import com.arcadesignpro.auroravpn.database.RemoteBlocklistPacksMapRepository
import com.arcadesignpro.auroravpn.database.RethinkRemoteFileTag
import com.arcadesignpro.auroravpn.database.RethinkRemoteFileTagRepository
import com.arcadesignpro.auroravpn.util.Constants.Companion.REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME
import com.arcadesignpro.auroravpn.util.Utilities
import com.arcadesignpro.auroravpn.util.Utilities.togs
import com.arcadesignpro.auroravpn.util.Utilities.tos
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RDNS
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.IOException

object RethinkBlocklistManager : KoinComponent {

    private val remoteFileTagRepository by inject<RethinkRemoteFileTagRepository>()
    private val remoteBlocklistPacksMapRepository by inject<RemoteBlocklistPacksMapRepository>()
    private val persistentState by inject<PersistentState>()

    private const val EMPTY_SUBGROUP = "others"

    private const val PARENTAL_CONTROL_TAG = "ParentalControl"
    private const val SECURITY_TAG = "Security"
    private const val PRIVACY_TAG = "Privacy"

    data class RethinkBlockType(val name: String, val label: Int, val desc: Int)

    data class PacksMappingKey(val pack: String, val level: Int)

    enum class RethinkBlocklistType {
        LOCAL,
        REMOTE;

        companion object {
            fun getType(id: Int): RethinkBlocklistType {
                if (id == LOCAL.ordinal) return LOCAL

                return REMOTE
            }
        }

        fun isLocal(): Boolean {
            return this == LOCAL
        }

        fun isRemote(): Boolean {
            return this == REMOTE
        }
    }

    enum class DownloadType(val id: Int) {
        LOCAL(0),
        REMOTE(1);

        fun isLocal(): Boolean {
            return this == LOCAL
        }

        fun isRemote(): Boolean {
            return this == REMOTE
        }
    }

    // TODO: move this strings to strings.xml
    val PARENTAL_CONTROL =
        RethinkBlockType(
            PARENTAL_CONTROL_TAG,
            R.string.rbl_parental_control,
            R.string.rbl_parental_control_desc
        )
    val SECURITY = RethinkBlockType(SECURITY_TAG, R.string.rbl_security, R.string.rbl_security_desc)
    val PRIVACY = RethinkBlockType(PRIVACY_TAG, R.string.rbl_privacy, R.string.rbl_privacy_desc)

    // read and parse the json file for the remote blocklist
    suspend fun readJson(context: Context, type: DownloadType, timestamp: Long): Boolean {
        return readRemoteJson(context, timestamp)
    }

    private suspend fun readRemoteJson(context: Context, timestamp: Long): Boolean {
        val packsBlocklistMapping: Multimap<PacksMappingKey, Int> = HashMultimap.create()
        try {
            val dbFileTagRemote: MutableList<RethinkRemoteFileTag> = mutableListOf()
            val dir =
                Utilities.blocklistDownloadBasePath(
                    context,
                    REMOTE_BLOCKLIST_DOWNLOAD_FOLDER_NAME,
                    timestamp
                )

            val file =
                Utilities.blocklistFile(
                    dir,
                    com.arcadesignpro.auroravpn.util.Constants.Companion.ONDEVICE_BLOCKLIST_FILE_TAG
                ) ?: return false

            val jsonString = file.bufferedReader().use { it.readText() }
            val gson =
                GsonBuilder()
                    .registerTypeAdapter(FileTag::class.java, FileTagDeserializer())
                    .create()
            val entries: JsonObject = Gson().fromJson(jsonString, JsonObject::class.java)
            entries.entrySet().forEach {
                val t = gson.fromJson(it.value, FileTag::class.java)
                if (t.subg.isEmpty()) {
                    t.subg = EMPTY_SUBGROUP
                }
                t.group = t.group.lowercase()
                val r = getRethinkRemoteObj(t)

                if (r.pack?.isNotEmpty() == true) {
                    r.pack?.forEachIndexed { index, s ->
                        if (s.isEmpty() || r.level == null || r.level?.isEmpty() == true) {
                            r.level = arrayListOf()
                            packsBlocklistMapping.put(PacksMappingKey(s, 0), r.value)
                            return@forEachIndexed
                        }
                        val level = r.level?.getOrNull(index) ?: 2
                        packsBlocklistMapping.put(PacksMappingKey(s, level), r.value)
                    }
                }
                dbFileTagRemote.add(r)
                Logger.i(
                    LOG_TAG_DNS,
                    "Remote file tag: ${r.group}, ${r.pack}, ${r.simpleTagId}, ${r.level}, ${r.value}, ${r.entries}, ${r.isSelected}, ${r.show}, ${r.subg}, ${r.uname}, ${r.url}, ${r.vname}"
                )
            }
            val selectedTags = remoteFileTagRepository.getSelectedTags()
            remoteFileTagRepository.deleteAll()
            remoteFileTagRepository.insertAll(dbFileTagRemote.toList())
            remoteFileTagRepository.updateTags(selectedTags.toSet(), 1)
            remoteBlocklistPacksMapRepository.deleteAll()
            remoteBlocklistPacksMapRepository.insertAll(
                packsBlocklistMapping.keySet().map { key ->
                    RemoteBlocklistPacksMap(
                        key.pack,
                        key.level,
                        packsBlocklistMapping.get(key).toList(),
                        dbFileTagRemote.firstOrNull { it.pack?.contains(key.pack) == true }?.group ?: ""
                    )
                }
            )
            Logger.i(LOG_TAG_DNS, "New Remote blocklist files inserted into database")
            return true
        } catch (ioException: IOException) {
            Logger.crash(
                LOG_TAG_DNS,
                "Failure reading json file, blocklist type: remote, timestamp: $timestamp",
                ioException
            )
        }
        return false
    }

    private fun getRethinkRemoteObj(t: FileTag): RethinkRemoteFileTag {
        return RethinkRemoteFileTag(
            t.value,
            t.uname,
            t.vname,
            t.group,
            t.subg,
            t.pack,
            t.level,
            t.urls,
            t.show,
            t.entries,
            t.simpleTagId,
            t.isSelected
        )
    }

    suspend fun updateFiletagRemote(remote: RethinkRemoteFileTag) {
        remoteFileTagRepository.update(remote)
    }

    suspend fun updateFiletagsRemote(values: Set<Int>, isSelected: Int) {
        remoteFileTagRepository.updateTags(values, isSelected)
    }

    suspend fun getSelectedFileTagsRemote(): List<Int> {
        return remoteFileTagRepository.getSelectedTags()
    }

    suspend fun clearTagsSelectionRemote() {
        remoteFileTagRepository.clearSelectedTags()
    }

    fun getStamp(): String {
        return ""
    }

    suspend fun getStamp(fileValues: Set<Int>, type: RethinkBlocklistType): String {
        return try {
            val flags = convertListToCsv(fileValues)
            val flags2Stamp = getRDNS(type)?.flagsToStamp(flags.togs(), Backend.EB32).tos() ?: ""
            Logger.d(LOG_TAG_VPN, "${type.name} flags: $flags; stamp: $flags2Stamp")
            flags2Stamp
        } catch (e: java.lang.Exception) {
            Logger.e(LOG_TAG_VPN, "err stamp2tags for $fileValues of type: ${type.name} ${e.message}, $e")
            ""
        }
    }

    suspend fun getTagsFromStamp(stamp: String, type: RethinkBlocklistType): Set<Int> {
        return try {
            val tags = convertCsvToList(getRDNS(type)?.stampToFlags(stamp.togs()).tos())
            Logger.d(LOG_TAG_VPN, "${type.name} stamp: $stamp; tags: $tags")
            tags
        } catch (e: Exception) {
            Logger.e(LOG_TAG_VPN, "err tags2stamp for $stamp of type: ${type.name} ${e.message}, $e")
            setOf()
        }
    }

    private fun convertCsvToList(csv: String?): Set<Int> {
        if (csv == null) return setOf()

        return csv.split(",").map { it.toIntOrNull() ?: 0 }.toSet()
    }

    private fun convertListToCsv(s: Set<Int>): String {
        return s.joinToString(",")
    }

    private suspend fun getRDNS(type: RethinkBlocklistType): RDNS? {
        return VpnController.getRDNS(type)
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
