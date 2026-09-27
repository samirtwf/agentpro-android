/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.utils

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import org.linphone.core.tools.Log
import org.linphone.R

/**
 * Trial / activation licensing for AgentPro "Trial" builds.
 *
 * A Trial build carries a `[trial]` block (enabled=1, duration_seconds, pubkey) injected into
 * `assets/linphonerc_factory` by the APK Builder. The app runs normally until the trial elapses,
 * then [evaluate] returns [State.EXPIRED] and the UI shows the trial-ended screen.
 *
 * Activation is unforgeable: the builder's KeyGen signs the device's ANDROID_ID with an EC P-256
 * **private** key (kept only on the builder machine); the app verifies the signature with the
 * embedded **public** key. Reverse-engineering the app only exposes the public key, which cannot
 * mint codes. A valid code permanently unlocks this device (per the user's choice).
 *
 * Clock tampering: elapsed time is computed from a monotonic "max seen" timestamp, so setting the
 * phone clock backwards does not extend the trial. (A fully offline trial can still be reset by a
 * reinstall — an online check would be needed to close that; see notes.)
 *
 * Crypto contract (must match the builder KeyGen):
 *  - device message  = ANDROID_ID as UTF-8 bytes
 *  - signature       = ECDSA(SHA-256) DER, Base64 -> the activation code
 *  - public key      = X.509 SubjectPublicKeyInfo, Base64 -> [trial] pubkey
 */
object LicenseManager {
    private const val TAG = "[License]"
    private const val PREFS = "agentpro_license"
    private const val KEY_FIRST_SEEN = "first_seen"
    private const val KEY_LAST_SEEN = "last_seen"
    private const val KEY_ACTIVATED = "activated"
    private const val KEY_ACTIVATED_DEVICE = "activated_device"

    enum class State { LICENSED, TRIAL, EXPIRED }

    private data class TrialConfig(val enabled: Boolean, val durationSeconds: Long, val pubKey: String)

    @Volatile
    private var cached: TrialConfig? = null

    private fun config(context: Context): TrialConfig {
        cached?.let { return it }
        var enabled = false
        var duration = 0L
        var pubkey = ""
        try {
            context.assets.open("linphonerc_factory").bufferedReader().use { reader ->
                var inTrial = false
                reader.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.startsWith("[") && line.endsWith("]")) {
                        inTrial = line == "[trial]"
                        return@forEachLine
                    }
                    if (!inTrial || line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val eq = line.indexOf('=')
                    if (eq < 0) return@forEachLine
                    val k = line.substring(0, eq).trim()
                    val v = line.substring(eq + 1).trim()
                    when (k) {
                        "enabled" -> enabled = v == "1" || v.equals("true", true)
                        "duration_seconds" -> duration = v.toLongOrNull() ?: 0L
                        "pubkey" -> pubkey = v
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("$TAG Failed to read [trial] config: $e")
        }
        val cfg = TrialConfig(enabled && duration > 0 && pubkey.isNotEmpty(), duration, pubkey)
        cached = cfg
        return cfg
    }

    /** True for a Trial build (regardless of remaining time). False for Full/Locked builds. */
    fun isTrialBuild(context: Context): Boolean = config(context).enabled

    fun deviceId(context: Context): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    fun isActivated(context: Context): Boolean {
        val cfg = config(context)
        if (!cfg.enabled) return true // not a trial build -> always licensed
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ACTIVATED, false) &&
            prefs.getString(KEY_ACTIVATED_DEVICE, "") == deviceId(context)
    }

    fun evaluate(context: Context): State {
        val cfg = config(context)
        if (!cfg.enabled) return State.LICENSED
        if (isActivated(context)) return State.LICENSED

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val first = if (prefs.contains(KEY_FIRST_SEEN)) {
            prefs.getLong(KEY_FIRST_SEEN, now)
        } else {
            prefs.edit().putLong(KEY_FIRST_SEEN, now).apply()
            now
        }
        // Monotonic "max seen" — rolling the clock back can't shrink elapsed time.
        val last = maxOf(prefs.getLong(KEY_LAST_SEEN, now), now)
        prefs.edit().putLong(KEY_LAST_SEEN, last).apply()

        val elapsedSeconds = (last - first) / 1000
        return if (elapsedSeconds >= cfg.durationSeconds) State.EXPIRED else State.TRIAL
    }

    fun remainingSeconds(context: Context): Long {
        val cfg = config(context)
        if (!cfg.enabled || isActivated(context)) return Long.MAX_VALUE
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val first = prefs.getLong(KEY_FIRST_SEEN, now)
        val last = maxOf(prefs.getLong(KEY_LAST_SEEN, now), now)
        val elapsedSeconds = (last - first) / 1000
        return (cfg.durationSeconds - elapsedSeconds).coerceAtLeast(0)
    }

    /** Human-readable license state for display (Settings ▸ About). */
    fun statusSummary(context: Context): String {
        if (!config(context).enabled) return context.getString(R.string.ap_license_full)
        if (isActivated(context)) return context.getString(R.string.ap_license_activated)
        val rem = remainingSeconds(context)
        if (rem <= 0) return context.getString(R.string.ap_license_trial_expired)
        val days = rem / 86400
        val hours = rem / 3600
        val minutes = rem / 60
        val left = when {
            days >= 1 -> context.resources.getQuantityString(R.plurals.ap_duration_days, days.toInt(), days)
            hours >= 1 -> context.resources.getQuantityString(R.plurals.ap_duration_hours, hours.toInt(), hours)
            else -> context.resources.getQuantityString(R.plurals.ap_duration_minutes, minutes.toInt(), minutes)
        }
        return context.getString(R.string.ap_license_trial_remaining_fmt, left)
    }

    /** Verifies a signed activation code for THIS device and, if valid, permanently unlocks it. */
    fun activate(context: Context, code: String): Boolean {
        val cfg = config(context)
        if (cfg.pubKey.isEmpty()) return false
        val device = deviceId(context)
        return try {
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(Base64.decode(cfg.pubKey, Base64.DEFAULT)))
            val sigBytes = Base64.decode(code.trim().replace("\\s".toRegex(), ""), Base64.DEFAULT)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pub)
            verifier.update(device.toByteArray(Charsets.UTF_8))
            val ok = verifier.verify(sigBytes)
            if (ok) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ACTIVATED, true)
                    .putString(KEY_ACTIVATED_DEVICE, device)
                    .apply()
                Log.i("$TAG Activation succeeded for device [$device]")
            } else {
                Log.i("$TAG Activation code rejected for device [$device]")
            }
            ok
        } catch (e: Exception) {
            Log.e("$TAG Activation error: $e")
            false
        }
    }

    /** Hidden test helper (developer only): immediately expire the trial to preview the lock screen. */
    fun forceExpireForTest(context: Context) {
        val cfg = config(context)
        if (!cfg.enabled) return
        val now = System.currentTimeMillis()
        val past = now - (cfg.durationSeconds + 60) * 1000
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_FIRST_SEEN, past)
            .putLong(KEY_LAST_SEEN, now)
            .apply()
        Log.i("$TAG Trial force-expired for testing")
    }
}
