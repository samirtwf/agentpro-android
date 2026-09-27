package org.linphone.ui.main.wallboard

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import org.linphone.R
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.databinding.WallboardFragmentBinding
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.panel.AmiClient
import org.linphone.ui.main.panel.PbxPrefs
import org.linphone.ui.main.panel.QueueStat
import org.linphone.ui.main.viewmodel.AbstractMainViewModel

/**
 * Wallboard: a live call-center dashboard. Polls AMI QueueSummary ([AmiClient]) every few seconds
 * and shows one card per queue — callers waiting, agents logged in / available, longest wait and
 * average talk time. Reuses the shared AMI connection (same as the Panel / Agent tabs).
 */
@UiThread
class WallboardFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Wallboard]"
        private const val POLL_MS = 4000L
    }

    private lateinit var binding: WallboardFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel
    private lateinit var adapter: WallboardAdapter

    private val pollHandler = Handler(Looper.getMainLooper())
    private var lastConnectAttempt = 0L
    private val pollTick = object : Runnable {
        override fun run() {
            if (AmiClient.isConnected()) {
                AmiClient.queueSummary()
            } else {
                // Reconnect gently (at most every 20s) — never hammer the PBX every poll, or its
                // anti-flood protection will block this device's IP.
                val now = SystemClock.elapsedRealtime()
                if (now - lastConnectAttempt > 20_000) {
                    lastConnectAttempt = now
                    connectIfConfigured()
                }
            }
            pollHandler.postDelayed(this, POLL_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = WallboardFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel
        mainViewModel.title.value = getString(R.string.ap_wallboard)
        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(dummySlidingPane, dummyTopBar, binding.bottomNavBar, R.id.wallboardFragment)

        adapter = WallboardAdapter()
        binding.wbRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.wbRecycler.adapter = adapter

        binding.wbRefresh.setOnClickListener {
            if (AmiClient.isConnected()) AmiClient.queueSummary() else connectIfConfigured()
        }

        AmiClient.connected.observe(viewLifecycleOwner) { c ->
            binding.wbStatus.setTextColor(
                if (c == true) Color.parseColor("#2ECC71") else Color.parseColor("#6E7681")
            )
        }
        AmiClient.statusMessage.observe(viewLifecycleOwner) { msg ->
            binding.wbStatus.text = if (msg.isNullOrEmpty()) getString(R.string.ap_disconnected) else msg
        }
        AmiClient.queueStats.observe(viewLifecycleOwner) { render(it ?: emptyMap()) }
        AmiClient.errorEvent.observe(viewLifecycleOwner) { event ->
            event?.consume { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
        }
    }

    override fun onResume() {
        super.onResume()
        render(AmiClient.queueStats.value ?: emptyMap())
        lastConnectAttempt = SystemClock.elapsedRealtime()
        connectIfConfigured()
        pollHandler.removeCallbacks(pollTick)
        pollHandler.post(pollTick)
    }

    override fun onPause() {
        pollHandler.removeCallbacks(pollTick)
        super.onPause()
    }

    override fun onDefaultAccountChanged() {}

    override fun onDestroyView() {
        pollHandler.removeCallbacks(pollTick)
        super.onDestroyView()
    }

    private fun connectIfConfigured() {
        val ctx = requireContext()
        if (PbxPrefs.isConfigured(ctx)) {
            if (!AmiClient.isConnected()) AmiClient.connect(PbxPrefs.load(ctx)) else AmiClient.queueSummary()
        } else {
            binding.wbStatus.text = getString(R.string.ap_not_configured_set_pbx_panel)
        }
    }

    private fun render(stats: Map<String, QueueStat>) {
        val list = stats.values.sortedBy { it.queue.toIntOrNull() ?: Int.MAX_VALUE }
        adapter.submit(list)

        val empty = list.isEmpty()
        binding.wbRecycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.wbEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.wbEmpty.text = getString(
            if (PbxPrefs.isConfigured(requireContext())) {
                R.string.ap_no_active_queues_found_on_the_pbx
            } else {
                R.string.ap_connect_to_your_pbx_to_see_live_queues
            }
        )
    }
}
