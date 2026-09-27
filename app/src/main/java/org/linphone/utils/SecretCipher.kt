package org.linphone.utils

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM decryption for the credentials the "AgentPro APK Builder" embeds into
 * linphonerc_factory. Encrypted values are stored as  "ENC:" + base64(nonce[12] | ciphertext | tag).
 * Plaintext (no "ENC:" prefix) is returned untouched, so vanilla / older builds keep working.
 *
 * NOTE: this is obfuscation-grade protection. The key lives in the (R8-obfuscated) binary, so a
 * determined attacker can still extract it — this stops a casual `unzip` of the APK from leaking the
 * SIP/PBX credentials, it is NOT unbreakable. Always pair it with limited-privilege per-customer
 * accounts so a leak has minimal blast radius.
 *
 * The key is never in the source code: [CredentialKey] is generated at build time from the private
 * credential-key.properties (see app/build.gradle.kts), so publishing the source reveals nothing.
 */
object SecretCipher {
    private const val PREFIX = "ENC:"

    private val key: ByteArray by lazy {
        val masked = hexToBytes(CredentialKey.MASKED_HEX)
        ByteArray(masked.size) { (masked[it].toInt() xor CredentialKey.MASK).toByte() }
    }

    /** Encrypt [value] to "ENC:" + base64(nonce|ciphertext|tag); pairs with [decrypt]. Empty stays empty. */
    fun encrypt(value: String): String {
        if (value.isEmpty()) return value
        return try {
            val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            PREFIX + Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        } catch (e: Exception) {
            value // never lose the value on an encryption failure
        }
    }

    fun decrypt(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        return try {
            val blob = Base64.decode(value.substring(PREFIX.length), Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, 12)
            val data = blob.copyOfRange(12, blob.size) // ciphertext + 16-byte GCM tag
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            "" // wrong key / tampered ciphertext
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = (
                (Character.digit(hex[i * 2], 16) shl 4) +
                    Character.digit(hex[i * 2 + 1], 16)
                ).toByte()
        }
        return out
    }
}
