/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.core

import android.annotation.SuppressLint
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.MutableLiveData
import com.google.firebase.crashlytics.FirebaseCrashlytics
import kotlin.system.exitProcess
import org.linphone.BuildConfig
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.compatibility.Compatibility
import org.linphone.contacts.ContactsManager
import org.linphone.core.tools.Log
import org.linphone.notifications.NotificationsManager
import org.linphone.telecom.TelecomManager
import org.linphone.ui.call.CallActivity
import org.linphone.ui.main.AccountScope
import org.linphone.ui.main.panel.PbxConfig
import org.linphone.ui.main.panel.PbxPrefs
import org.linphone.utils.ActivityMonitor
import org.linphone.utils.AppUtils
import org.linphone.utils.AudioUtils
import org.linphone.utils.Event
import org.linphone.utils.FileUtils
import org.linphone.utils.LinphoneUtils

class CoreContext
    @UiThread
    constructor(val context: Context) : HandlerThread("Core Thread") {
    companion object {
        private const val TAG = "[Core Context]"

        // Only ever used to discover this phone's public address when it is outside the
        // customer's network - see applyNatPolicyForCurrentNetwork(). Google's public STUN
        // server answers one UDP question ("what address did this come from?") and is told
        // nothing about who is calling whom.
        private const val STUN_SERVER = "stun.l.google.com:19302"
    }

    lateinit var core: Core

    fun isCoreAvailable(): Boolean {
        return ::core.isInitialized
    }

    val contactsManager = ContactsManager()

    val notificationsManager = NotificationsManager(context)

    val telecomManager = TelecomManager(context)

    @get:AnyThread
    val sdkVersion: String by lazy {
        val sdkVersion = context.getString(R.string.linphone_sdk_version)
        val sdkBranch = context.getString(R.string.linphone_sdk_branch)
        val sdkBuildType = org.linphone.core.BuildConfig.BUILD_TYPE
        "$sdkVersion ($sdkBranch, $sdkBuildType)"
    }

    private val activityMonitor = ActivityMonitor()

    private val mainThread = Handler(Looper.getMainLooper())

    var defaultAccountHasVideoConferenceFactoryUri: Boolean = false

    var bearerAuthInfoPendingPasswordUpdate: AuthInfo? = null
    var digestAuthInfoPendingPasswordUpdate: AuthInfo? = null

    var isConnectedToAndroidAuto: Boolean = false

    val bearerAuthenticationRequestedEvent: MutableLiveData<Event<Pair<String, String?>>> by lazy {
        MutableLiveData<Event<Pair<String, String?>>>()
    }

    val digestAuthenticationRequestedEvent: MutableLiveData<Event<String>> by lazy {
        MutableLiveData<Event<String>>()
    }

    val clearAuthenticationRequestDialogEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val refreshMicrophoneMuteStateEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val showGreenToastEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData<Event<Pair<Int, Int>>>()
    }

    val showRedToastEvent: MutableLiveData<Event<Pair<Int, Int>>> by lazy {
        MutableLiveData<Event<Pair<Int, Int>>>()
    }

    val showFormattedRedToastEvent: MutableLiveData<Event<Pair<String, Int>>> by lazy {
        MutableLiveData<Event<Pair<String, Int>>>()
    }

    val provisioningAppliedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val mdmConfigAppliedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    val mdmConfigRemovedEvent: MutableLiveData<Event<Boolean>> by lazy {
        MutableLiveData<Event<Boolean>>()
    }

    private var filesToExportToNativeMediaGallery = arrayListOf<String>()
    val filesToExportToNativeMediaGalleryEvent: MutableLiveData<Event<List<String>>> by lazy {
        MutableLiveData<Event<List<String>>>()
    }

    private var keepAliveServiceStarted = false

    private lateinit var proximityWakeLock: PowerManager.WakeLock

    // Held for the duration of any call so Wi-Fi never drops into power-save mode. Power-save delivers
    // incoming packets in bursts, which shows up as choppy INCOMING audio (outgoing stays fine because
    // the phone transmits whenever it wants). Created lazily, not reference-counted.
    private var callWifiLock: android.net.wifi.WifiManager.WifiLock? = null

    @SuppressLint("HandlerLeak")
    private lateinit var coreThread: Handler

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        @WorkerThread
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (!addedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${addedDevices.size}] new device(s) have been added:")
                var atLeastOneNewDeviceIsBluetooth = false
                for (device in addedDevices) {
                    Log.i(
                        "$TAG Added device [${device.productName}] with ID [${device.id}] and type [${device.type}]"
                    )

                    when (device.type) {
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> {
                            atLeastOneNewDeviceIsBluetooth = true
                        }
                    }
                }

                Log.i("$TAG Reloading sound devices in 500ms")
                postOnCoreThreadDelayed({
                    Log.i("$TAG Reloading sound devices")
                    core.reloadSoundDevices()

                    if (atLeastOneNewDeviceIsBluetooth && core.callsNb > 0 && corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG It seems a bluetooth device is now available, trying to route audio to it")
                        AudioUtils.routeAudioToEitherBluetoothOrHearingAid()
                    }
                }, 500)
            }
        }

        @WorkerThread
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (!removedDevices.isNullOrEmpty()) {
                Log.i("$TAG [${removedDevices.size}] existing device(s) have been removed")
                for (device in removedDevices) {
                    Log.i(
                        "$TAG Removed device [${device.id}][${device.productName}][${device.type}]"
                    )
                }

                Log.i("$TAG Reloading sound devices in 500ms")
                postOnCoreThreadDelayed({
                    Log.i("$TAG Reloading sound devices")
                    core.reloadSoundDevices()
                }, 500)
            }
        }
    }

    private var previousCallState = Call.State.Idle

    // AgentPro: timestamps (SystemClock.elapsedRealtime based) backing the dialpad uptime/idle display
    @Volatile
    var sipRegisteredSinceTimestamp: Long = 0L

    @Volatile
    var lastCallEndedTimestamp: Long = 0L

    // AgentPro: fired when a call ends and Auto Disposition is enabled, carrying (number, direction)
    val callDispositionRequestedEvent: MutableLiveData<Event<Pair<String, String>>> by lazy {
        MutableLiveData<Event<Pair<String, String>>>()
    }

    private val coreListener = object : CoreListenerStub() {
        @WorkerThread
        override fun onAccountRegistrationStateChanged(
            core: Core,
            account: Account,
            state: RegistrationState?,
            message: String
        ) {
            when (state) {
                RegistrationState.Ok -> {
                    if (sipRegisteredSinceTimestamp == 0L) {
                        sipRegisteredSinceTimestamp = android.os.SystemClock.elapsedRealtime()
                    }
                    applyNatPolicyForCurrentNetwork(core, account)
                }
                RegistrationState.Cleared, RegistrationState.Failed, RegistrationState.None -> {
                    sipRegisteredSinceTimestamp = 0L
                }
                else -> {}
            }
        }

        @WorkerThread
        override fun onDefaultAccountChanged(core: Core, account: Account?) {
            defaultAccountHasVideoConferenceFactoryUri = account?.params?.audioVideoConferenceFactoryAddress != null

            val defaultDomain = corePreferences.defaultDomain
            val isAccountOnDefaultDomain = account?.params?.domain == defaultDomain
            val domainFilter = corePreferences.contactsFilter
            Log.i("$TAG Currently selected filter is [$domainFilter]")

            if (!isAccountOnDefaultDomain && domainFilter == defaultDomain) {
                corePreferences.contactsFilter = "*"
                Log.i(
                    "$TAG New default account isn't on default domain, changing filter to any SIP contacts instead"
                )
            } else if (isAccountOnDefaultDomain && domainFilter != "") {
                corePreferences.contactsFilter = defaultDomain
                Log.i("$TAG New default account is on default domain, using that domain as filter instead of wildcard")
            }
        }

        @WorkerThread
        override fun onMessagesReceived(
            core: Core,
            chatRoom: ChatRoom,
            messages: Array<out ChatMessage?>
        ) {
            if (corePreferences.makePublicMediaFilesDownloaded && core.maxSizeForAutoDownloadIncomingFiles >= 0) {
                for (message in messages) {
                    // Never do auto media export for ephemeral messages!
                    if (message?.isEphemeral == true) continue

                    for (content in message?.contents.orEmpty()) {
                        if (content.isFile) {
                            val path = content.filePath
                            if (path.isNullOrEmpty()) continue

                            val mime = "${content.type}/${content.subtype}"
                            val mimeType = FileUtils.getMimeType(mime)
                            when (mimeType) {
                                FileUtils.MimeType.Image, FileUtils.MimeType.Video, FileUtils.MimeType.Audio -> {
                                    Log.i("$TAG Added file path [$path] to the list of media to export to native media gallery")
                                    filesToExportToNativeMediaGallery.add(path)
                                }
                                else -> {}
                            }
                        }
                    }
                }
            }

            if (filesToExportToNativeMediaGallery.isNotEmpty()) {
                Log.i("$TAG Creating event with [${filesToExportToNativeMediaGallery.size}] files to export to native media gallery")
                filesToExportToNativeMediaGalleryEvent.postValue(Event(filesToExportToNativeMediaGallery))
            }
        }

        @WorkerThread
        override fun onGlobalStateChanged(core: Core, state: GlobalState, message: String) {
            Log.i("$TAG Global state changed [$state]")

            if (state == GlobalState.On) {
                // Wait for GlobalState.ON as some settings modification won't be saved
                // in RC file if Core isn't ON
                onCoreStarted()
            } else if (state == GlobalState.Shutdown) {
                onCoreStopped()
            }
        }

        @WorkerThread
        override fun onConfiguringStatus(
            core: Core,
            status: ConfiguringState?,
            message: String?
        ) {
            Log.i("$TAG Configuring state changed [$status], message is [$message]")
            if (status == ConfiguringState.Successful) {
                val accounts = core.accountList
                if (core.defaultAccount == null && accounts.isNotEmpty()) {
                    val firstAccount = accounts.firstOrNull()
                    if (firstAccount != null) {
                        val sipUri = firstAccount.params.identityAddress?.asStringUriOnly()
                        Log.w(
                            "$TAG Default account is null but account list isn't empty, using account [$sipUri] as default"
                        )
                        core.defaultAccount = firstAccount
                    }
                }

                provisioningAppliedEvent.postValue(Event(true))
                corePreferences.firstLaunch = false
                showGreenToastEvent.postValue(
                    Event(
                        Pair(
                            org.linphone.R.string.remote_provisioning_config_applied_toast,
                            org.linphone.R.drawable.smiley
                        )
                    )
                )
            } else if (status == ConfiguringState.Failed) {
                showRedToastEvent.postValue(
                    Event(
                        Pair(
                            org.linphone.R.string.remote_provisioning_config_failed_toast,
                            org.linphone.R.drawable.warning_circle
                        )
                    )
                )
            }
        }

        @WorkerThread
        override fun onCallStateChanged(
            core: Core,
            call: Call,
            state: Call.State?,
            message: String
        ) {
            val currentState = call.state
            Log.i(
                "$TAG Call [${call.remoteAddress.asStringUriOnly()}] state changed [$currentState]"
            )
            when (currentState) {
                Call.State.IncomingReceived -> {
                    if (corePreferences.isDoNotDisturbActive()) {
                        Log.w("$TAG Do Not Disturb is enabled, declining incoming call")
                        terminateCall(call)
                    } else if (corePreferences.autoAnswerEnabled) {
                        // Delay slightly so the Android Telecom layer can register the call
                        // first; answering before that leaves the call inactive ("call waiting").
                        val autoAnswerDelay = maxOf(corePreferences.autoAnswerDelay, 1000)
                        Log.i("$TAG Scheduling auto answering in $autoAnswerDelay milliseconds")
                        postOnCoreThreadDelayed({
                            if (LinphoneUtils.isCallIncoming(call.state)) {
                                Log.w("$TAG Auto answering call")
                                answerCall(call, true)
                            } else {
                                Log.i("$TAG Call no longer incoming, skipping auto answer")
                            }
                        }, autoAnswerDelay.toLong())
                    }
                }
                Call.State.IncomingEarlyMedia -> {
                    if (core.ringDuringIncomingEarlyMedia) {
                        val speaker = core.audioDevices.find {
                            it.type == AudioDevice.Type.Speaker
                        }
                        if (speaker != null) {
                            Log.i("$TAG Ringing during incoming early media enabled, make sure speaker audio device [${speaker.id}] is used")
                            call.outputAudioDevice = speaker
                        } else {
                            Log.w("$TAG No speaker device found, incoming call early media ringing will be played on default device")
                        }
                    }
                }
                Call.State.OutgoingInit -> {
                    val conferenceInfo = core.findConferenceInformationFromUri(call.remoteAddress)
                    // Do not show outgoing call view for conference calls, wait for connected state
                    if (conferenceInfo == null) {
                        postOnMainThread {
                            showCallActivity()
                        }
                    } else {
                        Log.i(
                            "$TAG Call peer address matches known conference, delaying in-call UI until Connected state"
                        )
                    }
                }
                Call.State.OutgoingRinging, Call.State.OutgoingEarlyMedia -> {
                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Trying to route audio to either bluetooth or hearing aid if available")
                        AudioUtils.routeAudioToEitherBluetoothOrHearingAid(call)
                    }
                }
                Call.State.Connected -> {
                    postOnMainThread {
                        showCallActivity()
                    }
                    if (corePreferences.routeAudioToBluetoothWhenPossible) {
                        Log.i("$TAG Call is connected, trying to route audio to either bluetooth or hearing aid if available")
                        AudioUtils.routeAudioToEitherBluetoothOrHearingAid(call)
                    }
                }
                Call.State.StreamsRunning -> {
                    if (previousCallState == Call.State.Connected) {
                        if (corePreferences.automaticallyStartCallRecording && !call.params.isRecording) {
                            if (call.conference == null) { // TODO: FIXME: Conference recordings are currently disabled
                                Log.i("$TAG Auto record calls is enabled, starting it now")
                                call.startRecording()
                            }
                        }

                        if (core.isInBackground) {
                            // App is in background which means user likely answered the call from the notification
                            // In this case start proximity sensor, otherwise CallActivity will handle it
                            postOnMainThread {
                                Log.i("$TAG App is in background, start proximity sensor")
                                enableProximitySensor(true)
                            }
                        }
                    }
                }
                Call.State.Error -> {
                    val errorInfo = call.errorInfo
                    Log.w(
                        "$TAG Call error reason is [${errorInfo.reason}](${errorInfo.protocolCode}): ${errorInfo.phrase}"
                    )
                    val text = LinphoneUtils.getCallErrorInfoToast(call)
                    showFormattedRedToastEvent.postValue(
                        Event(Pair(text, org.linphone.R.drawable.warning_circle))
                    )
                    lastCallEndedTimestamp = android.os.SystemClock.elapsedRealtime()
                }
                Call.State.End, Call.State.Released -> {
                    lastCallEndedTimestamp = android.os.SystemClock.elapsedRealtime()
                    if (currentState == Call.State.Released && corePreferences.autoDispEnabled) {
                        val remote = call.remoteAddress
                        val number = remote.username?.takeIf { it.isNotEmpty() }
                            ?: remote.asStringUriOnly()
                        val direction = if (call.dir == Call.Dir.Outgoing) "out" else "in"
                        Log.i("$TAG Auto Disposition is on, requesting disposition for [$number]")
                        callDispositionRequestedEvent.postValue(Event(Pair(number, direction)))
                    }
                }
                else -> {
                }
            }

            // Keep Wi-Fi at full power for as long as any call is up — fixes choppy INCOMING audio
            // caused by Wi-Fi power-save delivering RTP in bursts.
            holdWifiLockForCalls(core.callsNb > 0)

            previousCallState = currentState
        }

        @WorkerThread
        override fun onTransferStateChanged(core: Core, transfered: Call, state: Call.State) {
            Log.i(
                "$TAG Transferred call [${transfered.remoteAddress.asStringUriOnly()}] state changed [$state]"
            )
            if (state == Call.State.Connected) {
                val icon = org.linphone.R.drawable.phone_transfer
                showGreenToastEvent.postValue(
                    Event(Pair(org.linphone.R.string.call_transfer_successful_toast, icon))
                )
            }
        }

        @WorkerThread
        override fun onAudioDevicesListUpdated(core: Core) {
            Log.i("$TAG Available audio devices list was updated")
        }

        @WorkerThread
        override fun onFirstCallStarted(core: Core) {
            Log.i("$TAG First call started")
        }

        @WorkerThread
        override fun onLastCallEnded(core: Core) {
            Log.i("$TAG Last call ended")
            val currentCamera = core.videoDevice
            if (currentCamera != "FrontFacingCamera") {
                val frontFacing = core.videoDevicesList.find { it == "FrontFacingCamera" }
                if (frontFacing == null) {
                    Log.w("$TAG Failed to find [FrontFacingCamera] camera, doing nothing...")
                } else {
                    Log.i("$TAG Last call ended, setting [$frontFacing] as the default one")
                    core.videoDevice = frontFacing
                }
            }

            postOnMainThread {
                Log.i("$TAG Releasing proximity sensor if it was enabled")
                enableProximitySensor(false)
            }
        }

        @WorkerThread
        override fun onAuthenticationRequested(core: Core, authInfo: AuthInfo, method: AuthMethod) {
            when (method) {
                AuthMethod.Bearer -> {
                    if (authInfo.authorizationServer == null) {
                        Log.e(
                            "$TAG Authentication request using Bearer method but authorization server is null!"
                        )
                        return
                    }

                    val serverUrl = authInfo.authorizationServer
                    val username = authInfo.username
                    if (!serverUrl.isNullOrEmpty()) {
                        Log.i(
                            "$TAG Authentication requested method is Bearer, starting Single Sign On activity with server URL [$serverUrl] and username [$username]"
                        )
                        bearerAuthInfoPendingPasswordUpdate = authInfo
                        bearerAuthenticationRequestedEvent.postValue(
                            Event(Pair(serverUrl, username))
                        )
                    } else {
                        Log.e(
                            "$TAG Authentication requested method is Bearer but no authorization server was found in auth info!"
                        )
                    }
                }
                AuthMethod.HttpDigest -> {
                    if (authInfo.username == null || authInfo.domain == null) {
                        Log.e(
                            "$TAG Authentication request using Digest method but either username [${authInfo.username}] or domain [${authInfo.domain}] is null!"
                        )
                        return
                    }
                    if (authInfo.realm == null) {
                        Log.w(
                            "$TAG Authentication request using Digest method with null realm, using domain as realm"
                        )
                        authInfo.realm = authInfo.domain
                    }

                    val accountFound = core.accountList.find {
                        it.params.identityAddress?.username == authInfo.username && it.params.identityAddress?.domain == authInfo.domain
                    }
                    if (accountFound == null) {
                        Log.w(
                            "$TAG Failed to find account matching auth info, aborting auth dialog"
                        )
                        return
                    }

                    val identity = "${authInfo.username}@${authInfo.domain}"
                    Log.i(
                        "$TAG Authentication requested method is HttpDigest, showing dialog asking user for password for identity [$identity]"
                    )
                    digestAuthInfoPendingPasswordUpdate = authInfo
                    digestAuthenticationRequestedEvent.postValue(Event(identity))
                }
                AuthMethod.Tls -> {
                    Log.w("$TAG Authentication requested method is TLS, not doing anything...")
                }
                else -> {
                    Log.w("$TAG Unexpected authentication request method [$method]")
                }
            }
        }

        @WorkerThread
        override fun onAccountAdded(core: Core, account: Account) {
            // Prevent this trigger when core is stopped/start in remote prov
            if (core.globalState == GlobalState.Off) return

            Log.i(
                "$TAG New account configured: [${account.params.identityAddress?.asStringUriOnly()}]"
            )

            // Enable STUN for NAT traversal - ICE stays disabled (force_ice_disablement=1 in factory)
            // STUN-only: app discovers public IP for SDP -> bidirectional RTP from WAN
            // LAN: symmetric_rtp ensures PBX uses source IP of received RTP, not SDP IP -> still works
            val params = account.params
            var policy = params.natPolicy
            if (policy == null) {
                policy = core.createNatPolicy()
            }
            if (policy != null) {
                if (policy.stunServer.isNullOrEmpty()) {
                    Log.i("$TAG Initializing NAT policy: enabling STUN for WAN audio, ICE stays disabled")
                    policy.stunServer = STUN_SERVER
                    policy.isStunEnabled = true
                    policy.isIceEnabled = false
                    policy.isTurnEnabled = false
                    val newParams = params.clone()
                    newParams.natPolicy = policy
                    account.params = newParams
                } else {
                    Log.i("$TAG NAT policy already has STUN server [${policy.stunServer}], not overriding")
                }
            }

            if (!core.isPushNotificationAvailable || !account.params.isPushNotificationAvailable) {
                if (!corePreferences.keepServiceAlive) {
                    Log.w(
                        "$TAG Newly added account (or the whole Core) doesn't support push notifications, enabling keep-alive foreground service..."
                    )
                    corePreferences.keepServiceAlive = true
                    startKeepAliveService()
                } else {
                    Log.i(
                        "$TAG Newly added account (or the whole Core) doesn't support push notifications but keep-alive foreground service is already enabled, nothing to do"
                    )
                }
            }
        }

        @WorkerThread
        override fun onAccountRemoved(core: Core, account: Account) {
            Log.i("$TAG Account [${account.params.identityAddress?.asStringUriOnly()}] removed, clearing auth request dialog if needed")
            if (account.findAuthInfo() == digestAuthInfoPendingPasswordUpdate) {
                Log.i("$TAG Removed account matches auth info pending password update, removing dialog")
                clearAuthenticationRequestDialogEvent.postValue(Event(true))
                digestAuthInfoPendingPasswordUpdate = null
            }

            if (core.defaultAccount == null || core.defaultAccount == account) {
                Log.w("$TAG Removed account was the default one, choosing another as default if possible")
                val newDefaultAccount = core.accountList.find {
                    it.params.isRegisterEnabled
                } ?: core.accountList.firstOrNull()
                if (newDefaultAccount == null) {
                    Log.e("$TAG Failed to find a new default account!")
                } else {
                    Log.i("$TAG New default account will be [${newDefaultAccount.params.identityAddress?.asStringUriOnly()}]")
                    // Delay changing default account to allow for other onAccountRemoved listeners to trigger first
                    postOnCoreThread {
                        core.defaultAccount = newDefaultAccount
                    }
                }
            }
        }
    }

    private var logcatEnabled: Boolean = corePreferences.printLogsInLogcat

    private var crashlyticsEnabled: Boolean = corePreferences.sendLogsToCrashlytics
    private var crashlyticsAvailable = BuildConfig.CRASHLYTICS_ENABLED

    private val loggingServiceListener = object : LoggingServiceListenerStub() {
        @WorkerThread
        override fun onLogMessageWritten(
            logService: LoggingService,
            domain: String,
            level: LogLevel,
            message: String
        ) {
            if (logcatEnabled) {
                when (level) {
                    LogLevel.Error -> android.util.Log.e(domain, message)
                    LogLevel.Warning -> android.util.Log.w(domain, message)
                    LogLevel.Message -> android.util.Log.i(domain, message)
                    LogLevel.Fatal -> android.util.Log.wtf(domain, message)
                    else -> android.util.Log.d(domain, message)
                }
            }
            if (crashlyticsAvailable && crashlyticsEnabled) {
                FirebaseCrashlytics.getInstance().log("[$domain] [${level.name}] $message")
            }
        }
    }

    init {
        (context as Application).registerActivityLifecycleCallbacks(activityMonitor)
    }

    @WorkerThread
    override fun run() {
        Log.i("$TAG Creating Core")
        Looper.prepare()

        if (BuildConfig.CRASHLYTICS_ENABLED) {
            Log.i("$TAG Crashlytics is enabled, registering logging service listener")
            try {
                FirebaseCrashlytics.getInstance()
                Factory.instance().loggingService.addListener(loggingServiceListener)
            } catch (e: Exception) {
                Log.e("$TAG Failed to instantiate Crashlytics: $e")
                crashlyticsEnabled = false
                crashlyticsAvailable = false
            }
        } else {
            Log.i("$TAG Crashlytics is disabled")
            crashlyticsAvailable = false
        }
        Log.i("=========================================")
        Log.i("==== Linphone-android information dump ====")
        val gitVersion = AppUtils.getString(org.linphone.R.string.linphone_app_version)
        val gitBranch = AppUtils.getString(org.linphone.R.string.linphone_app_branch)
        Log.i("VERSION=${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE} ($gitVersion from $gitBranch branch)")
        Log.i("PACKAGE=${BuildConfig.APPLICATION_ID}")
        Log.i("BUILD TYPE=${BuildConfig.BUILD_TYPE}")
        Log.i("=========================================")

        val looper = Looper.myLooper() ?: return
        coreThread = Handler(looper)

        core = Factory.instance().createCoreWithConfig(corePreferences.config, context)
        core.isAutoIterateEnabled = true
        core.addListener(coreListener)

        defaultAccountHasVideoConferenceFactoryUri = core.defaultAccount?.params?.audioVideoConferenceFactoryAddress != null

        coreThread.postDelayed({
            startCore()
            ManagedConfiguration.applyMdmConfigToCore(context, core)
        }, 50)

        Looper.loop()
    }

    override fun quit(): Boolean {
        destroyCore()
        return super.quit()
    }

    override fun quitSafely(): Boolean {
        destroyCore()
        return super.quitSafely()
    }

    @WorkerThread
    fun startCore() {
        Log.i("$TAG Starting Core")

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, coreThread)

        // AgentPro "locked build": auto-provision the embedded account + PBX settings before the Core
        // starts, so a customer-bound APK registers on first launch with no login screen.
        provisionLockedSetupIfNeeded()

        val accounts = core.accountList
        if (core.defaultAccount == null && accounts.isNotEmpty()) {
            Log.e("$TAG No default account set but accounts list not empty!")
            val firstAccount = accounts.first()
            core.defaultAccount = firstAccount
            Log.w("$TAG Set account [${firstAccount?.params?.identityAddress?.asStringUriOnly()}] as default")
        }

        // Per-account data: scope every per-account store (PBX/AMI/UCM/Agent settings, dispositions,
        // Campaign + Contacts lists, dialer delay, speed-dials) to the active account so the whole app
        // uses the current account's own data (kept in sync on every account switch). The first launch
        // after this was introduced migrates the legacy un-scoped data into the default account.
        AccountScope.activate(
            context,
            core.defaultAccount?.params?.identityAddress?.asStringUriOnly()
        )

        // Codec policy: leave EVERY audio codec enabled and let the user (App Settings ▸ codecs) or
        // the UCM negotiate freely — no app-side forcing. This one-time reset re-enables all audio
        // codecs once, to undo an earlier "force G.711" experiment that had persisted OPUS/G722 as
        // disabled; afterwards codecs are left untouched so manual choices are respected.
        if (!core.config.getBool("app", "codec_reset_done", false)) {
            for (payload in core.audioPayloadTypes) {
                if (!payload.enabled()) payload.enable(true)
            }
            core.config.setBool("app", "codec_reset_done", true)
            Log.i("$TAG One-time codec reset: re-enabled all audio codecs (no forced codec)")
        }

        computeUserAgent()
        Log.i("$TAG Core has been configured with user-agent [${core.userAgent}], starting it")

        // Ensure all SIP transports are properly enabled before starting
        val transports = core.transports
        Log.i("$TAG SIP Transports status before start: UDP=${transports.udpPort}, TCP=${transports.tcpPort}, TLS=${transports.tlsPort}")
        if (transports.udpPort == 0 && transports.tcpPort == 0 && transports.tlsPort == 0) {
            Log.w("$TAG All SIP transports are disabled! Enabling standard ports...")
            transports.udpPort = 5060
            transports.tcpPort = 5060
            transports.tlsPort = 5061
            core.transports = transports
            Log.i("$TAG SIP Transports fixed: UDP=${transports.udpPort}, TCP=${transports.tcpPort}, TLS=${transports.tlsPort}")
        } else if (transports.udpPort == 0 || transports.tcpPort == 0 || transports.tlsPort == 0) {
            Log.w("$TAG Some SIP transports are disabled: UDP=${transports.udpPort}, TCP=${transports.tcpPort}, TLS=${transports.tlsPort}")
            if (transports.udpPort == 0) {
                transports.udpPort = 5060
                Log.i("$TAG Enabled UDP on port 5060")
            }
            if (transports.tcpPort == 0) {
                transports.tcpPort = 5060
                Log.i("$TAG Enabled TCP on port 5060")
            }
            if (transports.tlsPort == 0) {
                transports.tlsPort = 5061
                Log.i("$TAG Enabled TLS on port 5061")
            }
            core.transports = transports
        }

        core.start()
    }

    // ============================================================================================
    // AgentPro "locked build" provisioning. Driven by the [locked_account] / [pbx] sections that the
    // desktop "AgentPro APK Builder" writes into linphonerc_factory. No-op on normal "full" builds.
    // ============================================================================================

    @WorkerThread
    private fun provisionLockedSetupIfNeeded() {
        // Runs for both a pre-filled "Full" build (provision only) and a "Locked" build
        // (provision + UI lock). The lock itself is handled separately via corePreferences.accountLocked.
        if (!corePreferences.provisionAccount) return

        // 1) Auto-create the embedded SIP account once (it then persists in the writable config).
        if (core.accountList.isEmpty()) {
            val username = corePreferences.lockedAccountUsername.trim()
            val domain = corePreferences.lockedAccountDomain.trim()
            if (username.isEmpty() || domain.isEmpty()) {
                Log.e("$TAG [Locked] enabled but username/domain missing in factory config, skipping account provisioning")
            } else {
                provisionLockedAccount(username, domain)
            }
        } else {
            Log.i("$TAG [Locked] Embedded account already present, skipping SIP provisioning")
        }

        // 2) Seed the PBX/AMI/UCM connection settings once (for the Panel/CDR/Agent/Wallboard tabs).
        seedLockedPbxSettingsIfNeeded()
    }

    @WorkerThread
    private fun provisionLockedAccount(username: String, domainValue: String) {
        Log.i("$TAG [Locked] Provisioning embedded SIP account for [$username@$domainValue]")

        // Apply the same default account values the assistant login uses (codecs, etc.)
        core.loadConfigFromXml(corePreferences.thirdPartyDefaultValuesPath)

        val domainWithoutSip = when {
            domainValue.startsWith("sips:") -> domainValue.substring("sips:".length)
            domainValue.startsWith("sip:") -> domainValue.substring("sip:".length)
            else -> domainValue
        }
        val domainAddress = Factory.instance().createAddress("sip:$domainWithoutSip")
        val port = domainAddress?.port ?: -1
        val domain = domainAddress?.domain ?: domainWithoutSip

        var user = username
        if (user.startsWith("sip:")) {
            user = user.substring("sip:".length)
        } else if (user.startsWith("sips:")) {
            user = user.substring("sips:".length)
        }
        if (user.contains("@")) user = user.split("@")[0]

        val userId = corePreferences.lockedAccountAuthId.trim()
        val password = corePreferences.lockedAccountPassword.trim()
        val transportType = when (corePreferences.lockedAccountTransport.trim().lowercase()) {
            "tcp" -> TransportType.Tcp
            "tls" -> TransportType.Tls
            else -> TransportType.Udp
        }

        val identity = "sip:$user@$domain"
        val identityAddress = Factory.instance().createAddress(identity)
        if (identityAddress == null) {
            Log.e("$TAG [Locked] Can't parse [$identity] as Address, aborting provisioning")
            return
        }

        val authInfoDomain = domainAddress?.domain ?: domainWithoutSip
        val authInfo = Factory.instance().createAuthInfo(
            user,
            userId,
            password,
            null,
            authInfoDomain,
            authInfoDomain
        )
        core.addAuthInfo(authInfo)

        val accountParams = core.createAccountParams()

        val displayName = corePreferences.lockedAccountDisplayName.trim()
        if (displayName.isNotEmpty()) {
            identityAddress.displayName = displayName
        }
        accountParams.identityAddress = identityAddress

        val proxyValue = corePreferences.lockedAccountProxy.trim()
        val proxyServerAddress = if (proxyValue.isNotEmpty()) {
            val server = if (proxyValue.startsWith("sip:") || proxyValue.startsWith("sips:")) {
                proxyValue
            } else {
                "sip:$proxyValue"
            }
            Factory.instance().createAddress(server)
        } else {
            val addr = domainAddress ?: Factory.instance().createAddress("sip:$domainWithoutSip")
            if (port != -1) addr?.port = port
            addr
        }
        proxyServerAddress?.transport = transportType
        accountParams.serverAddress = proxyServerAddress

        val outboundValue = corePreferences.lockedAccountOutboundProxy.trim()
        if (outboundValue.isNotEmpty()) {
            val server = if (outboundValue.startsWith("sip:") || outboundValue.startsWith("sips:")) {
                outboundValue
            } else {
                "sip:$outboundValue"
            }
            val addr = Factory.instance().createAddress(server)
            if (port != -1) addr?.port = port
            addr?.transport = transportType
            if (addr != null) accountParams.setRoutesAddresses(arrayOf(addr))
        }

        val prefix = corePreferences.lockedAccountInternationalPrefix.trim()
        if (prefix.isNotEmpty()) {
            val prefixDigits = if (prefix.startsWith("+")) prefix.substring(1) else prefix
            if (prefixDigits.isNotEmpty()) {
                accountParams.internationalPrefix = prefixDigits
                accountParams.internationalPrefixIsoCountryCode =
                    corePreferences.lockedAccountInternationalPrefixIso
            }
        }

        val account = core.createAccount(accountParams)
        core.addAccount(account)
        core.defaultAccount = account
        Log.i("$TAG [Locked] Embedded account created & set as default [${identityAddress.asStringUriOnly()}] via [$transportType]")
    }

    @WorkerThread
    private fun seedLockedPbxSettingsIfNeeded() {
        if (!corePreferences.lockedPbxConfigured) {
            Log.i("$TAG [Locked] No [pbx] section in factory config, skipping PBX seeding")
            return
        }
        if (PbxPrefs.isConfigured(context)) {
            Log.i("$TAG [Locked] PBX settings already present, skipping seeding")
            return
        }
        Log.i("$TAG [Locked] Seeding embedded PBX/AMI/UCM connection settings")
        PbxPrefs.save(
            context,
            PbxConfig(
                host = corePreferences.lockedPbxHost,
                port = corePreferences.lockedPbxAmiPort,
                username = corePreferences.lockedPbxAmiUser,
                password = corePreferences.lockedPbxAmiPass,
                serverType = "UCM"
            )
        )
        PbxPrefs.setApi(
            context,
            corePreferences.lockedPbxApiPort,
            corePreferences.lockedPbxApiUser,
            corePreferences.lockedPbxApiPass
        )
        PbxPrefs.setWeb(context, corePreferences.lockedPbxWebUser, corePreferences.lockedPbxWebPass)
        PbxPrefs.setAgent(context, corePreferences.lockedPbxAgentExt, corePreferences.lockedPbxAgentProto)
        val queues = corePreferences.lockedPbxAgentQueues
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (queues.isNotEmpty()) PbxPrefs.setAgentQueues(context, queues)
        PbxPrefs.setSpyCodes(
            context,
            corePreferences.lockedPbxListenCode,
            corePreferences.lockedPbxWhisperCode,
            corePreferences.lockedPbxBargeCode
        )
    }

    // AgentPro: AgentPro connects only to the customer's own PBX and ships its own builds, so
    // it must never call linphone. Two endpoints in the SDK's config do exactly that: the
    // update check queries their release server, and the log collection uploader posts debug
    // traces - which name the customer's PBX host, their extensions and their SIP traffic - to
    // their file server.
    //
    // Cleared on every launch rather than in configurationMigration5To6(), where the upstream
    // URL handling lives: that migration is gated on the stored config version and runs once,
    // for devices coming from before 6.0.0. A device that already carries either URL would
    // never pass through it again and would keep calling out.
    //
    // Clearing them also takes the "Check for update" and "Upload logs" buttons out of the Help
    // screen, which HelpViewModel already hides when these are empty.

    /** Every non-loopback address this device currently holds, across all live interfaces. */
    @WorkerThread
    private fun localAddresses(): Set<String> {
        val addresses = mutableSetOf<String>()
        try {
            for (nif in java.net.NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.inetAddresses) {
                    if (address.isLoopbackAddress) continue
                    // IPv6 addresses come back scoped ("fe80::1%wlan0"); SIP carries them bare.
                    address.hostAddress?.substringBefore('%')?.let { addresses.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w("$TAG [NAT] Could not list this device's addresses: $e")
        }
        return addresses
    }

    /**
     * Decide per network whether this account needs STUN, and apply it.
     *
     * Two situations, and they want opposite things:
     *
     *  - **Outside the customer's network** (reaching the PBX through its domain over the
     *    internet). The phone only knows its own LAN address, so that is what liblinphone
     *    writes into the SDP - and the PBX cannot route to it. Audio only survives because the
     *    PBX latches onto the source address of the RTP it receives, which works in one
     *    direction and not the other, and RTCP never comes back at all. STUN fixes it: the
     *    phone learns its public address and offers that instead.
     *
     *  - **Inside the customer's network.** The LAN address in the SDP is exactly right, and
     *    asking a STUN server on the internet for a public address is both pointless and slow -
     *    it delays call setup for nothing, and the address it returns is the wrong one to use.
     *
     * The signal used is the one liblinphone has already worked out for itself: after a
     * successful REGISTER it rewrites the account's Contact to whatever the PBX reported seeing
     * (the rport/received values). If that address is one this phone actually holds on one of
     * its interfaces, nothing translated it and we are inside. If it is not, there is a NAT in
     * between and we are outside.
     *
     * That beats comparing subnets, because it is right for the awkward case too: on the office
     * WiFi where the SIP domain still resolves to the public address, the Contact comes back
     * unchanged and STUN correctly stays off.
     *
     * Runs on every successful registration, so switching WiFi to mobile data - or walking into
     * the office - re-decides on its own, with no setting for anyone to get wrong.
     */
    @WorkerThread
    private fun applyNatPolicyForCurrentNetwork(core: Core, account: Account) {
        val identity = account.params.identityAddress?.asStringUriOnly() ?: "?"

        val publicAddress = account.contactAddress?.domain
        if (publicAddress.isNullOrEmpty()) {
            Log.w("$TAG [NAT] No contact address yet for [$identity], leaving the policy alone")
            return
        }

        // If the address the PBX reported seeing is one this phone actually holds, the two are
        // on the same network. If it is not, something translated it on the way - we are
        // outside. An empty set (enumeration failed) reads as "outside", which is the safe way
        // to be wrong: a needless STUN lookup rather than one-way audio.
        val ownAddresses = localAddresses()
        val behindNat = publicAddress !in ownAddresses
        val params = account.params
        val policy = params.natPolicy ?: core.createNatPolicy()
        if (policy == null) {
            Log.w("$TAG [NAT] Could not obtain a NAT policy for [$identity]")
            return
        }

        // Logged on every registration, whether or not anything changes. This decides how calls
        // are set up, and a silent decision is one nobody can check when audio goes wrong.
        Log.i(
            if (behindNat) {
                "$TAG [NAT] [$identity] is OUTSIDE the PBX network - the PBX sees [$publicAddress], " +
                    "this phone holds $ownAddresses. STUN on."
            } else {
                "$TAG [NAT] [$identity] is INSIDE the PBX network - the PBX sees [$publicAddress], " +
                    "which is this phone's own address. STUN off."
            }
        )

        // Already in that state: do not churn the account params on every registration refresh.
        if (policy.isStunEnabled == behindNat && !policy.isIceEnabled && !policy.isTurnEnabled) {
            return
        }

        if (behindNat) {
            policy.stunServer = STUN_SERVER
            policy.isStunEnabled = true
        } else {
            policy.isStunEnabled = false
        }
        Log.i("$TAG [NAT] Applying the change to [$identity]")
        // ICE is off in both cases: it made calls one-way against this PBX, and
        // force_ice_disablement=1 in the factory config says the same thing.
        policy.isIceEnabled = false
        policy.isTurnEnabled = false

        val newParams = params.clone()
        newParams.natPolicy = policy
        account.params = newParams
    }

    @WorkerThread
    private fun clearLinphoneEndpoints() {
        if (corePreferences.checkForUpdateServerUrl.isNotEmpty()) {
            Log.i("$TAG Clearing the update-check server URL - AgentPro ships its own builds")
            corePreferences.checkForUpdateServerUrl = ""
        }
        if (!core.logCollectionUploadServerUrl.isNullOrEmpty()) {
            Log.i("$TAG Clearing the log upload server URL - debug traces stay on the device")
            core.logCollectionUploadServerUrl = ""
        }
        // Chat attachments defaulted to files.linphone.org, so a customer's files would have
        // travelled through linphone's server. The feature does not work in this product
        // anyway, so there is nothing to preserve by keeping the endpoint.
        if (!core.fileTransferServer.isNullOrEmpty()) {
            Log.i("$TAG Clearing the file transfer server URL - no customer file leaves for linphone")
            core.fileTransferServer = ""
        }
        // The setters above change the running Core, but the linphonerc on disk was still found
        // to hold the old linphone.org addresses afterwards. Harmless while the app runs - the
        // in-memory value is what gets used - but it leaves their URLs sitting in the customer's
        // config file, and makes every launch depend on this function to neutralise them again.
        // Writing the keys straight through the config and syncing removes them from the file.
        core.config.setString("misc", "version_check_url_root", "")
        core.config.setString("misc", "log_collection_upload_server_url", "")
        core.config.setString("misc", "file_transfer_server_url", "")
        core.config.sync()
    }

    @WorkerThread
    fun onCoreStarted() {
        Log.i("$TAG Core started, updating configuration if required")

        clearLinphoneEndpoints()

        // Log transport status after core start
        val transportsAfterStart = core.transports
        Log.i("$TAG SIP Transports after start: UDP=${transportsAfterStart.udpPort}, TCP=${transportsAfterStart.tcpPort}, TLS=${transportsAfterStart.tlsPort}")
        Log.i("$TAG Network reachable: ${core.isNetworkReachable}, Accounts count: ${core.accountList.size}")

        // Force Symmetric RTP and No RTP Port Check for PBX compatibility
        Log.i("$TAG Forcing symmetric RTP and enabling STUN IP discovery for NAT/domain compatibility")
        core.config.setBool("rtp", "symmetric_rtp", true)
        core.config.setBool("rtp", "no_rtp_port_check", true)

        // Start every account with STUN on; applyNatPolicyForCurrentNetwork() turns it back off
        // once the first REGISTER tells us we are inside the customer's network. That is the
        // fail-safe order: if the detection never gets to run, the cost is a pointless STUN
        // lookup on a LAN call, not one-way audio from outside. ICE stays off either way
        // (force_ice_disablement=1 in the factory config).
        for (account in core.accountList) {
            val params = account.params
            var policy = params.natPolicy
            if (policy == null) {
                policy = core.createNatPolicy()
            }
            if (policy != null) {
                val currentStun = policy.stunServer.orEmpty()
                if (currentStun.isEmpty()) {
                    Log.i("$TAG Setting STUN server for account [${params.identityAddress?.asStringUriOnly()}] (was empty)")
                    policy.stunServer = STUN_SERVER
                    policy.isStunEnabled = true
                    policy.isIceEnabled = false
                    policy.isTurnEnabled = false
                    val newParams = params.clone()
                    newParams.natPolicy = policy
                    account.params = newParams
                } else {
                    Log.i("$TAG Account [${params.identityAddress?.asStringUriOnly()}] already has STUN [$currentStun], ensuring ICE is off")
                    policy.isIceEnabled = false
                    val newParams = params.clone()
                    newParams.natPolicy = policy
                    account.params = newParams
                }
            }
        }

        // Global NAT policy: enable STUN, disable ICE
        var globalPolicy = core.natPolicy
        if (globalPolicy == null) {
            globalPolicy = core.createNatPolicy()
        }
        if (globalPolicy != null) {
            globalPolicy.stunServer = STUN_SERVER
            globalPolicy.isStunEnabled = true
            globalPolicy.isIceEnabled = false
            globalPolicy.isTurnEnabled = false
            core.natPolicy = globalPolicy
            Log.i("$TAG Global NAT policy set: STUN enabled, ICE disabled")
        }

        core.videoCodecPriorityPolicy = CodecPriorityPolicy.Auto

        val currentVersion = BuildConfig.VERSION_CODE
        val oldVersion = corePreferences.linphoneConfigurationVersion
        Log.w("$TAG Current configuration version is [$oldVersion]")

        if (oldVersion < currentVersion) {
            Log.w("$TAG Migrating configuration to [$currentVersion]")

            if (oldVersion < 600000) { // 6.0.0 initial release
                configurationMigration5To6()
            } else if (oldVersion < 600004) { // 6.0.4
                disablePushNotificationsFromThirdPartySipAccounts()
            } else if (oldVersion < 600009) { // 6.0.9
                removePortFromSipIdentity()
            }

            if (core.logCollectionUploadServerUrl.isNullOrEmpty()) {
                Log.w("$TAG Logs sharing server URL not set, fixing that")
                core.logCollectionUploadServerUrl = "https://files.linphone.org/http-file-transfer-server/hft.php"
            }

            corePreferences.linphoneConfigurationVersion = currentVersion
            Log.w(
                "$TAG Core configuration updated to version [${corePreferences.linphoneConfigurationVersion}]"
            )
        } else {
            Log.i("$TAG No configuration migration required")
        }

        contactsManager.onCoreStarted(core)
        telecomManager.onCoreStarted(core)
        notificationsManager.onCoreStarted(core, oldVersion < 600000) // Re-create channels when migrating from a non 6.0 version
        Log.i("$TAG Started contacts, telecom & notifications managers")

        if (corePreferences.keepServiceAlive) {
            if (activityMonitor.isInForeground() || corePreferences.autoStart) {
                Log.i("$TAG Keep alive service is enabled and either app is in foreground or auto start is enabled, starting it")
                startKeepAliveService()
            } else {
                Log.w("$TAG Keep alive service is enabled but auto start isn't and app is not in foreground, not starting it")
            }
        }

        val powerManager = context.getSystemService(POWER_SERVICE) as PowerManager
        if (!powerManager.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            Log.w("$TAG PROXIMITY_SCREEN_OFF_WAKE_LOCK isn't supported on this device!")
        } else {
            proximityWakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "${context.packageName};proximity_sensor"
            )
        }
    }

    @WorkerThread
    private fun onCoreStopped() {
        Log.w("$TAG Core is being shut down, notifying managers so they can remove their listeners and do some cleanup if needed")
        contactsManager.onCoreStopped(core)
        telecomManager.onCoreStopped(core)
        notificationsManager.onCoreStopped(core)
    }

    @WorkerThread
    private fun destroyCore() {
        if (!::core.isInitialized) {
            return
        }

        val state = core.globalState
        if (state != GlobalState.On) {
            Log.w("$TAG Core is in state [$state], do not continue destroy process")
            return
        }
        Log.w("$TAG Stopping Core and destroying context related objects")

        postOnMainThread {
            (context as Application).unregisterActivityLifecycleCallbacks(activityMonitor)
        }

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)

        core.stop()

        // It's very unlikely the process will survive until the Core reaches GlobalStateOff sadly
        Log.w("$TAG Core has been shut down")
        exitProcess(0)
    }

    @AnyThread
    fun isReady(): Boolean {
        return ::core.isInitialized
    }

    @AnyThread
    fun postOnCoreThread(
        @WorkerThread lambda: (core: Core) -> Unit
    ) {
        if (::coreThread.isInitialized) {
            coreThread.post {
                lambda.invoke(core)
            }
        } else {
            Log.e("$TAG Core's thread not initialized yet!")
        }
    }

    @AnyThread
    fun postOnCoreThreadDelayed(
        @WorkerThread lambda: (core: Core) -> Unit,
        delay: Long
    ) {
        if (::coreThread.isInitialized) {
            coreThread.postDelayed({
                lambda.invoke(core)
            }, delay)
        } else {
            Log.e("$TAG Core's thread not initialized yet!")
        }
    }

    @AnyThread
    fun postOnCoreThreadWhenAvailableForHeavyTask(@WorkerThread lambda: (core: Core) -> Unit, name: String) {
        postOnCoreThread {
            if (core.callsNb >= 1) {
                Log.i("$TAG At least one call is active, wait until there is no more call before executing lambda [$name] (checking again in 1 sec)")
                coreContext.postOnCoreThreadDelayed({
                    postOnCoreThreadWhenAvailableForHeavyTask(lambda, name)
                }, 1000)
            } else {
                Log.i("$TAG No active call at the moment, executing lambda [$name] right now")
                lambda.invoke(core)
            }
        }
    }

    @AnyThread
    fun postOnMainThread(
        @UiThread lambda: () -> Unit
    ) {
        mainThread.post {
            lambda.invoke()
        }
    }

    @UiThread
    fun onForeground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in foreground, PUBLISHING presence as Online")
                core.consolidatedPresence = ConsolidatedPresence.Online
            }

            if (corePreferences.keepServiceAlive && !keepAliveServiceStarted) {
                startKeepAliveService()
            }
        }
    }

    @UiThread
    fun onBackground() {
        postOnCoreThread {
            // We can't rely on defaultAccount?.params?.isPublishEnabled
            // as it will be modified by the SDK when changing the presence status
            if (corePreferences.publishPresence) {
                Log.i("$TAG App is in background, un-PUBLISHING presence info")
                // We don't use ConsolidatedPresence.Busy but Offline to do an unsubscribe,
                // Flexisip will handle the Busy status depending on other devices
                core.consolidatedPresence = ConsolidatedPresence.Offline
            }
        }
    }

    @WorkerThread
    fun updateAuthInfo(password: String) {
        val authInfo = digestAuthInfoPendingPasswordUpdate
        if (authInfo != null) {
            Log.i(
                "$TAG Updating password for username [${authInfo.username}] using auth info [$authInfo]"
            )
            authInfo.password = password
            core.addAuthInfo(authInfo)
            digestAuthInfoPendingPasswordUpdate = null
            core.refreshRegisters()
        } else {
            Log.e("$TAG No pending auth info for digest authentication!")
        }
    }

    @WorkerThread
    fun isAddressMyself(address: Address): Boolean {
        val found = core.accountList.find {
            it.params.identityAddress?.weakEqual(address) == true
        }
        return found != null
    }

    @WorkerThread
    fun clearFilesToExportToNativeGallery() {
        filesToExportToNativeMediaGallery.clear()
    }

    @WorkerThread
    fun startAudioCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        val params = core.createCallParams(null)
        params?.isVideoEnabled = false
        startCall(address, params, forceZRTP, localAddress)
    }

    @WorkerThread
    fun startVideoCall(
        address: Address,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        val params = core.createCallParams(null)
        params?.isVideoEnabled = true
        params?.videoDirection = MediaDirection.SendRecv
        startCall(address, params, forceZRTP, localAddress)
    }

    @WorkerThread
    fun startCall(
        address: Address,
        callParams: CallParams? = null,
        forceZRTP: Boolean = false,
        localAddress: Address? = null
    ) {
        if (!core.isNetworkReachable) {
            Log.e("$TAG Network unreachable, abort outgoing call")
            return
        }

        val currentCall = core.currentCall
        if (currentCall != null) {
            Log.w(
                "$TAG Found current call [${currentCall.remoteAddress.asStringUriOnly()}], pausing it first"
            )
            currentCall.pause()
        }

        val params = callParams ?: core.createCallParams(null)
        if (params == null) {
            val call = core.inviteAddress(address)
            Log.w("$TAG Starting call $call without params")
            return
        }

        if (forceZRTP) {
            params.mediaEncryption = MediaEncryption.ZRTP
        }

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(address)

        if (localAddress != null) {
            val account = core.accountList.find { account ->
                account.params.identityAddress?.weakEqual(localAddress) == true
            }
            if (account != null) {
                params.account = account
                Log.i(
                    "$TAG Using account matching address ${localAddress.asStringUriOnly()} as From"
                )
            } else {
                val defaultAccount = core.defaultAccount
                params.account = defaultAccount
                Log.e(
                    "$TAG Failed to find account matching address ${localAddress.asStringUriOnly()}, using default one [${defaultAccount?.params?.identityAddress?.asStringUriOnly()}]"
                )
            }
        } else {
            val defaultAccount = core.defaultAccount
            params.account = defaultAccount
            Log.i("$TAG No local address given, using default account [${defaultAccount?.params?.identityAddress?.asStringUriOnly()}]")
        }

        val username = address.username.orEmpty()
        val domain = address.domain.orEmpty()
        val account = params.account ?: core.defaultAccount
        if (account != null && Compatibility.isIpAddress(domain)) {
            Log.i("$TAG SIP URI [${address.asStringUriOnly()}] seems to have an IP address as domain")
            if (username.isNotEmpty() && (username.startsWith("+") || username.isDigitsOnly())) {
                val identityDomain = account.params.identityAddress?.domain
                Log.w("$TAG Username [$username] looks like a phone number, replacing domain [$domain] by the local account one [$identityDomain]")
                if (identityDomain != null) {
                    val newAddress = address.clone()
                    newAddress.domain = identityDomain

                    core.inviteAddressWithParams(newAddress, params)
                    Log.i("$TAG Starting call to [${newAddress.asStringUriOnly()}]")
                    return
                }
            }
        }

        core.inviteAddressWithParams(address, params)
        Log.i("$TAG Starting call to [${address.asStringUriOnly()}]")
    }

    @WorkerThread
    fun switchCamera() {
        val currentDevice = core.videoDevice
        Log.i("$TAG Current camera device is $currentDevice")

        for (camera in core.videoDevicesList) {
            if (camera != currentDevice && camera != "StaticImage: Static picture") {
                Log.i("$TAG New camera device will be $camera")
                core.videoDevice = camera
                break
            }
        }

        val call = core.currentCall
        if (call == null) {
            Log.w("$TAG Switching camera while not in call")
            return
        }
        call.update(null)
    }

    @WorkerThread
    fun showSwitchCameraButton(): Boolean {
        return core.isVideoCaptureEnabled && core.videoDevicesList.size > 2 // Count StaticImage camera
    }

    @WorkerThread
    fun answerCall(call: Call, autoAnswer: Boolean = false) {
        Log.i(
            "$TAG Answering call with remote address [${call.remoteAddress.asStringUriOnly()}] and to address [${call.toAddress.asStringUriOnly()}]"
        )
        val params = core.createCallParams(call)
        if (params == null) {
            Log.w("$TAG Answering call without params!")
            call.accept()
            return
        }

        params.recordFile = LinphoneUtils.getRecordingFilePathForAddress(call.remoteAddress)

        /*if (LinphoneUtils.checkIfNetworkHasLowBandwidth(context)) {
            Log.w("$TAG Enabling low bandwidth mode!")
            params.isLowBandwidthEnabled = true
        }*/

        if (call.callLog.wasConference()) {
            // Prevent incoming group call to start in audio only layout
            // Do the same as the conference waiting room
            params.isVideoEnabled = true
            params.videoDirection = if (core.videoActivationPolicy.automaticallyInitiate) MediaDirection.SendRecv else MediaDirection.RecvOnly
            Log.i(
                "$TAG Enabling video on call params to prevent audio-only layout when answering"
            )
        } else if (autoAnswer) {
            val videoBothWays = corePreferences.autoAnswerVideoCallsWithVideoDirectionSendReceive
            if (videoBothWays) {
                Log.i("$TAG Call is being auto-answered, requesting video in both ways according to user setting")
                params.videoDirection = MediaDirection.SendRecv
            }
        }

        call.acceptWithParams(params)
    }

    @WorkerThread
    fun terminateCall(call: Call) {
        if (call.dir == Call.Dir.Incoming && LinphoneUtils.isCallIncoming(call.state)) {
            val reason = if (call.core.callsNb > 1) Reason.Busy else Reason.Declined
            Log.i(
                "$TAG Declining call [${call.remoteAddress.asStringUriOnly()}] with reason [$reason]"
            )
            call.decline(reason)
        } else {
            Log.i("$TAG Terminating call [${call.remoteAddress.asStringUriOnly()}]")
            call.terminate()
        }
    }

    @UiThread
    fun showCallActivity() {
        Log.i("$TAG Starting Call activity")
        val intent = Intent(context, CallActivity::class.java)
        // This flag is required to start an Activity from a Service context
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        val options = Compatibility.getPendingIntentActivityOptions(true)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            options.toBundle()
        )

        val senderOptions = Compatibility.getPendingIntentActivityOptions(false)
        Compatibility.sendPendingIntent(pendingIntent, senderOptions.toBundle())
    }

    @WorkerThread
    fun startKeepAliveService() {
        if (keepAliveServiceStarted) {
            Log.w("$TAG Keep alive service already started, skipping")
        }

        val serviceIntent = Intent(Intent.ACTION_MAIN).setClass(
            context,
            CoreKeepAliveThirdPartyAccountsService::class.java
        )
        Log.i("$TAG Starting Keep alive for third party accounts Service")
        try {
            context.startService(serviceIntent)
            keepAliveServiceStarted = true
        } catch (e: Exception) {
            Log.e("$TAG Failed to start keep alive service: $e")
        }
    }

    @WorkerThread
    fun stopKeepAliveService() {
        val serviceIntent = Intent(Intent.ACTION_MAIN).setClass(
            context,
            CoreKeepAliveThirdPartyAccountsService::class.java
        )
        Log.i(
            "$TAG Stopping Keep alive for third party accounts Service"
        )
        context.stopService(serviceIntent)
        keepAliveServiceStarted = false
    }

    private var toneGenerator: ToneGenerator? = null
    private var toneGeneratorStream: Int = -1

    @WorkerThread
    fun playDtmf(character: Char, duration: Int = 200, ignoreSystemPolicy: Boolean = false) {
        if (!ignoreSystemPolicy) {
            try {
                if (Settings.System.getInt(
                        context.contentResolver,
                        Settings.System.DTMF_TONE_WHEN_DIALING
                    ) == 0
                ) {
                    Log.w("$TAG Numpad DTMF tones are disabled in system settings, not playing them")
                    return
                }
            } catch (snfe: SettingNotFoundException) {
                Log.w("$TAG DTMF_TONE_WHEN_DIALING setting not found, playing tone anyway: $snfe")
            }
        }

        val tone = dtmfToneFor(character)
        if (tone < 0) return

        // Play with Android's ToneGenerator so the tone is ALWAYS audible. liblinphone's playDtmf is
        // silent when idle (the app doesn't grab audio focus, android_disable_audio_focus_requests=1).
        // Route through the in-call audio when a call is active, otherwise the dial-tone stream.
        val stream = if (core.callsNb > 0) {
            AudioManager.STREAM_VOICE_CALL
        } else {
            AudioManager.STREAM_DTMF
        }
        try {
            if (toneGenerator == null || toneGeneratorStream != stream) {
                toneGenerator?.release()
                toneGenerator = ToneGenerator(stream, 90)
                toneGeneratorStream = stream
            }
            toneGenerator?.startTone(tone, if (duration in 1..1000) duration else 160)
        } catch (e: Exception) {
            Log.e("$TAG Failed to play DTMF tone for [$character]: $e")
        }
    }

    private fun dtmfToneFor(character: Char): Int = when (character) {
        '0' -> ToneGenerator.TONE_DTMF_0
        '1' -> ToneGenerator.TONE_DTMF_1
        '2' -> ToneGenerator.TONE_DTMF_2
        '3' -> ToneGenerator.TONE_DTMF_3
        '4' -> ToneGenerator.TONE_DTMF_4
        '5' -> ToneGenerator.TONE_DTMF_5
        '6' -> ToneGenerator.TONE_DTMF_6
        '7' -> ToneGenerator.TONE_DTMF_7
        '8' -> ToneGenerator.TONE_DTMF_8
        '9' -> ToneGenerator.TONE_DTMF_9
        '*' -> ToneGenerator.TONE_DTMF_S
        '#' -> ToneGenerator.TONE_DTMF_P
        else -> -1
    }

    @WorkerThread
    fun computeUserAgent() {
        val savedDeviceName = corePreferences.deviceName
        val deviceName = if (savedDeviceName.isEmpty()) {
            Log.i("$TAG Device name not fetched yet, doing it now")
            AppUtils.getDeviceName(context)
        } else if (savedDeviceName.contains("'")) {
            // Some VoIP providers such as voip.ms seem to not like apostrophe in user-agent
            // https://github.com/BelledonneCommunications/linphone-android/issues/2287
            Log.i("$TAG Found an apostrophe in device name, removing it")
            savedDeviceName.replace("'", "")
        } else {
            savedDeviceName
        }
        if (savedDeviceName != deviceName) {
            corePreferences.deviceName = deviceName
        }
        Log.i("$TAG Device name for user-agent is [$deviceName]")

        val appName = context.getString(org.linphone.R.string.app_name)
        val androidVersion = BuildConfig.VERSION_NAME
        val userAgent = "${appName}Android/$androidVersion ($deviceName) SIP-UA"
        val sdkUserAgent = "6.1.0"
        core.setUserAgent(userAgent, sdkUserAgent)
    }

    // Migration between versions related

    @WorkerThread
    private fun removePortFromSipIdentity() {
        for (account in core.accountList) {
            val params = account.params
            val identity = params.identityAddress
            if (identity != null && identity.port != 0) {
                val clone = params.clone()
                val newIdentity = identity.clone()
                newIdentity.port = 0
                clone.identityAddress = newIdentity
                Log.w("$TAG Found account with identity address [${identity.asStringUriOnly()}] that contains port information in domain, removing port information in new identity [${newIdentity.asStringUriOnly()}]")
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun disablePushNotificationsFromThirdPartySipAccounts() {
        for (account in core.accountList) {
            val params = account.params
            val pushAvailableForDomain = params.identityAddress?.domain in corePreferences.pushNotificationCompatibleDomains
            if (!pushAvailableForDomain && params.pushNotificationAllowed) {
                val clone = params.clone()
                clone.pushNotificationAllowed = false
                Log.w("$TAG Updating account [${params.identityAddress?.asStringUriOnly()}] params to disable push notifications, they won't work and may cause issues when used with UDP transport protocol")
                account.params = clone
            }
        }
    }

    @WorkerThread
    private fun configurationMigration5To6() {
        val policy = core.videoActivationPolicy.clone()
        policy.automaticallyInitiate = false
        policy.automaticallyAccept = true
        policy.automaticallyAcceptDirection = MediaDirection.RecvOnly
        core.videoActivationPolicy = policy
        Log.i(
            "$TAG Updated video activation policy to disable auto initiate, enable auto accept with media direction RecvOnly"
        )

        core.isFecEnabled = true
        Log.i("$TAG Video FEC has been enabled")

        core.config.setBool("magic_search", "return_empty_friends", true)
        Log.i("$TAG Showing 'empty' friends enabled")

        if (LinphoneUtils.getDefaultAccount()?.params?.domain == corePreferences.defaultDomain) {
            corePreferences.contactsFilter = corePreferences.defaultDomain
            Log.i(
                "$TAG Setting default contacts list filter to [${corePreferences.contactsFilter}]"
            )
        }

        for (account in core.accountList) {
            val params = account.params
            if (params.identityAddress?.domain == corePreferences.defaultDomain && params.limeAlgo.isNullOrEmpty()) {
                val clone = params.clone()
                clone.limeAlgo = "c25519"
                Log.i("$TAG Updating account [${params.identityAddress?.asStringUriOnly()}] params to use LIME algo c25519")
                account.params = clone
            }
        }

        Log.i("$TAG Making sure both RFC2833 & SIP INFO are enabled for DTMFs")
        core.useRfc2833ForDtmf = true
        core.useInfoForDtmf = true

        // Add that flag back, was disabled for a time during dev process
        Log.i("$TAG Enabling hiding empty chat rooms")
        core.config.setBool("misc", "hide_empty_chat_rooms", true)

        // The upstream "replace old linphone.org URLs by new ones" block lived here. All three
        // endpoints are cleared on every launch instead, in clearLinphoneEndpoints() - doing it
        // here would only ever run once, and only for devices coming from before 6.0.0.

        Log.i("$TAG IMDN threshold set to 1 (meaning only sender will receive delivery & read notifications)")
        core.imdnToEverybodyThreshold = 1

        Log.i("$TAG Removing previous grammar files (without .belr extension)")
        corePreferences.clearPreviousGrammars()
    }

    @WorkerThread
    fun isCrashlyticsAvailable(): Boolean {
        return crashlyticsAvailable
    }

    @WorkerThread
    fun updateLogcatEnabledSetting(enabled: Boolean) {
        logcatEnabled = enabled
    }

    @WorkerThread
    fun updateCrashlyticsEnabledSetting(enabled: Boolean) {
        crashlyticsEnabled = enabled
    }

    @UiThread
    fun enableProximitySensor(enable: Boolean) {
        if (::proximityWakeLock.isInitialized) {
            if (enable && !proximityWakeLock.isHeld) {
                Log.i("$TAG Acquiring proximity sensor wake lock for 2 hours")
                proximityWakeLock.acquire(7200 * 1000L) // 2 hours
            } else if (!enable && proximityWakeLock.isHeld) {
                Log.i("$TAG Releasing proximity sensor wake lock")
                proximityWakeLock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
            }
        }
    }

    @WorkerThread
    private fun holdWifiLockForCalls(hold: Boolean) {
        // A Wi-Fi high-performance lock keeps the radio out of power-save during calls. Without it the
        // AP buffers downlink packets and releases them in bursts, so received RTP arrives jittery and
        // the INCOMING audio sounds choppy (sent audio is unaffected). Only held while a call is up.
        try {
            if (callWifiLock == null) {
                val wifiManager = context.applicationContext
                    .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                callWifiLock = wifiManager.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                    "ProAgent:Call"
                ).apply { setReferenceCounted(false) }
            }
            val lock = callWifiLock ?: return
            if (hold && !lock.isHeld) {
                lock.acquire()
                Log.i("$TAG Acquired Wi-Fi high-performance lock for the duration of the call")
            } else if (!hold && lock.isHeld) {
                lock.release()
                Log.i("$TAG Released Wi-Fi high-performance lock, no more calls")
            }
        } catch (e: Exception) {
            Log.e("$TAG Failed to update call Wi-Fi lock: $e")
        }
    }

    fun setBackCamera(): Boolean {
        for (camera in core.videoDevicesList) {
            if (camera.contains("Back")) {
                Log.i("TAG Found back facing camera [$camera], using it")
                coreContext.core.videoDevice = camera
                return true
            }
        }
        return false
    }

    fun setFrontCamera(): Boolean {
        for (camera in core.videoDevicesList) {
            if (camera.contains("Front")) {
                Log.i("$TAG Found front facing camera [$camera], using it")
                coreContext.core.videoDevice = camera
                return true
            }
        }
        return false
    }

    private fun isLocalDomain(domain: String): Boolean {
        if (domain.isEmpty()) return true
        
        // Check if it's an IP address
        val parts = domain.split(".")
        if (parts.size == 4) {
            try {
                val p0 = parts[0].toInt()
                val p1 = parts[1].toInt()
                if (p0 == 192 && p1 == 168) return true
                if (p0 == 10) return true
                if (p0 == 172 && p1 in 16..31) return true
                if (p0 == 127) return true
            } catch (e: NumberFormatException) {
                // Not an integer IP
            }
        }
        
        if (domain.equals("localhost", ignoreCase = true)) return true
        if (domain.endsWith(".local", ignoreCase = true)) return true
        if (domain.endsWith(".lan", ignoreCase = true)) return true
        
        return false
    }
}
