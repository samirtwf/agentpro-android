package org.linphone.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.tools.Log

/**
 * Encrypted, password-protected export/import of the whole AgentPro configuration: the linphone
 * config (accounts + app settings, from `core.config.dump()`) plus every per-account
 * `agentpro_*` SharedPreferences store (PBX/AMI/UCM/Agent settings, speed-dials, dialer delay, …).
 *
 * File format:  "APCFG1" | salt[16] | iv[12] | AES-256-GCM ciphertext(+tag).
 * The key is PBKDF2WithHmacSHA256(password, salt, 120000, 256), so the password is required to
 * import — a leaked backup file is useless without it. The contact/campaign/disposition LISTS
 * (their `*.json` data files) are intentionally NOT included; this is a settings transfer.
 */
object ConfigBackup {
    private const val TAG = "[Config Backup]"
    private val MAGIC = "APCFG1".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 120_000
    private const val PREF_PREFIX = "agentpro_"

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /**
     * Build the encrypted backup blob. [linphonerc] is the dumped Core config (read on the Core
     * thread by the caller via `core.config.dump()`); everything else is read here.
     */
    fun export(context: Context, linphonerc: String, password: String): ByteArray {
        val root = JSONObject()
        root.put("v", 1)
        root.put("linphonerc", linphonerc)

        val prefs = JSONObject()
        for (name in collectPrefNames(context)) {
            val sp = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val obj = JSONObject()
            for ((k, v) in sp.all) {
                val cell = JSONObject()
                when (v) {
                    is String -> { cell.put("t", "s"); cell.put("v", v) }
                    is Int -> { cell.put("t", "i"); cell.put("v", v) }
                    is Boolean -> { cell.put("t", "b"); cell.put("v", v) }
                    is Long -> { cell.put("t", "l"); cell.put("v", v) }
                    is Float -> { cell.put("t", "f"); cell.put("v", v.toDouble()) }
                    else -> continue
                }
                obj.put(k, cell)
            }
            prefs.put(name, obj)
        }
        root.put("prefs", prefs)

        val plaintext = root.toString().toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        return MAGIC + salt + iv + cipher.doFinal(plaintext)
    }

    /**
     * Decrypt [blob] with [password] and restore the config + prefs to disk. Returns true on
     * success (the caller should then [restartApp]); false on a wrong password or corrupt file
     * (nothing is changed in that case, since decryption fails before anything is written).
     */
    fun restore(context: Context, blob: ByteArray, password: String): Boolean {
        return try {
            require(blob.size > MAGIC.size + 28) { "File too small" }
            require(blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Not an AgentPro backup" }
            var off = MAGIC.size
            val salt = blob.copyOfRange(off, off + 16); off += 16
            val iv = blob.copyOfRange(off, off + 12); off += 12
            val ct = blob.copyOfRange(off, blob.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
            val root = JSONObject(String(cipher.doFinal(ct), Charsets.UTF_8)) // throws on wrong password

            // Restore the per-account SharedPreferences first (safe, no Core involvement).
            val prefs = root.optJSONObject("prefs")
            if (prefs != null) {
                val names = prefs.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    val obj = prefs.getJSONObject(name)
                    val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                    val keys = obj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val cell = obj.getJSONObject(k)
                        when (cell.optString("t")) {
                            "s" -> editor.putString(k, cell.optString("v"))
                            "i" -> editor.putInt(k, cell.optInt("v"))
                            "b" -> editor.putBoolean(k, cell.optBoolean("v"))
                            "l" -> editor.putLong(k, cell.optLong("v"))
                            "f" -> editor.putFloat(k, cell.optDouble("v").toFloat())
                        }
                    }
                    editor.commit()
                }
            }

            // Overwrite the linphone config last. The caller restarts the process immediately after
            // (hard exit, no Core sync), so the new .linphonerc is read fresh on next launch.
            val linphonerc = root.optString("linphonerc")
            if (linphonerc.isNotEmpty()) {
                File(corePreferences.configPath).writeText(linphonerc, Charsets.UTF_8)
            }
            true
        } catch (e: Exception) {
            Log.e("$TAG Restore failed (wrong password or corrupt file): $e")
            false
        }
    }

    /** Hard-restart the app so the freshly-written .linphonerc is reloaded by a new Core. */
    fun restartApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
        // Hard-exit so the old Core never syncs its (stale) config over the restored .linphonerc.
        Runtime.getRuntime().exit(0)
    }

    // The agentpro_* SharedPreferences stores (all scopes/variants), discovered from the prefs dir.
    private fun collectPrefNames(context: Context): List<String> {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { f ->
            val n = f.name
            if (n.startsWith(PREF_PREFIX) && n.endsWith(".xml")) n.removeSuffix(".xml") else null
        }
    }
}
