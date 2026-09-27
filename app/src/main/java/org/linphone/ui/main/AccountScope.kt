package org.linphone.ui.main

import android.content.Context
import java.io.File
import org.linphone.core.tools.Log

/**
 * Single source of truth for the **active SIP account's storage scope**.
 *
 * AgentPro keeps a separate copy of every per-account thing — PBX/AMI/UCM/Agent settings, call
 * dispositions, the Campaign list, the Contacts list, the auto-dialer delay and the dial-pad
 * speed-dials — so that switching the active account switches ALL of that data with it. The
 * Panel/CDR/Agent/Wallboard follow the new account's PBX, and Disp/Campaign/Contacts show the new
 * account's own lists.
 *
 * Each store keys its SharedPreferences-file name (or data-file name) off [scope]:
 *  - empty scope  -> the legacy un-scoped name (used before any account exists)
 *  - "<id>" scope -> "<base>__<id>"  (prefs)  /  "<name>__<id>.json"  (files)
 *
 * [activate] is the single entry point; it is called at Core start-up (with the default account)
 * and on every account switch (see [AccountSwitcher]). The first time a non-empty scope is
 * activated it migrates the old un-scoped data into that account, so an existing single-account
 * setup keeps everything it had.
 */
object AccountScope {
    private const val TAG = "[Account Scope]"
    private const val FLAGS = "agentpro_scope_flags"

    // SharedPreferences stores that are scoped per account.
    private val PREF_BASES = listOf(
        "agentpro_pbx", // PBX/AMI/UCM/Agent connection settings (PbxPrefs)
        "agentpro_dispositions", // call dispositions (DispositionStore)
        "agentpro_campaign", // auto-dialer delay (CampaignFragment)
        "agentpro_speed_dials" // dial-pad speed-dials (AgentProDialpadFragment)
    )

    // Data files (in filesDir) that are scoped per account.
    private val FILE_BASES = listOf(
        "campaign.json", // Campaign list (CampaignStore)
        "contacts.json" // Contacts list (ContactsListFragment via CampaignStore)
    )

    @Volatile
    var scope: String = ""
        private set

    private fun sanitize(identity: String?): String =
        (identity ?: "").trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

    /** Scoped SharedPreferences name for [base] ("agentpro_pbx" -> "agentpro_pbx__<scope>"). */
    fun prefsName(base: String): String = if (scope.isEmpty()) base else base + "__" + scope

    /** Scoped data-file name for [base] ("campaign.json" -> "campaign__<scope>.json"). */
    fun fileName(base: String): String {
        if (scope.isEmpty()) return base
        val dot = base.lastIndexOf('.')
        return if (dot > 0) {
            base.substring(0, dot) + "__" + scope + base.substring(dot)
        } else {
            base + "__" + scope
        }
    }

    /**
     * Point every per-account store at [identity]'s own data. Call at start-up and on each switch.
     * On the first non-empty scope it migrates the legacy un-scoped data into that account once.
     */
    fun activate(context: Context, identity: String?) {
        scope = sanitize(identity)
        if (scope.isEmpty()) return
        for (base in PREF_BASES) migratePrefsOnce(context, base)
        for (base in FILE_BASES) migrateFileOnce(context, base)
    }

    // ---- one-time legacy migration (un-scoped data -> the FIRST account it is activated for) ----
    // A global once-flag (per base) guarantees the legacy data lands in the first/default account
    // only; every account activated afterwards starts empty for that store.

    private fun migratePrefsOnce(context: Context, base: String) {
        val flags = context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
        val flagKey = "migrated_$base"
        if (flags.getBoolean(flagKey, false)) return
        flags.edit().putBoolean(flagKey, true).apply()

        val legacy = context.getSharedPreferences(base, Context.MODE_PRIVATE)
        val legacyAll = legacy.all
        if (legacyAll.isEmpty()) return
        val scoped = context.getSharedPreferences(prefsName(base), Context.MODE_PRIVATE)
        if (scoped.all.isNotEmpty()) return // target already populated (e.g. PbxPrefs migrated before)

        val e = scoped.edit()
        for ((k, v) in legacyAll) {
            when (v) {
                is String -> e.putString(k, v)
                is Int -> e.putInt(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> ->
                    @Suppress("UNCHECKED_CAST")
                    e.putStringSet(k, v as Set<String>)
            }
        }
        e.apply()
        Log.i("$TAG Migrated legacy [$base] into scope [$scope]")
    }

    private fun migrateFileOnce(context: Context, base: String) {
        val flags = context.getSharedPreferences(FLAGS, Context.MODE_PRIVATE)
        val flagKey = "migrated_file_$base"
        if (flags.getBoolean(flagKey, false)) return
        flags.edit().putBoolean(flagKey, true).apply()

        val legacy = File(context.filesDir, base)
        if (!legacy.exists()) return
        val scoped = File(context.filesDir, fileName(base))
        if (scoped.exists()) return
        try {
            legacy.copyTo(scoped, overwrite = false)
            Log.i("$TAG Migrated legacy file [$base] into scope [$scope]")
        } catch (e: Exception) {
            Log.e("$TAG Failed to migrate file [$base]: $e")
        }
    }
}
