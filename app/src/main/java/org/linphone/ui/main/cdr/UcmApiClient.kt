package org.linphone.ui.main.cdr

import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONObject
import org.linphone.core.tools.Log

/** One call detail record returned by the UCM cdrapi. */
data class CdrRecord(
    val start: String,
    val src: String,
    val dst: String,
    val durationSec: Int,
    val billSec: Int,
    val disposition: String,
    val recordFile: String,
    val id: String = "" // unique call id (uniqueid/AcctId) — used to de-duplicate across day batches
)

/**
 * Client for the Grandstream UCM63xx HTTPS API (default port 8089), used by the CDR tab.
 *
 * Auth is challenge/response: POST {action:challenge} -> challenge string; token = MD5(challenge +
 * password); POST {action:login, token} -> session cookie. Then {action:cdrapi} returns the call
 * records. (Recordings are played in the UCM web GUI via the in-app PBX page, not downloaded here.)
 * The UCM uses a self-signed certificate, so these requests trust all certificates (acceptable for
 * the user's own LAN PBX). All methods block and must be called off the main thread.
 */
object UcmApiClient {
    private const val TAG = "[UCM API]"

    private val trustAllContext: SSLContext by lazy {
        val trustManagers = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}

            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}

            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        SSLContext.getInstance("TLS").apply { init(null, trustManagers, SecureRandom()) }
    }
    private val allowAllHosts = HostnameVerifier { _, _ -> true }

    /** Returns a session cookie, or null on failure. */
    fun login(host: String, port: Int, user: String, pass: String): String? {
        val challengeReq = JSONObject().put(
            "request",
            JSONObject().put("action", "challenge").put("user", user).put("version", "1.0")
        )
        val ch = postApi(host, port, challengeReq.toString()) ?: return null
        val challenge = ch.optJSONObject("response")?.optString("challenge").orEmpty()
        if (challenge.isEmpty()) {
            Log.e("$TAG No challenge in response: $ch")
            return null
        }
        val token = md5(challenge + pass)
        val loginReq = JSONObject().put(
            "request",
            JSONObject().put("action", "login").put("token", token).put("user", user)
        )
        val lg = postApi(host, port, loginReq.toString())
        if (lg == null || lg.optInt("status", -1) != 0) {
            Log.e("$TAG Login failed: $lg")
            return null
        }
        return lg.optJSONObject("response")?.optString("cookie").orEmpty().ifBlank { null }
    }

    /** Reachability test for the web GUI: true if https://host:port/ returns any HTTP response. */
    fun isReachable(host: String, port: Int): Boolean {
        return try {
            val conn = open(URL("https://$host:$port/"))
            conn.requestMethod = "GET"
            conn.connectTimeout = 6000
            conn.readTimeout = 6000
            conn.responseCode > 0
        } catch (e: Exception) {
            false
        }
    }

    fun queryCdr(
        host: String,
        port: Int,
        cookie: String,
        startTime: String,
        endTime: String
    ): List<CdrRecord> {
        // Fetch ONE DAY at a time — exactly like the reference desktop tool (CDR_BATCH_BY_DAY, 1-day
        // batches). A single day's records sit well under the UCM's per-request cap, so we avoid the
        // wide-range high-offset paging that the UCM mis-handles (it silently drops records at large
        // offsets, which made our totals come out low). Within each day we still page until an empty
        // page so even an unusually busy day isn't truncated — that single un-paged request per day is
        // the one spot the desktop tool itself can under-count.
        val all = ArrayList<CdrRecord>()
        val seen = HashSet<String>() // a record on a day boundary can come back in two batches — dedup it
        val inFmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val apiFmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm", java.util.Locale.US)
        val start = try { inFmt.parse(startTime) } catch (e: Exception) { null } ?: return all
        val end = try { inFmt.parse(endTime) } catch (e: Exception) { null } ?: return all
        val cal = java.util.Calendar.getInstance().apply { time = start }
        var dayGuard = 0
        while (cal.time.before(end) && dayGuard++ < 1000) {
            val batchStart = cal.time
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            val batchEnd = if (cal.time.after(end)) end else cal.time
            var offset = 0
            while (offset < 50_000) {
                val resp = cdrApiJson(host, port, cookie, apiFmt.format(batchStart), apiFmt.format(batchEnd), 1000, offset)
                    ?: break
                val array = resp.optJSONArray("cdr_root") ?: break
                val n = array.length()
                if (n == 0) break
                for (rec in parseCdrArray(array)) {
                    if (rec.id.isEmpty() || seen.add(rec.id)) all.add(rec)
                }
                offset += n
            }
        }
        return all
    }

    /** One cdrapi page (numRecords + offset). UCM63xx returns a top-level "cdr_root" array. */
    fun cdrApiJson(
        host: String,
        port: Int,
        cookie: String,
        startTime: String,
        endTime: String,
        numRecords: Int,
        offset: Int
    ): JSONObject? {
        val req = JSONObject().put(
            "request",
            JSONObject()
                .put("action", "cdrapi")
                .put("cookie", cookie)
                .put("format", "json")
                .put("startTime", startTime)
                .put("endTime", endTime)
                .put("numRecords", numRecords)
                .put("offset", offset)
        )
        return postApi(host, port, req.toString())
    }

    /** Parses one cdr_root array — each item's fields live in "main_cdr" (with per-leg "sub_cdr_N"). */
    private fun parseCdrArray(array: org.json.JSONArray): List<CdrRecord> {
        val list = ArrayList<CdrRecord>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val main = o.optJSONObject("main_cdr") ?: o
            var disposition = main.optString("disposition")
            var recordfiles = main.optString("recordfiles")
            var k = 1
            while ((disposition.isBlank() || recordfiles.isBlank()) && k <= 8) {
                val sub = o.optJSONObject("sub_cdr_$k") ?: break
                if (disposition.isBlank()) disposition = sub.optString("disposition")
                if (recordfiles.isBlank()) recordfiles = sub.optString("recordfiles")
                k++
            }
            list.add(
                CdrRecord(
                    start = main.optString("start"),
                    src = main.optString("src", main.optString("new_src")),
                    dst = main.optString("dst"),
                    durationSec = main.optString("duration").toIntOrNull() ?: 0,
                    billSec = main.optString("billsec").toIntOrNull() ?: 0,
                    disposition = disposition,
                    recordFile = recordfiles,
                    id = main.optString("uniqueid", main.optString("AcctId", main.optString("cdr")))
                )
            )
        }
        return list
    }

    /**
     * Returns this extension's "Call Monitoring Allowlist" — the extensions it is allowed to monitor —
     * read from its getSIPAccount "callbarging_monitor" field. Returns null on any failure (so the
     * caller keeps whatever it had), or an empty list when it monitors nobody / is in "All" mode.
     */
    fun getMonitorAllowlist(host: String, port: Int, cookie: String, extension: String): List<String>? {
        val req = JSONObject().put(
            "request",
            JSONObject().put("action", "getSIPAccount").put("cookie", cookie).put("extension", extension)
        )
        val resp = postApi(host, port, req.toString()) ?: return null
        if (resp.optInt("status", -1) != 0) return null
        val ext = resp.optJSONObject("response")?.optJSONObject("extension") ?: return null
        return ext.optString("callbarging_monitor", "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // Grandstream outbound permission ladder (updateSIPAccount `permission`).
    private const val PERMISSION_FULL = "internal-local-national-international"
    private const val PERMISSION_INTERNAL = "internal"

    /**
     * Sets an extension's outbound permission, then applies the change on the UCM.
     * `internal` = external calls disabled (internal + incoming still work); the full
     * value restores everything. Returns true on success.
     */
    fun setExtensionPermission(
        host: String,
        port: Int,
        cookie: String,
        extension: String,
        permission: String
    ): Boolean {
        val ext = extension.filter { it.isDigit() }
        if (ext.isEmpty()) return false
        val req = JSONObject().put(
            "request",
            JSONObject()
                .put("action", "updateSIPAccount")
                .put("cookie", cookie)
                .put("extension", ext)
                .put("permission", permission)
        )
        val resp = postApi(host, port, req.toString()) ?: return false
        if (resp.optInt("status", -1) != 0) {
            Log.e("$TAG updateSIPAccount failed for $ext: $resp")
            return false
        }
        // Push the pending configuration live (result does not gate success).
        val apply = JSONObject().put(
            "request",
            JSONObject().put("action", "applyChanges").put("cookie", cookie)
        )
        postApi(host, port, apply.toString())
        return true
    }

    fun blockExtension(host: String, port: Int, cookie: String, extension: String): Boolean =
        setExtensionPermission(host, port, cookie, extension, PERMISSION_INTERNAL)

    fun unblockExtension(host: String, port: Int, cookie: String, extension: String): Boolean =
        setExtensionPermission(host, port, cookie, extension, PERMISSION_FULL)

    private fun postApi(host: String, port: Int, body: String): JSONObject? {
        return try {
            val conn = open(URL("https://$host:$port/api"))
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text)
        } catch (e: Exception) {
            Log.e("$TAG postApi failed: $e")
            null
        }
    }

    private fun open(url: URL): HttpsURLConnection {
        val conn = url.openConnection() as HttpsURLConnection
        conn.sslSocketFactory = trustAllContext.socketFactory
        conn.hostnameVerifier = allowAllHosts
        return conn
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
