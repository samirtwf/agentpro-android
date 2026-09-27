package org.linphone.ui.main.fragment

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.Account
import org.linphone.ui.assistant.AssistantActivity
import org.linphone.ui.main.AccountScope
import org.linphone.ui.main.AccountSwitcher
import org.linphone.ui.main.campaign.CampaignDialer
import org.linphone.ui.main.panel.AmiClient

/**
 * AgentPro Accounts manager (Settings ▸ Accounts). Lists every SIP account; tapping one makes it the
 * active account and applies its account + its own PBX settings everywhere (via [AccountSwitcher]).
 * You can add a new account (assistant) or remove one. Rows are built programmatically.
 */
@UiThread
class AccountsListFragment : Fragment() {
    private lateinit var container: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.accounts_list_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<ImageView>(R.id.accounts_back).setOnClickListener {
            findNavController().popBackStack()
        }
        view.findViewById<TextView>(R.id.accounts_add).setOnClickListener {
            startActivity(Intent(requireActivity(), AssistantActivity::class.java))
        }
        container = view.findViewById(R.id.accounts_container)
    }

    override fun onResume() {
        super.onResume()
        populate()
    }

    private data class Row(val account: Account, val title: String, val sub: String, val active: Boolean)

    private fun populate() {
        coreContext.postOnCoreThread { core ->
            val rows = ArrayList<Row>()
            val def = core.defaultAccount
            for (account in core.accountList) {
                val id = account.params.identityAddress?.asStringUriOnly()?.removePrefix("sip:") ?: "?"
                val server = account.params.serverAddress?.asStringUriOnly()?.removePrefix("sip:") ?: ""
                rows.add(Row(account, id, server, account == def))
            }
            coreContext.postOnMainThread {
                if (!isAdded) return@postOnMainThread
                container.removeAllViews()
                for (row in rows) addRow(row)
            }
        }
    }

    private fun addRow(data: Row) {
        val ctx = requireContext()
        val density = resources.displayMetrics.density

        fun px(value: Int) = (value * density).toInt()

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.squircle_transparent_button_background)
            setPadding(px(14), px(10), px(8), px(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = px(8) }
            isClickable = true
            setOnClickListener {
                if (!data.active) AccountSwitcher.switch(requireActivity(), data.account)
            }
        }

        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(
            TextView(ctx).apply {
                text = data.title
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
            }
        )
        texts.addView(
            TextView(ctx).apply {
                text = if (data.active) getString(R.string.ap_account_active_fmt, data.sub) else data.sub
                setTextColor(if (data.active) 0xFF2ECC71.toInt() else 0xFF7C8696.toInt())
                textSize = 12f
            }
        )
        row.addView(texts)

        row.addView(
            TextView(ctx).apply {
                text = "✕"
                setTextColor(0xFFE05B5B.toInt())
                textSize = 18f
                setPadding(px(16), px(6), px(12), px(6))
                isClickable = true
                setOnClickListener { confirmDelete(data.account, data.title) }
            }
        )

        container.addView(row)
    }

    private fun confirmDelete(account: Account, title: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ap_remove_account_title)
            .setMessage(getString(R.string.ap_remove_account_message_fmt, title))
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(R.string.dialog_remove) { _, _ ->
                coreContext.postOnCoreThread { core ->
                    val wasDefault = core.defaultAccount == account
                    core.removeAccount(account)
                    if (wasDefault && core.accountList.isNotEmpty()) {
                        core.defaultAccount = core.accountList.first()
                    }
                    val newDefaultId = core.defaultAccount?.params?.identityAddress?.asStringUriOnly()
                    coreContext.postOnMainThread {
                        if (isAdded) {
                            if (wasDefault) {
                                // The active account was removed: stop its auto-dialer + drop its PBX
                                // session before re-pointing the stores at the new default account.
                                CampaignDialer.stop()
                                AmiClient.disconnect()
                                AmiClient.setAgentInterface("")
                            }
                            AccountScope.activate(requireContext(), newDefaultId)
                            populate()
                        }
                    }
                }
            }
            .show()
    }
}
