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
package org.linphone.ui.assistant

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.annotation.UiThread
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.navigation.findNavController
import kotlin.math.max
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.compatibility.Compatibility
import org.linphone.core.tools.Log
import org.linphone.databinding.AssistantActivityBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.assistant.fragment.PermissionsFragmentDirections

@UiThread
class AssistantActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Assistant Activity]"

        const val SKIP_LANDING_EXTRA = "SkipLandingIfAtLeastAnAccount"

        // Set true to open the Linphone-account landing (login + create) instead of jumping straight
        // to the third-party SIP login form — used by the dialpad's "Add Linphone account" entry.
        const val LINPHONE_ACCOUNT_EXTRA = "ShowLinphoneAccountLanding"
    }

    private lateinit var binding: AssistantActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // AgentPro "locked build": the app is bound to a single provisioned account; the assistant
        // (account login/creation) must never be reachable. EXCEPTION: an "Ext-locked" build
        // (allow_linphone) may open the assistant ONLY to add a Linphone account (the dialpad passes
        // LINPHONE_ACCOUNT_EXTRA). Any other locked entry still bails out immediately.
        val linphoneAddInLocked = corePreferences.lockedAllowLinphone &&
            intent.getBooleanExtra(LINPHONE_ACCOUNT_EXTRA, false)
        if (corePreferences.accountLocked && !linphoneAddInLocked) {
            Log.w("$TAG Account is locked to a single provisioned account, closing assistant")
            finish()
            return
        }

        binding = DataBindingUtil.setContentView(this, R.layout.assistant_activity)
        binding.lifecycleOwner = this
        setUpToastsArea(binding.toastsArea)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            v.updatePadding(
                insets.left,
                insets.top,
                insets.right,
                max(insets.bottom, keyboard.bottom)
            )
            WindowInsetsCompat.CONSUMED
        }

        coreContext.postOnCoreThread { core ->
            if (core.accountList.isEmpty()) {
                Log.i("$TAG No account configured, disabling back gesture")
                coreContext.postOnMainThread {
                    // Disable back gesture / button
                    onBackPressedDispatcher.addCallback { }
                }
            }
        }

        coreContext.mdmConfigAppliedEvent.observe(this) {
            it.consume {
                Log.i("$TAG Managed configuration applied, checking for accounts")
                leaveAssistantIfAnAccountIsConfigured()
            }
        }

        coreContext.provisioningAppliedEvent.observe(this) {
            it.consume {
                Log.i("$TAG Provisioning applied, checking for accounts")
                leaveAssistantIfAnAccountIsConfigured()
            }
        }

        (binding.root as? ViewGroup)?.doOnPreDraw {
            if (!areAllPermissionsGranted()) {
                Log.w("$TAG Not all required permissions are granted, showing Permissions fragment")
                val action = PermissionsFragmentDirections.actionGlobalPermissionsFragment()
                binding.assistantNavContainer.findNavController().navigate(action)
            } else if (intent.getBooleanExtra(SKIP_LANDING_EXTRA, false)) {
                Log.w(
                    "$TAG We were asked to leave assistant if at least an account is already configured"
                )
                leaveAssistantIfAnAccountIsConfigured()
            }
        }
    }

    private fun areAllPermissionsGranted(): Boolean {
        for (permission in Compatibility.getAllRequiredPermissionsArray()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.w("$TAG Permission [$permission] hasn't been granted yet!")
                return false
            }
        }

        val granted = Compatibility.hasFullScreenIntentPermission(this)
        if (granted) {
            Log.i("$TAG All permissions have been granted!")
        }
        return granted
    }

    private fun leaveAssistantIfAnAccountIsConfigured() {
        coreContext.postOnCoreThread { core ->
            if (core.accountList.isNotEmpty()) {
                coreContext.postOnMainThread {
                    try {
                        Log.w("$TAG At least one account was found, leaving assistant")
                        finish()
                    } catch (ise: IllegalStateException) {
                        Log.e("$TAG Can't finish activity: $ise")
                    }
                }
            }
        }
    }
}
