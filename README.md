# AgentPro for Android

AgentPro is a SIP softphone for call-center agents: dialpad, call dispositions, a campaign
auto-dialer, contacts, a live extension panel, PBX call records, queue control, a wallboard and
outbound-minutes reports.

The interface is available in 17 languages: Arabic, English, French, German, Spanish,
Portuguese, Russian, Chinese, Turkish, Persian, Urdu, Pashto, Hindi, Bengali, Indonesian, Malay
and Swahili. It follows the phone's language, and Settings ▸ Language changes it.

It is a modified version of
[Linphone for Android](https://github.com/BelledonneCommunications/linphone-android) by
Belledonne Communications, and it is built on the Linphone SDK.

## License

AgentPro is free software, released under the **GNU General Public License, version 3** — see
[LICENSE.txt](LICENSE.txt). You may redistribute it and/or modify it under those terms. It comes
with ABSOLUTELY NO WARRANTY.

- Linphone for Android and the Linphone SDK: © 2010-2026 Belledonne Communications SARL, GPLv3.
- Modifications and additions for AgentPro: © 2026 Samt Software (سمت للبرمجيات), GPLv3.
  AgentPro was modified from Linphone for Android starting in June 2026. Source files that still
  carry Belledonne Communications' header come from upstream, and many of them were modified.

The Linphone name and logo belong to Belledonne Communications. AgentPro is an independent project
and is not affiliated with or endorsed by Belledonne Communications.

## Source of the Linphone SDK

The app is built against `linphone-sdk-android` **5.5.14-pre.4+139bdf8bcb**, included in
`linphone-sdk-local/` so that the build needs no remote repository. Its complete source code is
commit `139bdf8bcb` of the Linphone SDK:
<https://github.com/BelledonneCommunications/linphone-sdk/tree/139bdf8bcb>

## Building

Requirements: JDK 21 and the Android SDK (platform 36).

    ./gradlew assembleRelease      # APK
    ./gradlew bundleRelease        # App Bundle (.aab)

- The first build creates `credential-key.properties` with a random key. The app encrypts the
  credentials it stores with it. Keep that file private, and build every release you distribute
  with the same one.
- To sign a release, put your own keystore details in `keystore.properties`.

## Privacy

See [PRIVACY.md](PRIVACY.md) (Arabic and English): the app collects no data for its developer; it
talks only to the SIP server and PBX you configure, plus a public STUN server when needed.

## Contact

Samt Software — Samir, Bilal & Abdulrahman Shora — samirtwf@gmail.com
