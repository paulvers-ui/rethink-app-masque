package com.paulvers.auroravpn.service

import Logger
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.RandomAccessFile
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the tunnel keys (the WARP identities and wg0.conf) encrypted at rest.
 *
 * The AES-256-GCM key lives in Android Keystore (StrongBox or TEE where the phone has
 * one) and cannot be exported, so a copy of the app's files is useless off the phone.
 * A sealed file is `<name>.sealed`: a version byte, the 12-byte IV, then ciphertext and
 * tag, with the file name as associated data so one sealed file cannot be swapped in
 * for another. Plain copies left by older versions are sealed and wiped the first time
 * they are read. libusque.so gets the keys through a pipe (see [UsqueManager] and
 * [ChainManager]), so they are never written out in the clear again.
 *
 * In a release build the keys are not unsealed while a debugger or tracer is attached
 * to the app, the usual first step of dumping them from memory.
 *
 * If the phone's Keystore is broken (it happens on a few old ROMs) the files stay
 * plain, as before, rather than leave the tunnel without keys.
 */
// Every catch here sits on a Keystore / file boundary where a failure must turn into
// "no keys" or "not saved", never a crash of the VPN service; the early returns are those
// same failure exits. One flat object, like UsqueManager and ChainManager.
@Suppress("TooGenericExceptionCaught", "TooManyFunctions", "ReturnCount")
object KeyVault {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "auroravpn.tunnel-keys.v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BITS = 256
    private const val FORMAT_VERSION: Byte = 1
    private const val IV_LEN = 12
    private const val TAG_BITS = 128
    private const val SEALED_SUFFIX = ".sealed"
    private const val TMP_SUFFIX = ".tmp"
    private const val WIPE_CHUNK = 4096
    private const val SCRATCH_MAX_AGE_MS = 10 * 60 * 1000L

    /** True when [name] holds keys, sealed or (not yet migrated) plain. */
    @Synchronized
    fun exists(ctx: Context, name: String): Boolean {
        sweepScratchOnce(ctx)
        return sealedFile(ctx, name).length() > 0L || plainFile(ctx, name).length() > 0L
    }

