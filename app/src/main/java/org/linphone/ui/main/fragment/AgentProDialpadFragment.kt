package org.linphone.ui.main.fragment

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.TransportType
import org.linphone.core.tools.Log
import org.linphone.databinding.AgentProDialpadFragmentBinding
import org.linphone.databinding.MainActivityTopBarBinding
import org.linphone.ui.main.AccountScope
import org.linphone.ui.main.viewmodel.AbstractMainViewModel
import org.linphone.utils.LinphoneUtils
import org.linphone.utils.enablePasswordToggle

@UiThread
class AgentProDialpadFragment : AbstractMainFragment() {
    companion object {
        private const val TAG = "[AgentPro Dialpad Fragment]"
        private const val SPEED_DIAL_PREFS = "agentpro_speed_dials"
        private const val SPEED_DIAL_KEY = "entries"

        // Ext-locked password prompt guard. MUST be process-wide (not a per-fragment field): on a
        // cold launch the host can spin up TWO dialpad fragment instances (nav start destination +
        // re-creation around the notification-permission flow), and a per-instance flag lets each
        // one open its own prompt → the agent has to type the password twice. Static = one prompt.
        @Volatile
        private var lockedPasswordDialogShowing = false
    }

    private lateinit var binding: AgentProDialpadFragmentBinding
    private lateinit var mainViewModel: AbstractMainViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AgentProDialpadFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mainViewModel = ViewModelProvider(requireActivity())[AbstractMainViewModel::class.java]

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = mainViewModel

        mainViewModel.title.value = getString(R.string.ap_dialpad)
        setViewModel(mainViewModel)

        val dummySlidingPane = androidx.slidingpanelayout.widget.SlidingPaneLayout(requireContext())
        val dummyTopBar = MainActivityTopBarBinding.inflate(layoutInflater)
        initViews(
            dummySlidingPane,
            dummyTopBar,
            binding.bottomNavBar,
            R.id.agentProDialpadFragment
        )

        setupDialpad()
        setupToggles()
        setupSpeedDial()
        setupAccountSwitcher()
        startUptimeIdleTicker()

