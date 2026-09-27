package org.linphone.ui.main.fragment

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.UiThread
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.ui.TrialExpiredActivity
import org.linphone.utils.ConfigBackup
import org.linphone.utils.LicenseManager
import org.linphone.utils.enablePasswordToggle

/**
 * AgentPro's unified Settings menu — a plain text list (no linphone logo), opened by the bottom-nav
 * Settings tab. Replaces the old account side-drawer: each row opens a settings area, and the
 * current PBX/connection settings is one of the rows.
 */
@UiThread
class SettingsMenuFragment : Fragment() {
    companion object {
        private const val TAG = "[Settings Menu]"

        // The interface languages, each in its own name, so someone who cannot read the current
        // language can still find theirs. The same 17 as Rattil; keep in step with
        // res/xml/locales_config.xml and the values-* folders. "in" is Android's Indonesian.
        private val APP_LANGUAGES = listOf(
            "ar" to "العربية",
            "en" to "English",
            "fr" to "Français",
            "de" to "Deutsch",
            "es" to "Español",
            "pt" to "Português",
            "ru" to "Русский",
            "zh" to "中文",
            "tr" to "Türkçe",
            "fa" to "فارسی",
            "ur" to "اردو",
            "ps" to "پښتو",
            "hi" to "हिन्दी",
            "bn" to "বাংলা",
            "in" to "Bahasa Indonesia",
            "ms" to "Bahasa Melayu",
            "sw" to "Kiswahili"
        )
    }

    // Picks an encrypted .apcfg backup to import; the password is asked once a file is chosen.
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            promptPassword(getString(R.string.ap_import_configuration), getString(R.string.ap_import)) { pwd -> importConfig(uri, pwd) }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.settings_menu_fragment, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<ImageView>(R.id.menu_back).setOnClickListener {
            findNavController().popBackStack()
        }
        val accountsRow = view.findViewById<TextView>(R.id.menu_accounts)
        accountsRow.setOnClickListener {
            go(R.id.action_global_accountsListFragment)
        }
        // Locked build = a single embedded account, so hide multi-account management.
        if (corePreferences.accountLocked) {
            accountsRow.visibility = View.GONE
        }
        val pbxRow = view.findViewById<TextView>(R.id.menu_pbx)
        pbxRow.setOnClickListener {
            go(R.id.action_global_pbxServerFragment)
        }
        // AgentPro "locked build": PBX/AMI/UCM settings are embedded & immutable, so hide the row.
        if (corePreferences.accountLocked) {
            pbxRow.visibility = View.GONE
        }
        view.findViewById<TextView>(R.id.menu_app_settings).setOnClickListener {
            go(R.id.action_global_settingsFragment)
        }
        view.findViewById<TextView>(R.id.menu_language).setOnClickListener {
            showLanguagePicker()
        }
        view.findViewById<TextView>(R.id.menu_guide).setOnClickListener {
            go(R.id.action_global_userGuideFragment)
        }
        view.findViewById<TextView>(R.id.menu_export).setOnClickListener {
            promptPassword(getString(R.string.ap_export_configuration_title), getString(R.string.ap_export)) { exportConfig(it) }
        }
        view.findViewById<TextView>(R.id.menu_import).setOnClickListener {
            importLauncher.launch(arrayOf("*/*"))
        }
        view.findViewById<TextView>(R.id.menu_quit).setOnClickListener {
            coreContext.stopKeepAliveService()
            coreContext.postOnCoreThread { coreContext.quitSafely() }
            requireActivity().finishAndRemoveTask()
        }

        // White-label company branding (moved off the dialpad to here): the company logo
        // (assets/company_logo.png) above an optional "Made especially for <company>" line.
        val companyIcon = view.findViewById<ImageView>(R.id.company_icon)
        var hasCompanyLogo = false
        try {
            requireContext().assets.open("company_logo.png").use { stream ->
                val bmp = android.graphics.BitmapFactory.decodeStream(stream)
                if (bmp != null) {
                    companyIcon.setImageBitmap(bmp)
                    hasCompanyLogo = true
                }
            }
        } catch (e: Exception) {
            // No embedded company logo in this build
        }
        companyIcon.visibility = if (hasCompanyLogo) View.VISIBLE else View.GONE

        val companyName = getString(R.string.agentpro_company_name).trim()
        val companyText = view.findViewById<TextView>(R.id.company_credit_text)
        if (companyName.isNotEmpty()) {
            companyText.text = getString(R.string.ap_made_especially_for_fmt, companyName)
        } else {
            companyText.visibility = View.GONE
        }
        if (hasCompanyLogo || companyName.isNotEmpty()) {
            view.findViewById<View>(R.id.company_credit).visibility = View.VISIBLE
        }

