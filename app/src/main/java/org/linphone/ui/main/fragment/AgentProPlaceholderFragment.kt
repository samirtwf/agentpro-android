package org.linphone.ui.main.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import org.linphone.R
import org.linphone.databinding.AgentProPlaceholderFragmentBinding
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.ui.main.viewmodel.AbstractMainViewModel

/**
 * Shared screen used by the AgentPro call-center sections that don't have a
 * dedicated feature yet (Disposition, Campaign, Panel, CDR, Agent, Wallboard).
 * The title and icon are resolved from the navigation destination that opened it,
 * and the shared bottom navigation bar lets the user move between sections.
 */
@UiThread
class AgentProPlaceholderFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[AgentPro Placeholder Fragment]"
    }

    private lateinit var binding: AgentProPlaceholderFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AgentProPlaceholderFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel

        val destinationId = findNavController().currentDestination?.id ?: R.id.panelFragment
        val (title, icon) = sectionInfo(destinationId)
        binding.featureTitle.text = title
        binding.featureIcon.setImageResource(icon)
        mainViewModel.title.value = title

        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(
            dummySlidingPane,
            dummyTopBar,
            binding.bottomNavBar,
            destinationId
        )
    }

    override fun onDefaultAccountChanged() {
        // No-op
    }

    private fun sectionInfo(destinationId: Int): Pair<String, Int> {
        return when (destinationId) {
            R.id.dispFragment -> getString(R.string.ap_title_disposition) to R.drawable.calendar
            R.id.campaignFragment -> getString(R.string.ap_campaign) to R.drawable.list
            R.id.panelFragment -> getString(R.string.ap_title_agent_panel) to R.drawable.squares_four
            R.id.cdrFragment -> getString(R.string.ap_title_call_detail_records) to R.drawable.info
            R.id.agentFragment -> getString(R.string.ap_agent) to R.drawable.headset
            R.id.wallboardFragment -> getString(R.string.ap_wallboard) to R.drawable.monitor
            else -> "AgentPro" to R.drawable.squares_four
        }
    }
}
