package org.parallelnest.app.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

private val keystoreLock = Any()
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
    private val key: SecretKey get() = synchronized(keystoreLock) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("nest-storage-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nest-storage-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun put(name: String, value: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val encrypted = cipher.doFinal(value)
        check(prefs.edit().putString(name, Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)).commit())
    }
    @Synchronized fun get(name: String): ByteArray? {
        val data = prefs.getString(name, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, data.copyOfRange(0, 12))) }.doFinal(data.copyOfRange(12, data.size))
    }
    fun newBridgeKey(): ByteArray = ByteArray(32).apply { SecureRandom().nextBytes(this) }.also { put("bridge", it) }
    fun hasPin() = prefs.contains("pin")
    @Synchronized fun setPin(pin: CharArray) {
        try {
            require(pin.size in 6..32 && pin.all { it.isDigit() }) { "PIN must have 6–32 digits" }
            val salt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            put("pin", salt + derive(pin, salt))
            check(prefs.edit().remove("attempts").remove("blocked_until").commit())
        } finally { pin.fill('\u0000') }
    }
    private fun derive(pin: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin, salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    @Synchronized fun checkPin(pin: CharArray): Boolean {
      try {
        val until = prefs.getLong("blocked_until", 0)
        if (System.currentTimeMillis() < until) { pin.fill('\u0000'); return false }
        val data = get("pin") ?: return false
        val matches = MessageDigest.isEqual(data.copyOfRange(16, data.size), derive(pin, data.copyOfRange(0, 16)))
        pin.fill('\u0000')
        val attempts = if (matches) 0 else prefs.getInt("attempts", 0) + 1
        prefs.edit().putInt("attempts", attempts).putLong("blocked_until", if (attempts >= 5) System.currentTimeMillis() + 30_000 else 0).commit()
        return matches
      } finally { pin.fill('\u0000') }
    }
}
