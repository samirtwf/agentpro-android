package org.linphone.ui.main.cdr

import android.content.Context
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.tools.Log

/**
 * TLS trust for the Grandstream UCM HTTPS API and web GUI (port 8089 by default).
 *
 * A UCM normally presents a self-signed certificate. Instead of accepting every certificate, the
 * app accepts only:
 *  1. a certificate the system already trusts that is valid for the host name (a CA-signed UCM), or
 *  2. the ONE certificate the user explicitly approved for that PBX after seeing its SHA-256
 *     fingerprint ("trust on first use", like SSH known_hosts).
 * Anything else — including a certificate that changed since it was approved — is rejected until
 * the user approves it again.
 *
 * Used by [UcmApiClient] (every HTTPS request) and [PbxWebFragment] (the in-app PBX page);
 * [ensureTrusted] is the one place the user is asked.
 */
object PbxCertTrust {
    private const val TAG = "[PBX Cert Trust]"
    private const val PREFS = "agentpro_pbx_cert_pins"
    private const val TIMEOUT_MS = 6000

    /** Raised by the trust manager for a certificate that is neither system-trusted nor approved. */
    class UntrustedCertificateException(
        val certificate: X509Certificate,
        val fingerprint: String,
        cause: Throwable?
    ) : CertificateException("PBX certificate not trusted (SHA-256 $fingerprint)", cause)

    /** Outcome of [check]. */
    sealed class Check {
        object Trusted : Check()

        /** [changed]: another certificate was approved for this PBX before. */
        class Untrusted(val fingerprint: String, val changed: Boolean) : Check()

        class Failed(val error: Exception) : Check()
    }

    // ---- Approved certificates (one per host:port) -----------------------------------------------

    private fun key(host: String, port: Int) = "${host.trim().lowercase(Locale.US)}:$port"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun approvedFingerprint(c: Context, host: String, port: Int): String? =
        prefs(c).getString(key(host, port), null)?.takeIf { it.isNotBlank() }

    fun approve(c: Context, host: String, port: Int, fingerprint: String) {
        prefs(c).edit().putString(key(host, port), fingerprint).apply()
        Log.i("$TAG Approved certificate $fingerprint for ${key(host, port)}")
    }

    /** SHA-256 of the DER-encoded certificate, as colon-separated hex (what browsers display). */
    fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString(":") { "%02X".format(it) }

    private fun matches(approved: String?, cert: X509Certificate): Boolean =
        approved != null && approved.equals(fingerprint(cert), ignoreCase = true)

    // ---- HTTPS connections ---------------------------------------------------------------------

