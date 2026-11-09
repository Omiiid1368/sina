# ساخت نسخهٔ ۰٫۱۴٫۰

این پروژه برنامهٔ `ir.hezarzist` با `versionName=0.14.0` و `versionCode=6` را می‌سازد؛ حداقل Android برابر API 26 و نسخهٔ هدف و کامپایل API 35 است.

## ابزارها

JDK 17، Python 3.10 یا بالاتر، Android SDK Platform 35، Build Tools 34.0.0 و Android SDK Platform Tools را نصب کنید. Gradle Wrapper 8.9 همراه سورس است؛ پروژه از Android Gradle Plugin 8.7.3، Kotlin و افزونهٔ Compose نسخهٔ 2.0.21 و Compose BOM نسخهٔ 2024.12.01 استفاده می‌کند.

مسیر SDK را در محیط توسعه با `ANDROID_HOME` یا تنظیم محلی Android Studio مشخص کنید. برای دریافت اجزای SDK می‌توان از ابزار رسمی استفاده کرد:

```sh
sdkmanager "platform-tools" "platforms;android-35" "build-tools;34.0.0"
```

دریافت اولیهٔ ابزارها و کتابخانه‌های ساخت از مخزن‌های Google، Maven Central و Gradle انجام می‌شود. خود برنامه پس از نصب آفلاین کار می‌کند.

## اعتبارسنجی و ساخت

دستورها را از ریشهٔ سورس استخراج‌شده اجرا کنید:

```sh
python3 tools/validate_bank.py
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease
```

خروجی Debug در `app/build/outputs/apk/debug/app-debug.apk` و خروجی Release در `app/build/outputs/apk/release/app-release-unsigned.apk` قرار می‌گیرد. فایل `gradlew` در ZIP سورس مجوز اجرا دارد.

بانک‌های قابل ویرایش `content/questions.json` و `content/finals.json` هستند. تصاویر با مسیر نسبی از `app/src/main/assets/` ارجاع داده می‌شوند. پس از ویرایش بانک‌ها، نسخهٔ همراه برنامه را همگام و اعتبارسنجی کنید:

```sh
python3 tools/sync_bank.py
python3 tools/validate_bank.py
```

شناسهٔ هر سؤال پیوند آن با سابقهٔ پیشرفت است؛ هنگام ویرایش محتوای همان سؤال، شناسه را حفظ کنید. اگر تعداد سؤال یا عضویت مجموعه‌ها تغییر کرد، شمارها را در `app/src/main/assets/content-counts.json` و شمار کلی، پایه و فصل را در `content/release/summary.json` از روی بانک جدید به‌روزرسانی کنید و اعتبارسنجی را دوباره اجرا کنید. کار `sync_bank.py` همگام‌سازی دو بانک JSON است.

برای اجرای آزمون‌های دستگاه، شبیه‌ساز یا دستگاه آزمایشی را متصل کنید:

```sh
adb devices
./gradlew :app:connectedDebugAndroidTest
```

آزمون‌های ابزار آرشیو نیز با وابستگی Python زیر قابل اجرا هستند:

```sh
python3 -m pip install pypdf==6.10.0
python3 -m unittest discover -s tools -p test_konkur_coverage.py -v
```

نتایج ثبت‌شدهٔ نسخهٔ تحویلی در [گزارش آزمون](TEST_REPORT.fa.md) آمده است.

## امضای Release در محیط مالک

برای امضای خروجی، متغیرهای `HEZARZIST_KEYSTORE` و `HEZARZIST_KEY_ALIAS` را در محیط خود به گواهی و نام مستعار متعلق به مالک تنظیم کنید. برای به‌روزرسانی نصب موجود از همان گواهی امضای آن نصب استفاده کنید. ابزار امضا اطلاعات لازم را به‌صورت تعاملی دریافت می‌کند.

```sh
mkdir -p dist/release
"$ANDROID_HOME/build-tools/34.0.0/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk dist/release/HezarZist-0.14.0-aligned.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" sign --ks "$HEZARZIST_KEYSTORE" --ks-key-alias "$HEZARZIST_KEY_ALIAS" --out dist/release/HezarZist-0.14.0-release.apk dist/release/HezarZist-0.14.0-aligned.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --verbose --print-certs dist/release/HezarZist-0.14.0-release.apk
```

## ساخت بسته‌های تحویل

بررسی محتوای بسته‌ها و ساختار آن‌ها با اجرای زیر انجام می‌شود:

```sh
python3 tools/package_delivery.py --version 0.14.0 --dist dist/delivery --dry-run --self-check
```

برای تولید فایل‌ها، APK موردنظر را صریحاً مشخص کنید:

```sh
python3 tools/package_delivery.py --version 0.14.0 --dist dist/delivery --apk dist/release/HezarZist-0.14.0-release.apk --self-check
```

خروجی شامل `HezarZist-0.14.0-source.zip`، `HezarZist-0.14.0-bank.zip`، `HezarZist-0.14.0-delivery.zip`، فهرست فایل‌های بسته و `HezarZist-0.14.0-SHA256SUMS.txt` است. داخل هر ZIP، `MANIFEST.json` اندازه و SHA-256 هر فایل را ثبت می‌کند. بسته‌ساز برابری بانک مرجع با assets، وجود تصاویر مورد ارجاع، شمار مجموعه‌ها و CRC فایل‌های ZIP را بررسی می‌کند.
