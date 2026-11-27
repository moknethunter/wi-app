# wi-app — Wi-Call Pro / المرحلة الأولى

نُقل أساس Wi-Call-v2 من التحديث المثبت 4f2ffb572e0a5a5c4b7f1074fe61cd1f23f53f43 إلى هذا المستودع المستقل، وطُبقت تعديلات حزمة Phase1 مباشرة. لا حاجة لتشغيل prepare.py مرة أخرى. لم يُنقل سجل commits القديم؛ بقيت جميع ملفات الأساس، مع تعديلات المرحلة الأولى.

## التغييرات
- MainActivity.kt: إزالة حقول IPv4 وbootstrap وكلمة سر المجموعة من واجهات الإعداد، وإبقاء طلب إذن VPN النظامي.
- ConnectionController.kt: إزالة bootstrap اليدوي من مسار الاكتشاف، وعدم عرض عنوان النفق في الحالة.
- WikallVpnService.kt: استخدام EasyTierEngine لتشغيل المحرك وربط TUN وإيقافه.
- EasyTierJNI.kt وEasyTierEngine.kt: استدعاءات JNI الأصلية في v2.6.4، التحقق من الإعداد وقراءة ملخص المحرك. snapshot ليست مراقبة دورية بعد، ولا يثبت تشغيل المحرك وصول الأقران.
- OverlayCredentials.kt: حماية سر Master باستخدام AES-GCM ومفتاح Android Keystore، ترحيل السر القديم وتدويره ورفض العبث.
- OverlayCredentialsSecurityTest.kt وEasyTierSnapshotTest.kt: اختبارات إضافية للأسرار وتفسير معلومات المحرك.

## البناء
يظل minSdk=29 وtargetSdk=35 وcompileSdk=35، ومعرّف التطبيق com.mokasas.wikall دون تغيير. يمكن أن يتعارض التثبيت جنبًا إلى جنب مع الإصدار القديم؛ قد يمنع اختلاف التوقيع تحديثه. لا تحذف بيانات تطبيقك القديم بلا نسخة احتياطية.

تستخدم .github/workflows/android.yml لبناء EasyTier على ثلاث معماريات، وتشغيل JVM وlint واختبارات Android 10 وبناء APK تجريبي. راجع Actions وملفات النتائج. نجاح CI القديم يخص الأساس فقط.

## ما لم ينجز
- تخصيص DHCP وتحديث TUN ديناميكيًا وإزالة OVERLAY_NETWORK وOVERLAY_HOST واشتقاق العنوان من الرقم. تعديل OverlayProfile.kt وNetworkStateMonitor.kt وWikallVpnService.kt وConnectionController.kt والاختبارات بتنسيق واحد.
- مراقبة EasyTier الفعلية وحماية مقابس المحرك الأصلية من حلقات VPN وإثبات عدم استخدام الإنترنت وCellular.
- إزالة مسارات الإدخال اليدوية الداخلية وتلميحات العنوان غير الضرورية بعد توفير بديل موثوق.
- اكتشاف الشبكات المختلفة المصرح بها والدمج والرجوع الآمن والتعامل مع تعارض الأرقام والترحيل عبر الوصلات الفعلية.
- انتخاب منسق بديل وتحسين handoff واستعادة الصوت واختبارات الهواتف الفعلية والأداء والتوقيع الإنتاجي.
- الالتزام بترخيص EasyTier وباقي الاعتماديات.

هذه مرحلة تأسيس وليست نسخة إنتاجية مستوفية للوثيقة الملزمة.

## المصادر
- https://github.com/moknethunter/Wi-Call-v2/tree/4f2ffb572e0a5a5c4b7f1074fe61cd1f23f53f43
- https://github.com/moknethunter/Wi-Call-v2/actions/runs/45959613707
- https://github.com/EasyTier/EasyTier/blob/v2.6.4/easytier-contrib/easytier-android-jni/kotlin/com/easytier/jni/EasyTierJNI.kt