    private val systemTrustManager: X509TrustManager by lazy {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Accepts a chain the system trusts, or else only the certificate approved for this PBX. Any
     * other certificate is rejected with [UntrustedCertificateException].
     */
    private class PbxTrustManager(private val approved: String?) : X509TrustManager {
        @Volatile
        var rejected: UntrustedCertificateException? = null

        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
            // The app never accepts incoming TLS connections.
            throw CertificateException("Client certificates are not accepted")
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            if (chain == null || chain.isEmpty()) throw CertificateException("No server certificate")
            val systemError = try {
                systemTrustManager.checkServerTrusted(chain, authType)
                return // CA-signed; the host name is then checked by PbxHostnameVerifier
            } catch (e: CertificateException) {
                e
            }
            val leaf = chain[0]
            if (matches(approved, leaf)) return // exactly the certificate the user approved
            val error = UntrustedCertificateException(leaf, fingerprint(leaf), systemError)
            rejected = error
            throw error
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrustManager.acceptedIssuers
    }

    /** Accepts a certificate issued for the host name, or else only the approved certificate. */
    private class PbxHostnameVerifier(private val approved: String?) : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?): Boolean {
            if (hostname == null || session == null) return false
            if (HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session)) return true
            // A UCM certificate rarely names the IP / host it is reached on: allow the mismatch only
            // for the exact certificate the user approved for this PBX.
            val leaf = try {
                session.peerCertificates.firstOrNull() as? X509Certificate
            } catch (e: Exception) {
                null
            }
            return leaf != null && matches(approved, leaf)
        }
    }

    private fun newContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }

    private val factories = HashMap<String, SSLSocketFactory>()

    @Synchronized
    private fun socketFactory(approved: String?): SSLSocketFactory =
        factories.getOrPut(approved.orEmpty()) { newContext(PbxTrustManager(approved)).socketFactory }

    /** Applies the PBX trust rules above to an HTTPS connection to [host]:[port]. */
    fun secure(conn: HttpsURLConnection, host: String, port: Int) {
        val approved = approvedFingerprint(coreContext.context, host, port)
        conn.sslSocketFactory = socketFactory(approved)
        conn.hostnameVerifier = PbxHostnameVerifier(approved)
    }

    // ---- Checking a PBX, and asking the user -------------------------------------------------

    /** Connects to [host]:[port] and reports whether its certificate is trusted. Blocking. */
    @WorkerThread
    fun check(c: Context, host: String, port: Int): Check {
        val approved = approvedFingerprint(c, host, port)
        val trustManager = PbxTrustManager(approved)
        return try {
            Socket().use { raw ->
                raw.connect(InetSocketAddress(host, port), TIMEOUT_MS)
                raw.soTimeout = TIMEOUT_MS
                val factory = newContext(trustManager).socketFactory
                (factory.createSocket(raw, host, port, true) as SSLSocket).use { ssl ->
                    ssl.startHandshake()
                    val session = ssl.session
                    if (PbxHostnameVerifier(approved).verify(host, session)) {
                        Check.Trusted
                    } else {
                        // A chain the system trusts, but not issued for this host name.
                        val leaf = session.peerCertificates[0] as X509Certificate
                        Check.Untrusted(fingerprint(leaf), approved != null)
                    }
                }
            }
        } catch (e: Exception) {
            val rejected = trustManager.rejected
            if (rejected != null) {
                Check.Untrusted(rejected.fingerprint, approved != null)
            } else {
                Log.w("$TAG Cannot check the certificate of $host:$port: $e")
                Check.Failed(e)
            }
        }
    }

    // Trust dialogs currently on screen, by host:port — so two screens asking at once share one.
    private val pendingAnswers = HashMap<String, CompletableDeferred<Boolean>>()

    /**
     * Makes sure the certificate of [host]:[port] is trusted, asking the user — with its SHA-256
     * fingerprint — when the PBX presents one they have not approved. Returns false only when the
     * user declined; true when the certificate is trusted, and also when the PBX could not be
     * reached (the caller's own request then reports that error). Call it from the main thread while
     * the fragment's view exists.
     */
    @MainThread
    suspend fun ensureTrusted(fragment: Fragment, host: String, port: Int): Boolean {
        if (host.isBlank()) return true
        val appContext = fragment.requireContext().applicationContext
        val result = withContext(Dispatchers.IO) { check(appContext, host, port) }
        if (result !is Check.Untrusted) return true
        if (!fragment.isAdded) return false

        val k = key(host, port)
        pendingAnswers[k]?.let { return it.await() }
        val answer = CompletableDeferred<Boolean>()
        pendingAnswers[k] = answer
        try {
            val trusted = askUser(fragment, host, port, result)
            if (trusted) approve(appContext, host, port, result.fingerprint)
            answer.complete(trusted)
            return trusted
        } finally {
            pendingAnswers.remove(k)
            answer.complete(false) // no-op once answered; releases waiters if this one was cancelled
        }
    }

    private suspend fun askUser(fragment: Fragment, host: String, port: Int, untrusted: Check.Untrusted): Boolean =
        suspendCancellableCoroutine { cont ->
            val dialog = AlertDialog.Builder(fragment.requireContext())
                .setTitle(if (untrusted.changed) R.string.ap_cert_changed_title else R.string.ap_cert_new_title)
                .setMessage(
                    fragment.getString(
                        if (untrusted.changed) R.string.ap_cert_changed_message else R.string.ap_cert_new_message,
                        "$host:$port",
                        untrusted.fingerprint
                    )
                )
                .setPositiveButton(R.string.ap_cert_trust) { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton(R.string.dialog_cancel) { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .show()
            cont.invokeOnCancellation { dialog.dismiss() }
        }

    // ---- WebView (PbxWebFragment) --------------------------------------------------------------

    /**
     * For WebViewClient.onReceivedSslError: true only when the failing page is on the PBX itself
     * ([host]:[port]) and presents exactly the certificate the user approved for it.
     */
    fun isApprovedPbxCertificate(c: Context, host: String, port: Int, error: SslError?): Boolean {
        if (error == null) return false
        val uri = Uri.parse(error.url ?: return false)
        if (!host.trim().equals(uri.host, ignoreCase = true)) return false
        if ((if (uri.port > 0) uri.port else 443) != port) return false
        val cert = error.certificate?.let { x509(it) } ?: return false
        return matches(approvedFingerprint(c, host, port), cert)
    }

    private fun x509(cert: SslCertificate): X509Certificate? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return cert.x509Certificate
        val der = SslCertificate.saveState(cert)?.getByteArray("x509-certificate") ?: return null
        return try {
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
        } catch (e: Exception) {
            null
        }
    }
}
