package dev.caradb

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only fixed codes cross the storage/UI boundary; never persist exception text or input. */
internal enum class SafeError(val messageRes: Int) {
    NONE(0),
    INVALID_CONFIG(R.string.error_invalid_config),
    SAVE_REQUIRED(R.string.error_save_required),
    STORE_UNREADABLE(R.string.error_store_unreadable),
    KEY_UNAVAILABLE(R.string.error_key_unavailable),
    WRITE_FAILED(R.string.error_write_failed),
    FOREGROUND_DENIED(R.string.error_foreground_denied),
    RESTORE_TIMEOUT(R.string.error_restore_timeout),
    NETWORK_MONITOR_FAILED(R.string.error_network_monitor),
    ENGINE_FAILED(R.string.error_engine),
    CORE_WARNING(R.string.error_core_warning),
    STOP_FAILED(R.string.error_stop),
    PROBE_FAILED(R.string.probe_failed),
}

internal data class SavedProfile(val config: String, val adbHost: String)
internal class StoreFailure(val code: SafeError) : Exception()

/** All calls, including flag-only reads, belong to TunnelController's single executor. */
internal class ConfigStore(context: Context) {
    internal data class Record(
        val bootAutoConnect: Boolean = false,
        val desiredRunning: Boolean = false,
        val lastErrorCode: SafeError = SafeError.NONE,
        val iv: String? = null,
        val ciphertext: String? = null,
    ) {
        val hasProfile: Boolean get() = iv != null && ciphertext != null
    }

    // Resolving noBackupFilesDir can create the directory; defer that I/O to the first worker call.
    private val file by lazy(LazyThreadSafetyMode.NONE) {
        AtomicFile(File(context.noBackupFilesDir, "tunnel-profile.json"))
    }

    fun read(): Record {
        val bytes = readBytes() ?: return Record()
        try {
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            if (json.getInt("version") != VERSION || json.get("bootAutoConnect") !is Boolean ||
                json.get("desiredRunning") !is Boolean
            ) throw StoreFailure(SafeError.STORE_UNREADABLE)
            val code = SafeError.valueOf(json.getString("lastErrorCode"))
            val iv = if (json.isNull("iv")) null else json.getString("iv")
            val ciphertext = if (json.isNull("ciphertext")) null else json.getString("ciphertext")
            if ((iv == null) != (ciphertext == null)) throw StoreFailure(SafeError.STORE_UNREADABLE)
            if (iv != null && (decode(iv).size != 12 || decode(ciphertext!!).size < 16)) {
                throw StoreFailure(SafeError.STORE_UNREADABLE)
            }
            return Record(json.getBoolean("bootAutoConnect"), json.getBoolean("desiredRunning"), code, iv, ciphertext)
        } catch (_: Exception) {
            throw StoreFailure(SafeError.STORE_UNREADABLE)
        }
    }

