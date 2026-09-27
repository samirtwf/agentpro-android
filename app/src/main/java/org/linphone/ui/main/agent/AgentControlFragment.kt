package org.linphone.ui.main.agent

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.databinding.AgentControlFragmentBinding
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.panel.AmiClient
import org.linphone.ui.main.panel.PbxPrefs
import org.linphone.ui.main.viewmodel.AbstractMainViewModel

/**
 * Agent tab: lets a call-center agent log into / out of Asterisk call queues (one button per
 * queue), pause/unpause, and see live state — driven by AMI (QueueAdd / QueueRemove / QueuePause
 * via [AmiClient]). The member interface is "<protocol>/<ext>" (e.g. PJSIP/5555); nothing is
 * hard-coded — extension, protocol and queue names are entered by the user and persisted.
 */
@UiThread
class AgentControlFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Agent Control]"
        private val PROTOCOLS = listOf("PJSIP", "SIP")
    }

    private lateinit var binding: AgentControlFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel
    private lateinit var adapter: AgentQueueAdapter

    private val uiHandler = Handler(Looper.getMainLooper())
    private val extRunnable = Runnable { updateInterface() }

    private var timerRunning = false
    private var loginBaseElapsed = 0L
    private val timerTick = object : Runnable {
        override fun run() {
            val secs = (SystemClock.elapsedRealtime() - loginBaseElapsed) / 1000
            binding.agentTimer.text = formatHms(secs)
            uiHandler.postDelayed(this, 1000)
        }
    }

    private val pauseListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        AmiClient.queuePauseAll(checked)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AgentControlFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel
        mainViewModel.title.value = getString(R.string.ap_agent)
        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(dummySlidingPane, dummyTopBar, binding.bottomNavBar, R.id.agentFragment)

        adapter = AgentQueueAdapter(
            onToggle = { queue, join -> onQueueToggle(queue, join) },
            onDelete = { queue -> onQueueDelete(queue) }
        )
        binding.agentRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.agentRecycler.adapter = adapter

        // Extension field (white text already; persisted + interface updated on change).
        binding.agentExt.setText(PbxPrefs.agentExt(requireContext()))
        binding.agentExt.doAfterTextChanged {
            uiHandler.removeCallbacks(extRunnable)
            uiHandler.postDelayed(extRunnable, 700)
        }

        // Protocol spinner (white selected text on the dark card).
        val spinnerAdapter = object :
            ArrayAdapter<String>(requireContext(), android.R.layout.simple_spinner_item, PROTOCOLS) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                return v
            }
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.agentProto.adapter = spinnerAdapter
        binding.agentProto.setSelection(PROTOCOLS.indexOf(PbxPrefs.agentProtocol(requireContext())).coerceAtLeast(0))
        binding.agentProto.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = updateInterface()

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.agentPause.setOnCheckedChangeListener(pauseListener)
        binding.agentRefresh.setOnClickListener {
            if (AmiClient.isConnected()) AmiClient.queryQueues() else connectIfConfigured()
        }
        binding.agentAdd.setOnClickListener { showAddQueueDialog() }

        AmiClient.connected.observe(viewLifecycleOwner) { c ->
            binding.agentStatus.setTextColor(
                if (c == true) Color.parseColor("#2ECC71") else Color.parseColor("#6E7681")
            )
        }
        AmiClient.statusMessage.observe(viewLifecycleOwner) { msg ->
            binding.agentStatus.text = if (msg.isNullOrEmpty()) getString(R.string.ap_disconnected) else msg
        }
        AmiClient.agentQueues.observe(viewLifecycleOwner) { rebuildRows(it ?: emptyMap()) }
        AmiClient.errorEvent.observe(viewLifecycleOwner) { event ->
            event?.consume { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }

        updateInterface()

        // Prefill the extension from the registered SIP account if the user hasn't set one.
        if (binding.agentExt.text.isNullOrBlank()) {
            coreContext.postOnCoreThread { core ->
                val ext = core.defaultAccount?.params?.identityAddress?.username.orEmpty()
                coreContext.postOnMainThread {
                    if (isAdded && view.parent != null && binding.agentExt.text.isNullOrBlank() && ext.isNotBlank()) {
                        binding.agentExt.setText(ext)
                        updateInterface()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rebuildRows(AmiClient.agentQueues.value ?: emptyMap())
        connectIfConfigured()
    }

    override fun onDefaultAccountChanged() {}

    override fun onDestroyView() {
        uiHandler.removeCallbacks(timerTick)
        uiHandler.removeCallbacks(extRunnable)
        super.onDestroyView()
    }

    private fun connectIfConfigured() {
        val ctx = requireContext()
        if (PbxPrefs.isConfigured(ctx)) {
            if (!AmiClient.isConnected()) AmiClient.connect(PbxPrefs.load(ctx)) else AmiClient.queryQueues()
        } else {
            binding.agentStatus.text = getString(R.string.ap_not_configured_set_pbx_panel)
        }
    }

    /** Persist ext + protocol and push the member interface to [AmiClient]. */
    private fun updateInterface() {
        val ctx = requireContext()
        val ext = binding.agentExt.text.toString().trim()
        val proto = binding.agentProto.selectedItem?.toString() ?: "PJSIP"
        PbxPrefs.setAgent(ctx, ext, proto)
        AmiClient.setAgentInterface(if (ext.isNotBlank()) "$proto/$ext" else "")
    }

    private fun rebuildRows(membership: Map<String, Boolean>) {
        val queues = PbxPrefs.agentQueues(requireContext())
        adapter.submit(
            queues.map { AgentQueueAdapter.Row(it, membership.containsKey(it), membership[it] == true) }
        )

        val empty = queues.isEmpty()
        binding.agentRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.agentEmpty.visibility = if (empty) View.VISIBLE else View.GONE

        val loggedIn = membership.isNotEmpty()
        binding.agentState.text = if (loggedIn) getString(R.string.ap_logged_in) else getString(R.string.ap_logged_out)
        binding.agentState.setTextColor(Color.parseColor(if (loggedIn) "#2ECC71" else "#C7CDD6"))
        if (loggedIn) startTimer() else stopTimer()

        binding.agentPause.isEnabled = loggedIn
        setPauseChecked(loggedIn && membership.values.all { it })
    }

    private fun setPauseChecked(checked: Boolean) {
        val sw = binding.agentPause
        if (sw.isChecked == checked) return
        sw.setOnCheckedChangeListener(null)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener(pauseListener)
    }

    private fun onQueueToggle(queue: String, join: Boolean) {
        if (join) AmiClient.queueLogin(queue, binding.agentPause.isChecked) else AmiClient.queueLogout(queue)
        // Some PBXs don't emit a member event — re-query shortly after to refresh the row.
        binding.agentRecycler.postDelayed({ AmiClient.queryQueues() }, 1200)
    }

    private fun onQueueDelete(queue: String) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.ap_remove_queue_title_fmt, queue))
            .setMessage(R.string.ap_remove_queue_message)
            .setPositiveButton(R.string.dialog_remove) { _, _ ->
                if ((AmiClient.agentQueues.value ?: emptyMap()).containsKey(queue)) AmiClient.queueLogout(queue)
                val list = PbxPrefs.agentQueues(ctx)
                if (list.remove(queue)) PbxPrefs.setAgentQueues(ctx, list)
                rebuildRows(AmiClient.agentQueues.value ?: emptyMap())
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showAddQueueDialog() {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(ctx).apply {
            hint = getString(R.string.ap_hint_queue)
            setSingleLine()
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.ap_add_queue)
            .setView(input)
            .setPositiveButton(R.string.ap_add) { _, _ ->
                val q = input.text.toString().trim()
                if (q.isNotEmpty()) {
                    val list = PbxPrefs.agentQueues(ctx)
                    if (!list.contains(q)) {
                        list.add(q)
                        PbxPrefs.setAgentQueues(ctx, list)
                    }
                    rebuildRows(AmiClient.agentQueues.value ?: emptyMap())
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun startTimer() {
        if (timerRunning) return
        timerRunning = true
        loginBaseElapsed = SystemClock.elapsedRealtime()
        uiHandler.post(timerTick)
    }

    private fun stopTimer() {
        timerRunning = false
        uiHandler.removeCallbacks(timerTick)
        binding.agentTimer.text = "00:00:00"
    }

    private fun formatHms(totalSecs: Long): String {
        val s = totalSecs.coerceAtLeast(0)
        return "%02d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    }
}