    /**
     * The plaintext of [name], or null when there is none or it cannot be unsealed (the
     * Keystore key is gone, the file was tampered with, or a debugger is attached).
     */
    @Synchronized
    fun read(ctx: Context, name: String): ByteArray? {
        sweepScratchOnce(ctx)
        if (tracedInRelease(ctx)) {
            Logger.w(Logger.LOG_TAG_PROXY, "keys: not unsealing $name while a debugger/tracer is attached")
            return null
        }
        val sealed = sealedFile(ctx, name)
        if (sealed.length() > 0L) return unseal(name, sealed)
        val plain = plainFile(ctx, name)
        if (plain.length() == 0L) return null
        // Left by an older version: seal it now, then wipe the plain copy.
        val data = try { plain.readBytes() } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_PROXY, "keys: cannot read $name: ${e.message}")
            return null
        }
        if (writeLocked(ctx, name, data)) Logger.i(Logger.LOG_TAG_PROXY, "keys: sealed the plain $name")
        return data
    }

    /**
     * True when [name] is sealed but can no longer be opened (the Keystore key was reset,
     * or the file was damaged) and no debugger is the reason. The keys are lost for good;
     * the caller can only replace them (register again).
     */
    @Synchronized
    fun damaged(ctx: Context, name: String): Boolean {
        if (tracedInRelease(ctx)) return false
        val sealed = sealedFile(ctx, name)
        if (sealed.length() == 0L) return false
        val data = unseal(name, sealed) ?: return true
        data.fill(0)
        return false
    }

    /** Seals [data] as [name], replacing what was there. False if nothing was saved. */
    @Synchronized
    fun write(ctx: Context, name: String, data: ByteArray): Boolean = writeLocked(ctx, name, data)

    /** Seals the plain file [from] (e.g. what `usque register` wrote) as [name] and wipes [from]. */
    @Synchronized
    fun adopt(ctx: Context, from: File, name: String): Boolean {
        val data = try { from.readBytes() } catch (e: Exception) {
            Logger.w(Logger.LOG_TAG_PROXY, "keys: cannot read ${from.name}: ${e.message}")
            return false
        }
        return try {
            data.isNotEmpty() && writeLocked(ctx, name, data)
        } finally {
            wipe(from)
            data.fill(0)
        }
    }

    /** A private scratch file for `usque register` to write into; see [adopt]. */
    fun scratchFile(ctx: Context, name: String): File =
        File(ctx.noBackupFilesDir, "$name.${System.nanoTime()}$TMP_SUFFIX")

    @Volatile private var swept = false

    // Scratch copies are wiped as soon as they are used, but a crash in between would
    // leave one behind in the clear: wipe any old enough not to belong to a running start.
    private fun sweepScratchOnce(ctx: Context) {
        if (swept) return
        swept = true
        val cutoff = System.currentTimeMillis() - SCRATCH_MAX_AGE_MS
        ctx.noBackupFilesDir.listFiles { f -> f.name.endsWith(TMP_SUFFIX) && f.lastModified() < cutoff }
            ?.forEach { wipe(it) }
    }

    private fun writeLocked(ctx: Context, name: String, data: ByteArray): Boolean {
        val key = key()
        if (key == null) {
            // No usable Keystore: keep the old plain behaviour rather than lose the keys.
            // A sealed copy that can no longer be opened would shadow it (read prefers it).
            val ok = writeAtomically(plainFile(ctx, name), data)
            if (ok) wipe(sealedFile(ctx, name))
            return ok
        }
        val blob = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            val ct = cipher.doFinal(data)
            byteArrayOf(FORMAT_VERSION) + cipher.iv + ct
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "keys: sealing $name failed: ${e.message}", e)
            return false
        }
        if (!writeAtomically(sealedFile(ctx, name), blob)) return false
        wipe(plainFile(ctx, name))
        return true
    }

    private fun unseal(name: String, file: File): ByteArray? {
        return try {
            val blob = file.readBytes()
            if (blob.size <= 1 + IV_LEN || blob[0] != FORMAT_VERSION) {
                Logger.w(Logger.LOG_TAG_PROXY, "keys: $name is not a sealed file")
                return null
            }
            val key = key() ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN))
            cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
            cipher.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
        } catch (e: Exception) {
            // Wrong key (data restored from another phone), tampering, or a Keystore reset.
            Logger.e(Logger.LOG_TAG_PROXY, "keys: cannot unseal $name: ${e.javaClass.simpleName}", e)
            null
        }
    }

    /** Removes [name] in every form. */
    @Synchronized
    fun delete(ctx: Context, name: String) {
        wipe(sealedFile(ctx, name))
        wipe(plainFile(ctx, name))
    }

    /** Overwrites [file] with zeros before deleting it, so the keys do not linger in it. */
    fun wipe(file: File) {
        if (!file.exists()) return
        try {
            RandomAccessFile(file, "rws").use { raf ->
                val zeros = ByteArray(WIPE_CHUNK)
                var left = raf.length()
                raf.seek(0)
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    raf.write(zeros, 0, n)
                    left -= n
                }
            }
        } catch (_: Exception) {}
        if (!file.delete()) Logger.w(Logger.LOG_TAG_PROXY, "keys: could not delete ${file.name}")
    }

    private fun writeAtomically(target: File, data: ByteArray): Boolean = try {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        tmp.writeBytes(data)
        ownerOnly(tmp)
        if (!tmp.renameTo(target)) {
            target.writeBytes(data)
            wipe(tmp)
        }
        ownerOnly(target)
        true
    } catch (e: Exception) {
        Logger.e(Logger.LOG_TAG_PROXY, "keys: writing ${target.name} failed: ${e.message}", e)
        false
    }

    private fun ownerOnly(f: File) {
        try {
            f.setReadable(false, false); f.setReadable(true, true)
            f.setWritable(false, false); f.setWritable(true, true)
        } catch (_: Exception) {}
    }

    private fun sealedFile(ctx: Context, name: String) = File(ctx.filesDir, name + SEALED_SUFFIX)

    private fun plainFile(ctx: Context, name: String) = File(ctx.filesDir, name)

    @Volatile private var cachedKey: SecretKey? = null
    @Volatile private var keystoreBroken = false

    private fun key(): SecretKey? {
        cachedKey?.let { return it }
        if (keystoreBroken) return null
        return try {
            val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
            (existing ?: generateKey()).also { cachedKey = it }
        } catch (e: Exception) {
            Logger.e(Logger.LOG_TAG_PROXY, "keys: Android Keystore unusable, keys stay unencrypted: ${e.message}", e)
            keystoreBroken = true
            null
        }
    }

    private fun generateKey(): SecretKey {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
            if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        return try {
            gen.init(spec(strongBox = true))
            gen.generateKey()
        } catch (_: Exception) {
            // No StrongBox (most phones): the TEE-backed key is the next best thing.
            gen.init(spec(strongBox = false))
            gen.generateKey()
        }
    }

    // A release build never has a debugger attached in normal use; a tracer (gdb,
    // frida-server attaching, strace) is how the keys would be read out of memory.
    private fun tracedInRelease(ctx: Context): Boolean {
        if ((ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false
        if (android.os.Debug.isDebuggerConnected()) return true
        return try {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("TracerPid:") }
                    ?.substringAfter(':')?.trim()?.toIntOrNull()?.let { it != 0 } ?: false
            }
        } catch (_: Exception) {
            false
        }
    }
}
