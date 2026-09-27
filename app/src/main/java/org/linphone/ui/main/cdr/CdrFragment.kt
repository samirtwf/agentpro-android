package org.linphone.ui.main.cdr

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.CdrFragmentBinding
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.ui.main.campaign.XlsxUtils
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.panel.PbxPrefs
import org.linphone.ui.main.viewmodel.AbstractMainViewModel

/**
 * CDR tab: queries the Grandstream UCM call records over the HTTPS API ([UcmApiClient]),
 * with date-range + number search, Excel export, and playback of recordings stored on the PBX.
 */
@UiThread
class CdrFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[CDR Fragment]"
    }

    private lateinit var binding: CdrFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel
    private lateinit var adapter: CdrAdapter

    private val all = mutableListOf<CdrRecord>()
    private var query = ""
    private val fromCal = Calendar.getInstance()
    private val toCal = Calendar.getInstance()
    private val apiFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val btnFmt = SimpleDateFormat("MM-dd", Locale.US)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = CdrFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel
        mainViewModel.title.value = getString(R.string.ap_cdr)
        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(dummySlidingPane, dummyTopBar, binding.bottomNavBar, R.id.cdrFragment)

        // Default range: today 00:00 → 23:59
        fromCal.set(Calendar.HOUR_OF_DAY, 0); fromCal.set(Calendar.MINUTE, 0); fromCal.set(Calendar.SECOND, 0)
        toCal.set(Calendar.HOUR_OF_DAY, 23); toCal.set(Calendar.MINUTE, 59); toCal.set(Calendar.SECOND, 59)
        updateDateButtons()

        adapter = CdrAdapter()
        binding.cdrRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.cdrRecycler.adapter = adapter

        binding.cdrFrom.setOnClickListener { pickDate(fromCal, true) }
        binding.cdrTo.setOnClickListener { pickDate(toCal, false) }
        binding.cdrLoad.setOnClickListener { load() }
        binding.cdrExport.setOnClickListener { exportXlsx() }
        binding.cdrOpenWeb.setOnClickListener {
            try {
                findNavController().navigate(R.id.pbxWebFragment)
            } catch (e: Exception) {
                Log.e("$TAG Cannot open PBX page: $e")
            }
        }
        binding.cdrSettings.setOnClickListener {
            try {
                findNavController().navigate(R.id.pbxServerFragment)
            } catch (e: Exception) {
                Log.e("$TAG Cannot open settings: $e")
            }
        }
        binding.cdrSearch.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            applyFilter()
        }
    }

    override fun onDefaultAccountChanged() {}

    override fun onDestroyView() {
        super.onDestroyView()
    }

    private fun updateDateButtons() {
        binding.cdrFrom.text = getString(R.string.ap_from_fmt, btnFmt.format(fromCal.time))
        binding.cdrTo.text = getString(R.string.ap_to_fmt, btnFmt.format(toCal.time))
    }

    private fun pickDate(cal: Calendar, isFrom: Boolean) {
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                cal.set(Calendar.YEAR, y); cal.set(Calendar.MONTH, m); cal.set(Calendar.DAY_OF_MONTH, d)
                cal.set(Calendar.HOUR_OF_DAY, if (isFrom) 0 else 23)
                cal.set(Calendar.MINUTE, if (isFrom) 0 else 59)
                cal.set(Calendar.SECOND, if (isFrom) 0 else 59)
                updateDateButtons()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun load() {
        val ctx = requireContext()
        val host = PbxPrefs.load(ctx).host
        if (host.isBlank()) {
            Toast.makeText(ctx, getString(R.string.ap_toast_set_pbx_host_first), Toast.LENGTH_LONG).show()
            return
        }
        binding.cdrStatus.text = getString(R.string.ap_loading)
        val from = apiFmt.format(fromCal.time)
        val to = apiFmt.format(toCal.time)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val cookie = UcmApiClient.login(host, PbxPrefs.apiPort(ctx), PbxPrefs.apiUser(ctx), PbxPrefs.apiPass(ctx))
                    ?: return@withContext null
                UcmApiClient.queryCdr(host, PbxPrefs.apiPort(ctx), cookie, from, to)
            }
            if (result == null) {
                binding.cdrStatus.text = getString(R.string.ap_login_failed_pbx_api)
                Toast.makeText(ctx, getString(R.string.ap_toast_could_not_connect_pbx_api), Toast.LENGTH_LONG).show()
                return@launch
            }
            all.clear()
            all.addAll(result.sortedByDescending { it.start })
            applyFilter()
            binding.cdrStatus.text = resources.getQuantityString(R.plurals.ap_records_count, all.size, all.size)
        }
    }

    private fun applyFilter() {
        val list = if (query.isEmpty()) {
            all
        } else {
            all.filter { it.src.contains(query) || it.dst.contains(query) }
        }
        adapter.submit(list)
        binding.cdrEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.cdrRecycler.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        if (all.isEmpty()) {
            binding.cdrEmpty.text = getString(R.string.ap_tap_load_to_fetch_call_records)
        } else if (list.isEmpty()) binding.cdrEmpty.text = getString(R.string.ap_no_matching_records)
    }

    private fun exportXlsx() {
        if (all.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.ap_toast_nothing_to_export), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val headers = listOf("Start", "From", "To", "Duration (s)", "Disposition", "Recording")
            val rows = all.map { listOf(it.start, it.src, it.dst, it.durationSec.toString(), it.disposition, it.recordFile) }
            val file = File(requireContext().cacheDir, "AgentPro_CDR.xlsx")
            XlsxUtils.write(file, headers, rows, textColumns = setOf(1, 2))
            val uri = FileProvider.getUriForFile(requireContext(), getString(R.string.file_provider), file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AgentPro CDR")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.ap_export_cdr_chooser)))
        } catch (e: Exception) {
            Log.e("$TAG Export failed: $e")
            Toast.makeText(requireContext(), getString(R.string.ap_toast_export_failed), Toast.LENGTH_SHORT).show()
        }
    }
}
