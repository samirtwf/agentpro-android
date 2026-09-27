package org.linphone.ui.main.panel

import android.content.Context
import org.linphone.ui.main.AccountScope
import org.linphone.utils.SecretCipher

/** Connection settings for the PBX (Asterisk Manager Interface). */
data class PbxConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val serverType: String = "UCM"
) {
    fun isValid(): Boolean = host.isNotBlank() && username.isNotBlank() && port in 1..65535
}

/** Persists the PBX/AMI connection settings and the list of monitored extensions. */
object PbxPrefs {
    private const val PREFS = "agentpro_pbx"

    // No baked-in PBX identity/credentials — host/username/password are entered by the user in the
    // PBX Server settings (Panel) and persisted here. Port keeps the UCM AMI default (7777).
    private const val DEF_HOST = ""
    private const val DEF_PORT = 7777
    private const val DEF_USER = ""
    private const val DEF_PASS = ""

    // Per-account scope lives in AccountScope (the single source of truth shared by every
    // per-account store), so switching the active account re-points all of them at once. Empty
    // scope = the legacy single store (used before any account exists). Migration of the legacy
    // un-scoped store into the first account is handled centrally by AccountScope.activate().
    private fun prefsName(): String = AccountScope.prefsName(PREFS)

    private fun p(c: Context) = c.getSharedPreferences(prefsName(), Context.MODE_PRIVATE)

    fun load(c: Context): PbxConfig = PbxConfig(
        host = p(c).getString("host", DEF_HOST).orEmpty(),
        port = p(c).getInt("port", DEF_PORT),
        username = p(c).getString("user", DEF_USER).orEmpty(),
        password = SecretCipher.decrypt(p(c).getString("pass", DEF_PASS).orEmpty()),
        serverType = p(c).getString("type", "UCM").orEmpty()
    )

    fun save(c: Context, cfg: PbxConfig) {
        p(c).edit()
            .putString("host", cfg.host)
            .putInt("port", cfg.port)
            .putString("user", cfg.username)
            .putString("pass", SecretCipher.encrypt(cfg.password))
            .putString("type", cfg.serverType)
            .apply()
    }

    fun isConfigured(c: Context): Boolean = load(c).isValid()

    fun monitored(c: Context): MutableList<String> {
        val s = p(c).getString("ext", "").orEmpty()
        return if (s.isBlank()) {
            mutableListOf()
        } else {
            s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        }
    }

    fun setMonitored(c: Context, list: List<String>) {
        p(c).edit().putString("ext", list.joinToString(",")).apply()
    }

    /** Extensions the user removed from the panel (hidden even if auto-discovered). */
    fun hidden(c: Context): MutableList<String> {
        val s = p(c).getString("hidden", "").orEmpty()
        return if (s.isBlank()) {
            mutableListOf()
        } else {
            s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        }
    }

    fun setHidden(c: Context, list: List<String>) {
        p(c).edit().putString("hidden", list.joinToString(",")).apply()
    }

    /** Extensions THIS account is allowed to monitor — synced from the UCM "Call Monitoring Allowlist"
     * (the extension's callbarging_monitor). Empty = not synced yet / "All" mode → panel falls back to
     * auto-discovering every extension. */
    fun allowlist(c: Context): List<String> {
        val s = p(c).getString("allowlist", "").orEmpty()
        return if (s.isBlank()) emptyList() else s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setAllowlist(c: Context, list: List<String>) {
        p(c).edit().putString("allowlist", list.joinToString(",")).apply()
    }

    // Grandstream UCM "Call Barging" feature codes — dialed (code + target ext) to spy.
    // Defaults match the UCM defaults; editable in PBX settings.
    fun listenCode(c: Context) = code(c, "listen_code", "*54")

    fun whisperCode(c: Context) = code(c, "whisper_code", "*55")

    fun bargeCode(c: Context) = code(c, "barge_code", "*56")

    private fun code(c: Context, key: String, def: String) =
        p(c).getString(key, def).orEmpty().ifBlank { def }

    fun setSpyCodes(c: Context, listen: String, whisper: String, barge: String) {
        p(c).edit()
            .putString("listen_code", listen.ifBlank { "*54" })
            .putString("whisper_code", whisper.ifBlank { "*55" })
            .putString("barge_code", barge.ifBlank { "*56" })
            .apply()
    }

    // Grandstream UCM HTTPS API ("PBX Portal", port 8089) — used by the CDR tab to query call
    // records. The API host is the same server as the AMI host. Credentials are entered by the user
    // in the PBX Server settings (Panel); port keeps the UCM HTTPS-API default (8089).
    fun apiPort(c: Context) = p(c).getInt("api_port", 8089)

    fun apiUser(c: Context) = p(c).getString("api_user", "").orEmpty()

    fun apiPass(c: Context) = SecretCipher.decrypt(p(c).getString("api_pass", "").orEmpty())

    fun setApi(c: Context, port: Int, user: String, pass: String) {
        p(c).edit()
            .putInt("api_port", if (port in 1..65535) port else 8089)
            .putString("api_user", user)
            .putString("api_pass", SecretCipher.encrypt(pass))
            .apply()
    }

    // UCM web GUI login (for the in-app WebView that opens the PBX's own CDR page).
    // This is the web/user-portal login, separate from the AMI and HTTPS-API users.
    // No baked-in default — the user enters these in the CDR settings (gear) and they persist here.
    fun webUser(c: Context) = p(c).getString("web_user", "").orEmpty()

    fun webPass(c: Context) = SecretCipher.decrypt(p(c).getString("web_pass", "").orEmpty())

    fun setWeb(c: Context, user: String, pass: String) {
        p(c).edit().putString("web_user", user).putString("web_pass", SecretCipher.encrypt(pass)).apply()
    }

    // Agent (call-queue) settings for the Agent tab — entered by the user, persisted here.
    // The queue member interface is "<protocol>/<ext>", e.g. PJSIP/5555.
    fun agentExt(c: Context) = p(c).getString("agent_ext", "").orEmpty()

    fun agentProtocol(c: Context) = p(c).getString("agent_proto", "PJSIP").orEmpty().ifBlank { "PJSIP" }

    fun setAgent(c: Context, ext: String, proto: String) {
        p(c).edit().putString("agent_ext", ext.trim()).putString("agent_proto", proto).apply()
    }

    fun agentQueues(c: Context): MutableList<String> {
        val s = p(c).getString("agent_queues", "").orEmpty()
        return if (s.isBlank()) {
            mutableListOf()
        } else {
            s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        }
    }

    fun setAgentQueues(c: Context, list: List<String>) {
        p(c).edit().putString("agent_queues", list.joinToString(",")).apply()
    }
}
