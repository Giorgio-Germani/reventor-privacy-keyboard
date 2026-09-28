package org.futo.inputmethod.latin.uix.clipboardsync

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.futo.inputmethod.latin.uix.getSettingBlocking
import org.futo.inputmethod.latin.uix.setSettingBlocking
import org.reventor.sync.protocol.Hex
import org.reventor.sync.protocol.SyncIdentity
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The phone's long-lived sync identity. The P-256 private key lives outside
 * the Keystore (Keystore keys can't do ECDH below API 31; minSdk is 24), so
 * it is wrapped with an AES-GCM Keystore key before being stored in DataStore.
 * Blob format: "deviceId:x509PubHex:wrappedPkcs8Hex".
 */
object AndroidIdentity {
    private val lock = Any()

    @Volatile
    private var cached: SyncIdentity? = null

    /** Returns the identity, or null while the device is locked (Keystore unavailable). */
    fun loadOrCreate(context: Context): SyncIdentity? {
        val identity = synchronized(lock) {
            try {
                val blob = context.getSettingBlocking(CLIPBOARD_SYNC_IDENTITY)
                (if (blob.isNotEmpty()) restore(blob) else null) ?: createAndStore(context)
            } catch (e: Exception) {
                null
            }?.also { cached = it }
        } ?: return null

        identity.deviceName = displayName(context)
        return identity
    }

    fun displayName(context: Context): String {
        context.getSettingBlocking(CLIPBOARD_SYNC_DEVICE_NAME).takeIf { it.isNotBlank() }?.let { return it }
        val bluetoothName = try {
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        } catch (e: Exception) {
            null
        }
        return bluetoothName?.takeIf { it.isNotBlank() } ?: (Build.MODEL ?: "Android device")
    }

    private fun createAndStore(context: Context): SyncIdentity {
        val identity = SyncIdentity.generate(UUID.randomUUID().toString(), "Android")
        val blob = identity.deviceId + ":" +
            Hex.encode(identity.publicKeyBytes()) + ":" +
            Hex.encode(WrapKey.encrypt(identity.privateKeyBytes()))
        context.setSettingBlocking(CLIPBOARD_SYNC_IDENTITY.key, blob)
        return identity
    }

    private fun restore(blob: String): SyncIdentity? {
        val parts = blob.split(":", limit = 3)
        if (parts.size != 3) return null
        return try {
            SyncIdentity.fromEncodings(
                parts[0], "Android",
                WrapKey.decrypt(Hex.decode(parts[2])),
                Hex.decode(parts[1]),
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Non-exportable AES-256-GCM key in the Android Keystore, used to wrap the
 * identity key at rest. If the Keystore is wiped, the old identity becomes
 * undecryptable and a fresh one is generated (peers must re-pair).
 */
private object WrapKey {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val WRAP_KEY_ALIAS = "reventor_clipboard_sync_wrap"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV size ${iv.size}" }
        return iv + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
        return cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun getKey(): SecretKey {
        val entry = keyStore().getEntry(WRAP_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        return entry?.secretKey ?: createKey()
    }

    private fun getOrCreateKey(): SecretKey = try {
        getKey()
    } catch (e: Exception) {
        createKey()
    }

    private fun createKey(): SecretKey {
        keyStore().deleteEntry(WRAP_KEY_ALIAS)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAP_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
