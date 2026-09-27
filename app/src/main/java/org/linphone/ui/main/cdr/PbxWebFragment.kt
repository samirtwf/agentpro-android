package org.linphone.ui.main.cdr

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import java.io.File
import java.io.IOException
import java.util.UUID
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.ui.main.panel.PbxPrefs
import org.linphone.utils.AudioConverter

/**
 * Opens the Grandstream UCM web GUI (its own CDR page, where recordings can be played) inside the
 * app via a WebView, auto-filling the web login. The UCM uses a self-signed cert, so SSL errors
 * are accepted for this host. Auto-login is best-effort JS injection; if it doesn't fill, the user
 * can log in manually once (the WebView keeps the session).
 *
 * A WebView ignores file downloads by default, so recording-download links did nothing. We add a
 * DownloadListener that fetches the file with the WebView's session cookie over the same trust-all
 * TLS (self-signed cert), saves it under cacheDir/recordings, then opens a share sheet (which also
 * lets the user save it to Files/Drive). SPA "blob:" downloads are handled via a small JS bridge.
 */
@UiThread
class PbxWebFragment : Fragment() {
    companion object {
        private const val TAG = "[PBX Web Fragment]"
    }

    private lateinit var web: WebView
    private lateinit var appCtx: Context

    // Guards the JS download bridge: only our injected blob-fetch script is given this random token,
    // so a stray or hostile page script can't drive the interface with arbitrary bytes.
    private val bridgeToken: String = UUID.randomUUID().toString()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.pbx_web_fragment, container, false)
        web = v.findViewById(R.id.pbx_web)
        appCtx = requireContext().applicationContext
        val status = v.findViewById<TextView>(R.id.pbx_web_status)

        v.findViewById<ImageView>(R.id.pbx_web_back).setOnClickListener {
            if (web.canGoBack()) web.goBack() else findNavController().popBackStack()
        }
        v.findViewById<ImageView>(R.id.pbx_web_reload).setOnClickListener { web.reload() }

        val ctx = requireContext()
        val host = PbxPrefs.load(ctx).host
        val port = PbxPrefs.apiPort(ctx)
        val user = PbxPrefs.webUser(ctx)
        val pass = PbxPrefs.webPass(ctx)

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.webChromeClient = WebChromeClient()
        // Bridge used only to receive blob bytes the page generated (SPA recording downloads).
        web.addJavascriptInterface(DownloadBridge(), "AndroidDownload")
        // Catch recording-download links the WebView would otherwise ignore.
        web.setDownloadListener { url, _, _, _, _ ->
            onDownloadRequested(url)
        }

        // Auto-login only runs when the web credentials are configured via the CDR settings gear;
        // otherwise we just show the login page for the user to sign in manually.
        val autoLogin = user.isNotBlank() && pass.isNotBlank()
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                // The UCM presents a self-signed certificate.
                handler?.proceed()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                status.text = ""
                if (autoLogin) view?.evaluateJavascript(autoLoginJs(user, pass), null)
            }
        }

        when {
            host.isBlank() -> status.text = getString(R.string.ap_pbx_web_set_host_hint)
            !autoLogin -> {
                status.text = getString(R.string.ap_pbx_web_enter_login_hint)
                web.loadUrl("https://$host:$port/")
            }
            else -> {
                status.text = getString(R.string.ap_loading)
                web.loadUrl("https://$host:$port/")
            }
        }
        return v
    }

    override fun onDestroyView() {
        web.destroy()
        super.onDestroyView()
    }

    // ---- Recording download + share -----------------------------------------------------------

    private fun onDownloadRequested(url: String) {
        if (!isAdded) return
        Toast.makeText(requireContext(), getString(R.string.ap_toast_downloading_recording), Toast.LENGTH_SHORT).show()
        // Fetch the file from INSIDE the page (its own request + session). The download is gated by
        // the UCM rate-limit (status -46 "retry after 15s"), which assetFetchJs detects + auto-retries.
        web.evaluateJavascript(assetFetchJs(url), null)
    }

    private fun newRecordingFile(fileName: String): File {
        val dir = File(appCtx.cacheDir, "recordings").apply { mkdirs() }
        return File(dir, fileName)
    }

    private fun saveBytesAndShare(fileName: String, mimeType: String?, bytes: ByteArray) {
        try {
            val file = newRecordingFile(sanitizeName(fileName))
            file.outputStream().use { it.write(bytes) }
            val (audioFile, audioMime) = prepareForSharing(file, mimeType)
            Log.i("$TAG Saved blob recording [${audioFile.name}] (${audioFile.length()} bytes)")
            val savedTo = saveToDownloads(audioFile, audioMime)
            web.post { afterDownload(audioFile, audioMime, savedTo) }
        } catch (e: Exception) {
            postDownloadError(e)
        }
    }

    private fun afterDownload(file: File, mimeType: String?, savedTo: String?) {
        if (!isAdded) return
        val msg = if (savedTo != null) getString(R.string.ap_saved_to_fmt, savedTo) else getString(R.string.ap_downloaded_fmt, file.name)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        shareRecording(file, mimeType)
    }

    /**
     * Copies the recording into the public Downloads/AgentPro folder so it shows up in the phone's
     * Downloads (Android 10+). Returns a display path, or null (Android 9 falls back to the share
     * sheet's "Save to Files"). Runs on a background thread.
     */
    private fun saveToDownloads(file: File, mimeType: String?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val relativePath = Environment.DIRECTORY_DOWNLOADS + "/AgentPro"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType?.takeIf { it.isNotBlank() } ?: "audio/wav")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = appCtx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "$relativePath/${file.name}"
        } catch (e: Exception) {
            Log.e("$TAG Save to Downloads failed: $e")
            null
        }
    }

    private fun shareRecording(file: File, mimeType: String?) {
        if (!isAdded) return
        try {
            val uri = FileProvider.getUriForFile(requireContext(), getString(R.string.file_provider), file)
            val type = mimeType?.takeIf { it.isNotBlank() } ?: "audio/*"
            val intent = Intent(Intent.ACTION_SEND).apply {
                setType(type)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.ap_share_recording_fmt, file.name)))
        } catch (e: Exception) {
            Log.e("$TAG Failed to share recording: $e")
            Toast.makeText(requireContext(), getString(R.string.ap_toast_saved_share_failed), Toast.LENGTH_LONG).show()
        }
    }

    private fun postDownloadError(e: Exception) {
        Log.e("$TAG Recording download failed: $e")
        web.post {
            if (isAdded) {
                Toast.makeText(requireContext(), getString(R.string.ap_recording_dl_failed_fmt, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sanitizeName(name: String): String {
        val cleaned = name.trim().replace(Regex("[^A-Za-z0-9._-]"), "_")
        return cleaned.ifBlank { "recording_${System.currentTimeMillis()}.wav" }
    }

    private fun extForMime(mime: String): String = when {
        mime.contains("wav", true) || mime.contains("x-wav", true) -> ".wav"
        mime.contains("mpeg", true) || mime.contains("mp3", true) -> ".mp3"
        mime.contains("ogg", true) -> ".ogg"
        mime.contains("mp4", true) || mime.contains("m4a", true) || mime.contains("aac", true) -> ".m4a"
        else -> ".wav"
    }

    /**
     * Resolves the final (file, MIME) to share: detects the real audio format, then converts WAV to
     * M4A/AAC because WhatsApp rejects WAV but plays AAC inline. Falls back to the original file on
     * any failure (so it's never worse than before). Runs on a background thread (MediaCodec blocks).
     */
    private fun prepareForSharing(file: File, serverMime: String?): Pair<File, String> {
        val (audioFile, audioMime) = resolveAudioType(file, serverMime)
        if (audioMime == "audio/wav") {
            val m4a = AudioConverter.wavToM4a(audioFile)
            if (m4a != null) return m4a to "audio/mp4"
        }
        return audioFile to audioMime
    }

    /**
     * Recordings often arrive with a generic name/type (e.g. "downloadfile.bin",
     * application/octet-stream), so WhatsApp etc. can't recognise them as audio. Sniff the real
     * format from the file's magic bytes, rename the file to a proper extension, and return the
     * matching audio MIME type so the shared file is playable.
     */
    private fun resolveAudioType(file: File, serverMime: String?): Pair<File, String> {
        val header = ByteArray(16)
        val read = try {
            file.inputStream().use { it.read(header) }
        } catch (e: Exception) {
            -1
        }
        val (ext, mime) = sniffAudio(header, read, serverMime)

        var base = file.nameWithoutExtension
        if (base.isBlank() || base.equals("downloadfile", true)) {
            base = "recording_${System.currentTimeMillis()}"
        }
        val target = File(file.parentFile, "$base.$ext")
        if (target.name == file.name) return file to mime
        return if (file.renameTo(target)) target to mime else file to mime
    }

    /** Detects the audio container from the leading bytes; falls back to WAV (the UCM default). */
    private fun sniffAudio(b: ByteArray, read: Int, serverMime: String?): Pair<String, String> {
        fun has(sig: String, off: Int = 0): Boolean {
            if (read < off + sig.length) return false
            for (i in sig.indices) {
                if ((b[off + i].toInt() and 0xFF) != sig[i].code) return false
            }
            return true
        }
        return when {
            has("RIFF") && has("WAVE", 8) -> "wav" to "audio/wav"
            has("ID3") -> "mp3" to "audio/mpeg"
            read >= 2 && (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xE0) == 0xE0 -> "mp3" to "audio/mpeg"
            has("OggS") -> "ogg" to "audio/ogg"
            has("fLaC") -> "flac" to "audio/flac"
            has("ftyp", 4) -> "m4a" to "audio/mp4"
            serverMime != null && serverMime.startsWith("audio/", true) ->
                extForMime(serverMime).trimStart('.') to serverMime
            else -> "wav" to "audio/wav"
        }
    }

    private fun assetFetchJs(fileUrl: String): String {
        val u = fileUrl.replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function(){
              var url = '$u';
              var token = '$bridgeToken';
              var delays = [16000, 16000];   // UCM returns status -46 "retry after 15 seconds" when rate-limited
              function pass(b){
                var fr = new FileReader();
                fr.onloadend = function(){ AndroidDownload.onBlob(fr.result, b.type || '', token); };
                fr.onerror = function(){ AndroidDownload.onError('read error', token); };
                fr.readAsDataURL(b);
              }
              function handle(b, n){
                // A real recording is binary; the UCM rate-limit error is a small JSON body. Sniff
                // small responses as text and, if it's the busy error, wait and retry automatically.
                if (b.size > 0 && b.size < 8192) {
                  var tr = new FileReader();
                  tr.onloadend = function(){
                    var t = tr.result || '';
                    if (t.indexOf('error_msg') >= 0 || t.indexOf('need_apply') >= 0) {
                      if (n < delays.length) {
                        AndroidDownload.onInfo('PBX busy, retrying in ' + (delays[n] / 1000) + 's…', token);
                        setTimeout(function(){ attempt(n + 1); }, delays[n]);
                      } else {
                        AndroidDownload.onError('PBX busy (rate-limited). Please try again in a few seconds.', token);
                      }
                    } else { pass(b); }
                  };
                  tr.onerror = function(){ pass(b); };
                  tr.readAsText(b);
                } else { pass(b); }
              }
              function attempt(n){
                fetch(url, { credentials: 'include' }).then(function(r){
                  if (!r.ok) { AndroidDownload.onError('HTTP ' + r.status, token); return; }
                  r.blob().then(function(b){ handle(b, n); });
                }).catch(function(e){ AndroidDownload.onError('' + e, token); });
              }
              attempt(0);
            })();
        """.trimIndent()
    }

    /**
     * Receives blob bytes generated by the UCM page (SPA recording downloads). Runs on a binder
     * thread, not the UI thread. The interface surface is intentionally tiny and only ever saves
     * bytes the user asked to download (then opens a user-confirmed share chooser) or shows status.
     */
    private inner class DownloadBridge {
        @JavascriptInterface
        fun onBlob(dataUrl: String, mime: String, token: String) {
            if (token != bridgeToken) {
                Log.e("$TAG Ignoring blob bridge call with an invalid token")
                return
            }
            try {
                val comma = dataUrl.indexOf(',')
                if (comma < 0) {
                    Log.e("$TAG Blob data URL had no payload")
                    return
                }
                val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                val name = "recording_${System.currentTimeMillis()}${extForMime(mime)}"
                saveBytesAndShare(name, mime.ifBlank { "audio/*" }, bytes)
            } catch (e: Exception) {
                postDownloadError(e)
            }
        }

        @JavascriptInterface
        fun onError(message: String, token: String) {
            if (token != bridgeToken) return
            Log.e("$TAG Blob fetch error: $message")
            postDownloadError(IOException(message))
        }

        @JavascriptInterface
        fun onInfo(message: String, token: String) {
            if (token != bridgeToken) return
            Log.i("$TAG $message")
            web.post {
                if (isAdded) Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Best-effort auto-login + jump-to-CDR for the UCM single-page web GUI.
     *
     * A single polling runner handles two phases:
     *  1. LOGIN — while a password field exists, fill username + password. It only clicks "Login"
     *     when there is NO visible verification-code (captcha) field. The UCM shows a captcha after
     *     repeated failed logins (or when always-on), which JS can't read; submitting blindly just
     *     fails and keeps the lockout alive, so when a captcha is present we focus it and let the
     *     user type the short code + tap Login once (the WebView then keeps the session).
     *  2. CDR — once logged in (no password field), click the "CDR" menu item so the GUI opens on
     *     the call-records page, where recordings can be played.
     *
     * It is re-injected on every onPageFinished; the window guards keep each action one-shot.
     */
    private fun autoLoginJs(user: String, pass: String): String {
        val u = user.replace("\\", "\\\\").replace("'", "\\'")
        val p = pass.replace("\\", "\\\\").replace("'", "\\'")
        return """
            (function(){
              if (window.__apRunner) return;
              window.__apRunner = true;
              function setVal(el, val){
                el.focus();
                var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if (d && d.set) { d.set.call(el, val); } else { el.value = val; }
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
              }
              var tries = 0;
              var timer = setInterval(function(){
                tries++;
                if (tries > 180) { clearInterval(timer); return; }   // ~90s budget (allows manual captcha)
                var pw = document.querySelector('input[type=password]');
                if (pw) {
                  // ---- LOGIN PHASE ----
                  if (window.__apFilled) return;       // filled; waiting for submit / redirect
                  var inputs = Array.prototype.slice.call(document.querySelectorAll('input'));
                  var uf = null, captcha = null;
                  inputs.forEach(function(el){
                    var t = (el.type || '').toLowerCase();
                    if (t === 'password') return;
                    var meta = ((el.placeholder||'') + ' ' + (el.name||'') + ' ' + (el.id||'')).toLowerCase();
                    if (/captcha|verif|code/.test(meta)) { if (!captcha) captcha = el; return; }
                    if ((t === 'text' || t === 'email' || t === '') && !uf) uf = el;
                  });
                  if (!uf) return;
                  setVal(uf, '$u');
                  setVal(pw, '$p');
                  window.__apFilled = true;
                  var captchaVisible = captcha && captcha.offsetParent !== null;
                  if (captchaVisible) {
                    captcha.focus();   // creds are filled — user only types the captcha + taps Login
                  } else {
                    var btn = document.querySelector('button[type=submit]') || document.querySelector('button');
                    if (btn) btn.click();
                  }
                  return;
                }
                // ---- LOGGED-IN PHASE ----
                // "CDR" is an expandable parent menu whose first child "CDR" is the records page.
                // So: (1) expand the CDR parent, then (2) click the child "CDR". The two share the
                // same label, disambiguated by vertical position: parent = topmost group, child =
                // bottom group. Within each group click the innermost (most-indented) element, since
                // a click on the outer <li> doesn't reach the inner React/Vue handler.
                window.__apFilled = false;
                if (window.__apCdrDone) { clearInterval(timer); return; }
                window.__liTicks = (window.__liTicks || 0) + 1;
                window.__apCdr = {
                  labels: function(){
                    var arr = [];
                    var els = document.querySelectorAll('span, a, div, li, p');
                    for (var i = 0; i < els.length; i++) {
                      var el = els[i];
                      if ((el.textContent || '').trim() !== 'CDR' || el.offsetParent === null) continue;
                      var r = el.getBoundingClientRect();
                      arr.push({ el: el, top: r.top, left: r.left });
                    }
                    arr.sort(function(a, b){ return a.top - b.top; });
                    return arr;
                  },
                  groups: function(arr){
                    var tops = [];
                    arr.forEach(function(o){
                      for (var i = 0; i < tops.length; i++) { if (Math.abs(tops[i] - o.top) <= 12) return; }
                      tops.push(o.top);
                    });
                    return tops;
                  },
                  fire: function(el){
                    ['mousedown', 'mouseup', 'click'].forEach(function(t){
                      el.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
                    });
                  },
                  clickGroup: function(targetTop){
                    var arr = this.labels(), best = null, bestL = -1;
                    for (var i = 0; i < arr.length; i++) {
                      if (Math.abs(arr[i].top - targetTop) <= 12 && arr[i].left > bestL) { bestL = arr[i].left; best = arr[i].el; }
                    }
                    if (best) { this.fire(best); return true; }
                    return false;
                  }
                };
                // (1) Expand the CDR parent (topmost CDR group).
                if (!window.__cdrExpanded && window.__liTicks >= 4) {
                  var a1 = window.__apCdr.labels();
                  if (a1.length >= 1) {
                    window.__apCdr.clickGroup(a1[0].top);
                    window.__cdrExpanded = true; window.__cdrTick = window.__liTicks;
                  }
                }
                // (2) Once a child "CDR" appears below the parent, click it (bottom group).
                if (window.__cdrExpanded) {
                  if (location.href.indexOf('/cdr') >= 0) {
                    window.__apCdrDone = true; clearInterval(timer); return;
                  }
                  if (window.__liTicks >= window.__cdrTick + 2) {
                    var a2 = window.__apCdr.labels();
                    var g = window.__apCdr.groups(a2);
                    if (g.length >= 2) {
                      window.__apCdr.clickGroup(g[g.length - 1]);
                      window.__apCdrDone = true; clearInterval(timer);
                    } else if (window.__liTicks > window.__cdrTick + 12) {
                      window.__apCdrDone = true; clearInterval(timer);  // give up; submenu left open for the user
                    }
                  }
                }
              }, 500);
            })();
        """.trimIndent()
    }
}
