package org.linphone.ui.main.panel

import androidx.lifecycle.MutableLiveData
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import org.linphone.core.tools.Log
import org.linphone.utils.Event
import org.linphone.R
import org.linphone.utils.AppUtils

enum class ExtState { AVAILABLE, RINGING, IN_CALL, OFFLINE, UNKNOWN }

/** Live per-queue statistics for the Wallboard (from AMI QueueSummary). */
data class QueueStat(
    val queue: String,
    val loggedIn: Int,
    val available: Int,
    val waiting: Int,
    val longestHoldSec: Int,
    val talkTimeSec: Int
)

/**
 * Minimal Asterisk Manager Interface (AMI) client used by the Extension Panel.
 *
 * It connects over a plain TCP socket to the PBX (Grandstream UCM is Asterisk-based), logs in,
 * subscribes to events, and tracks each extension's presence from DeviceStateChange /
 * ExtensionStatus / PeerStatus events (seeded by an initial DeviceStateList). Listen / Whisper /
 * Barge are implemented as an Originate of ChanSpy to the supervisor's own extension.
 *
 * It is a process-wide singleton so the connection survives fragment lifecycle. All socket I/O
 * runs on a dedicated daemon thread; state is published via LiveData.
 */
object AmiClient {
    private const val TAG = "[AMI Client]"

    val connected = MutableLiveData(false)
    val statusMessage = MutableLiveData("")
    val states = MutableLiveData<Map<String, ExtState>>(emptyMap())
    val errorEvent = MutableLiveData<Event<String>>()

    /** Queues the agent (see [agentInterface]) is currently a member of -> paused flag.
     *  Empty map = the agent is logged out of every queue. Used by the Agent tab. */
    val agentQueues = MutableLiveData<Map<String, Boolean>>(emptyMap())
    private val memberQueues = HashMap<String, Boolean>()

    /** Live per-queue stats for the Wallboard (queue -> QueueStat), refreshed via QueueSummary. */
    val queueStats = MutableLiveData<Map<String, QueueStat>>(emptyMap())
    private val pendingSummary = HashMap<String, QueueStat>()

    /** The agent's queue-member interface, e.g. "PJSIP/5555"; set from the Agent tab. */
    @Volatile var agentInterface: String = ""
        private set

    @Volatile private var socket: Socket? = null

    @Volatile private var output: OutputStream? = null

    @Volatile private var running = false
    private var worker: Thread? = null
    private var pinger: Thread? = null
    private val actionId = AtomicInteger(1)
    private val sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Channel technology seen on the PBX (PJSIP or SIP), used to build Originate channels. */
    @Volatile var channelTech = "PJSIP"
        private set

    private val stateMap = HashMap<String, ExtState>()

    fun isConnected(): Boolean = connected.value == true

    fun connect(cfg: PbxConfig) {
        disconnect()
        running = true
        worker = Thread { runLoop(cfg) }.apply { isDaemon = true; start() }
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        output = null
        worker = null
        pinger = null
        connected.postValue(false)
        // Drop every account's live presence/queue state so the next connection (e.g. after an
        // account switch) starts from a clean slate. Without this the singleton kept the previous
        // account's extensions/stats in memory, so the Panel/Wallboard showed the other account's
        // numbers (and ghost extensions no longer on the PBX). Re-seeded on the next login
        // (DeviceStateList / QueueStatus) and the Wallboard's QueueSummary poll.
        resetLiveState()
    }

    /** Wipe all in-memory presence / queue / membership state (per-account isolation). */
    private fun resetLiveState() {
        synchronized(stateMap) { stateMap.clear() }
        states.postValue(emptyMap())
        synchronized(pendingSummary) { pendingSummary.clear() }
        queueStats.postValue(emptyMap())
        synchronized(memberQueues) { memberQueues.clear() }
        agentQueues.postValue(emptyMap())
    }

    fun refresh() {
        if (isConnected()) requestInitialState()
    }

    private fun runLoop(cfg: PbxConfig) {
        try {
            statusMessage.postValue(AppUtils.getString(R.string.ap_connecting))
            val s = Socket()
            s.connect(InetSocketAddress(cfg.host, cfg.port), 8000)
            s.soTimeout = 0
            socket = s
            output = s.getOutputStream()
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))

            val banner = reader.readLine()
            Log.i("$TAG Connected, banner: $banner")

