package org.linphone.ui.main.contactlist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.CampaignFragmentBinding
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.ui.main.campaign.CampaignAdapter
import org.linphone.ui.main.campaign.CampaignContact
import org.linphone.ui.main.campaign.CampaignStore
import org.linphone.ui.main.campaign.XlsxUtils
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.viewmodel.AbstractMainViewModel

/**
 * A simple contact manager that looks like the Campaign tab (Excel import/export, searchable
 * scrollable table, per-row call/edit/delete) but WITHOUT the auto-dialer controls (Start/Stop/
 * delay) and the Scheduled Callbacks bar. It is backed by its own [FILE] store so the contacts
 * list is independent from the dialing campaign. It reuses the Campaign layout and adapter.
 */
@UiThread
class ContactsListFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Contacts List Fragment]"
        private const val FILE = "contacts.json"
    }

    private lateinit var binding: CampaignFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel
    private lateinit var adapter: CampaignAdapter

    private val contacts = mutableListOf<CampaignContact>()
    private var query = ""

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importFromUri(uri)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = CampaignFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel
        mainViewModel.title.value = getString(R.string.bottom_navigation_contacts_label)
        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(dummySlidingPane, dummyTopBar, binding.bottomNavBar, R.id.agentContactsFragment)

        // Turn the Campaign layout into a plain contacts manager.
        binding.campaignTitle.text = getString(R.string.bottom_navigation_contacts_label)
        binding.campaignDialerRow.visibility = View.GONE
        binding.campaignCallbacksBar.visibility = View.GONE
        binding.fabAdd.visibility = View.VISIBLE
        binding.fabAdd.setOnClickListener { showAddDialog() }

        adapter = CampaignAdapter(
            onCall = { callContact(it) },
            onDelete = { confirmDelete(it) },
            onEdit = { showEditDialog(it) }
        )
        binding.campaignRecycler.layoutManager = LinearLayoutManager(requireContext())
        binding.campaignRecycler.adapter = adapter

        binding.campaignSearch.doAfterTextChanged {
            query = it?.toString()?.trim().orEmpty()
            refresh()
        }

        binding.btnImport.setOnClickListener { importLauncher.launch(arrayOf("*/*")) }
        binding.btnExport.setOnClickListener { exportXlsx() }
        binding.btnTemplate.setOnClickListener { exportTemplate() }
        binding.btnClear.setOnClickListener { confirmClear() }
        binding.btnReset.setOnClickListener { confirmReset() }
        binding.campaignSelectAll.setOnCheckedChangeListener { _, checked ->
            contacts.forEach { it.selected = checked }
            adapter.notifyDataSetChanged()
        }

        loadData()
    }

    override fun onResume() {
        super.onResume()
        loadData()
    }

    override fun onDefaultAccountChanged() {}

    private fun loadData() {
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { CampaignStore.load(requireContext(), FILE) }
            contacts.clear()
            contacts.addAll(loaded)
            refresh()
        }
    }

    private fun refresh() {
        val filtered = if (query.isEmpty()) {
            contacts
        } else {
            val q = query.lowercase(Locale.getDefault())
            contacts.filter {
                it.phone.lowercase(Locale.getDefault()).contains(q) ||
                    it.name.lowercase(Locale.getDefault()).contains(q)
            }
        }
        adapter.submit(filtered)

        val total = contacts.size
        val called = contacts.count { it.status == CampaignContact.STATUS_CALLED }
        binding.campaignTotal.text = getString(R.string.ap_total_fmt, total)
        binding.campaignCalled.text = getString(R.string.ap_called_fmt, called)
        binding.campaignNotcalled.text = getString(R.string.ap_not_called_fmt, total - called)
        binding.campaignEmpty.visibility = if (total == 0) View.VISIBLE else View.GONE
    }

    private fun persist() {
        val snapshot = contacts.toList()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            CampaignStore.save(requireContext(), snapshot, FILE)
        }
    }

    private fun callContact(c: CampaignContact) {
        if (c.phone.isEmpty()) return
        c.status = CampaignContact.STATUS_CALLED
        c.lastCall = System.currentTimeMillis()
        persist()
        refresh()
        coreContext.postOnCoreThread { core ->
            // Dial exactly as imported (no international prefix).
            val address = core.interpretUrl(c.phone, false)
            if (address != null) coreContext.startAudioCall(address)
        }
    }

    private fun confirmDelete(c: CampaignContact) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ap_delete_contact_title)
            .setMessage("${if (c.name.isNotEmpty()) c.name + " — " else ""}${c.phone}")
            .setPositiveButton(R.string.dialog_delete) { _, _ ->
                contacts.removeAll { it.id == c.id }
                persist()
                refresh()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showAddDialog() {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val nameInput = EditText(ctx).apply { hint = getString(R.string.ap_hint_name); setSingleLine() }
        val phoneInput = EditText(ctx).apply {
            hint = getString(R.string.ap_number); inputType = InputType.TYPE_CLASS_PHONE; setSingleLine()
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput); addView(phoneInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.ap_add_contact)
            .setView(container)
            .setPositiveButton(R.string.ap_add) { _, _ ->
                val name = nameInput.text.toString().trim()
                val phone = phoneInput.text.toString().trim()
                if (phone.isEmpty()) {
                    Toast.makeText(ctx, R.string.ap_toast_enter_number, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                contacts.add(CampaignContact(id = System.currentTimeMillis(), name = name, phone = phone))
                persist()
                refresh()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showEditDialog(c: CampaignContact) {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val nameInput = EditText(ctx).apply { hint = getString(R.string.ap_hint_name); setText(c.name); setSingleLine() }
        val phoneInput = EditText(ctx).apply {
            hint = getString(R.string.ap_number); setText(c.phone); inputType = InputType.TYPE_CLASS_PHONE; setSingleLine()
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput); addView(phoneInput)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.ap_edit_contact)
            .setView(container)
            .setPositiveButton(R.string.ap_save) { _, _ ->
                c.name = nameInput.text.toString().trim()
                c.phone = phoneInput.text.toString().trim()
                persist()
                refresh()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun importFromUri(uri: Uri) {
        Toast.makeText(requireContext(), R.string.ap_toast_importing, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                try {
                    val bytes = requireContext().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() } ?: ByteArray(0)
                    CampaignStore.parseImport(bytes)
                } catch (e: Exception) {
                    Log.e("$TAG Import failed: $e")
                    emptyList()
                }
            }
            if (imported.isEmpty()) {
                Toast.makeText(requireContext(), R.string.ap_toast_no_contacts_in_file, Toast.LENGTH_SHORT).show()
                return@launch
            }
            contacts.addAll(imported)
            withContext(Dispatchers.IO) { CampaignStore.save(requireContext(), contacts.toList(), FILE) }
            refresh()
            Toast.makeText(requireContext(), getString(R.string.ap_toast_imported_fmt, imported.size), Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ap_clear_all_contacts_title)
            .setMessage(R.string.ap_clear_list_message)
            .setPositiveButton(R.string.ap_clear) { _, _ ->
                contacts.clear()
                CampaignStore.clear(requireContext(), FILE)
                refresh()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ap_reset_call_status_title)
            .setMessage(R.string.ap_reset_status_message)
            .setPositiveButton(R.string.ap_reset) { _, _ ->
                contacts.forEach {
                    it.status = CampaignContact.STATUS_PENDING
                    it.disposition = ""
                    it.lastCall = 0L
                }
                persist()
                refresh()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun exportXlsx() {
        if (contacts.isEmpty()) {
            Toast.makeText(requireContext(), R.string.ap_toast_nothing_to_export, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val headers = listOf("Name", "Phone", "Status", "Disposition", "Last Call")
            val rows = contacts.map { c ->
                listOf(
                    c.name,
                    c.phone,
                    c.status,
                    c.disposition,
                    if (c.lastCall > 0) fmt.format(Date(c.lastCall)) else ""
                )
            }
            val file = File(requireContext().cacheDir, "AgentPro_Contacts.xlsx")
            XlsxUtils.write(file, headers, rows, textColumns = setOf(1))
            shareXlsx(file, "AgentPro Contacts")
        } catch (e: Exception) {
            Log.e("$TAG Export failed: $e")
            Toast.makeText(requireContext(), R.string.ap_toast_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportTemplate() {
        try {
            val headers = listOf("Name", "Phone")
            val rows = listOf(
                listOf("Example Name 1", "0500000001"),
                listOf("Example Name 2", "0500000002")
            )
            val file = File(requireContext().cacheDir, "AgentPro_Contacts_Template.xlsx")
            XlsxUtils.write(file, headers, rows, textColumns = setOf(1))
            shareXlsx(file, "AgentPro Contacts Template")
        } catch (e: Exception) {
            Log.e("$TAG Template export failed: $e")
            Toast.makeText(requireContext(), R.string.ap_toast_could_not_create_template, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareXlsx(file: File, subject: String) {
        val uri = FileProvider.getUriForFile(requireContext(), getString(R.string.file_provider), file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, subject)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.ap_export_excel_chooser)))
    }
}
