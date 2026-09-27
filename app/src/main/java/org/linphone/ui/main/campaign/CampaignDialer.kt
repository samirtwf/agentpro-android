package org.linphone.ui.main.campaign

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.Call
import org.linphone.core.Core
import org.linphone.core.CoreListenerStub
import org.linphone.core.tools.Log
import org.linphone.utils.Event
import org.linphone.R
import org.linphone.utils.AppUtils

/**
 * Drives the Campaign auto-dialer loop (Start/Stop).
 *
 * It lives as a process-wide singleton (not tied to the fragment) so the loop keeps running
 * while the in-call UI covers the app and the Campaign fragment is stopped. It registers its
 * own [CoreListenerStub] while running and removes it on stop.
 *
 * Flow per contact: dial -> wait for the call to be released -> if Auto Disp is ON, wait for
 * the disposition dialog to be resolved (see [onDispositionResolved]); otherwise wait
 * `delaySeconds` -> dial the next Pending contact. Stops when no Pending contact remains.
 *
 * The dialer owns its own deep copy of the list and is the only writer to campaign.json while
 * running; the fragment reloads from disk on [changedEvent].
 */
object CampaignDialer {
    private const val TAG = "[Campaign Dialer]"

    /** True while the auto-dial loop is active. */
    val running = MutableLiveData(false)

    /** Id of the contact currently being dialed (-1 when idle); drives row highlight. */
    val currentContactId = MutableLiveData(-1L)

    /** Short status line shown in the fragment while running. */
    val statusMessage = MutableLiveData("")

    /** Fired whenever the persisted list changed so the fragment can reload from disk. */
    val changedEvent = MutableLiveData<Event<Boolean>>()

    private var appContext: Context? = null
    private var queue: MutableList<CampaignContact> = mutableListOf()
    private var delayMs = 10_000L
    private var index = -1
    private var waitingForCallEnd = false
    private var waitingForDisposition = false

    private val handler = Handler(Looper.getMainLooper())
    private var advanceRunnable: Runnable? = null
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private val listener = object : CoreListenerStub() {
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            if (state == Call.State.Released) {
                val remote = call.remoteAddress
                val number = remote.username?.takeIf { it.isNotEmpty() }
                    ?: remote.asStringUriOnly()
                coreContext.postOnMainThread { onCallReleased(number) }
            }
        }
    }

    fun isRunning(): Boolean = running.value == true

    fun start(context: Context, contacts: List<CampaignContact>, delaySeconds: Int) {
        if (isRunning()) return
        appContext = context.applicationContext
        queue = contacts.map { it.copy() }.toMutableList()
        delayMs = delaySeconds.coerceAtLeast(1).toLong() * 1000L
        index = -1
        waitingForCallEnd = false
        waitingForDisposition = false
        running.value = true
        Log.i("$TAG Starting campaign: ${queue.size} contacts, delay ${delaySeconds}s")
        coreContext.postOnCoreThread { core -> core.addListener(listener) }
        dialNext()
    }

    fun stop() {
        if (!isRunning() && currentContactId.value == -1L) return
        Log.i("$TAG Stopping campaign")
        running.value = false
        currentContactId.value = -1L
        waitingForCallEnd = false
        waitingForDisposition = false
        advanceRunnable?.let { handler.removeCallbacks(it) }
        advanceRunnable = null
        coreContext.postOnCoreThread { core -> core.removeListener(listener) }
    }

    private fun dialNext() {
        if (!isRunning()) return
        var next = -1
        for (i in (index + 1) until queue.size) {
            if (queue[i].status == CampaignContact.STATUS_PENDING) {
                next = i
                break
            }
        }
        if (next == -1) {
            val done = queue.count { it.status == CampaignContact.STATUS_CALLED }
            Log.i("$TAG No more pending contacts, campaign complete ($done done)")
            stop()
            statusMessage.value = AppUtils.getFormattedString(R.string.ap_campaign_complete_fmt, done)
            return
        }
        index = next
        val contact = queue[index]
        currentContactId.value = contact.id
        waitingForCallEnd = true
        statusMessage.value = AppUtils.getFormattedString(R.string.ap_campaign_calling_fmt, label(contact))
        Log.i("$TAG Dialing [${contact.phone}]")
        coreContext.postOnCoreThread { core ->
            // Dial the number exactly as imported (no international prefix), so local numbers
            // and short extensions are not reformatted into a server-rejected +<country> form.
            val address = core.interpretUrl(contact.phone, false)
            if (address != null) {
                coreContext.startAudioCall(address)
            } else {
                Log.e("$TAG Could not interpret [${contact.phone}], skipping")
                coreContext.postOnMainThread {
                    if (waitingForCallEnd) {
                        waitingForCallEnd = false
                        markCalled(contact)
                        scheduleNext()
                    }
                }
            }
        }
    }

    private fun onCallReleased(number: String) {
        if (!isRunning() || !waitingForCallEnd) return
        val contact = queue.getOrNull(index) ?: return
        if (!phonesMatch(contact.phone, number)) return // not our campaign call
        waitingForCallEnd = false
        markCalled(contact)
        if (corePreferences.autoDispEnabled) {
            waitingForDisposition = true
            statusMessage.value = AppUtils.getString(R.string.ap_campaign_waiting_disposition)
            Log.i("$TAG Call ended; waiting for disposition before next call")
        } else {
            scheduleNext()
        }
    }

    /**
     * Called by the disposition dialog when it is resolved (saved with [status], or dismissed
     * with null). Also called for manual calls (when not running) to mirror the disposition
     * onto a matching campaign row.
     */
    fun onDispositionResolved(context: Context, number: String, status: String?) {
        if (status != null) {
            if (isRunning()) {
                val contact = queue.getOrNull(index)
                if (contact != null && phonesMatch(contact.phone, number)) {
                    contact.disposition = status
                    markCalled(contact)
                }
            } else {
                CampaignStore.updateDispositionByPhone(context, number, status)
                changedEvent.value = Event(true)
            }
        }
        if (isRunning() && waitingForDisposition) {
            waitingForDisposition = false
            scheduleNext()
        }
    }

    private fun markCalled(contact: CampaignContact) {
        contact.status = CampaignContact.STATUS_CALLED
        if (contact.lastCall == 0L) contact.lastCall = System.currentTimeMillis()
        persist()
    }

    private fun scheduleNext() {
        if (!isRunning()) return
        statusMessage.value = AppUtils.getFormattedString(R.string.ap_campaign_next_call_fmt, delayMs / 1000)
        advanceRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable { dialNext() }
        advanceRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun persist() {
        val ctx = appContext ?: return
        val snapshot = queue.map { it.copy() } // snapshot on main thread, save off-thread
        ioExecutor.execute {
            CampaignStore.save(ctx, snapshot)
            handler.post { changedEvent.value = Event(true) }
        }
    }

    private fun label(c: CampaignContact): String =
        if (c.name.isNotEmpty()) c.name else c.phone

    private fun phonesMatch(a: String, b: String): Boolean {
        val da = a.filter { it.isDigit() }
        val db = b.filter { it.isDigit() }
        if (da.isEmpty() || db.isEmpty()) return false
        if (da == db) return true
        val min = minOf(da.length, db.length)
        if (min < 6) return false
        return da.takeLast(min) == db.takeLast(min)
    }
}
