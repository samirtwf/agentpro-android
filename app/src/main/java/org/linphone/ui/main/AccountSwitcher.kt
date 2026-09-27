package org.linphone.ui.main

import androidx.annotation.UiThread
import androidx.fragment.app.FragmentActivity
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.core.Account
import org.linphone.core.tools.Log
import org.linphone.ui.main.campaign.CampaignDialer
import org.linphone.ui.main.panel.AmiClient

/**
 * Switches the active SIP account **everywhere**: sets it as the linphone default account, points
 * [AccountScope] at that account's own data (PBX/AMI/UCM/Agent settings, dispositions, Campaign +
 * Contacts lists, dialer delay, speed-dials), drops the PBX backend tied to the old account, then
 * recreates the activity so every screen (dialpad, Panel, Disp, Campaign, Contacts, CDR, Agent,
 * Wallboard, Settings) re-reads the new account's account + data live, with no app restart.
 */
object AccountSwitcher {
    private const val TAG = "[Account Switcher]"

    @UiThread
    fun switch(activity: FragmentActivity, account: Account) {
        coreContext.postOnCoreThread { core ->
            if (core.defaultAccount != account) {
                core.defaultAccount = account
                Log.i("$TAG Switched default account to [${account.params.identityAddress?.asStringUriOnly()}]")
            }
            val identity = account.params.identityAddress?.asStringUriOnly()
            coreContext.postOnMainThread {
                // Tear down the PBX backend + auto-dialer of the account being left FIRST (while the
                // scope still points at it, so any final dialer save lands in the old account's file):
                // stop the auto-dial loop and drop the old AMI session — the Panel/Agent/Wallboard
                // then re-login with the NEW account's PBX credentials when next shown.
                CampaignDialer.stop()
                AmiClient.disconnect()
                AmiClient.setAgentInterface("")
                // Now re-point every per-account store at the new account.
                AccountScope.activate(activity, identity)
                try {
                    activity.recreate()
                } catch (e: Exception) {
                    Log.e("$TAG Can't recreate activity: $e")
                }
            }
        }
    }
}
