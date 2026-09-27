package org.linphone.ui.main.disposition

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.DispositionsFragmentBinding
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.ui.main.campaign.XlsxUtils
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.LinphoneUtils
import android.view.Menu

@UiThread
class DispositionsFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Dispositions Fragment]"
    }

    private lateinit var binding: DispositionsFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel
    private lateinit var adapter: DispositionsAdapter

    private var sortMode = "date"
    private var category = "All"
    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DispositionsFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel
        mainViewModel.title.value = getString(R.string.ap_dispositions)
        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(dummySlidingPane, dummyTopBar, binding.bottomNavBar, R.id.dispFragment)

        adapter = DispositionsAdapter(
            onCall = { number -> dial(number) },
            onDelete = { disposition -> confirmDelete(disposition) }
        )
        binding.dispRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.dispRecycler.adapter = adapter

        binding.dispSearch.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            refresh()
        }

        binding.dispCategory.setOnClickListener { showCategoryMenu() }
        binding.dispSortDate.setOnClickListener { setSort("date") }
        binding.dispSortNumber.setOnClickListener { setSort("number") }
        binding.dispSortStatus.setOnClickListener { setSort("status") }
        binding.dispExport.setOnClickListener { exportXlsx() }
        binding.dispClear.setOnClickListener { confirmClearAll() }

        highlightSort()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDefaultAccountChanged() {
        // No-op
    }

    private fun refresh() {
        var list = DispositionStore.getAll(requireContext()).toList()
        if (category != "All") {
            list = list.filter { it.status == category }
        }
        if (query.isNotEmpty()) {
            val q = query.lowercase(Locale.getDefault())
            list = list.filter {
                it.number.lowercase(Locale.getDefault()).contains(q) ||
                    it.notes.lowercase(Locale.getDefault()).contains(q)
            }
        }
        list = when (sortMode) {
            "number" -> list.sortedBy { it.number }
            "status" -> list.sortedBy { it.status }
            else -> list.sortedByDescending { it.timestamp }
        }
        adapter.submit(list)

        binding.dispCount.text = resources.getQuantityString(R.plurals.ap_records_count, list.size, list.size)
        val empty = list.isEmpty()
        binding.dispEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.dispRecycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun showCategoryMenu() {
        val popup = PopupMenu(requireContext(), binding.dispCategory)
        // Titles are shown translated; the item id picks the stored status (or "All") back out.
        val keys = listOf("All") + DispositionStore.ALL_STATUSES
        keys.forEachIndexed { i, key -> popup.menu.add(Menu.NONE, i, i, categoryLabel(key)) }
        popup.setOnMenuItemClickListener { item ->
            category = keys[item.itemId]
            binding.dispCategory.text = categoryLabel(category)
            refresh()
            true
        }
        popup.show()
    }

    private fun categoryLabel(key: String) =
        if (key == "All") getString(R.string.ap_all) else DispositionStore.label(requireContext(), key)

    private fun setSort(mode: String) {
        sortMode = mode
        highlightSort()
        refresh()
    }

    private fun highlightSort() {
        val chips = mapOf(
            "date" to binding.dispSortDate,
            "number" to binding.dispSortNumber,
            "status" to binding.dispSortStatus
        )
        val d = resources.displayMetrics.density
        for ((mode, chip) in chips) {
            if (mode == sortMode) {
                val bg = GradientDrawable().apply {
                    cornerRadius = 14 * d
                    setColor(Color.parseColor("#2A3340"))
                    setStroke((2 * d).toInt(), Color.parseColor("#6C8CFF"))
                }
                chip.background = bg
            } else {
                chip.setBackgroundResource(R.drawable.shape_disposition_button)
            }
        }
    }

    private fun dial(number: String) {
        if (number.isEmpty()) return
        Log.i("$TAG Calling [$number] from dispositions")
        coreContext.postOnCoreThread { core ->
            val address = core.interpretUrl(number, LinphoneUtils.applyInternationalPrefix())
            if (address != null) {
                coreContext.startAudioCall(address)
            }
        }
    }

    private fun confirmDelete(disposition: Disposition) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ap_delete_disposition_title)
            .setMessage("${disposition.number} — ${DispositionStore.label(requireContext(), disposition.status)}")
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                DispositionStore.delete(requireContext(), disposition.id)
                refresh()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ap_clear_all_dispositions_title)
            .setMessage(R.string.ap_clear_all_dispositions_message)
            .setPositiveButton(R.string.ap_clear) { _, _ ->
                DispositionStore.clear(requireContext())
                refresh()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun exportXlsx() {
        val all = DispositionStore.getAll(requireContext()).sortedByDescending { it.timestamp }
        if (all.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.ap_toast_no_dispositions_to_export), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val headers = listOf("Number", "Status", "Direction", "Date", "Notes")
            val rows = all.map {
                listOf(it.number, it.status, it.direction, fmt.format(Date(it.timestamp)), it.notes)
            }
            val file = File(requireContext().cacheDir, "AgentPro_Dispositions.xlsx")
            // Column 0 (Number) kept as text so Excel preserves leading zeros.
            XlsxUtils.write(file, headers, rows, textColumns = setOf(0), sheetName = "Dispositions")

            val uri = FileProvider.getUriForFile(
                requireContext(),
                getString(R.string.file_provider),
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AgentPro Dispositions")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.ap_export_dispositions_chooser)))
        } catch (e: Exception) {
            Log.e("$TAG Failed to export dispositions: $e")
            Toast.makeText(requireContext(), getString(R.string.ap_toast_export_failed), Toast.LENGTH_SHORT).show()
        }
    }
}
