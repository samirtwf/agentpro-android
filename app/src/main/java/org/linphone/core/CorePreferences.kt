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

import android.content.Context
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import org.linphone.BuildConfig
import java.io.File
import java.io.FileOutputStream
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.contacts.ContactLoader.Companion.LINPHONE_ADDRESS_BOOK_FRIEND_LIST
import org.linphone.utils.SecretCipher

class CorePreferences
    @UiThread
    constructor(private val context: Context) {
    companion object {
        private const val TAG = "[Preferences]"

        const val CONFIG_FILE_NAME = ".linphonerc"
    }

    private var _config: Config? = null

    @get:AnyThread @set:WorkerThread
    var config: Config
        get() = _config ?: coreContext.core.config
        set(value) {
            _config = value
        }

    @get:AnyThread @set:WorkerThread
    var printLogsInLogcat: Boolean
        get() = config.getBool("app", "debug", BuildConfig.DEBUG)
        set(value) {
            config.setBool("app", "debug", value)
        }

    @get:AnyThread @set:WorkerThread
    var sendLogsToCrashlytics: Boolean
        get() = config.getBool("app", "send_logs_to_crashlytics", BuildConfig.CRASHLYTICS_ENABLED)
        set(value) {
            config.setBool("app", "send_logs_to_crashlytics", value)
        }

    @get:AnyThread @set:WorkerThread
    var firstLaunch: Boolean
        get() = config.getBool("app", "first_6.0_launch", true)
        set(value) {
            config.setBool("app", "first_6.0_launch", value)
        }

    @get:AnyThread @set:WorkerThread
    var linphoneConfigurationVersion: Int
        get() = config.getInt("app", "config_version", 52005)
        set(value) {
            config.setInt("app", "config_version", value)
        }

    @get:AnyThread @set:WorkerThread
    var autoStart: Boolean
        get() = config.getBool("app", "auto_start", true)
        set(value) {
            config.setBool("app", "auto_start", value)
        }

    @get:AnyThread @set:WorkerThread
    var checkForUpdateServerUrl: String
        get() = config.getString("misc", "version_check_url_root", "").orEmpty()
        set(value) {
            config.setString("misc", "version_check_url_root", value)
        }

    @get:AnyThread @set:WorkerThread
    var conditionsAndPrivacyPolicyAccepted: Boolean
        get() = config.getBool("app", "read_and_agree_terms_and_privacy", false)
        set(value) {
            config.setBool("app", "read_and_agree_terms_and_privacy", value)
        }

    @get:AnyThread @set:WorkerThread
    var publishPresence: Boolean
        get() = config.getBool("app", "publish_presence", true)
        set(value) {
            config.setBool("app", "publish_presence", value)
        }

    @get:AnyThread @set:WorkerThread
    // AgentPro: on by default. This is what keeps the foreground service - and with it the SIP
    // registration - alive once the agent leaves the app, and it is what makes the app come
    // back after a reboot: BOOT_COMPLETED starts the process, LinphoneApplication starts the
    // Core, and onCoreStarted() only starts the service if this is set.
    //
    // Upstream defaults it off and turns it on opportunistically, when an account is added that
    // has no push support. That is the wrong shape for this product: it depends on the moment
    // an account happens to be created, leaves an already-provisioned phone unprotected, and a
    // call centre agent whose phone dropped its registration in the background simply stops
    // receiving calls with nothing on screen to say so. Settings still has the toggle, so it
    // can be turned off deliberately - the point is that it is not off by accident.
    var keepServiceAlive: Boolean
        get() = config.getBool("app", "keep_service_alive", true)
        set(value) {
            config.setBool("app", "keep_service_alive", value)
        }

    @get:AnyThread @set:WorkerThread
    var deviceName: String
        get() = config.getString("app", "device", "").orEmpty().trim()
        set(value) {
            config.setString("app", "device", value.trim())
        }

    @get:AnyThread @set:WorkerThread
    var showDeveloperSettings: Boolean
        get() = config.getBool("ui", "show_developer_settings", false)
        set(value) {
            config.setBool("ui", "show_developer_settings", value)
        }

    // Call settings

    // This won't be done if bluetooth or wired headset is used
    @get:AnyThread @set:WorkerThread
    var routeAudioToBluetoothWhenPossible: Boolean
        get() = config.getBool("app", "route_audio_to_bluetooth_when_possible", true)
        set(value) {
            config.setBool("app", "route_audio_to_bluetooth_when_possible", value)
        }

    @get:AnyThread @set:WorkerThread
    var routeAudioToSpeakerWhenVideoIsEnabled: Boolean
        get() = config.getBool("app", "route_audio_to_speaker_when_video_enabled", true)
        set(value) {
            config.setBool("app", "route_audio_to_speaker_when_video_enabled", value)
        }

    @get:AnyThread @set:WorkerThread
    var callRecordingUseSmffFormat: Boolean
        get() = config.getBool("app", "use_smff_for_call_recording", false)
        set(value) {
            config.setBool("app", "use_smff_for_call_recording", value)
        }

    @get:AnyThread @set:WorkerThread
    var automaticallyStartCallRecording: Boolean
        get() = config.getBool("app", "auto_start_call_record", false)
        set(value) {
            config.setBool("app", "auto_start_call_record", value)
        }

    @get:AnyThread @set:WorkerThread
    var showDialogWhenCallingDeviceUuidDirectly: Boolean
        get() = config.getBool("app", "show_confirmation_dialog_zrtp_trust_call", true)
        set(value) {
            config.setBool("app", "show_confirmation_dialog_zrtp_trust_call", value)
        }

    @get:AnyThread @set:WorkerThread
    var acceptEarlyMedia: Boolean
        get() = config.getBool("sip", "incoming_calls_early_media", false)
        set(value) {
            config.setBool("sip", "incoming_calls_early_media", value)
        }

    @get:AnyThread @set:WorkerThread
    var allowOutgoingEarlyMedia: Boolean
        get() = config.getBool("misc", "real_early_media", false)
        set(value) {
            config.setBool("misc", "real_early_media", value)
        }

    @get:AnyThread @set:WorkerThread
    var autoAnswerEnabled: Boolean
        get() = config.getBool("app", "auto_answer", false)
        set(value) {
            config.setBool("app", "auto_answer", value)
        }

    // AgentPro: Do Not Disturb — when enabled, incoming calls are declined automatically
    @get:AnyThread @set:WorkerThread
    var dndEnabled: Boolean
        get() = config.getBool("app", "dnd", false)
        set(value) {
            config.setBool("app", "dnd", value)
        }

    // AgentPro: epoch SECONDS at which a timed DND should auto-expire; 0 = no timer (indefinite)
    @get:AnyThread @set:WorkerThread
    var dndUntilEpochSeconds: Int
        get() = config.getInt("app", "dnd_until", 0)
        set(value) {
            config.setInt("app", "dnd_until", value)
        }

    // AgentPro: Auto Disposition — when enabled, the call disposition dialog is shown after each call
    @get:AnyThread @set:WorkerThread
    var autoDispEnabled: Boolean
        get() = config.getBool("app", "auto_disp", false)
        set(value) {
            config.setBool("app", "auto_disp", value)
        }

    // Returns true if DND should currently block calls. If a timer was set and has elapsed,
    // it disables DND as a side effect so calls are received again.
    @WorkerThread
    fun isDoNotDisturbActive(): Boolean {
        if (!dndEnabled) return false
        val until = dndUntilEpochSeconds
        if (until > 0 && (System.currentTimeMillis() / 1000L) >= until) {
            dndEnabled = false
            dndUntilEpochSeconds = 0
            return false
        }
        return true
    }

    @get:AnyThread @set:WorkerThread
    var autoAnswerDelay: Int
        get() = config.getInt("app", "auto_answer_delay", 0)
        set(value) {
            config.setInt("app", "auto_answer_delay", value)
        }

    @get:AnyThread @set:WorkerThread
    var autoAnswerVideoCallsWithVideoDirectionSendReceive: Boolean
        get() = config.getBool("app", "auto_answer_video_send_receive", false)
        set(value) {
            config.setBool("app", "auto_answer_video_send_receive", value)
        }

    @get:AnyThread @set:WorkerThread
    var showAdvancedCallStats: Boolean
        get() = config.getBool("ui", "show_advanced_call_stats", false)
        set(value) {
            config.setBool("ui", "show_advanced_call_stats", value)
        }

    // Conversation related

    @get:AnyThread @set:WorkerThread
    var markConversationAsReadWhenDismissingMessageNotification: Boolean
        get() = config.getBool("app", "mark_as_read_notif_dismissal", false)
        set(value) {
            config.setBool("app", "mark_as_read_notif_dismissal", value)
        }

    @get:AnyThread @set:WorkerThread
    var makePublicMediaFilesDownloaded: Boolean
        // Keep old name for backward compatibility
        get() = config.getBool("app", "make_downloaded_images_public_in_gallery", false)
        set(value) {
            config.setBool("app", "make_downloaded_images_public_in_gallery", value)
        }

    // Conference related

    @get:AnyThread @set:WorkerThread
    var createEndToEndEncryptedMeetingsAndGroupCalls: Boolean
        get() = config.getBool("app", "create_e2e_encrypted_conferences", false)
        set(value) {
            config.setBool("app", "create_e2e_encrypted_conferences", value)
        }

    // Contacts related

    @get:AnyThread @set:WorkerThread
    var sortContactsByFirstName: Boolean
        get() = config.getBool("ui", "sort_contacts_by_first_name", true) // If disabled, last name will be used
        set(value) {
            config.setBool("ui", "sort_contacts_by_first_name", value)
        }

    @get:AnyThread
    var hideContactsWithoutPhoneNumberOrSipAddress: Boolean
        get() = config.getBool("ui", "hide_contacts_without_phone_number_or_sip_address", false)
        set(value) {
            config.setBool("ui", "hide_contacts_without_phone_number_or_sip_address", value)
        }

    @get:AnyThread @set:WorkerThread
    var contactsFilter: String
        get() = config.getString("ui", "contacts_filter", "")!! // Default value must be empty!
        set(value) {
            config.setString("ui", "contacts_filter", value)
        }

    @get:AnyThread @set:WorkerThread
    var showFavoriteContacts: Boolean
        get() = config.getBool("ui", "show_favorites_contacts", true)
        set(value) {
            config.setBool("ui", "show_favorites_contacts", value)
        }

    @get:AnyThread @set:WorkerThread
    var friendListInWhichStoreNewlyCreatedFriends: String
        get() = config.getString(
            "app",
            "friend_list_to_store_newly_created_contacts",
            LINPHONE_ADDRESS_BOOK_FRIEND_LIST
        )!!
        set(value) {
            config.setString("app", "friend_list_to_store_newly_created_contacts", value)
        }

    @get:AnyThread @set:WorkerThread
    var editNativeContactsInLinphone: Boolean
        get() = config.getBool("ui", "edit_native_contact_in_linphone", false)
        set(value) {
            config.setBool("ui", "edit_native_contact_in_linphone", value)
        }

    @get:AnyThread @set:WorkerThread
    var disableAddContact: Boolean
        get() = config.getBool("ui", "disable_add_contact", false)
        set(value) {
            config.setBool("ui", "disable_add_contact", value)
        }

    // Voice recordings related

    @get:AnyThread @set:WorkerThread
    var voiceRecordingMaxDuration: Int
        get() = config.getInt("app", "voice_recording_max_duration", 600000) // in ms
        set(value) = config.setInt("app", "voice_recording_max_duration", value)

    // User interface related

    // -1 means auto, 0 no, 1 yes
    @get:AnyThread @set:WorkerThread
    var darkMode: Int
        get() {
            if (!darkModeAllowed) return 0
            return config.getInt("app", "dark_mode", -1)
        }
        set(value) {
            config.setInt("app", "dark_mode", value)
        }

    // Allows to make screenshots
    @get:AnyThread @set:WorkerThread
    var enableSecureMode: Boolean
        get() = config.getBool("ui", "enable_secure_mode", true)
        set(value) {
            config.setBool("ui", "enable_secure_mode", value)
        }

    @get:AnyThread @set:WorkerThread
    var automaticallyShowDialpad: Boolean
        get() = config.getBool("ui", "automatically_show_dialpad", false)
        set(value) {
            config.setBool("ui", "automatically_show_dialpad", value)
        }

    @get:AnyThread @set:WorkerThread
    var themeMainColor: String
        get() = config.getString("ui", "theme_main_color", "blue")!!
        set(value) {
            config.setString("ui", "theme_main_color", value)
        }

    // Customization options

    @get:AnyThread @set:WorkerThread
    var showMicrophoneAndSpeakerVuMeters: Boolean
        get() = config.getBool("ui", "show_mic_speaker_vu_meter", false)
        set(value) {
            config.setBool("ui", "show_mic_speaker_vu_meter", value)
        }

    @get:AnyThread @set:WorkerThread
    var pushNotificationCompatibleDomains: Array<String>
        get() = config.getStringList("app", "push_notification_domains", arrayOf(""))
        set(value) {
            config.setStringList("app", "push_notification_domains", value)
        }

    @get:AnyThread
    val defaultDomain: String
        get() = config.getString("app", "default_domain", "")!!

    @get:AnyThread
    val darkModeAllowed: Boolean
        get() = config.getBool("ui", "dark_mode_allowed", true)

    @get:AnyThread
    val changeMainColorAllowed: Boolean
        get() = config.getBool("ui", "change_main_color_allowed", false)

    @get:AnyThread
    val onlyDisplaySipUriUsername: Boolean
        get() = config.getBool("ui", "only_display_sip_uri_username", false)

    @get:AnyThread
    val hideSipAddresses: Boolean
        get() = config.getBool("ui", "hide_sip_addresses", false)

    @get:AnyThread
    val disableChat: Boolean
        get() = config.getBool("ui", "disable_chat_feature", false)

    @get:AnyThread
    val disableMeetings: Boolean
        get() = config.getBool("ui", "disable_meetings_feature", false)

    @get:AnyThread
    val disableBroadcasts: Boolean
        get() = config.getBool("ui", "disable_broadcast_feature", true) // TODO FIXME: not implemented yet

    @get:AnyThread
    val disableCallRecordings: Boolean
        get() = config.getBool("ui", "disable_call_recordings_feature", false)

    @get:AnyThread
    val maxAccountsCount: Int
        get() = config.getInt("ui", "max_account", 0) // 0 means no max

    @get:AnyThread
    val hidePhoneNumbers: Boolean
        get() = config.getBool("ui", "hide_phone_numbers", false)

    @get:AnyThread
    val hideSettings: Boolean
        get() = config.getBool("ui", "hide_settings", false)

    @get:AnyThread
    val hideAccountSettings: Boolean
        get() = config.getBool("ui", "hide_account_settings", false)

    @get:AnyThread
    val hideAdvancedSettings: Boolean
        get() = config.getBool("ui", "hide_advanced_settings", false)

    @get:AnyThread
    val hideAssistantCreateAccount: Boolean
        get() = config.getBool("ui", "assistant_hide_create_account", true)

    @get:AnyThread
    val hideAssistantScanQrCode: Boolean
        get() = config.getBool("ui", "assistant_disable_qr_code", false)

    @get:AnyThread
    val hideAssistantThirdPartySipAccount: Boolean
        get() = config.getBool("ui", "assistant_hide_third_party_account", false)

    @get:AnyThread
    val magicSearchResultsLimit: Int
        get() = config.getInt("ui", "max_number_of_magic_search_results", 300)

    @get:AnyThread
    val singleSignOnClientId: String
        get() = config.getString("app", "oidc_client_id", "linphone")!!

    @get:AnyThread
    val useUsernameAsSingleSignOnLoginHint: Boolean
        get() = config.getBool("ui", "use_username_as_sso_login_hint", false)

    @get:AnyThread
    // AgentPro: TLS by default, matching the desktop build. UDP sends SIP signalling in the
    // clear, so the account's username and password, the numbers dialled and who called whom
    // are all readable by anything on the path. Leaving that as the default means an agent who
    // never touches the dropdown is unencrypted without knowing it - and this product goes to
    // some length elsewhere to keep exactly that data off the wire and off the device.
    // Still a dropdown: a PBX that only speaks UDP or TCP can be selected as before.
    val thirdPartySipAccountDefaultTransport: String
        get() = config.getString("ui", "assistant_third_party_sip_account_transport", "tls")!!

    @get:AnyThread
    val thirdPartySipAccountDefaultDomain: String
        get() = config.getString("ui", "assistant_third_party_sip_account_domain", "")!!

    @get:AnyThread
    val assistantDirectlyGoToThirdPartySipAccountLogin: Boolean
        get() = config.getBool(
            "ui",
            "assistant_go_directly_to_third_party_sip_account_login",
            true
        )

    @get:AnyThread
    val fetchContactsFromDefaultDirectory: Boolean
        get() = config.getBool("app", "fetch_contacts_from_default_directory", true)

    @get:AnyThread
    val showLettersOnDialpad: Boolean
        get() = config.getBool("ui", "show_letters_on_dialpad", true)

    // ============================================================================================
    // AgentPro "locked build" provisioning — these keys are written into linphonerc_factory by the
    // desktop "AgentPro APK Builder" tool. When [locked_account] enabled=1 the app auto-creates the
    // embedded SIP account on first launch and hides every account-management entry point (assistant,
    // edit, delete, PBX settings), permanently binding the APK to a single customer account.
    // When the section is absent (normal "full" build) every getter returns its harmless default and
    // accountLocked == false, so the app behaves exactly like before.
    // ============================================================================================

    // "enabled" = auto-provision the embedded account (+ PBX) on first launch. This happens in BOTH
    // the "Full (pre-filled, editable)" and "Locked" builds.
    @get:AnyThread
    val provisionAccount: Boolean
        get() = config.getBool("locked_account", "enabled", false)

    // "locked" = also hide every account-management entry point so the APK is bound to one customer.
    // Only the "Locked" build sets this; a pre-filled "Full" build provisions but stays editable.
    @get:AnyThread
    val accountLocked: Boolean
        get() = config.getBool("locked_account", "locked", false)

    // "ask_password" = the "Ext-locked / per-device" build: PBX + extension are embedded AND locked
    // but the password is NOT — the agent types it once on first launch (see AgentProDialpadFragment).
    @get:AnyThread
    val lockedAskPassword: Boolean
        get() = config.getBool("locked_account", "ask_password", false)

    // "allow_linphone" = even when locked, the user may still add a Linphone account (but no SIP one).
    @get:AnyThread
    val lockedAllowLinphone: Boolean
        get() = config.getBool("locked_account", "allow_linphone", false)

    // Set true once the agent has successfully connected with the password they typed, so the
    // first-launch prompt is never shown again — liblinphone replaces the plaintext password with an
    // ha1 hash after a successful REGISTER, which would otherwise look like "no password set".
    @get:AnyThread @set:WorkerThread
    var lockedPasswordEntered: Boolean
        get() = config.getBool("locked_account", "password_set", false)
        set(value) {
            config.setBool("locked_account", "password_set", value)
            // Flush to disk NOW: a swipe-away kill won't run a clean core stop, and without this the
            // flag (and the freshly-stored account ha1, same config) would be lost → re-prompt every
            // launch. sync() writes the whole linphonerc, persisting both.
            config.sync()
        }

    // -------- Clean-install guard --------
    // Every APK the builder produces stamps a unique [build] token into linphonerc_factory. We record
    // the token of the build we're running; if the APK on disk later carries a DIFFERENT token, a new
    // build was installed over us → wipe our own data so it starts fresh (fresh provisioning + a new
    // password prompt). This is what makes a manual .apk update start clean — Android otherwise keeps
    // the old data on update (adb/builder installs are already made clean by uninstall-first).
    @get:AnyThread
    val bakedBuildToken: String
        // From linphonerc_factory (read-only, re-applied from the APK on every launch).
        get() = config.getString("build", "token", "").orEmpty()

    /**
     * If a different-token build is running on top of an old install's data, erase this app's data
     * (the OS then force-stops us; the user re-opens once → clean state) and return true so the caller
     * aborts the rest of startup. NEVER loops: a wipe happens only when a NON-EMPTY recorded token
     * differs from the baked one, and right after a wipe the recorded token reads back empty (→ we
     * only record, never wipe). Safe failure mode: on any error we just record and never wipe.
     */
    @UiThread
    fun wipeDataIfNewBuildInstalled(): Boolean {
        val baked = bakedBuildToken
        if (baked.isEmpty()) return false // build with no token → nothing to guard
        val applied = config.getString("build", "applied_token", "").orEmpty()
        if (applied == baked) return false // same build → normal launch

        fun record() {
            config.setString("build", "applied_token", baked)
            config.sync()
        }
        if (applied.isNotEmpty()) {
            return try {
                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                if (am.clearApplicationUserData()) {
                    true // data wiped — the OS will force-stop us
                } else {
                    record(); false // couldn't wipe → record so we don't keep retrying
                }
            } catch (e: Exception) {
                record(); false
            }
        }
        record() // fresh / post-wipe data → just remember which build we are
        return false
    }

    // Reads an embedded credential the APK Builder wrote (ENC:... AES-256-GCM via SecretCipher);
    // plaintext passes through unchanged, so vanilla / older builds keep working.
    @AnyThread
    private fun lockedString(section: String, key: String, default: String = ""): String =
        SecretCipher.decrypt(config.getString(section, key, default)!!)

    @get:AnyThread
    val lockedAccountUsername: String
        get() = lockedString("locked_account", "username")

    @get:AnyThread
    val lockedAccountAuthId: String
        get() = lockedString("locked_account", "auth_id")

    @get:AnyThread
    val lockedAccountPassword: String
        get() = lockedString("locked_account", "password")

    @get:AnyThread
    val lockedAccountDomain: String
        get() = lockedString("locked_account", "domain")

    @get:AnyThread
    val lockedAccountTransport: String
        get() = lockedString("locked_account", "transport", "udp")

    @get:AnyThread
    val lockedAccountDisplayName: String
        get() = lockedString("locked_account", "display_name")

    @get:AnyThread
    val lockedAccountProxy: String
        get() = lockedString("locked_account", "proxy")

    @get:AnyThread
    val lockedAccountOutboundProxy: String
        get() = lockedString("locked_account", "outbound_proxy")

    @get:AnyThread
    val lockedAccountInternationalPrefix: String
        get() = lockedString("locked_account", "international_prefix")

    @get:AnyThread
    val lockedAccountInternationalPrefixIso: String
        get() = lockedString("locked_account", "international_prefix_iso")

    // PBX/AMI/UCM connection — seeded into PbxPrefs (SharedPreferences) on first launch when locked.
    @get:AnyThread
    val lockedPbxConfigured: Boolean
        get() = lockedPbxHost.isNotEmpty()

    @get:AnyThread
    val lockedPbxHost: String
        get() = lockedString("pbx", "host")

    @get:AnyThread
    val lockedPbxAmiPort: Int
        get() = config.getInt("pbx", "ami_port", 7777)

    @get:AnyThread
    val lockedPbxAmiUser: String
        get() = lockedString("pbx", "ami_user")

    @get:AnyThread
    val lockedPbxAmiPass: String
        get() = lockedString("pbx", "ami_pass")

    @get:AnyThread
    val lockedPbxApiPort: Int
        get() = config.getInt("pbx", "api_port", 8089)

    @get:AnyThread
    val lockedPbxApiUser: String
        get() = lockedString("pbx", "api_user")

    @get:AnyThread
    val lockedPbxApiPass: String
        get() = lockedString("pbx", "api_pass")

    @get:AnyThread
    val lockedPbxWebUser: String
        get() = lockedString("pbx", "web_user")

    @get:AnyThread
    val lockedPbxWebPass: String
        get() = lockedString("pbx", "web_pass")

    @get:AnyThread
    val lockedPbxAgentExt: String
        get() = lockedString("pbx", "agent_ext")

    @get:AnyThread
    val lockedPbxAgentProto: String
        get() = lockedString("pbx", "agent_proto", "PJSIP")

    @get:AnyThread
    val lockedPbxAgentQueues: String
        get() = lockedString("pbx", "agent_queues")

    @get:AnyThread
    val lockedPbxListenCode: String
        get() = lockedString("pbx", "listen_code", "*54")

    @get:AnyThread
    val lockedPbxWhisperCode: String
        get() = lockedString("pbx", "whisper_code", "*55")

    @get:AnyThread
    val lockedPbxBargeCode: String
        get() = lockedString("pbx", "barge_code", "*56")

    // Paths

    @get:AnyThread
    val configPath: String
        get() = context.filesDir.absolutePath + "/" + CONFIG_FILE_NAME

    @get:AnyThread
    val factoryConfigPath: String
        get() = context.filesDir.absolutePath + "/linphonerc"

    @get:AnyThread
    val linphoneDefaultValuesPath: String
        get() = context.filesDir.absolutePath + "/assistant_linphone_default_values"

    @get:AnyThread
    val thirdPartyDefaultValuesPath: String
        get() = context.filesDir.absolutePath + "/assistant_third_party_default_values"

    @get:AnyThread
    val vfsCachePath: String
        get() = context.cacheDir.absolutePath + "/evfs/"

    @get:AnyThread
    val ssoCacheFile: String
        get() = context.filesDir.absolutePath + "/auth_state.json"

    @get:AnyThread
    val messageReceivedInVisibleConversationNotificationSound: String
        get() = context.filesDir.absolutePath + "/share/sounds/linphone/incoming_chat.wav"

    @get:AnyThread @set:WorkerThread
    var isMdmConfigured: Boolean
        get() = config.getBool("app", "mdm_configured", false)
        set(value) {
            config.setBool("app", "mdm_configured", value)
        }

    @UiThread
    fun copyAssetsFromPackage() {
        copy("linphonerc_default", configPath)
        copy("linphonerc_factory", factoryConfigPath, true)
        copy("assistant_linphone_default_values", linphoneDefaultValuesPath, true)
        copy("assistant_third_party_default_values", thirdPartyDefaultValuesPath, true)
    }

    @AnyThread
    fun resetConfigToDefault() {
        copy("linphonerc_default", configPath, true)
    }

    @AnyThread
    fun clearPreviousGrammars() {
        val cpimGrammar = File("${context.filesDir.absolutePath}/share/belr/grammars/cpim_grammar")
        if (cpimGrammar.exists()) {
            cpimGrammar.delete()
        }
        val icsGrammar = File("${context.filesDir.absolutePath}/share/belr/grammars/ics_grammar")
        if (icsGrammar.exists()) {
            icsGrammar.delete()
        }
        val identityGrammar = File(
            "${context.filesDir.absolutePath}/share/belr/grammars/identity_grammar"
        )
        if (identityGrammar.exists()) {
            identityGrammar.delete()
        }
        val mwiGrammar = File("${context.filesDir.absolutePath}/share/belr/grammars/mwi_grammar")
        if (mwiGrammar.exists()) {
            mwiGrammar.delete()
        }
        val sdpGrammar = File("${context.filesDir.absolutePath}/share/belr/grammars/sdp_grammar")
        if (sdpGrammar.exists()) {
            sdpGrammar.delete()
        }
        val sipGrammar = File("${context.filesDir.absolutePath}/share/belr/grammars/sip_grammar")
        if (sipGrammar.exists()) {
            sipGrammar.delete()
        }
        val vcard3Grammar = File(
            "${context.filesDir.absolutePath}/share/belr/grammars/vcard3_grammar"
        )
        if (vcard3Grammar.exists()) {
            vcard3Grammar.delete()
        }
        val vcardGrammar = File(
            "${context.filesDir.absolutePath}/share/belr/grammars/vcard_grammar"
        )
        if (vcardGrammar.exists()) {
            vcardGrammar.delete()
        }
    }

    @AnyThread
    private fun copy(from: String, to: String, overrideIfExists: Boolean = false) {
        val outFile = File(to)
        if (outFile.exists()) {
            if (!overrideIfExists) {
                android.util.Log.i(
                    context.getString(org.linphone.R.string.app_name),
                    "$TAG File $to already exists"
                )
                return
            }
        }
        android.util.Log.i(
            context.getString(org.linphone.R.string.app_name),
            "$TAG Overriding $to by $from asset"
        )

        val outStream = FileOutputStream(outFile)
        val inFile = context.assets.open(from)
        val buffer = ByteArray(1024)
        var length: Int = inFile.read(buffer)

        while (length > 0) {
            outStream.write(buffer, 0, length)
            length = inFile.read(buffer)
        }

        inFile.close()
        outStream.flush()
        outStream.close()
    }
}
