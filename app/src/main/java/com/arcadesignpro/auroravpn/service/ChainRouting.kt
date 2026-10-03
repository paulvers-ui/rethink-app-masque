package com.arcadesignpro.auroravpn.service

import Logger
import android.content.Context
import com.arcadesignpro.auroravpn.R
import com.arcadesignpro.auroravpn.data.AppConfig
import com.arcadesignpro.auroravpn.database.ProxyEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Points the VPN tunnel at the chain (WARP1 -> wg0 -> WARP2, SOCKS5 on
 * 127.0.0.1:[ChainManager.SOCKS_PORT]) and back.
 *
 * The tunnel's only custom SOCKS5 upstream is the proxy row [WARP_PROXY_ID],
 * which the simple WARP switch writes with [UsqueManager.SOCKS_PORT]. Chain mode
 * rewrites that same row with the chain's port rather than adding a second
 * SOCKS5 row (the proxyMode lookup has no ORDER BY, so a second row could win).
 *
 * Simple WARP is stopped while the chain is the upstream: it uses the same
 * config.json identity as WARP1, and two live MASQUE sessions on one key are
 * not something Cloudflare documents. persistentState.usqueEnabled keeps the
 * user's simple-WARP choice untouched, so leaving the chain restores it.
 *
 * Both calls switch to IO themselves: they write the proxy row through Room
 * (main-thread writes throw) and re-point firestack over JNI.
 */
object ChainRouting {
    // Same row as ProxySettingsActivity.WARP_PROXY_ID (the WARP SOCKS5 entry).
    private const val WARP_PROXY_ID = -1

    /**
     * Call once the chain carries traffic (awaitChainLiveness passed). Points
     * the tunnel at the chain first and stops simple WARP only afterwards, so
     * the tunnel never targets a dead :40000 in between. chainEnabled is set
     * only once the row is written, so a failed write cannot leave chain mode
     * on without its route. Returns whether the tunnel now uses the chain.
     */
    // Any failure here (Room, prefs) must turn into "not routed" so the caller
    // stops the chain, rather than crash the connect flow halfway.
    @Suppress("TooGenericExceptionCaught")
    suspend fun routeThroughChain(ctx: Context, appConfig: AppConfig, persistentState: PersistentState): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val name = ctx.getString(R.string.chain_tunnel_title)
                appConfig.updateCustomSocks5Proxy(loopbackSocks5(name, ChainManager.SOCKS_PORT))
                // Before stopping usque, so its watchdogs (simpleWarpActive) stand down.
                persistentState.chainEnabled = true
                VpnController.reapplyLoopbackSocks5("chain on")
                UsqueManager.stopSocksProxy()
                Logger.i(Logger.LOG_TAG_PROXY, "chain: VPN tunnel now uses SOCKS5 :${ChainManager.SOCKS_PORT}")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(Logger.LOG_TAG_PROXY, "chain: routing the VPN tunnel through the chain failed", e)
                // Undo a half-applied switch: put the row back the way leaveChain would.
                if (persistentState.chainEnabled) leaveChainOnIo(ctx, appConfig, persistentState)
                false
            }
        }

    /**
     * Gives the tunnel back to simple WARP (if the user had it on) or to no
     * custom proxy, the same end state as the WARP switch. Call before
     * ChainManager.stopChain() so traffic moves off the chain first. No-op
     * unless the chain is the current upstream, so a failed connect never
     * touches the user's proxy settings.
     */
    suspend fun leaveChain(ctx: Context, appConfig: AppConfig, persistentState: PersistentState) =
        withContext(Dispatchers.IO) {
            if (persistentState.chainEnabled) leaveChainOnIo(ctx, appConfig, persistentState)
        }

    private suspend fun leaveChainOnIo(ctx: Context, appConfig: AppConfig, persistentState: PersistentState) {
        persistentState.chainEnabled = false
        if (persistentState.usqueEnabled) {
            // Same as the VPN-start path: keep the row even if the start fails;
            // the usque watchdog retries and the tunnel resumes on its own.
            val started = UsqueManager.startSocksProxy(ctx)
            val name = ctx.getString(R.string.warp_tunnel_title)
            appConfig.updateCustomSocks5Proxy(loopbackSocks5(name, UsqueManager.SOCKS_PORT))
            Logger.i(Logger.LOG_TAG_PROXY, "chain: VPN tunnel back on simple WARP :${UsqueManager.SOCKS_PORT}, started=$started")
        } else {
            appConfig.removeProxy(AppConfig.ProxyType.SOCKS5, AppConfig.ProxyProvider.CUSTOM)
            Logger.i(Logger.LOG_TAG_PROXY, "chain: VPN tunnel custom SOCKS5 removed")
        }
        VpnController.reapplyLoopbackSocks5("chain off")
    }

    private fun loopbackSocks5(name: String, port: Int) = ProxyEndpoint(
        WARP_PROXY_ID,
        name,
        ProxyManager.ProxyMode.SOCKS5.value,
        ProxyEndpoint.DEFAULT_PROXY_TYPE,
        /* appName */ "",
        ChainManager.SOCKS_HOST,
        port,
        /* userName */ "",
        /* password */ "",
        isSelected = true,
        isCustom = true,
        isUDP = false,
        modifiedDataTime = 0L,
        latency = 0
    )
}
