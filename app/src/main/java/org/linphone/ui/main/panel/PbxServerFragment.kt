package org.linphone.ui.main.panel

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.RegistrationState
import org.linphone.R
import org.linphone.ui.assistant.AssistantActivity
import org.linphone.ui.main.cdr.UcmApiClient
import org.linphone.ui.main.settings.fragment.AccountProfileFragmentDirections
import org.linphone.utils.enablePasswordToggle

/** PBX (Asterisk Manager Interface) connection settings — edit / test / save. */
@UiThread
class PbxServerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.pbx_server_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // AgentPro "locked build": PBX/AMI/UCM settings and account management are embedded &
        // immutable. The menu entry is hidden, but close the page defensively if reached anyway.
        if (corePreferences.accountLocked) {
            findNavController().popBackStack()
            return
        }

        val host = view.findViewById<EditText>(R.id.pbx_host)
        val port = view.findViewById<EditText>(R.id.pbx_port)
        val user = view.findViewById<EditText>(R.id.pbx_user)
        val pass = view.findViewById<EditText>(R.id.pbx_pass)
        val listen = view.findViewById<EditText>(R.id.pbx_listen)
        val whisper = view.findViewById<EditText>(R.id.pbx_whisper)
        val barge = view.findViewById<EditText>(R.id.pbx_barge)
        val apiPort = view.findViewById<EditText>(R.id.pbx_api_port)
        val apiUser = view.findViewById<EditText>(R.id.pbx_api_user)
        val apiPass = view.findViewById<EditText>(R.id.pbx_api_pass)
        val webUser = view.findViewById<EditText>(R.id.pbx_web_user)
        val webPass = view.findViewById<EditText>(R.id.pbx_web_pass)

        val cfg = PbxPrefs.load(requireContext())
        if (cfg.host.isNotEmpty()) host.setText(cfg.host)
        port.setText(cfg.port.toString())
        if (cfg.username.isNotEmpty()) user.setText(cfg.username)
        if (cfg.password.isNotEmpty()) pass.setText(cfg.password)
        listen.setText(PbxPrefs.listenCode(requireContext()))
        whisper.setText(PbxPrefs.whisperCode(requireContext()))
        barge.setText(PbxPrefs.bargeCode(requireContext()))
        apiPort.setText(PbxPrefs.apiPort(requireContext()).toString())
        apiUser.setText(PbxPrefs.apiUser(requireContext()))
        apiPass.setText(PbxPrefs.apiPass(requireContext()))
        webUser.setText(PbxPrefs.webUser(requireContext()))
        webPass.setText(PbxPrefs.webPass(requireContext()))

        // Tap-to-reveal eye on every password field so the user can verify what they type.
        pass.enablePasswordToggle()
        apiPass.enablePasswordToggle()
        webPass.enablePasswordToggle()

        // Banner: spell out which account these (per-account) PBX settings are saved for, so a
        // multi-account user doesn't enter a second PBX over the first account's settings.
        val accountBanner = view.findViewById<TextView>(R.id.pbx_account_banner)
        coreContext.postOnCoreThread { core ->
            val id = core.defaultAccount?.params?.identityAddress?.asStringUriOnly().orEmpty()
                .removePrefix("sips:").removePrefix("sip:")
            coreContext.postOnMainThread {
                accountBanner.text = id.ifEmpty { getString(R.string.ap_no_account_selected) }
            }
        }

        view.findViewById<ImageView>(R.id.pbx_back).setOnClickListener {
            findNavController().popBackStack()
        }

        // SIP account management (moved here from the removed side-drawer).
        view.findViewById<TextView>(R.id.pbx_account_manage).setOnClickListener {
            coreContext.postOnCoreThread { core ->
                val identity = core.defaultAccount?.params?.identityAddress?.asStringUriOnly().orEmpty()
                coreContext.postOnMainThread {
                    if (identity.isEmpty()) {
                        toast(getString(R.string.ap_toast_no_sip_account_yet))
                    } else {
                        try {
                            findNavController().navigate(
                                AccountProfileFragmentDirections.actionGlobalAccountProfileFragment(identity)
                            )
                        } catch (e: Exception) {
                            org.linphone.core.tools.Log.e("[Settings] Cannot open account profile: $e")
                        }
                    }
                }
            }
        }

        view.findViewById<TextView>(R.id.pbx_account_add).setOnClickListener {
            startActivity(Intent(requireActivity(), AssistantActivity::class.java))
        }

        fun read() = PbxConfig(
            host = host.text.toString().trim(),
            port = port.text.toString().trim().toIntOrNull() ?: 0,
            username = user.text.toString().trim(),
            password = pass.text.toString()
        )

        view.findViewById<TextView>(R.id.pbx_save).setOnClickListener {
            val c = read()
            if (!c.isValid()) {
                toast(getString(R.string.ap_toast_enter_host_port_user))
                return@setOnClickListener
            }
            PbxPrefs.save(requireContext(), c)
            PbxPrefs.setSpyCodes(
                requireContext(),
                listen.text.toString().trim(),
                whisper.text.toString().trim(),
                barge.text.toString().trim()
            )
            PbxPrefs.setApi(
                requireContext(),
                apiPort.text.toString().trim().toIntOrNull() ?: 8089,
                apiUser.text.toString().trim(),
                apiPass.text.toString()
            )
            PbxPrefs.setWeb(
                requireContext(),
                webUser.text.toString().trim(),
                webPass.text.toString()
            )
            AmiClient.connect(c)
            toast(getString(R.string.ap_saved))
            findNavController().popBackStack()
        }

        val statusSip = view.findViewById<TextView>(R.id.pbx_status_sip)
        val statusAmi = view.findViewById<TextView>(R.id.pbx_status_ami)
        val statusApi = view.findViewById<TextView>(R.id.pbx_status_api)
        val statusWeb = view.findViewById<TextView>(R.id.pbx_status_web)

        view.findViewById<TextView>(R.id.pbx_test).setOnClickListener {
            val c = read()
            val hostStr = host.text.toString().trim()
            val apiPortNum = apiPort.text.toString().trim().toIntOrNull() ?: 8089
            val apiUserStr = apiUser.text.toString().trim()
            val apiPassStr = apiPass.text.toString()
            for (s in listOf(statusSip, statusAmi, statusApi, statusWeb)) setStatus(s, getString(R.string.ap_testing), null)

            // SIP account registration state (read on the Core thread).
            coreContext.postOnCoreThread { core ->
                val ok = core.defaultAccount?.state == RegistrationState.Ok
                coreContext.postOnMainThread {
                    if (isAdded) setStatus(statusSip, getString(if (ok) R.string.ap_registered else R.string.ap_not_registered), ok)
                }
            }

            // AMI socket login, then HTTPS API login, then web-GUI reachability.
            viewLifecycleOwner.lifecycleScope.launch {
                val amiOk = withContext(Dispatchers.IO) { testConnection(c).startsWith("Connection OK") }
                setStatus(statusAmi, getString(if (amiOk) R.string.ap_connected else R.string.ap_failed), amiOk)

                if (hostStr.isEmpty()) {
                    setStatus(statusApi, getString(R.string.ap_no_host), false)
                    setStatus(statusWeb, getString(R.string.ap_no_host), false)
                    return@launch
                }
                val apiOk = withContext(Dispatchers.IO) {
                    UcmApiClient.login(hostStr, apiPortNum, apiUserStr, apiPassStr) != null
                }
                setStatus(statusApi, getString(if (apiOk) R.string.ap_connected else R.string.ap_failed), apiOk)

                val webOk = withContext(Dispatchers.IO) { UcmApiClient.isReachable(hostStr, apiPortNum) }
                setStatus(statusWeb, getString(if (webOk) R.string.ap_reachable else R.string.ap_unreachable), webOk)
            }
        }
    }

    private fun setStatus(tv: TextView, text: String, ok: Boolean?) {
        tv.text = text
        tv.setTextColor(
            when (ok) {
                true -> android.graphics.Color.parseColor("#2ECC71")
                false -> android.graphics.Color.parseColor("#E74C3C")
                else -> android.graphics.Color.parseColor("#6E7681")
            }
        )
    }

    private fun toast(m: String) = Toast.makeText(requireContext(), m, Toast.LENGTH_SHORT).show()

    private fun testConnection(cfg: PbxConfig): String {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(cfg.host, cfg.port), 6000)
                s.soTimeout = 6000
                val out = s.getOutputStream()
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                reader.readLine() // banner
                out.write(
                    ("Action: Login\r\nUsername: ${cfg.username}\r\nSecret: ${cfg.password}\r\nEvents: off\r\n\r\n")
                        .toByteArray(Charsets.UTF_8)
                )
                out.flush()
                var success = false
                var message = ""
                var sawResponse = false
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!
                    if (l.isEmpty()) {
                        if (sawResponse) break else continue
                    }
                    val low = l.lowercase()
                    if (low.startsWith("response:")) {
                        sawResponse = true
                        success = low.contains("success")
                    } else if (low.startsWith("message:")) {
                        message = l.substringAfter(":").trim()
                    }
                }
                if (success) "Connection OK" else "Login failed" + if (message.isNotEmpty()) ": $message" else ""
            }
        } catch (e: Exception) {
            "Failed: ${e.message}"
        }
    }
}
