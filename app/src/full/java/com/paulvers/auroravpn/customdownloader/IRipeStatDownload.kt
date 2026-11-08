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
package com.paulvers.auroravpn.customdownloader

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

// RIPEstat announced-prefixes: the CIDR prefixes a network (ASN) announces,
// used by the Countries & networks firewall to block a whole network.
// https://stat.ripe.net/docs/data_api/#announced-prefixes
interface IRipeStatDownload {

    @GET("data/announced-prefixes/data.json")
    suspend fun announcedPrefixes(@Query("resource") asn: String): Response<JsonObject?>?
}
