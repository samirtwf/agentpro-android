package org.linphone.ui.main.usage

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.databinding.MinutesUsageFragmentBinding
import org.linphone.ui.main.campaign.XlsxUtils
import org.linphone.ui.main.cdr.UcmApiClient
import org.linphone.ui.main.fragment.AbstractMainFragment
import org.linphone.ui.main.panel.PbxPrefs
import org.linphone.ui.main.viewmodel.AbstractMainViewModel

/**
 * Minutes Usage tab: talk minutes (billed seconds) per extension between two date/times (second
 * precision), from the UCM call records ([UcmApiClient.queryCdr]). Two modes — a single extension
 * (detailed, outbound/inbound split) or many extensions (a range like 2000-2010 or a list like
 * 2000,2001 → a table that can be exported to Excel and shared). An editable per-call alarm threshold
 * flags extensions that reach the limit.
 */
@UiThread
class MinutesUsageFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[Minutes Usage]"
        private const val PREFS = "agentpro_usage"
        private const val KEY_ALARM = "alarm_minutes"
        private const val DEFAULT_ALARM = 2700
        private const val KEY_MINDIGITS = "min_digits"
        private const val DEFAULT_MINDIGITS = 10 // count a call only if the OTHER number has >= N digits
        private const val KEY_MAXDIGITS = "max_digits"
        private const val DEFAULT_MAXDIGITS = 10 // ...and <= N digits (matches the reference tool's 10-10 filter)
        private const val KEY_NETMAP = "net_map"
        private const val DEFAULT_NETMAP =
            "STC=050,053,055;Mobily=054,056;Zain=058,059;Landline=011,012,013,014,016,017"
        private const val KEY_NETLIMITS = "net_limits"
        private const val KEY_AUTOBLOCK = "auto_block"
        private const val OTHER = "Other"
    }

    private data class UsageRow(
        val ext: String, val totalSec: Int, val callSec: Int,
        val outSec: Int, val inSec: Int, val calls: Int, val over: Boolean,
        val netSec: Map<String, Int> = emptyMap(),
        val overNets: List<String> = emptyList()
    )

    // Carrier networks + per-network quota, rebuilt from the Networks / Limits boxes.
    private var netRules: List<Pair<String, String>> = emptyList() // prefix -> network, longest first
    private var networks: List<String> = listOf(OTHER)             // display order + Other
    private var netLimits: Map<String, Double> = emptyMap()        // network -> quota minutes
    private val blockedExts = HashSet<String>()

    private lateinit var binding: MinutesUsageFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel
    private val apiFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }

    private var exportRows: List<UsageRow> = emptyList()
    private var exportRange = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = MinutesUsageFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]
        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel
        mainViewModel.title.value = getString(R.string.ap_minutes_usage)
        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(dummySlidingPane, dummyTopBar, binding.bottomNavBar, R.id.usageFragment)

        // Default range: today 00:00:00 → now.
        val from = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }
        binding.usageFrom.setText(apiFmt.format(from.time))
        binding.usageTo.setText(apiFmt.format(Calendar.getInstance().time))
        binding.usageAlarm.setText(prefs().getInt(KEY_ALARM, DEFAULT_ALARM).toString())
        binding.usageMindigits.setText(prefs().getInt(KEY_MINDIGITS, DEFAULT_MINDIGITS).toString())
        binding.usageMaxdigits.setText(prefs().getInt(KEY_MAXDIGITS, DEFAULT_MAXDIGITS).toString())

        // Pre-fill with this account's own extension (the user can change it).
        coreContext.postOnCoreThread { core ->
            val ext = core.defaultAccount?.params?.identityAddress?.username.orEmpty()
            coreContext.postOnMainThread {
                if (isAdded && binding.usageExt.text.isNullOrEmpty()) binding.usageExt.setText(ext)
            }
        }

        binding.usageNetworks.setText(prefs().getString(KEY_NETMAP, DEFAULT_NETMAP))
        binding.usageLimits.setText(prefs().getString(KEY_NETLIMITS, ""))
        binding.usageAutoblock.isChecked = prefs().getBoolean(KEY_AUTOBLOCK, false)

        binding.usageMode.setOnCheckedChangeListener { _, _ -> applyMode() }
        applyMode()
        binding.usageCalc.setOnClickListener { calculate() }
        binding.usageExport.setOnClickListener { exportXlsx() }
        binding.usageBlock.setOnClickListener { showBlockingDialog() }
    }

    /** Rebuilds [netRules] / [networks] / [netLimits] from the two config boxes. */
    private fun rebuildNetworks() {
        val map = binding.usageNetworks.text?.toString()?.trim().orEmpty()
        val limitsText = binding.usageLimits.text?.toString()?.trim().orEmpty()
        val rules = ArrayList<Pair<String, String>>()
        val names = LinkedHashSet<String>()
        for (group in map.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = group.indexOf('=')
            if (eq <= 0) continue
            val net = group.substring(0, eq).trim()
            if (net.isEmpty()) continue
            names.add(net)
            for (p in group.substring(eq + 1).split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                val prefix = p.filter { it.isDigit() }
                if (prefix.isNotEmpty()) rules.add(prefix to net)
            }
        }
        rules.sortByDescending { it.first.length } // longest prefix wins
        netRules = rules
        val limits = HashMap<String, Double>()
        for (group in limitsText.split(";").map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = group.indexOf('=')
            if (eq <= 0) continue
            val net = group.substring(0, eq).trim()
            val minutes = group.substring(eq + 1).trim().toDoubleOrNull()
            if (net.isNotEmpty() && minutes != null && minutes > 0) limits[net] = minutes
        }
        netLimits = limits
        networks = names.toMutableList().apply { add(OTHER) }
    }

    /** Classifies an external number by carrier after normalising it to local (0…) format. */
    private fun classify(dst: String): String {
        var d = dst.filter { it.isDigit() }
        when {
            d.startsWith("00966") -> d = "0" + d.substring(5)
            d.startsWith("966") && d.length >= 11 -> d = "0" + d.substring(3)
            !d.startsWith("0") -> d = "0$d"
        }
        for ((prefix, net) in netRules) if (d.startsWith(prefix)) return net
        return OTHER
    }

    override fun onDefaultAccountChanged() {}

    private fun multiMode() = binding.usageModeMulti.isChecked

    private fun applyMode() {
        if (multiMode()) {
            binding.usageExtLabel.text = getString(R.string.ap_extensions_empty_all_active)
            binding.usageExt.hint = getString(R.string.ap_ext_hint_multi)
        } else {
            binding.usageExtLabel.text = getString(R.string.ap_extension_2)
            binding.usageExt.hint = getString(R.string.ap_e_g_2090)
        }
        // Hide the stale export button / table when switching modes.
        binding.usageExport.visibility = View.GONE
        binding.usageBlock.visibility = View.GONE
        binding.usageResult.text = ""
        exportRows = emptyList()
    }

    private fun calculate() {
        val ctx = requireContext()
        val multi = multiMode()
        val extText = binding.usageExt.text?.toString()?.trim().orEmpty()
        // In multi mode an empty box (or "all"/"*") means "every active extension", auto-detected
        // from the CDR — like the desktop tool, which loads them all without typing a range.
        val auto = multi && (extText.isBlank() || extText.equals("all", true) || extText == "*")
        if (!multi && extText.isEmpty()) {
            Toast.makeText(ctx, R.string.ap_toast_enter_extension, Toast.LENGTH_SHORT).show()
            return
        }
        if (multi && !auto && parseExtensions(extText).isEmpty()) {
            Toast.makeText(ctx, R.string.ap_toast_no_valid_extensions, Toast.LENGTH_SHORT).show()
            return
        }
        val fromStr = binding.usageFrom.text?.toString()?.trim().orEmpty()
        val toStr = binding.usageTo.text?.toString()?.trim().orEmpty()
        val fromDate = try { apiFmt.parse(fromStr) } catch (e: Exception) { null }
        val toDate = try { apiFmt.parse(toStr) } catch (e: Exception) { null }
        if (fromDate == null || toDate == null) {
            Toast.makeText(ctx, R.string.ap_toast_use_date_format, Toast.LENGTH_LONG).show()
            return
        }
        if (toDate.before(fromDate)) {
            Toast.makeText(ctx, R.string.ap_toast_to_after_from, Toast.LENGTH_LONG).show()
            return
        }
        val host = PbxPrefs.load(ctx).host
        if (host.isBlank()) {
            Toast.makeText(ctx, R.string.ap_toast_set_pbx_host_first, Toast.LENGTH_LONG).show()
            return
        }
        // Alarm threshold (minutes) — editable here and remembered for next time.
        val alarmMin = binding.usageAlarm.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_ALARM
        val minDigits = binding.usageMindigits.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_MINDIGITS
        val maxDigits = binding.usageMaxdigits.text?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: DEFAULT_MAXDIGITS
        rebuildNetworks()
        prefs().edit()
            .putInt(KEY_ALARM, alarmMin).putInt(KEY_MINDIGITS, minDigits).putInt(KEY_MAXDIGITS, maxDigits)
            .putString(KEY_NETMAP, binding.usageNetworks.text?.toString()?.trim().orEmpty())
            .putString(KEY_NETLIMITS, binding.usageLimits.text?.toString()?.trim().orEmpty())
            .putBoolean(KEY_AUTOBLOCK, binding.usageAutoblock.isChecked)
            .apply()

        binding.usageExport.visibility = View.GONE
        binding.usageBlock.visibility = View.GONE
        binding.usageResult.setTextColor(Color.WHITE)
        binding.usageResult.text = ""
        binding.usageStatus.text = getString(R.string.ap_calculating)
        viewLifecycleOwner.lifecycleScope.launch {
            val records = withContext(Dispatchers.IO) {
                val cookie = UcmApiClient.login(host, PbxPrefs.apiPort(ctx), PbxPrefs.apiUser(ctx), PbxPrefs.apiPass(ctx))
                    ?: return@withContext null
                UcmApiClient.queryCdr(host, PbxPrefs.apiPort(ctx), cookie, fromStr, toStr)
            }
            if (!isAdded) return@launch
            if (records == null) {
                binding.usageStatus.text = getString(R.string.ap_login_failed_pbx_api)
                Toast.makeText(ctx, R.string.ap_toast_could_not_connect_pbx_api, Toast.LENGTH_LONG).show()
                return@launch
            }

            // Outbound minutes per extension, counted the logically-correct way — by DIGIT COUNT, not
            // raw string length (so a "+" / space / dash in a number can't skew the filter the way it
            // does in the desktop tool):
            //   • the extension is the call's `src`: all digits, not "0000", at most 4 digits (internal)
            //   • the destination is EXTERNAL: more than 4 digits, and its digit count is within the
            //     [minDigits, maxDigits] filter (0 = unbounded on that side)
            //   • per extension, sum billsec (Talk, no ringing) and duration (Call, with ringing)
            fun digitsOf(x: String) = x.count { it.isDigit() }
            val talk = HashMap<String, Int>() // src -> Σ billsec
            val call = HashMap<String, Int>() // src -> Σ duration
            val cnt = HashMap<String, Int>() // src -> outbound call count
            val netTalk = HashMap<String, HashMap<String, Int>>() // src -> network -> Σ billsec
            for (r in records) {
                val s = r.src
                if (s.isEmpty() || s == "0000" || s.length > 4 || !s.all { it.isDigit() }) continue
                val d = digitsOf(r.dst)
                if (d <= 4) continue // internal / not an external number
                if (minDigits > 0 && d < minDigits) continue
                if (maxDigits > 0 && d > maxDigits) continue
                talk[s] = (talk[s] ?: 0) + r.billSec
                call[s] = (call[s] ?: 0) + r.durationSec
                cnt[s] = (cnt[s] ?: 0) + 1
                val net = classify(r.dst)
                val perNet = netTalk.getOrPut(s) { HashMap() }
                perNet[net] = (perNet[net] ?: 0) + r.billSec
            }
            // Auto (empty box) = every extension that placed a qualifying call, like the desktop tool;
            // otherwise the typed range/list, or the single extension.
            val exts = when {
                auto -> talk.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                multi -> parseExtensions(extText)
                else -> listOf(extText)
            }
            val allRows = exts.map { e ->
                val t = talk[e] ?: 0
                val perNet = netTalk[e] ?: emptyMap()
                val overNets = networks.filter { n ->
                    val lim = netLimits[n] ?: 0.0
                    lim > 0 && (perNet[n] ?: 0) / 60.0 >= lim
                }
                val over = overNets.isNotEmpty() || (alarmMin > 0 && t / 60.0 >= alarmMin)
                UsageRow(e, t, call[e] ?: 0, t, 0, cnt[e] ?: 0, over, perNet, overNets)
            }
            // Hide extensions that placed no qualifying call (no zero rows in the table).
            val rows = if (multi) allRows.filter { it.calls > 0 } else allRows
            exportRows = rows
            exportRange = getString(R.string.ap_range_fmt, fromStr, toStr)

            val overQuota = rows.filter { it.overNets.isNotEmpty() }
            if (multi) {
                if (rows.isEmpty()) {
                    binding.usageStatus.text = getString(R.string.ap_no_external_calls_in_this_range)
                    binding.usageResult.text = ""
                } else {
                    showTable(rows, alarmMin, records.size)
                }
            } else {
                showSingle(rows.first(), alarmMin, fromStr, toStr, records.size)
            }

            // Quota enforcement: auto-block the offenders, or offer the manual dialog.
            if (overQuota.isNotEmpty()) {
                if (binding.usageAutoblock.isChecked) {
                    autoBlock(overQuota.map { it.ext }, host, ctx)
                } else {
                    binding.usageBlock.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Blocks each over-quota extension on the PBX (internal-only), then refreshes the table. */
    private fun autoBlock(exts: List<String>, host: String, ctx: Context) {
        binding.usageStatus.text = getString(R.string.ap_blocking_over_quota_fmt, exts.size)
        viewLifecycleOwner.lifecycleScope.launch {
            val blocked = withContext(Dispatchers.IO) {
                val cookie = UcmApiClient.login(host, PbxPrefs.apiPort(ctx), PbxPrefs.apiUser(ctx), PbxPrefs.apiPass(ctx))
                    ?: return@withContext -1
                var ok = 0
                for (e in exts) if (UcmApiClient.blockExtension(host, PbxPrefs.apiPort(ctx), cookie, e)) {
                    blockedExts.add(e); ok++
                }
                ok
            }
            if (!isAdded) return@launch
            if (blocked < 0) {
                Toast.makeText(ctx, R.string.ap_toast_could_not_connect_pbx_api, Toast.LENGTH_LONG).show()
            } else {
                binding.usageStatus.text = getString(R.string.ap_auto_blocked_over_quota_fmt, blocked)
                exportRows.firstOrNull()?.let {
                    if (multiMode()) showTable(exportRows, currentAlarm(), -1)
                }
            }
        }
    }

    private fun currentAlarm(): Int =
        binding.usageAlarm.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_ALARM

    /** Manual per-extension block/unblock dialog for the over-quota extensions. */
    private fun showBlockingDialog() {
        val ctx = requireContext()
        val overQuota = exportRows.filter { it.overNets.isNotEmpty() }.map { it.ext }
        if (overQuota.isEmpty()) {
            Toast.makeText(ctx, R.string.ap_toast_no_ext_over_quota, Toast.LENGTH_SHORT).show()
            return
        }
        val host = PbxPrefs.load(ctx).host
        val labels = overQuota.map { e ->
            getString(if (blockedExts.contains(e)) R.string.ap_unblock_ext_fmt else R.string.ap_block_ext_fmt, e)
        }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.ap_block_over_quota_title)
            .setItems(labels) { _, which ->
                val ext = overQuota[which]
                val block = !blockedExts.contains(ext)
                toggleBlock(ext, block, host, ctx)
            }
            .setNegativeButton(R.string.ap_close, null)
            .show()
    }

    private fun toggleBlock(ext: String, block: Boolean, host: String, ctx: Context) {
        binding.usageStatus.text = getString(
            if (block) R.string.ap_blocking_ext_fmt else R.string.ap_unblocking_ext_fmt, ext
        )
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                val cookie = UcmApiClient.login(host, PbxPrefs.apiPort(ctx), PbxPrefs.apiUser(ctx), PbxPrefs.apiPass(ctx))
                    ?: return@withContext false
                if (block) {
                    UcmApiClient.blockExtension(host, PbxPrefs.apiPort(ctx), cookie, ext)
                } else {
                    UcmApiClient.unblockExtension(host, PbxPrefs.apiPort(ctx), cookie, ext)
                }
            }
            if (!isAdded) return@launch
            if (ok) {
                if (block) blockedExts.add(ext) else blockedExts.remove(ext)
                binding.usageStatus.text = getString(
                    if (block) R.string.ap_ext_blocked_fmt else R.string.ap_ext_unblocked_fmt, ext
                )
                if (multiMode()) showTable(exportRows, currentAlarm(), -1)
            } else {
                Toast.makeText(ctx, getString(if (block) R.string.ap_blocking_failed_fmt else R.string.ap_unblocking_failed_fmt, ext), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showSingle(r: UsageRow, alarmMin: Int, fromStr: String, toStr: String, totalRecords: Int) {
        binding.usageStatus.text = getString(R.string.ap_calls_for_ext_fmt, r.calls, r.ext, totalRecords)
        binding.usageResult.setTextColor(if (r.over) Color.parseColor("#FF5A52") else Color.WHITE)
        binding.usageResult.text = buildString {
            if (r.over) append(getString(R.string.ap_usage_alarm_fmt, alarmMin) + "\n\n")
            append(getString(R.string.ap_usage_extension_fmt, r.ext) + "\n")
            append("$fromStr  →  $toStr\n\n")
            append(getString(R.string.ap_usage_talk_fmt, minutes(r.totalSec)))
            append("   (" + getString(R.string.ap_usage_secs_calls_fmt, r.totalSec, r.calls) + ")\n")
            append(getString(R.string.ap_usage_call_fmt, minutes(r.callSec)) + "\n\n")
            append(getString(R.string.ap_usage_per_network) + "\n")
            for (n in networks) {
                val sec = r.netSec[n] ?: 0
                val flag = if (r.overNets.contains(n)) "  ⚠️ " + getString(R.string.ap_usage_over_quota) else ""
                append("  $n:  ${minutes(sec)}$flag\n")
            }
        }
        binding.usageExport.visibility = View.VISIBLE
        if (r.over) {
            Toast.makeText(requireContext(), getString(R.string.ap_toast_usage_alarm_fmt, r.ext, minutes(r.totalSec), alarmMin), Toast.LENGTH_LONG).show()
        }
    }

    private fun showTable(rows: List<UsageRow>, alarmMin: Int, totalRecords: Int) {
        val over = rows.count { it.over }
        binding.usageStatus.text = buildString {
            append(getString(R.string.ap_usage_ext_count_fmt, rows.size))
            if (totalRecords >= 0) append("  ·  " + resources.getQuantityString(R.plurals.ap_records_count, totalRecords, totalRecords))
            if (over > 0) append("  ·  " + getString(R.string.ap_usage_over_limit_fmt, over))
        }
        binding.usageResult.setTextColor(Color.WHITE)
        // One column per configured network, then Talk / Call / Calls.
        val netHead = networks.joinToString("") { String.format(Locale.US, "%8s", it.take(7)) }
        val width = 6 + networks.size * 8 + 8 + 8 + 6
        binding.usageResult.text = buildString {
            append(String.format(Locale.US, "%-6s", "Ext")).append(netHead)
            append(String.format(Locale.US, "%8s %8s %5s\n", "Talk", "Call", "Calls"))
            append("-".repeat(width)).append("\n")
            for (r in rows) {
                append(String.format(Locale.US, "%-6s", r.ext))
                for (n in networks) append(String.format(Locale.US, "%8s", minutes(r.netSec[n] ?: 0)))
                val mark = when {
                    blockedExts.contains(r.ext) -> " ⛔"
                    r.overNets.isNotEmpty() -> " ⚠️"
                    r.over -> " *"
                    else -> ""
                }
                append(String.format(Locale.US, "%8s %8s %5d%s\n", minutes(r.totalSec), minutes(r.callSec), r.calls, mark))
            }
            append("-".repeat(width)).append("\n")
            append(String.format(Locale.US, "%-6s", "TOTAL"))
            for (n in networks) append(String.format(Locale.US, "%8s", minutes(rows.sumOf { it.netSec[n] ?: 0 })))
            append(
                String.format(
                    Locale.US, "%8s %8s %5d\n",
                    minutes(
                        rows.sumOf {
                it.totalSec
            }
                    ),
                minutes(rows.sumOf { it.callSec }), rows.sumOf { it.calls }
                )
            )
            append("\n" + getString(R.string.ap_usage_legend))
            if (over > 0) append("\n" + getString(R.string.ap_usage_legend_flags))
        }
        binding.usageExport.visibility = View.VISIBLE
    }

    private fun exportXlsx() {
        val rows = exportRows
        if (rows.isEmpty()) {
            Toast.makeText(requireContext(), R.string.ap_toast_nothing_to_export_calculate, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val headers = ArrayList<String>()
            headers.add("Extension")
            for (n in networks) headers.add("$n (min)")
            headers.addAll(listOf("Talk min (outbound)", "Call min (outbound)", "Calls", "Status"))
            val data = ArrayList<List<String>>()
            for (r in rows) {
                val row = ArrayList<String>()
                row.add(r.ext)
                for (n in networks) row.add(minutes(r.netSec[n] ?: 0))
                val status = when {
                    blockedExts.contains(r.ext) -> "BLOCKED"
                    r.overNets.isNotEmpty() -> r.overNets.joinToString(", ")
                    r.over -> "YES"
                    else -> ""
                }
                row.addAll(listOf(minutes(r.totalSec), minutes(r.callSec), r.calls.toString(), status))
                data.add(row)
            }
            // TOTAL row — the whole point of the export for accounting.
            val total = ArrayList<String>()
            total.add("TOTAL")
            for (n in networks) total.add(minutes(rows.sumOf { it.netSec[n] ?: 0 }))
            total.addAll(
                listOf(
                    minutes(
                        rows.sumOf {
                it.totalSec
            }
                    ),
                minutes(rows.sumOf { it.callSec }), rows.sumOf { it.calls }.toString(), ""
                )
            )
            data.add(total)

            val file = File(requireContext().cacheDir, "AgentPro_Minutes.xlsx")
            XlsxUtils.write(file, headers, data, textColumns = setOf(0))
            val uri = FileProvider.getUriForFile(requireContext(), getString(R.string.file_provider), file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "AgentPro minutes usage")
                putExtra(Intent.EXTRA_TEXT, "Minutes usage  ($exportRange)")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.ap_export_minutes_chooser)))
        } catch (e: Exception) {
            Log.e("$TAG Export failed: $e")
            Toast.makeText(requireContext(), R.string.ap_toast_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Parses "2090", a range "2000-2010", and lists "2000,2001,2005" (any mix) into extensions. */
    private fun parseExtensions(text: String): List<String> {
        val out = LinkedHashSet<String>()
        for (token in text.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }) {
            val range = token.split("-").map { it.trim() }
            val a = range.getOrNull(0)?.toIntOrNull()
            val b = range.getOrNull(1)?.toIntOrNull()
            if (range.size == 2 && a != null && b != null && a <= b && b - a <= 2000) {
                for (n in a..b) out.add(n.toString())
            } else if (token.all { it.isDigit() }) {
                out.add(token)
            }
        }
        return out.toList()
    }

    private fun prefs() = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun minutes(seconds: Int): String = String.format(Locale.US, "%.1f", seconds / 60.0)
}