        // About — contact the developer (moved here from the PBX settings screen).
        view.findViewById<TextView>(R.id.about_email).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:samirtwf@gmail.com")))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "samirtwf@gmail.com", Toast.LENGTH_SHORT).show()
            }
        }
        view.findViewById<TextView>(R.id.about_whatsapp).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/966552486524")))
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+966552486524")))
                } catch (e2: Exception) {
                    Toast.makeText(requireContext(), "00966552486524", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // License status: Trial builds show remaining time / "Activated"; Full/Locked show "Full version".
        view.findViewById<TextView>(R.id.about_license)?.text =
            getString(R.string.ap_license_fmt, LicenseManager.statusSummary(requireContext()))
        view.findViewById<TextView>(R.id.about_activate)?.let { activate ->
            val canActivate = LicenseManager.isTrialBuild(requireContext()) &&
                !LicenseManager.isActivated(requireContext())
            activate.visibility = if (canActivate) View.VISIBLE else View.GONE
            activate.setOnClickListener {
                startActivity(
                    Intent(requireContext(), TrialExpiredActivity::class.java)
                        .putExtra(TrialExpiredActivity.EXTRA_VOLUNTARY, true)
                )
            }
        }

        // Hidden developer gesture: 7 quick taps on the company line force-expires a Trial build
        // so the trial-ended screen can be previewed instantly. No effect on Full/Locked builds.
        view.findViewById<TextView>(R.id.about_company)?.let { company ->
            var taps = 0
            var lastTap = 0L
            company.setOnClickListener {
                val now = System.currentTimeMillis()
                taps = if (now - lastTap < 800) taps + 1 else 1
                lastTap = now
                if (taps >= 7) {
                    taps = 0
                    if (LicenseManager.isTrialBuild(requireContext())) {
                        LicenseManager.forceExpireForTest(requireContext())
                        Toast.makeText(requireContext(), "Trial force-expired (test)", Toast.LENGTH_SHORT).show()
                        requireActivity().recreate()
                    }
                }
            }
        }
    }

    private fun go(actionId: Int) {
        try {
            findNavController().navigate(actionId)
        } catch (e: Exception) {
            Log.e("$TAG Cannot navigate: $e")
        }
    }

    // AgentPro: in-app language switcher. Uses AndroidX per-app locales so the whole app
    // language changes immediately and the choice is kept; "System default" clears the override.
    private fun showLanguagePicker() {
        val names = arrayOf(getString(R.string.settings_user_interface_language_system_default)) +
            APP_LANGUAGES.map { it.second }
        val tags = listOf("") + APP_LANGUAGES.map { it.first }
        val active = AppCompatDelegate.getApplicationLocales()
        val activeTag = if (active.isEmpty) "" else active[0]?.language.orEmpty()
        val checked = tags.indexOf(activeTag).let { if (it >= 0) it else 0 }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_user_interface_language_title)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val tag = tags[which]
                Log.i("$TAG Selected app language is now [$tag]")
                val locales = if (tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
                AppCompatDelegate.setApplicationLocales(locales)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    // Ask for a password (min 4 chars) in a simple dialog, then invoke [onPassword].
    private fun promptPassword(title: String, positive: String, onPassword: (String) -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.ap_hint_password)
            enablePasswordToggle()
        }
        val pad = (16 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(container)
            .setNegativeButton(R.string.dialog_cancel, null)
            .setPositiveButton(positive) { _, _ ->
                val pwd = input.text.toString()
                if (pwd.length < 4) {
                    toast(getString(R.string.ap_toast_password_min_4))
                } else {
                    onPassword(pwd)
                }
            }
            .show()
    }

    // Export: dump the Core config (Core thread), encrypt with [password] (IO), share the .apcfg file.
    private fun exportConfig(password: String) {
        coreContext.postOnCoreThread { core ->
            core.config.sync()
            val linphonerc = core.config.dump()
            coreContext.postOnMainThread {
                if (!isAdded) return@postOnMainThread
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val ctx = requireContext()
                        val file = withContext(Dispatchers.IO) {
                            val blob = ConfigBackup.export(ctx, linphonerc, password)
                            File(ctx.cacheDir, "AgentPro_Config.apcfg").apply { writeBytes(blob) }
                        }
                        val uri = FileProvider.getUriForFile(ctx, getString(R.string.file_provider), file)
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/octet-stream"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(share, getString(R.string.ap_export_configuration_title)))
                    } catch (e: Exception) {
                        Log.e("$TAG Export failed: $e")
                        toast(getString(R.string.ap_toast_export_failed_fmt, e.message.orEmpty()))
                    }
                }
            }
        }
    }

    // Import: read + decrypt the chosen file (IO); on success restart the app to apply it.
    private fun importConfig(uri: Uri, password: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext()
            val ok = withContext(Dispatchers.IO) {
                try {
                    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) false else ConfigBackup.restore(ctx, bytes, password)
                } catch (e: Exception) {
                    Log.e("$TAG Import failed: $e")
                    false
                }
            }
            if (!isAdded) return@launch
            if (ok) {
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.ap_config_imported_title)
                    .setMessage(R.string.ap_config_imported_message)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ap_restart) { _, _ ->
                        ConfigBackup.restartApp(ctx.applicationContext)
                    }
                    .show()
            } else {
                toast(getString(R.string.ap_toast_wrong_password_or_file))
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }
}