    fun decrypt(record: Record): SavedProfile? {
        if (!record.hasProfile) return null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, decode(record.iv!!)))
            cipher.updateAAD(AAD)
            val clear = cipher.doFinal(decode(record.ciphertext!!))
            try {
                val json = JSONObject(clear.toString(Charsets.UTF_8))
                return SavedProfile(json.getString("config"), json.getString("adbHost"))
            } finally {
                clear.fill(0)
            }
        } catch (_: Exception) {
            // Includes missing/replaced keys, an invalid authentication tag, and malformed plaintext.
            throw StoreFailure(SafeError.KEY_UNAVAILABLE)
        }
    }

    /** Explicit, statically validated re-entry is the only operation allowed to replace a bad record. */
    fun save(profile: SavedProfile, connect: Boolean): Record {
        val previous = try { read() } catch (_: StoreFailure) { Record() }
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).also {
                try {
                    it.init(Cipher.ENCRYPT_MODE, key(create = true))
                } catch (_: KeyPermanentlyInvalidatedException) {
                    // Such a key cannot decrypt the old record either. Do not rotate on transient failures.
                    val keys = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                    keys.deleteEntry(KEY_ALIAS)
                    it.init(Cipher.ENCRYPT_MODE, key(create = true))
                }
                it.updateAAD(AAD)
            }
        } catch (_: Exception) {
            throw StoreFailure(SafeError.KEY_UNAVAILABLE)
        }
        val clear = JSONObject().put("config", profile.config).put("adbHost", profile.adbHost)
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = try {
            cipher.doFinal(clear)
        } catch (_: Exception) {
            throw StoreFailure(SafeError.KEY_UNAVAILABLE)
        } finally {
            clear.fill(0)
        }
        val updated = previous.copy(
            // Save-only preserves both behavioral flags. A successful re-entry clears the old error.
            desiredRunning = if (connect) true else previous.desiredRunning,
            lastErrorCode = SafeError.NONE,
            iv = encode(cipher.iv),
            ciphertext = encode(encrypted),
        )
        write(updated)
        return updated
    }

    /** Does not touch Keystore, decrypt, or generate a new IV; merges the latest committed flags. */
    fun flags(change: (Record) -> Record): Record {
        val previous = read()
        val updated = change(previous)
        check(updated.iv == previous.iv && updated.ciphertext == previous.ciphertext)
        if (updated != previous) write(updated)
        return updated
    }

    private fun write(record: Record) {
        val bytes = JSONObject()
            .put("version", VERSION)
            .put("bootAutoConnect", record.bootAutoConnect)
            .put("desiredRunning", record.desiredRunning)
            .put("lastErrorCode", record.lastErrorCode.name)
            .put("iv", record.iv ?: JSONObject.NULL)
            .put("ciphertext", record.ciphertext ?: JSONObject.NULL)
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw StoreFailure(SafeError.WRITE_FAILED)
        // Keep the old bytes even when explicitly repairing a malformed (but readable) record.
        val previous = readBytes()
        try {
            atomicWrite(bytes)
            // AtomicFile.finishWrite can log a rename failure instead of throwing on some releases.
            // Sync before committing, then compare the actual committed bytes (not a custom hash).
            if (readBytes()?.contentEquals(bytes) != true) throw StoreFailure(SafeError.WRITE_FAILED)
        } catch (_: Exception) {
            // startWrite/failWrite normally already rolled back. Best effort also covers a failed
            // post-commit verification; never report this write as durably saved or start a tunnel.
            try {
                if (previous != null) atomicWrite(previous) else file.delete()
            } catch (_: Exception) {
                // The caller reports persistence uncertainty and keeps the runtime stopped.
            }
            throw StoreFailure(SafeError.WRITE_FAILED)
        }
    }

    private fun atomicWrite(bytes: ByteArray) {
        var output: FileOutputStream? = null
        try {
            output = file.startWrite()
            output.write(bytes)
            output.flush()
            output.fd.sync()
            file.finishWrite(output)
            output = null
        } finally {
            if (output != null) file.failWrite(output)
        }
    }

    private fun readBytes(): ByteArray? {
        try {
            return file.openRead().use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = input.read(chunk)
                    if (count < 0) break
                    if (buffer.size() + count > MAX_BYTES) throw StoreFailure(SafeError.STORE_UNREADABLE)
                    buffer.write(chunk, 0, count)
                }
                buffer.toByteArray()
            }
        } catch (_: FileNotFoundException) {
            if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
            throw StoreFailure(SafeError.STORE_UNREADABLE)
        } catch (_: Exception) {
            throw StoreFailure(SafeError.STORE_UNREADABLE)
        }
    }

    private fun key(create: Boolean): SecretKey {
        val keys = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keys.getKey(KEY_ALIAS, null)
        if (existing is SecretKey) return existing
        if (!create) throw StoreFailure(SafeError.KEY_UNAVAILABLE)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val VERSION = 1
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "caradb.profile.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MAX_BYTES = 1024 * 1024
        private val AAD = "CarADB encrypted profile v1".toByteArray(Charsets.UTF_8)
    }
}
