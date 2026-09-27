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
package org.linphone.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.linphone.R
import org.linphone.ui.main.MainActivity
import org.linphone.utils.LicenseManager

/**
 * Full-screen lock shown when a Trial build's period has elapsed. Displays the device ID and the
 * developer's contact info, and lets the user paste a signed activation code to permanently unlock.
 * Self-contained (no Core dependency) so it works even before the Core is up.
 */
class TrialExpiredActivity : AppCompatActivity() {
    companion object {
        /** True when opened from Settings to activate during the trial (vs the expiry lock). */
        const val EXTRA_VOLUNTARY = "voluntary"
    }

    private var voluntary = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.trial_expired_activity)

        voluntary = intent.getBooleanExtra(EXTRA_VOLUNTARY, false)
        if (voluntary) {
            findViewById<TextView>(R.id.trial_title).text = getString(R.string.ap_activate_agentpro)
            findViewById<TextView>(R.id.trial_message).text =
                getString(R.string.ap_activate_device_message)
            findViewById<Button>(R.id.trial_exit).text = getString(R.string.ap_back)
        }

        val deviceId = LicenseManager.deviceId(this)
        findViewById<TextView>(R.id.trial_device_id).text = deviceId

        findViewById<TextView>(R.id.trial_copy_id).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AgentPro Device ID", deviceId))
            Toast.makeText(this, R.string.ap_toast_device_id_copied, Toast.LENGTH_SHORT).show()
        }

        findViewById<TextView>(R.id.trial_email).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:samirtwf@gmail.com"))
                        .putExtra(Intent.EXTRA_SUBJECT, "AgentPro activation – device $deviceId")
                )
            } catch (e: Exception) {
                Toast.makeText(this, "samirtwf@gmail.com", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<TextView>(R.id.trial_whatsapp).setOnClickListener {
            val text = Uri.encode("AgentPro activation request. My device ID: $deviceId")
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/966552486524?text=$text")))
            } catch (e: Exception) {
                Toast.makeText(this, "+966552486524", Toast.LENGTH_LONG).show()
            }
        }

        val status = findViewById<TextView>(R.id.trial_status)
        findViewById<Button>(R.id.trial_activate).setOnClickListener {
            val code = findViewById<EditText>(R.id.trial_code_input).text.toString()
            if (code.isBlank()) {
                status.text = getString(R.string.ap_paste_activation_code_first)
                return@setOnClickListener
            }
            if (LicenseManager.activate(this, code)) {
                Toast.makeText(this, R.string.ap_toast_activated_thanks, Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
                finish()
            } else {
                status.text = getString(R.string.ap_code_not_valid_for_device)
            }
        }

        findViewById<Button>(R.id.trial_exit).setOnClickListener {
            if (voluntary) finish() else finishAffinity()
        }
    }

    @Deprecated("Block back navigation into the locked app")
    override fun onBackPressed() {
        // Voluntary (from Settings) → just return to the app; locked (expired) → close the app.
        if (voluntary) finish() else finishAffinity()
    }
}
