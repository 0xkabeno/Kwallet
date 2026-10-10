package app.hwallet

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** v3.84 at-rest encryption keys, kept in the Android Keystore (TEE / StrongBox): never exportable, never leave the phone.
 *  MAC: binds every PIN guess to this phone's secure hardware, so a copied data file can't be brute-forced elsewhere.
 *  BIO: wraps the data key; usable only right after a strong fingerprint/face check (CryptoObject). */
object KwKeys {
    private const val MAC = "hw_vault_mac_v1"
    private const val BIO = "hw_vault_bio_v1"
    private fun ks(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun macKey(): SecretKey {
        (ks().getKey(MAC, null) as? SecretKey)?.let { return it }
        fun gen(strongBox: Boolean): SecretKey {
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
            val b = KeyGenParameterSpec.Builder(MAC, KeyProperties.PURPOSE_SIGN)
            if (strongBox && Build.VERSION.SDK_INT >= 28) b.setIsStrongBoxBacked(true)
            g.init(b.build()); return g.generateKey()
        }
        return try { gen(true) } catch (_: Exception) { try { ks().deleteEntry(MAC) } catch (_: Exception) {}; gen(false) }
    }

    fun mac(data: ByteArray): String {
        val m = Mac.getInstance("HmacSHA256"); m.init(macKey()); return b64(m.doFinal(data))
    }

    fun bioCipher(encrypt: Boolean, iv: ByteArray?): Cipher {
        if (encrypt) {
            deleteBio()
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            val b = KeyGenParameterSpec.Builder(BIO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            g.init(b.build()); g.generateKey()
        }
        val key = ks().getKey(BIO, null) as SecretKey
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        if (encrypt) c.init(Cipher.ENCRYPT_MODE, key) else c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return c
    }

    fun deleteBio() { try { ks().deleteEntry(BIO) } catch (_: Exception) {} }
}