            send(
                "Action: Login\r\n" +
                    "Username: ${cfg.username}\r\n" +
                    "Secret: ${cfg.password}\r\n" +
                    "Events: on\r\n\r\n"
            )

            val packet = HashMap<String, String>()
            var loggedIn = false
            var line: String?
            while (running) {
                line = reader.readLine()
                if (line == null) {
                    Log.w("$TAG Stream closed by server (EOF) — check AMI permit/deny ACL & credentials")
                    break
                }
                if (line.isEmpty()) {
                    if (packet.isNotEmpty()) {
                        if (!loggedIn) {
                            val resp = packet["response"]
                            if (resp != null) {
                                Log.i("$TAG Login reply: response=$resp message=${packet["message"]}")
                            }
                            if (resp.equals("Success", true)) {
                                loggedIn = true
                                connected.postValue(true)
                                statusMessage.postValue(AppUtils.getString(R.string.ap_connected))
                                Log.i("$TAG Logged in, requesting device states")
                                requestInitialState()
                                startPinger()
                                if (agentInterface.isNotBlank()) queryQueues()
                            } else if (resp.equals("Error", true)) {
                                errorEvent.postValue(Event(packet["message"] ?: AppUtils.getString(R.string.ap_ami_login_failed)))
                                break
                            }
                        }
                        handlePacket(packet)
                        packet.clear()
                    }
                } else {
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        packet[key] = value
                    }
                }
            }
        } catch (e: Exception) {
            if (running) {
                Log.e("$TAG Connection error: $e")
                errorEvent.postValue(Event(e.message ?: AppUtils.getString(R.string.ap_connection_error)))
            }
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            output = null
            running = false
            connected.postValue(false)
            statusMessage.postValue(AppUtils.getString(R.string.ap_disconnected))
        }
    }

    private fun startPinger() {
        pinger = Thread {
            while (running) {
                try { Thread.sleep(30000) } catch (_: InterruptedException) { break }
                if (running) send("Action: Ping\r\nActionID: ${actionId.getAndIncrement()}\r\n\r\n")
            }
        }.apply { isDaemon = true; start() }
    }

    private fun send(raw: String) {
        // Always write off the caller's thread — button taps call send() on the main thread,
        // and a socket write there throws NetworkOnMainThreadException. The single-thread
        // executor also serializes concurrent writes.
        sendExecutor.execute {
            try {
                val o = output ?: return@execute
                o.write(raw.toByteArray(Charsets.UTF_8))
                o.flush()
            } catch (e: Exception) {
                Log.e("$TAG Send failed: $e")
            }
        }
    }

    private fun requestInitialState() {
        send("Action: DeviceStateList\r\nActionID: ${actionId.getAndIncrement()}\r\n\r\n")
    }

    private fun handlePacket(p: Map<String, String>) {
        val event = p["event"]
        if (event == null) {
            if (p["response"].equals("Error", true)) {
                Log.w("$TAG Action error: ${p["message"]}")
            }
            return
        }
        when (event) {
            "OriginateResponse" -> {
                val resp = p["response"]
                Log.i("$TAG OriginateResponse: response=$resp reason=${p["reason"]}")
                if (resp != null && !resp.equals("Success", true)) {
                    errorEvent.postValue(Event(AppUtils.getFormattedString(R.string.ap_spy_failed_fmt, p["reason"].orEmpty())))
                }
            }
            "DeviceStateChange" -> {
                val device = p["device"] ?: return
                val state = p["state"] ?: return
                val ext = sipExtOf(device) ?: return
                setState(ext, mapDeviceState(state))
            }
            "ExtensionStatus" -> {
                val ext = p["exten"] ?: return
                val text = p["statustext"] ?: p["status"] ?: return
                if (ext.all { it.isDigit() }) setState(ext, mapStatusText(text))
            }
            "PeerStatus" -> {
                val ext = sipExtOf(p["peer"] ?: return) ?: return
                when (p["peerstatus"]?.lowercase()) {
                    "unreachable", "lagged" -> setState(ext, ExtState.OFFLINE)
                    "reachable", "registered" ->
                        if ((stateMap[ext] ?: ExtState.OFFLINE) == ExtState.OFFLINE) {
                            setState(ext, ExtState.AVAILABLE)
                        }
                }
            }
            // ---- Agent / call-queue membership for our own interface ----
            "QueueMember" -> if (matchesAgent(p)) setMember(p["queue"] ?: return, isPaused(p))
            "QueueMemberAdded" -> if (matchesAgent(p)) setMember(p["queue"] ?: return, isPaused(p))
            "QueueMemberRemoved" -> if (matchesAgent(p)) removeMember(p["queue"] ?: return)
            "QueueMemberPause" -> if (matchesAgent(p)) {
                val q = p["queue"] ?: return
                if (synchronized(memberQueues) { memberQueues.containsKey(q) }) setMember(q, isPaused(p))
            }
            // ---- Wallboard: live per-queue stats (QueueSummary burst, published on Complete) ----
            "QueueSummary" -> {
                val q = p["queue"] ?: return
                synchronized(pendingSummary) {
                    pendingSummary[q] = QueueStat(
                        queue = q,
                        loggedIn = p["loggedin"]?.toIntOrNull() ?: 0,
                        available = p["available"]?.toIntOrNull() ?: 0,
                        waiting = p["callers"]?.toIntOrNull() ?: 0,
                        longestHoldSec = p["longestholdtime"]?.toIntOrNull() ?: 0,
                        talkTimeSec = p["talktime"]?.toIntOrNull() ?: 0
                    )
                }
            }
            "QueueSummaryComplete" -> {
                synchronized(pendingSummary) {
                    queueStats.postValue(HashMap(pendingSummary))
                    pendingSummary.clear()
                }
            }
        }
    }

    private fun isPaused(p: Map<String, String>): Boolean =
        p["paused"] == "1" || p["paused"].equals("true", true)

    /** True if an AMI queue event refers to our agent interface (matches the full interface or ext). */
    private fun matchesAgent(p: Map<String, String>): Boolean {
        val iface = agentInterface
        if (iface.isBlank()) return false
        val ext = extOf(iface)
        return sequenceOf(p["interface"], p["location"], p["stateinterface"], p["name"])
            .any { it != null && (it.equals(iface, true) || extOf(it).equals(ext, true)) }
    }

    private fun setMember(queue: String, paused: Boolean) {
        synchronized(memberQueues) {
            if (memberQueues.containsKey(queue) && memberQueues[queue] == paused) return
            memberQueues[queue] = paused
            agentQueues.postValue(HashMap(memberQueues))
        }
    }

    private fun removeMember(queue: String) {
        synchronized(memberQueues) {
            if (memberQueues.remove(queue) != null) agentQueues.postValue(HashMap(memberQueues))
        }
    }

    private fun extOf(device: String): String {
        val slash = device.indexOf('/')
        var e = if (slash >= 0) device.substring(slash + 1) else device
        val dash = e.indexOf('-')
        if (dash >= 0) e = e.substring(0, dash)
        return e.trim()
    }

    /**
     * Numeric extension of a PJSIP/SIP device string ("PJSIP/2000" -> "2000"), or null for any
     * other channel technology (DAHDI / Local / IAX / Custom trunks & channels) or a non-numeric
     * id. The panel is a SIP-extension board, so non-SIP device states — which is where ghost
     * entries like "1" / "2" come from (e.g. DAHDI/1) — must never become extensions. Also latches
     * [channelTech] for ChanSpy when a real SIP device is seen.
     */
    private fun sipExtOf(device: String): String? {
        when {
            device.startsWith("PJSIP/", true) -> channelTech = "PJSIP"
            device.startsWith("SIP/", true) -> channelTech = "SIP"
            else -> return null
        }
        val ext = extOf(device)
        return if (ext.isNotEmpty() && ext.all { it.isDigit() }) ext else null
    }

    private fun mapDeviceState(s: String): ExtState = when (s.uppercase()) {
        "NOT_INUSE", "IDLE" -> ExtState.AVAILABLE
        "INUSE", "BUSY", "ONHOLD", "RINGINUSE" -> ExtState.IN_CALL
        "RINGING" -> ExtState.RINGING
        "UNAVAILABLE", "INVALID", "UNKNOWN" -> ExtState.OFFLINE
        else -> ExtState.UNKNOWN
    }

    private fun mapStatusText(s: String): ExtState = when (s.uppercase()) {
        "IDLE" -> ExtState.AVAILABLE
        "INUSE", "BUSY", "ONHOLD" -> ExtState.IN_CALL
        "RINGING" -> ExtState.RINGING
        "UNAVAILABLE" -> ExtState.OFFLINE
        else -> ExtState.UNKNOWN
    }

    private fun setState(ext: String, state: ExtState) {
        synchronized(stateMap) {
            if (stateMap[ext] == state) return
            stateMap[ext] = state
            states.postValue(HashMap(stateMap))
        }
    }

    /**
     * Spy on [targetExt] from the supervisor's own [myExt] via ChanSpy.
     * mode: "" = listen only, "w" = whisper (talk to agent), "B" = barge (talk to both).
     */
    fun spy(targetExt: String, myExt: String, mode: String) {
        if (!isConnected()) {
            errorEvent.postValue(Event(AppUtils.getString(R.string.ap_not_connected_to_pbx)))
            return
        }
        if (myExt.isBlank()) {
            errorEvent.postValue(Event(AppUtils.getString(R.string.ap_own_extension_unknown)))
            return
        }
        val channel = "$channelTech/$myExt"
        val data = "$channelTech/$targetExt,q$mode"
        send(
            "Action: Originate\r\n" +
                "ActionID: ${actionId.getAndIncrement()}\r\n" +
                "Channel: $channel\r\n" +
                "Application: ChanSpy\r\n" +
                "Data: $data\r\n" +
                "CallerID: Spy $targetExt\r\n" +
                "Async: true\r\n\r\n"
        )
        Log.i("$TAG Spy: channel=$channel data=$data")
    }

    // ---------- Agent / call-queue control (QueueAdd / QueueRemove / QueuePause) ----------

    /** Set the agent's member interface (e.g. "PJSIP/5555"); resets tracked membership. */
    fun setAgentInterface(iface: String) {
        if (agentInterface == iface) return
        agentInterface = iface
        synchronized(memberQueues) { memberQueues.clear() }
        agentQueues.postValue(emptyMap())
        if (isConnected() && iface.isNotBlank()) queryQueues()
    }

    /** Log the agent into [queue] (QueueAdd), optionally already paused. */
    fun queueLogin(queue: String, paused: Boolean) {
        if (!ensureAgentReady()) return
        send(
            "Action: QueueAdd\r\n" +
                "ActionID: ${actionId.getAndIncrement()}\r\n" +
                "Queue: $queue\r\n" +
                "Interface: $agentInterface\r\n" +
                "MemberName: $agentInterface\r\n" +
                "Paused: ${amiBool(paused)}\r\n\r\n"
        )
        Log.i("$TAG QueueAdd queue=$queue interface=$agentInterface paused=$paused")
    }

    /** Log the agent out of [queue] (QueueRemove). */
    fun queueLogout(queue: String) {
        if (!ensureAgentReady()) return
        send(
            "Action: QueueRemove\r\n" +
                "ActionID: ${actionId.getAndIncrement()}\r\n" +
                "Queue: $queue\r\n" +
                "Interface: $agentInterface\r\n\r\n"
        )
        Log.i("$TAG QueueRemove queue=$queue interface=$agentInterface")
    }

    /** Pause / unpause the agent in every queue it belongs to (QueuePause without Queue). */
    fun queuePauseAll(paused: Boolean) {
        if (!ensureAgentReady()) return
        send(
            "Action: QueuePause\r\n" +
                "ActionID: ${actionId.getAndIncrement()}\r\n" +
                "Interface: $agentInterface\r\n" +
                "Paused: ${amiBool(paused)}\r\n\r\n"
        )
        Log.i("$TAG QueuePause interface=$agentInterface paused=$paused")
    }

    /** Ask the PBX for the current queue/member state (seeds [agentQueues]). */
    fun queryQueues() {
        if (isConnected()) send("Action: QueueStatus\r\nActionID: ${actionId.getAndIncrement()}\r\n\r\n")
    }

    /** Ask the PBX for a live summary of every queue (drives [queueStats] for the Wallboard). */
    fun queueSummary() {
        if (isConnected()) send("Action: QueueSummary\r\nActionID: ${actionId.getAndIncrement()}\r\n\r\n")
    }

    private fun ensureAgentReady(): Boolean {
        if (!isConnected()) {
            errorEvent.postValue(Event(AppUtils.getString(R.string.ap_not_connected_to_pbx)))
            return false
        }
        if (agentInterface.isBlank()) {
            errorEvent.postValue(Event(AppUtils.getString(R.string.ap_set_agent_extension_first)))
            return false
        }
        return true
    }

    private fun amiBool(v: Boolean) = if (v) "true" else "false"
}
