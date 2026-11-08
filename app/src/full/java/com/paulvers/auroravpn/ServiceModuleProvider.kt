/*
 * Copyright 2020 RethinkDNS and its authors
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
package com.paulvers.auroravpn

import android.content.ContentResolver
import com.paulvers.auroravpn.data.DataModule
import com.paulvers.auroravpn.database.DatabaseModule
import com.paulvers.auroravpn.scheduler.ScheduleManager
import com.paulvers.auroravpn.scheduler.WorkScheduler
import com.paulvers.auroravpn.service.AppUpdater
import com.paulvers.auroravpn.service.ServiceModule
import com.paulvers.auroravpn.util.Constants
import com.paulvers.auroravpn.viewmodel.ViewModelModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

private val rootModule = module { single<ContentResolver> { androidContext().contentResolver } }
private val updaterModule = module {
    single { NonStoreAppUpdater(Constants.RETHINK_APP_UPDATE_CHECK, get()) }
    single<AppUpdater> { get<NonStoreAppUpdater>() }
}

private val updaterModules = listOf(updaterModule)



private val workerModule = module { single { WorkScheduler(androidContext()) } }

private val schedulerModule = module { single { ScheduleManager(androidContext()) } }
/*
private val stateMachine = module {
    single { SubscriptionStateMachineV2() }
    single { StateMachineDatabaseSyncService() }
}*/

//private val stateMachineModules = listOf(stateMachine)

val AppModules: List<Module> by lazy {
    mutableListOf<Module>().apply {
        add(rootModule)
        addAll(DatabaseModule.modules)
        addAll(ViewModelModule.modules)
        addAll(DataModule.modules)
        addAll(ServiceModule.modules)
        //addAll(stateMachineModules)
        addAll(updaterModules)
        add(schedulerModule)
        add(workerModule)
    }
}
