package org.linphone.ui.main.panel

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.ui.main.cdr.UcmApiClient
import org.linphone.databinding.ExtensionPanelFragmentBinding
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.viewmodel.AbstractMainViewModel

/**
 * Extension Panel: a live grid of PBX extensions (Available / In call / Offline) fed by AMI
 * events ([AmiClient]), with Listen / Whisper / Barge supervision on in-call extensions.
 */
@UiThread
class ExtensionPanelFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Extension Panel]"
    }

    private lateinit var binding: ExtensionPanelFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel
    private lateinit var adapter: ExtensionPanelAdapter

    private var myExt = ""

    // True once the UCM monitor allow-list has been fetched this session. Until then we don't
    // auto-discover (which would briefly flash every extension) if the PBX API is configured.
    private var allowlistSynced = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = ExtensionPanelFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel
        mainViewModel.title.value = getString(R.string.ap_extension_panel)
        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(dummySlidingPane, dummyTopBar, binding.bottomNavBar, R.id.panelFragment)

        adapter = ExtensionPanelAdapter(
            onCard = { showCardOptions(it) },
            onListen = { spy(it, "") },
            onWhisper = { spy(it, "w") },
            onBarge = { spy(it, "B") }
        )
        binding.panelRecycler.layoutManager = StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        binding.panelRecycler.adapter = adapter

        binding.panelSettingsBtn.setOnClickListener { openPbxSettings() }
        binding.panelRefresh.setOnClickListener {
            fetchAllowlist()
            if (AmiClient.isConnected()) {
                AmiClient.refresh()
            } else {
                connectIfConfigured()
            }
        }
        binding.panelAdd.setOnClickListener { showAddDialog() }

        AmiClient.connected.observe(viewLifecycleOwner) { connected ->
            binding.panelStatus.setTextColor(
                if (connected == true) Color.parseColor("#2ECC71") else Color.parseColor("#6E7681")
            )
        }
        AmiClient.statusMessage.observe(viewLifecycleOwner) { msg ->
            binding.panelStatus.text = if (msg.isNullOrEmpty()) getString(R.string.ap_disconnected) else msg
        }
        AmiClient.states.observe(viewLifecycleOwner) { rebuildGrid(it) }
        AmiClient.errorEvent.observe(viewLifecycleOwner) { event ->
            event?.consume { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }

        // Resolve the supervisor's own extension for ChanSpy origination.
        coreContext.postOnCoreThread { core ->
            val ext = core.defaultAccount?.params?.identityAddress?.username.orEmpty()
            coreContext.postOnMainThread {
                myExt = ext
                org.linphone.core.tools.Log.i("$TAG Supervisor extension = '$ext'")
                // Now that we know our own extension, pull its monitor allow-list from the UCM.
                fetchAllowlist()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildGrid(AmiClient.states.value ?: emptyMap())
        connectIfConfigured()
    }

    override fun onDefaultAccountChanged() {}

    private fun connectIfConfigured() {
        if (PbxPrefs.isConfigured(requireContext())) {
            if (!AmiClient.isConnected()) AmiClient.connect(PbxPrefs.load(requireContext()))
            fetchAllowlist()
        } else {
            binding.panelStatus.text = getString(R.string.ap_not_configured)
        }
    }

    /**
     * Pulls THIS extension's "Call Monitoring Allowlist" (callbarging_monitor) from the UCM and caches
     * it, so the panel shows only the extensions it is allowed to monitor. Runs off the main thread;
     * leaves the cached list untouched on any failure.
     */
    private fun fetchAllowlist() {
        val ctx = requireContext()
        val ext = myExt
        if (!PbxPrefs.isConfigured(ctx) || ext.isBlank()) return
        Thread {
            val host = PbxPrefs.load(ctx).host
            val cookie = UcmApiClient.login(host, PbxPrefs.apiPort(ctx), PbxPrefs.apiUser(ctx), PbxPrefs.apiPass(ctx))
                ?: return@Thread
            val list = UcmApiClient.getMonitorAllowlist(host, PbxPrefs.apiPort(ctx), cookie, ext) ?: return@Thread
            PbxPrefs.setAllowlist(ctx, list)
            activity?.runOnUiThread {
                allowlistSynced = true
                if (isAdded) rebuildGrid(AmiClient.states.value ?: emptyMap())
            }
        }.start()
    }

    private fun rebuildGrid(states: Map<String, ExtState>) {
        val ctx = requireContext()
        val allow = PbxPrefs.allowlist(ctx)
        val hidden = PbxPrefs.hidden(ctx).toSet()
        // Waiting for the UCM allow-list? (PBX API configured, nothing cached, not synced this session)
        val awaitingSync = allow.isEmpty() && !allowlistSynced && PbxPrefs.apiUser(ctx).isNotBlank()
        val exts = when {
            // ALLOW-LIST mode: the UCM "Call Monitoring Allowlist" for THIS extension — show only the
            // extensions it may monitor, minus any the user removed locally.
            allow.isNotEmpty() ->
                allow.filter { it !in hidden }.distinct().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
            // Don't flash every extension while the allow-list is still loading.
            awaitingSync -> emptyList()
            // Synced with no list ("All" mode), or no PBX API → auto-discover everything.
            else ->
                states.keys.filter { it !in hidden }.distinct().sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
        }
        val items = exts.map { PanelItem(it, states[it] ?: ExtState.OFFLINE) }
        adapter.submit(items)

        val empty = items.isEmpty()
        binding.panelRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.panelEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.panelEmptyText.text = getString(
            when {
                !PbxPrefs.isConfigured(ctx) -> R.string.ap_connect_to_your_pbx_to_see_extensions
                awaitingSync -> R.string.ap_loading_the_extensions_you_may_monitor
                else -> R.string.ap_no_extensions_to_show
            }
        )
    }

    private fun spy(targetExt: String, mode: String) {
        if (targetExt.isBlank()) return
        if (targetExt == myExt) {
            Toast.makeText(requireContext(), getString(R.string.ap_toast_cannot_spy_own_ext), Toast.LENGTH_SHORT).show()
            return
        }
        val ctx = requireContext()
        // Dial the UCM "Call Barging" feature code + target extension (e.g. *541000).
        val (code, action) = when (mode) {
            "w" -> PbxPrefs.whisperCode(ctx) to getString(R.string.ap_whisper)
            "B" -> PbxPrefs.bargeCode(ctx) to getString(R.string.ap_barge)
            else -> PbxPrefs.listenCode(ctx) to getString(R.string.ap_listen)
        }
        val number = code + targetExt
        coreContext.postOnCoreThread { core ->
            val address = core.interpretUrl(number, false)
            if (address != null) coreContext.startAudioCall(address)
        }
        Toast.makeText(ctx, getString(R.string.ap_spy_action_fmt, action, targetExt, number), Toast.LENGTH_SHORT).show()
    }

    private fun showCardOptions(ext: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.ap_extension_title_fmt, ext))
            .setItems(arrayOf(getString(R.string.ap_call_ext_fmt, ext), getString(R.string.ap_delete_from_panel))) { _, which ->
                when (which) {
                    0 -> callExt(ext)
                    1 -> deleteExt(ext)
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun callExt(ext: String) {
        if (ext.isBlank()) return
        coreContext.postOnCoreThread { core ->
            val address = core.interpretUrl(ext, false)
            if (address != null) coreContext.startAudioCall(address)
        }
    }

    private fun deleteExt(ext: String) {
        val ctx = requireContext()
        val monitored = PbxPrefs.monitored(ctx)
        if (monitored.remove(ext)) PbxPrefs.setMonitored(ctx, monitored)
        val hidden = PbxPrefs.hidden(ctx)
        if (!hidden.contains(ext)) {
            hidden.add(ext)
            PbxPrefs.setHidden(ctx, hidden)
        }
        rebuildGrid(AmiClient.states.value ?: emptyMap())
    }

    private fun openPbxSettings() {
        try {
            findNavController().navigate(R.id.pbxServerFragment)
        } catch (e: Exception) {
            org.linphone.core.tools.Log.e("$TAG Cannot open PBX settings: $e")
        }
    }

    private fun showAddDialog() {
        val ctx = requireContext()
        val allow = PbxPrefs.allowlist(ctx)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(ctx).apply {
            hint = getString(R.string.ap_hint_extension_range)
            // PHONE allows digits plus '-' and ',' so a range or list can be typed.
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.ap_show_removed_extension)
            .setView(input)
            .setPositiveButton(R.string.ap_add) { _, _ ->
                val toAdd = parseExtensions(input.text.toString())
                if (toAdd.isNotEmpty()) {
                    val hidden = PbxPrefs.hidden(ctx)
                    val rejected = mutableListOf<String>()
                    for (ext in toAdd) {
                        // Only extensions the PBX permits THIS one to monitor can be (re-)shown.
                        if (allow.isEmpty() || allow.contains(ext)) hidden.remove(ext) else rejected.add(ext)
                    }
                    PbxPrefs.setHidden(ctx, hidden)
                    if (rejected.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            getString(R.string.ap_not_permitted_ext_fmt, rejected.joinToString(", ")),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    rebuildGrid(AmiClient.states.value ?: emptyMap())
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    /** Parses "2024", a range "2024-2031", and lists "2024,2026,2030" (any mix) into extensions. */
    private fun parseExtensions(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }) {
            val range = token.split("-").map { it.trim() }
            val a = range.getOrNull(0)?.toIntOrNull()
            val b = range.getOrNull(1)?.toIntOrNull()
            if (range.size == 2 && a != null && b != null && a <= b && b - a <= 500) {
                for (n in a..b) out.add(n.toString())
            } else if (token.all { it.isDigit() }) {
                out.add(token)
            }
        }
        return out.toList()
    }
}