        // Server line under the "Connected" badge: host:port via TRANSPORT
        mainViewModel.account.observe(viewLifecycleOwner) { updateServerLine() }
        updateServerLine()
    }

    // Tapping the account chip (the extension number) opens a quick switcher between all configured
    // SIP accounts. Selecting one applies its account + its own PBX settings everywhere (via
    // AccountSwitcher, which recreates the activity). Disabled on a locked single-account build.
    private fun setupAccountSwitcher() {
        // Locked builds hide the account switcher entirely (single fixed account).
        if (corePreferences.accountLocked) return
        binding.statusId.setOnClickListener { showAccountSwitcher() }
    }

    private fun showAccountSwitcher() {
        coreContext.postOnCoreThread { core ->
            val refs = ArrayList<org.linphone.core.Account>()
            val items = ArrayList<String>()
            val current = core.defaultAccount
            for (account in core.accountList) {
                refs.add(account)
                val id = account.params.identityAddress?.asStringUriOnly()?.removePrefix("sip:") ?: "?"
                items.add(if (account == current) "● $id" else "    $id")
            }
            val locked = corePreferences.accountLocked
            if (!locked) items.add("➕   " + getString(R.string.ap_add_account))
            coreContext.postOnMainThread {
                if (!isAdded) return@postOnMainThread
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.ap_switch_account)
                    .setItems(items.toTypedArray()) { _, which ->
                        when {
                            which < refs.size ->
                                org.linphone.ui.main.AccountSwitcher.switch(requireActivity(), refs[which])
                            !locked && which == refs.size ->
                                startActivity(
                                    android.content.Intent(
                                        requireActivity(),
                                        org.linphone.ui.assistant.AssistantActivity::class.java
                                    )
                                )
                        }
                    }
                    .show()
            }
        }
    }

    // ---- Ext-locked build: the agent enters the (un-embedded) password once on first launch ----
    // (lockedPasswordDialogShowing lives in the companion object — see note there.)

    private fun promptLockedPasswordIfNeeded() {
        if (!corePreferences.accountLocked || !corePreferences.lockedAskPassword) return
        if (corePreferences.lockedPasswordEntered || lockedPasswordDialogShowing) return
        // Claim the "showing" flag NOW, synchronously on the main thread, BEFORE the async core-thread
        // hop. The notification-permission prompt makes onResume fire twice in a row; without an
        // immediate claim, the 2nd call slips past the guard while the 1st is still in flight and we
        // stack a SECOND password dialog ("asks twice"). Released below if there's nothing to show.
        lockedPasswordDialogShowing = true
        coreContext.postOnCoreThread { core ->
            val account = core.defaultAccount
            val id = account?.params?.identityAddress
            val user = id?.username.orEmpty()
            val domain = id?.domain.orEmpty()
            // Already authenticated? Either the account is registered, or a credential is stored —
            // liblinphone replaces the plaintext password with an ha1 hash after a successful REGISTER,
            // so check BOTH `password` and `ha1`. If authenticated, remember it (persisted) and never re-ask.
            val authed = account?.state == org.linphone.core.RegistrationState.Ok ||
                core.authInfoList.any {
                    it.username == user && (!it.password.isNullOrEmpty() || !it.ha1.isNullOrEmpty())
                }
            if (authed) corePreferences.lockedPasswordEntered = true
            coreContext.postOnMainThread {
                if (!authed && account != null && isAdded && user.isNotEmpty()) {
                    showLockedPasswordDialog(user, domain) // keeps lockedPasswordDialogShowing = true
                } else {
                    lockedPasswordDialogShowing = false // nothing to show — release the claim
                }
            }
        }
    }

    private fun showLockedPasswordDialog(user: String, domain: String) {
        lockedPasswordDialogShowing = true
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.ap_hint_password)
            setSingleLine()
            enablePasswordToggle()
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setCancelable(false)
            .setTitle(getString(R.string.ap_connect_title_fmt, user))
            .setMessage("$user@$domain\n" + getString(R.string.ap_enter_password_to_connect))
            .setView(container)
            .setPositiveButton(R.string.ap_connect, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pwd = input.text.toString()
                if (pwd.isBlank()) {
                    android.widget.Toast.makeText(ctx, R.string.ap_toast_enter_password, android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    dialog.setMessage(getString(R.string.ap_connecting))
                    applyLockedPassword(user, domain, pwd, dialog)
                }
            }
        }
        dialog.show()
    }

    private fun applyLockedPassword(user: String, domain: String, pwd: String, dialog: AlertDialog) {
        coreContext.postOnCoreThread { core ->
            // Swap the empty embedded auth-info for one carrying the agent's password, then re-register.
            core.authInfoList.filter { it.username == user }.forEach { core.removeAuthInfo(it) }
            val userId = corePreferences.lockedAccountAuthId.trim().ifEmpty { null }
            core.addAuthInfo(
                org.linphone.core.Factory.instance().createAuthInfo(user, userId, pwd, null, null, domain)
            )
            val listener = object : org.linphone.core.CoreListenerStub() {
                override fun onAccountRegistrationStateChanged(
                    core: org.linphone.core.Core,
                    account: org.linphone.core.Account,
                    state: org.linphone.core.RegistrationState?,
                    message: String
                ) {
                    if (account != core.defaultAccount) return
                    when (state) {
                        org.linphone.core.RegistrationState.Ok -> {
                            core.removeListener(this)
                            corePreferences.lockedPasswordEntered = true // never re-ask after success
                            coreContext.postOnMainThread {
                                lockedPasswordDialogShowing = false
                                try { dialog.dismiss() } catch (_: Exception) {}
                            }
                        }
                        org.linphone.core.RegistrationState.Failed -> {
                            core.removeListener(this)
                            coreContext.postOnMainThread {
                                try {
                                    dialog.setMessage(getString(R.string.ap_wrong_password_try_again))
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                } catch (_: Exception) {}
                            }
                        }
                        else -> {}
                    }
                }
            }
            core.addListener(listener)
            core.refreshRegisters()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync toggles in case they were changed elsewhere
        binding.chkDnd.isChecked = corePreferences.dndEnabled
        binding.chkAutoAns.isChecked = corePreferences.autoAnswerEnabled
        binding.chkAutoDisp.isChecked = corePreferences.autoDispEnabled
        renderSpeedDials()
        promptLockedPasswordIfNeeded()
    }

    override fun onDefaultAccountChanged() {
        // No-op for now
    }

    private fun setupDialpad() {
        val appendDigit = { digit: String ->
            val current = binding.phoneInput.text.toString()
            binding.phoneInput.setText(current + digit)
            binding.phoneInput.setSelection(binding.phoneInput.text.length)
            // Audible DTMF feedback while typing (forced on, regardless of the system dial-tone setting).
            if (digit.isNotEmpty()) {
                coreContext.postOnCoreThread { coreContext.playDtmf(digit[0], ignoreSystemPolicy = true) }
            }
        }

        binding.btn1.root.setOnClickListener { appendDigit("1") }
        binding.btn2.root.setOnClickListener { appendDigit("2") }
        binding.btn3.root.setOnClickListener { appendDigit("3") }
        binding.btn4.root.setOnClickListener { appendDigit("4") }
        binding.btn5.root.setOnClickListener { appendDigit("5") }
        binding.btn6.root.setOnClickListener { appendDigit("6") }
        binding.btn7.root.setOnClickListener { appendDigit("7") }
        binding.btn8.root.setOnClickListener { appendDigit("8") }
        binding.btn9.root.setOnClickListener { appendDigit("9") }
        binding.btn0.root.setOnClickListener { appendDigit("0") }
        binding.btnStar.root.setOnClickListener { appendDigit("*") }
        binding.btnHash.root.setOnClickListener { appendDigit("#") }

        binding.btnDelete.setOnClickListener {
            val current = binding.phoneInput.text.toString()
            if (current.isNotEmpty()) {
                binding.phoneInput.setText(current.substring(0, current.length - 1))
                binding.phoneInput.setSelection(binding.phoneInput.text.length)
            }
        }

        binding.btnCall.setOnClickListener {
            val number = binding.phoneInput.text.toString()
            if (number.isNotEmpty()) {
                dial(number)
            } else {
                android.widget.Toast.makeText(
                    requireContext(),
                    getString(R.string.ap_toast_enter_number_first),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        binding.btnRefresh.setOnClickListener {
            Log.i("$TAG Refreshing account registrations")
            coreContext.postOnCoreThread { core ->
                core.refreshRegisters()
            }
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.ap_toast_refreshing_registration),
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }

        binding.speedDialAdd.setOnClickListener {
            showAddSpeedDialDialog()
        }
    }

    private fun dial(number: String) {
        if (number.isEmpty()) return
        Log.i("$TAG Starting call to [$number]")
        coreContext.postOnCoreThread { core ->
            val address = core.interpretUrl(
                number,
                LinphoneUtils.applyInternationalPrefix()
            )
            if (address != null) {
                Log.i("$TAG Calling [${address.asStringUriOnly()}]")
                coreContext.startAudioCall(address)
            } else {
                Log.e("$TAG Failed to parse [$number] as SIP address")
            }
        }
    }

    // region Toggles (DND / Auto Answer)

    private fun setupToggles() {
        binding.chkDnd.isChecked = corePreferences.dndEnabled
        binding.chkAutoAns.isChecked = corePreferences.autoAnswerEnabled
        binding.chkAutoDisp.isChecked = corePreferences.autoDispEnabled

        binding.chkAutoDisp.setOnCheckedChangeListener { _, checked ->
            Log.i("$TAG Auto disposition set to [$checked]")
            coreContext.postOnCoreThread { corePreferences.autoDispEnabled = checked }
        }
        binding.autoDispLabel.setOnClickListener { binding.chkAutoDisp.toggle() }

        binding.chkDnd.setOnCheckedChangeListener { _, checked ->
            Log.i("$TAG Do Not Disturb set to [$checked]")
            coreContext.postOnCoreThread {
                corePreferences.dndEnabled = checked
                // Toggling the checkbox off (or on) clears any running DND timer
                if (!checked) corePreferences.dndUntilEpochSeconds = 0
            }
        }
        binding.chkAutoAns.setOnCheckedChangeListener { _, checked ->
            Log.i("$TAG Auto answer set to [$checked]")
            coreContext.postOnCoreThread { corePreferences.autoAnswerEnabled = checked }
        }

        // Make the whole label tappable, not just the small checkbox
        binding.dndLabel.setOnClickListener { binding.chkDnd.toggle() }
        binding.autoAnsLabel.setOnClickListener { binding.chkAutoAns.toggle() }
        binding.dndTimer.setOnClickListener { showDndTimerDialog() }
    }

    private fun showDndTimerDialog() {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()
        val input = EditText(ctx).apply {
            hint = getString(R.string.ap_minutes)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText("30")
            setSelection(text.length)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        val nowSec = System.currentTimeMillis() / 1000L
        val until = corePreferences.dndUntilEpochSeconds
        val timerActive = corePreferences.dndEnabled && until > nowSec

        val builder = AlertDialog.Builder(ctx).setTitle(R.string.ap_dnd_timer_title)
        if (timerActive) {
            builder.setMessage(
                getString(R.string.ap_dnd_active_message_fmt, formatMs((until - nowSec) * 1000L))
            )
            builder.setNeutralButton(R.string.ap_turn_off) { _, _ ->
                binding.chkDnd.isChecked = false
            }
        } else {
            builder.setMessage(R.string.ap_dnd_message)
        }
        builder.setView(container)
            .setPositiveButton(R.string.ap_start) { _, _ ->
                val minutes = input.text.toString().trim().toIntOrNull() ?: 0
                if (minutes > 0) {
                    enableDndForMinutes(minutes)
                } else {
                    android.widget.Toast.makeText(ctx, R.string.ap_toast_enter_valid_minutes, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun enableDndForMinutes(minutes: Int) {
        val untilSeconds = (System.currentTimeMillis() / 1000L + minutes * 60L).toInt()
        // Reflect the checkbox first; its listener does not clear the timer when turning ON,
        // so the timestamp we set right after is kept.
        binding.chkDnd.isChecked = true
        coreContext.postOnCoreThread {
            corePreferences.dndEnabled = true
            corePreferences.dndUntilEpochSeconds = untilSeconds
        }
        Log.i("$TAG Do Not Disturb enabled for [$minutes] minutes")
        android.widget.Toast.makeText(
            requireContext(),
            getString(R.string.ap_toast_dnd_for_fmt, minutes),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    // endregion

    // region Uptime / Idle

    private fun startUptimeIdleTicker() {
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                updateUptimeIdle()
                updateDndTimer()
                delay(1000)
            }
        }
    }

    private fun updateDndTimer() {
        val grey = android.graphics.Color.parseColor("#757575")
        val red = android.graphics.Color.parseColor("#E74C3C")

        // Note: the "DND" label text is kept fixed so the row never grows and the third
        // toggle (Auto Disp) always stays visible. The clock icon turns red while a timer
        // is running and the remaining time is shown when tapping it.
        if (!corePreferences.dndEnabled || corePreferences.dndUntilEpochSeconds <= 0) {
            binding.dndTimer.setColorFilter(grey)
            return
        }
        val remainingSec = corePreferences.dndUntilEpochSeconds - (System.currentTimeMillis() / 1000L)
        if (remainingSec <= 0L) {
            // Timer elapsed: turn DND off so calls are received again
            Log.i("$TAG DND timer elapsed, disabling Do Not Disturb")
            coreContext.postOnCoreThread {
                corePreferences.dndEnabled = false
                corePreferences.dndUntilEpochSeconds = 0
            }
            if (binding.chkDnd.isChecked) binding.chkDnd.isChecked = false
            binding.dndTimer.setColorFilter(grey)
        } else {
            binding.dndTimer.setColorFilter(red)
        }
    }

    private fun updateUptimeIdle() {
        val now = SystemClock.elapsedRealtime()
        val registeredSince = coreContext.sipRegisteredSinceTimestamp
        val lastCallEnded = coreContext.lastCallEndedTimestamp

        val uptimeMs = if (registeredSince > 0L) now - registeredSince else 0L
        val idleBase = maxOf(registeredSince, lastCallEnded)
        val idleMs = if (idleBase > 0L) now - idleBase else 0L

        val uptimePart = getString(R.string.ap_uptime_fmt, formatHms(uptimeMs)) + "   |   "
        val idlePart = "● " + getString(R.string.ap_idle_fmt, formatMs(idleMs))
        val span = android.text.SpannableString(uptimePart + idlePart)
        span.setSpan(
            android.text.style.ForegroundColorSpan(android.graphics.Color.parseColor("#2ECC71")),
            uptimePart.length,
            uptimePart.length + idlePart.length,
            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        binding.statusUptime.text = span
    }

    private fun formatHms(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    private fun formatMs(ms: Long): String {
        val totalSeconds = ms / 1000
        val m = totalSeconds / 60
        val s = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", m, s)
    }

    // endregion

    // region Server line (host:port via TRANSPORT)

    private fun updateServerLine() {
        val model = mainViewModel.account.value
        val text = try {
            val server = model?.account?.params?.serverAddress
            if (server != null) {
                val transport = server.transport.name.uppercase(Locale.US) // UDP / TCP / TLS / DTLS
                val port = if (server.port > 0) {
                    server.port
                } else if (server.transport == TransportType.Tls) {
                    5061
                } else {
                    5060
                }
                getString(R.string.ap_server_via_fmt, server.domain, port, transport)
            } else {
                getString(R.string.ap_no_server)
            }
        } catch (e: Exception) {
            Log.e("$TAG Failed to build server line: $e")
            getString(R.string.ap_no_server)
        }
        binding.statusServer.text = text
    }

    // endregion

    // region Speed dial

    private fun setupSpeedDial() {
        renderSpeedDials()
    }

    private fun loadSpeedDials(): MutableList<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val raw = requireContext()
            .getSharedPreferences(AccountScope.prefsName(SPEED_DIAL_PREFS), Context.MODE_PRIVATE)
            .getString(SPEED_DIAL_KEY, "[]") ?: "[]"
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(obj.optString("name") to obj.optString("number"))
            }
        } catch (e: Exception) {
            Log.e("$TAG Failed to parse speed-dials: $e")
        }
        return list
    }

    private fun saveSpeedDials(list: List<Pair<String, String>>) {
        val array = JSONArray()
        for (entry in list) {
            val obj = JSONObject()
            obj.put("name", entry.first)
            obj.put("number", entry.second)
            array.put(obj)
        }
        requireContext()
            .getSharedPreferences(AccountScope.prefsName(SPEED_DIAL_PREFS), Context.MODE_PRIVATE)
            .edit()
            .putString(SPEED_DIAL_KEY, array.toString())
            .apply()
    }

    private fun renderSpeedDials() {
        val container = binding.speedDialList
        container.removeAllViews()

        val entries = loadSpeedDials()
        // The chips strip replaces the prompt text in-place (same row) so the keypad
        // below keeps its size and the digit letters stay visible.
        binding.speedDialScroll.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
        binding.speedDialText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

        val inflater = LayoutInflater.from(requireContext())
        for (entry in entries) {
            val chip = inflater.inflate(R.layout.speed_dial_chip, container, false)
            val nameView = chip.findViewById<TextView>(R.id.chip_name)
            val numberView = chip.findViewById<TextView>(R.id.chip_number)
            nameView.text = if (entry.first.isNotEmpty()) entry.first else entry.second
            numberView.text = entry.second
            chip.setOnClickListener { dial(entry.second) }
            chip.setOnLongClickListener {
                confirmDeleteSpeedDial(entry)
                true
            }
            container.addView(chip)
        }
    }

    private fun showAddSpeedDialDialog() {
        val ctx = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()

        val nameInput = EditText(ctx).apply {
            hint = getString(R.string.ap_hint_name_optional)
            setSingleLine()
        }
        val numberInput = EditText(ctx).apply {
            hint = getString(R.string.ap_number)
            inputType = InputType.TYPE_CLASS_PHONE
            setSingleLine()
        }
        val prefill = binding.phoneInput.text.toString().trim()
        if (prefill.isNotEmpty()) numberInput.setText(prefill)

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(nameInput)
            addView(numberInput)
        }

        AlertDialog.Builder(ctx)
            .setTitle(R.string.ap_add_speed_dial)
            .setView(container)
            .setPositiveButton(R.string.ap_add) { _, _ ->
                val number = numberInput.text.toString().trim()
                if (number.isNotEmpty()) {
                    val name = nameInput.text.toString().trim()
                    val list = loadSpeedDials()
                    list.add(name to number)
                    saveSpeedDials(list)
                    renderSpeedDials()
                } else {
                    android.widget.Toast.makeText(ctx, R.string.ap_toast_number_required, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun confirmDeleteSpeedDial(entry: Pair<String, String>) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.ap_remove_speed_dial)
            .setMessage(if (entry.first.isNotEmpty()) entry.first else entry.second)
            .setPositiveButton(R.string.dialog_remove) { _, _ ->
                val list = loadSpeedDials()
                list.removeAll { it.first == entry.first && it.second == entry.second }
                saveSpeedDials(list)
                renderSpeedDials()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // endregion
}
