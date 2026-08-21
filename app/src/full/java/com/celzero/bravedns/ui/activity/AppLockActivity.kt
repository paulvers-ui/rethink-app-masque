/*
 * Copyright 2025 RethinkDNS and its authors
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
import Logger.LOG_TAG_UI
import android.app.ComponentCaller
import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.LauncherSwitcher
import com.celzero.bravedns.util.Themes.Companion.getCurrentTheme
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.util.handleFrostEffectIfNeeded
import org.koin.android.ext.android.inject
import java.security.KeyStore
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlin.math.abs

class AppLockActivity : AppCompatActivity(R.layout.activity_app_lock) {
    private val persistentState by inject<PersistentState>()

    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt

    companion object {
        private const val TAG = "AppLockUi"
        const val APP_LOCK_ALIAS = ".ui.activity.LauncherAliasAppLock"
        const val HOME_ALIAS = ".ui.LauncherAliasHome"

        // AndroidKeyStore key alias used for the biometric CryptoObject cipher.
        // The key is bound to biometric enrolment: if the user adds or removes a
        // biometric after the key is created, the key is automatically invalidated
        // (invalidatedByBiometricEnrollment = true, the default), forcing
        // re-authentication at the next app launch rather than accepting a
        // potentially-attacker-enrolled biometric.
        private const val BIOMETRIC_KEY_ALIAS = "rethinkdns_biometric_auth_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_SIZE = 256
    }

    // TODO - #324 - Usage of isDarkTheme() in all activities.
    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        theme.applyStyle(getCurrentTheme(isDarkThemeOn(), persistentState.theme), true)
        super.onCreate(savedInstanceState)

        handleFrostEffectIfNeeded(persistentState.theme)

        if (isAtleastQ()) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.isAppearanceLightNavigationBars = false
            window.isNavigationBarContrastEnforced = false
        }

        if (!isBiometricEnabled() || isAppRunningOnTv()) {
            Logger.v(LOG_TAG_UI, "$TAG biometric authentication disabled or running on TV")

            // if the app lock alias is enabled, switch to home alias
            if (!LauncherSwitcher.isAliasEnabled(applicationContext, APP_LOCK_ALIAS)) {
                Logger.v(LOG_TAG_UI, "$TAG switching launcher alias to home")
                startHomeActivity()
                return
            }

            // if the app lock alias is not enabled, switch to home alias
            LauncherSwitcher.switchLauncherAlias(applicationContext, HOME_ALIAS, APP_LOCK_ALIAS)

            startHomeActivity()
            return
        }

        val lastAuthTime = persistentState.biometricAuthTime

        // if the biometric authentication is already done in the last configured mins, then skip
        var delay = MiscSettingsActivity.BioMetricType.fromValue(persistentState.biometricAuthType).mins

        // this is for backward compatibility with older versions
        // if enabled and lastUnlockTime is -1, then set it to 15 mins(maximum value)
        delay = if (delay == -1L) {
                MiscSettingsActivity.BioMetricType.FIFTEEN_MIN.mins
            } else {
                delay
            }

        Logger.d(LOG_TAG_UI, "$TAG timeout: $delay, last auth: $lastAuthTime")
        val timeSinceLastAuth = abs(SystemClock.elapsedRealtime() - lastAuthTime)
        if (timeSinceLastAuth < TimeUnit.MINUTES.toMillis(delay)) {
            Logger.i(LOG_TAG_UI, "$TAG biometric auth skipped, time since last auth: $timeSinceLastAuth")
            startHomeActivity()
            return
        }

        showBiometricPrompt()
    }

    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        setIntent(intent)
    }

    private fun showBiometricPrompt() {
        Logger.v(LOG_TAG_UI, "$TAG showing biometric prompt")
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Logger.i(LOG_TAG_UI, "$TAG auth error(code: $errorCode): $errString")
                    Logger.v(LOG_TAG_UI, "$TAG biometric auth err, finishing activity")
                    showToastUiCentered(this@AppLockActivity, errString.toString(), Toast.LENGTH_SHORT)
                    finishAffinity()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    persistentState.biometricAuthTime = SystemClock.elapsedRealtime()
                    startHomeActivity()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Logger.i(LOG_TAG_UI, "$TAG biometric authentication failed")
                }
            })

        // Prefer BIOMETRIC_STRONG (Class 3) so we can pass a CryptoObject and bind
        // the auth to a hardware-backed AES key.  This prevents an attacker with
        // physical access from hooking onAuthenticationSucceeded without actually
        // satisfying the biometric check — the cipher only becomes usable after a
        // genuine hardware-verified match.
        //
        // Fall back to DEVICE_CREDENTIAL-only on devices where BIOMETRIC_STRONG is
        // not enrolled or not available (e.g. PIN/pattern-only devices, older
        // Android versions).  CryptoObject cannot be used with DEVICE_CREDENTIAL
        // alone (Android throws IllegalArgumentException), so we call the
        // no-CryptoObject overload only in that path.
        val biometricManager = BiometricManager.from(this)
        val canUseStrongBiometric =
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                    BiometricManager.BIOMETRIC_SUCCESS

        if (canUseStrongBiometric) {
            authenticateWithCryptoObject()
        } else {
            authenticateWithDeviceCredential()
        }
    }

    /**
     * Primary path: BIOMETRIC_STRONG + CryptoObject.
     *
     * Creates (or reuses) an AES/GCM key in the AndroidKeyStore that is:
     *  - bound to the device's secure hardware (StrongBox or TEE)
     *  - invalidated when new biometrics are enrolled
     *  - never extractable from the keystore
     *
     * The Cipher is initialised in ENCRYPT_MODE and wrapped in a CryptoObject.
     * The OS only makes the key usable after a confirmed Class 3 biometric match,
     * so a successful callback guarantees the hardware actually verified the user.
     */
    private fun authenticateWithCryptoObject() {
        try {
            val cipher = buildCipher()
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.hs_biometeric_title))
                .setSubtitle(getString(R.string.hs_biometeric_desc))
                // BIOMETRIC_STRONG only: CryptoObject is incompatible with
                // DEVICE_CREDENTIAL and with BIOMETRIC_WEAK.
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(android.R.string.cancel))
                .setConfirmationRequired(false)
                .build()

            biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
            Logger.d(LOG_TAG_UI, "$TAG authenticating with CryptoObject (BIOMETRIC_STRONG)")
        } catch (e: Exception) {
            // Key may have been invalidated by a new biometric enrolment — delete it
            // and fall back to the device credential path so the user is not locked out.
            Logger.w(LOG_TAG_UI, "$TAG CryptoObject setup failed, falling back to device credential: ${e.message}")
            deleteKeyIfExists()
            authenticateWithDeviceCredential()
        }
    }

    /**
     * Fallback path: BIOMETRIC_WEAK | DEVICE_CREDENTIAL, no CryptoObject.
     * Used on devices without a Class 3 biometric sensor, or when the
     * AndroidKeyStore key has been invalidated.
     */
    private fun authenticateWithDeviceCredential() {
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.hs_biometeric_title))
            .setSubtitle(getString(R.string.hs_biometeric_desc))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .setConfirmationRequired(false)
            .build()

        biometricPrompt.authenticate(promptInfo)
        Logger.d(LOG_TAG_UI, "$TAG authenticating without CryptoObject (DEVICE_CREDENTIAL fallback)")
    }

    /** Returns an AES/GCM Cipher initialised for ENCRYPT_MODE against the biometric key. */
    private fun buildCipher(): Cipher {
        getOrCreateSecretKey()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        val key = keyStore.getKey(BIOMETRIC_KEY_ALIAS, null) as SecretKey
        return Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(Cipher.ENCRYPT_MODE, key)
        }
    }

    /**
     * Creates the AES-256/GCM key in AndroidKeyStore if it does not already exist.
     *
     * Key properties:
     *  - userAuthenticationRequired = true — key is unlocked only after biometric auth
     *  - invalidatedByBiometricEnrollment = true (default) — key is wiped when new
     *    biometrics are added, preventing an attacker from enrolling their own finger
     *  - isStrongBoxBacked (Android 9+) — prefer StrongBox (dedicated security chip)
     *    over the regular TEE when available
     */
    private fun getOrCreateSecretKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
        if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) return

        val keyGenSpec = KeyGenParameterSpec.Builder(
            BIOMETRIC_KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE)
            .setUserAuthenticationRequired(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).also {
            it.init(keyGenSpec)
            it.generateKey()
        }
        Logger.d(LOG_TAG_UI, "$TAG biometric key created in AndroidKeyStore")
    }

    /** Removes the biometric key from the AndroidKeyStore (called when the key is invalidated). */
    private fun deleteKeyIfExists() {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
            if (keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)) {
                keyStore.deleteEntry(BIOMETRIC_KEY_ALIAS)
                Logger.d(LOG_TAG_UI, "$TAG invalidated biometric key deleted from AndroidKeyStore")
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_UI, "$TAG failed to delete biometric key: ${e.message}")
        }
    }

    private fun startHomeActivity() {
        Logger.v(LOG_TAG_UI, "$TAG starting home activity")
        val intent = Intent(this, HomeScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
        finish()
    }

    private fun isBiometricEnabled(): Boolean {
        val type = MiscSettingsActivity.BioMetricType.fromValue(persistentState.biometricAuthType)
        // use the biometricAuth flag for backward compatibility with older version
        return type.enabled()
    }

    // check if app running on TV
    private fun isAppRunningOnTv(): Boolean {
        return try {
            val uiModeManager: UiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
            uiModeManager.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
        } catch (_: Exception) {
            false
        }
    }
}
