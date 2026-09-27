# سياسة الخصوصية — AgentPro

**تاريخ السريان:** 27 سبتمبر 2026
**المطوّر:** سمت للبرمجيات — samirtwf@gmail.com

AgentPro تطبيق اتصال (SIP) لموظفي مراكز الاتصال. يوضح هذا المستند البيانات التي يتعامل معها التطبيق وأين تذهب.

## ملخص
- **لا نجمع أي بيانات عنك.** لا يوجد حساب لدينا، ولا إعلانات، ولا أدوات تحليل أو تتبّع، ولا تقارير أعطال تُرسل إلينا.
- يتصل التطبيق بخادم الاتصال (SIP) ومقسم الهاتف (PBX) اللذين تُدخل أنت عنوانيهما، وعند الحاجة بخادم STUN عام (انظر أدناه) — ولا شيء غير ذلك.
- كل ما يحفظه التطبيق يبقى **على جهازك مشفّراً**، ويُحذف بالكامل عند إزالة التطبيق.

## البيانات المحفوظة على جهازك
- بيانات حساب SIP التي تُدخلها (اسم المستخدم، كلمة المرور، الخادم).
- إعدادات الاتصال بالمقسم التي تُدخلها (العنوان، وبيانات دخول AMI وواجهة HTTPS وصفحة الويب).
- سجل المكالمات، وتصنيفات المكالمات، وقوائم الحملات، وجهات الاتصال الخاصة بالتطبيق، وأرقام الاتصال السريع.
- تسجيلات المكالمات إن استخدمت ميزة التسجيل.
- سجلات التشغيل الفنية (logs).

تُحفظ هذه البيانات مشفّرة، ولا تُنسخ احتياطياً إلى أي خدمة سحابية (النسخ الاحتياطي في أندرويد معطّل لهذا التطبيق).

## إلى أين تذهب البيانات
- إلى **خادم SIP ومقسم الهاتف الخاصين بجهة عملك** فقط: لتسجيل الدخول، وإجراء المكالمات واستقبالها، وعرض حالة الطوابير وسجلات المكالمات والتسجيلات. هذه الخوادم تديرها جهة عملك، وليس لنا أي وصول إليها.
- إلى **خادم STUN العام من Google** (`stun.l.google.com`) عند الحاجة: ليعرف التطبيق عنوان الشبكة العام لجهازك حتى يعمل الصوت في الاتجاهين. هذا الطلب لا يحمل أي بيانات شخصية، لكن الخادم يرى عنوان الشبكة (IP) الذي يأتي منه جهازك.
- **لا نبيع ولا نشارك** أي بيانات مع أي طرف ثالث.
- لا يخرج من جهازك شيء آخر إلا بقرار منك: عند إرسال سجلات التشغيل بالبريد، أو تصدير الإعدادات إلى ملف محمي بكلمة مرور.

## الأذونات ولماذا نحتاجها
| الإذن | السبب |
|---|---|
| الميكروفون | إجراء المكالمات الصوتية |
| الكاميرا | مكالمات الفيديو (عند استخدامها فقط) |
| جهات الاتصال | عرض الأسماء والاتصال بجهات الاتصال في هاتفك — لا تُرفع إلى أي مكان |
| الإشعارات وشاشة المكالمة الكاملة | إظهار المكالمات الواردة والفائتة |
| إدارة المكالمات | ربط مكالمات التطبيق بنظام الهاتف |
| البلوتوث | استخدام السماعات اللاسلكية |
| الشبكة | الاتصال بخادمك |
| التشغيل عند بدء الجهاز وفي الخلفية، واستثناء توفير البطارية | البقاء متصلاً لاستقبال المكالمات |

## الأطفال
التطبيق موجّه لبيئات العمل، وليس موجّهاً للأطفال دون 13 عاماً.

## التغييرات
أي تعديل على هذه السياسة يُنشر على هذه الصفحة نفسها مع تاريخ السريان الجديد.

## التواصل
سمت للبرمجيات — samirtwf@gmail.com

---

# Privacy Policy — AgentPro

**Effective date:** 27 September 2026
**Developer:** Samt Software — samirtwf@gmail.com

AgentPro is a SIP calling app for call-center agents. This page explains which data the app handles
and where it goes.

## Summary
- **We collect no data about you.** There is no account with us, no ads, no analytics or tracking, and
  no crash reports sent to us.
- The app connects to the SIP server and the PBX whose addresses you enter, and when needed to a public
  STUN server (see below) — nothing else.
- Everything the app stores stays **on your device, encrypted**, and is deleted when you uninstall it.

## Data stored on your device
- The SIP account details you enter (username, password, server).
- The PBX connection settings you enter (address, and AMI, HTTPS API and web-page logins).
- Call history, call dispositions, campaign lists, the app's own contacts and speed-dials.
- Call recordings, if you use recording.
- Technical logs.

This data is stored encrypted and is not backed up to any cloud service (Android backup is disabled
for this app).

## Where data goes
- **Only to your organization's SIP server and PBX**: to sign in, make and receive calls, and show
  queue status, call records and recordings. Your organization runs these servers; we have no access
  to them.
- **To Google's public STUN server** (`stun.l.google.com`) when needed, so the app can learn your
  device's public network address and audio works both ways. The request carries no personal data, but
  the server sees the network (IP) address your device connects from.
- We **do not sell or share** any data with third parties.
- Nothing else leaves your device unless you choose to: when you email the logs, or export the
  settings to a password-protected file.

## Permissions and why they are needed
| Permission | Why |
|---|---|
| Microphone | Voice calls |
| Camera | Video calls (only when used) |
| Contacts | Show names and call your phone's contacts — never uploaded |
| Notifications and full-screen call screen | Show incoming and missed calls |
| Call management | Integrate the app's calls with the phone system |
| Bluetooth | Wireless headsets |
| Network | Connect to your server |
| Start at boot, run in background, battery-optimization exemption | Stay connected to receive calls |

## Children
The app is meant for workplaces and is not directed to children under 13.

## Changes
Any change to this policy is published on this same page, with the new effective date.

## Contact
Samt Software — samirtwf@gmail.com
