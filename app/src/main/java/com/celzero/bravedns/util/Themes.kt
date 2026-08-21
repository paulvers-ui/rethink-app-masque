/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.util

import com.celzero.bravedns.R
import com.celzero.bravedns.util.Utilities.isAtleastS

// Application themes enum
enum class Themes(val id: Int) {
    SYSTEM_DEFAULT(0),
    LIGHT(1),
    DARK(2),
    TRUE_BLACK(3),
    LIGHT_PLUS(4),
    DARK_PLUS(5),
    DARK_FROST(6);

    companion object {
        fun getThemeCount(): Int {
            return entries.count()
        }

        fun getAvailableThemeCount(): Int {
            return if (isAtleastS()) {
                entries.count()
            } else {
                // Exclude LIGHT_FROST and DARK_FROST for pre-Android S devices
                entries.count() - 2
            }
        }

        fun isFrostTheme(id: Int): Boolean {
            return id == DARK_FROST.id
        }

        fun isThemeAvailable(id: Int): Boolean {
            if (isFrostTheme(id)) {
                return isAtleastS()
            }
            return true
        }

        fun getTheme(id: Int): Int {
            return when (id) {
                SYSTEM_DEFAULT.id -> 0 // system default
                LIGHT.id -> R.style.AppThemeWhite
                DARK.id -> R.style.AppTheme
                TRUE_BLACK.id -> R.style.AppThemeTrueBlack
                LIGHT_PLUS.id -> R.style.AppThemeWhitePlus
                DARK_PLUS.id -> R.style.AppThemeTrueBlackPlus
                DARK_FROST.id -> R.style.AppThemeTrueBlackFrost
                else -> 0
            }
        }

        private fun getBottomSheetTheme(id: Int): Int {
            return when (id) {
                SYSTEM_DEFAULT.id -> 0 // system default
                LIGHT.id -> R.style.BottomSheetDialogThemeWhite
                DARK.id -> R.style.BottomSheetDialogTheme
                TRUE_BLACK.id -> R.style.BottomSheetDialogThemeTrueBlack
                LIGHT_PLUS.id -> R.style.BottomSheetDialogThemeWhitePlus
                DARK_PLUS.id -> R.style.BottomSheetDialogThemeTrueBlackPlus
                // for now use same as dark, can be changed later
                DARK_FROST.id -> R.style.BottomSheetDialogThemeTrueBlack
                else -> 0
            }
        }

        // This fork ships a single theme: DARK_PLUS. Every other option was removed
        // from the picker, and these resolvers ignore both the system setting and any
        // theme id previously persisted by an older build, so upgrading installs land
        // on Dark Plus too instead of being stuck on a theme they can no longer pick.
        // The enum itself is intentionally left intact -- it is referenced from ~40
        // files, and deleting entries would break them for no user-visible gain.
        @Suppress("UNUSED_PARAMETER")
        fun getCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            return getTheme(DARK_PLUS.id)
        }

        @Suppress("UNUSED_PARAMETER")
        fun getBottomsheetCurrentTheme(isDarkThemeOn: Boolean, theme: Int): Int {
            return getBottomSheetTheme(DARK_PLUS.id)
        }
    }
}
