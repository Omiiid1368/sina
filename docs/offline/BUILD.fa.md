# ساخت و بسته‌بندی پیش‌نمایش آفلاین

نسخهٔ فعال `0.12.0-preview`، کد نسخهٔ ۴، `applicationId=ir.hezarzist`، `minSdk=26` و `targetSdk=35` است. این راهنما دستور کار است؛ هیچ دستور درج‌شده به‌خودی‌خود گزارش قبولی ساخت، نصب یا آزمون نیست.

بانک این نسخه **صفر سؤال رسمی کنکورِ مستقلاً تأییدشده، ۹۶ تست تألیفی قبلی، ۶۲۹ تست مجموعهٔ ارسالی و ۷۲ تمرین نهایی تألیفی** دارد. ورود ۴۳۹ تست و ۱۶۵ صفحهٔ تشریحی ارسالی کامل نیست؛ [گزارش ورودی‌ها](v012/IMPORT_REPORT.fa.md) را بخوانید. شرط آرشیو جامع ۱۳۹۸ تا ۱۴۰۵ هنوز انجام نشده است. ضوابط جاری رسمی مایکت نیز در محیط فعلی خوانده نشده‌اند. خروجی را پیش‌نمایش ناقص بنامید و [گزارش پوشش](COVERAGE.fa.md)، [کمبودها](archive/SOURCE_GAPS.fa.md) و [وضعیت مایکت](../../release/myket/MYKET_READINESS.json) را همراهش بدهید.

## ابزارهای ساخت

JDK 17، Python 3.10 یا بالاتر، Android SDK Platform 35، Build Tools 34.0.0 موردنیاز AGP 8.7.3 و platform-tools را نصب کنید. Gradle Wrapper 8.9 همراه پروژه است. از تنظیم استاندارد مسیر SDK در محیط خود یا فایل دستگاهی `local.properties` استفاده کنید؛ برای مثال `sdk.dir=/absolute/path/to/android-sdk`. این فایل و تنظیمات خصوصی داخل ZIP عمومی نیستند.

دریافت اولیهٔ SDK، توزیع Gradle و کتابخانه‌ها به شبکهٔ محیط توسعه نیاز دارد؛ کاربر برنامه بعد از نصب به اینترنت نیاز ندارد. checksum توزیع Gradle، اعتبارسنجی TLS یا گواهی بسته‌ها را برای رفع خطای شبکه خاموش نکنید. کش کامل توسعه را می‌توان با گزینهٔ `--offline` Gradle استفاده کرد؛ وجود کش در هر دستگاه تضمین نشده است.

برای ممیزی PDFهای منابع و اجرای آزمون‌های ابزار آرشیو، وابستگی‌های جداگانه را نصب کنید:

```sh
python3 -m pip install -r content/konkur/requirements-audit.txt
```

## بانک و ساخت Debug

از ریشهٔ سورس استخراج‌شده اجرا کنید:

```sh
python3 tools/validate_bank.py
python3 tools/konkur_coverage.py
python3 -m unittest discover -s tools -p test_konkur_coverage.py -v
./gradlew testDebugUnitTest assembleDebug
```

خروجی Debug در `app/build/outputs/apk/debug/app-debug.apk` است. اگر فایل اجرایی Wrapper در انتقال مجوز اجرا نداشت، `chmod +x gradlew` را اجرا کنید. ZIP سورس تولیدشده توسط بسته‌ساز مجوز اجرای Wrapper را ثبت می‌کند.

بانک‌های قابل ویرایش `content/questions.json` و `content/finals.json` هستند. پس از اصلاح آن‌ها، ابتدا `python3 tools/sync_bank.py` را اجرا کنید تا نسخهٔ داخل assets هم یکسان شود؛ سپس اعتبارسنجی را تکرار کنید. `content/v2/build.py` مواد تألیفی را دوباره تولید و ویرایش دستی بانک‌ها را بازنویسی می‌کند؛ برای اصلاح مستقیم بانک لازم نیست. شناسهٔ موجود را بی‌دلیل تغییر ندهید، چون سابقه به آن متصل است.

آرشیو رسمی مسیر جداگانهٔ `content/konkur/` دارد. پس از ورود واقعی و بازبینی منابع، `python3 tools/konkur_coverage.py --write` گزارش‌ها و نسخهٔ bundled را همگام می‌کند. `--complete` همهٔ سال‌ها و شماره‌های دفترچه را مطالبه می‌کند و در وضع ناقص فعلی باید شکست بخورد. `python3 tools/validate_bank.py --release` نیز دروازهٔ تکمیل آرشیو و آمادگی انتشار است؛ قبولی ساخت یا وجود ZIP جای این دروازه را نمی‌گیرد.

بانک قدیمی `content/legacy/v1/questions.json` فقط مرجع محفوظ بازیابی و کنترل عدم بازاستفاده است و در assets فعال نیست. ابزار اعتبارسنجی از آن برای مقایسه استفاده می‌کند؛ به همین دلیل همین فایل در سورس تحویلی محفوظ است. گزارش‌های `docs/v2/` تاریخی‌اند و تنها پرونده‌های موردنیاز منشأ/بازبینی در بستهٔ فعال نگهداری می‌شوند؛ آزمون نسخهٔ آنلاین پیشین، شاهد این نسخه نیست.

## آزمون دستگاه

شبیه‌ساز یا دستگاه را متصل و پیش از نصب تازه/اجرا حالت پرواز را فعال کنید. در دستگاه توسعه، مثال دستورات:

```sh
adb devices
adb shell settings put global airplane_mode_on 1
adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
./gradlew connectedDebugAndroidTest
```

برخی دستگاه‌ها تنظیم حالت پرواز از ADB را محدود می‌کنند؛ در آن حالت آن را از تنظیمات خود دستگاه روشن کنید. اجرای اولین بار، تمرین، آزمون، دوئل با رایانه، مرور، آمادگی نهایی، همهٔ تصاویر، رابط فارسی، زمان و امتیاز، بستن اجباری/بازکردن دوباره و انتخاب‌گر واقعی خروجی/بازیابی فایل را روی همان APK بررسی کنید. حذف داده یا نصب تازه سابقه را پاک می‌کند؛ برای آزمون مهاجرت، ابتدا از دادهٔ دستگاه آزمون پشتیبان محلی بگیرید و ارتقا را جداگانه از نصب تازه بررسی کنید. کلید امضای متفاوت روی نصب قبلی به‌عنوان به‌روزرسانی پذیرفته نمی‌شود.

نتیجه و شمار آزمون‌های واقعاً اجراشده، خطاها، نسخهٔ دستگاه، هش APK و محدودیت‌های باقی‌مانده را در گزارش نسخه ثبت کنید. [گزارش نسخهٔ ۰٫۱۲](v012/TEST_REPORT.fa.md) بازبینی منبع و آماده‌سازی آزمون را شرح می‌دهد؛ نتیجهٔ اجرا باید از گزارش اجرای واقعی خوانده شود. بسته‌ساز آزمونی روی دستگاه اجرا نمی‌کند.

## Release و امضا

```sh
./gradlew assembleRelease
```

تنظیم عمومی پروژه کلید خصوصی ندارد؛ خروجی پیش‌فرض بدون امضا در `app/build/outputs/apk/release/app-release-unsigned.apk` ساخته می‌شود. مالک می‌تواند در Android Studio از Generate Signed Bundle / APK با کلید محفوظ خودش استفاده کند، یا با `zipalign` و `apksigner` رسمی امضا کند. برای به‌روزرسانی نصب قبلی، همان گواهی معتبر قبلی لازم است.

در نمونهٔ زیر Build Tools نسخهٔ ۳۵ یا بالاتر برای گزینهٔ `-P 16` استفاده شود. گذرواژهٔ کلید در این دستور با گذرواژهٔ keystore یکسان است؛ اگر متفاوت است، مسیر خصوصی جداگانهٔ گذرواژهٔ کلید را تنظیم کنید.

نمونهٔ زیر فقط پس از تعیین مسیرهای واقعی خصوصی و alias صحیح اجرا شود؛ گذرواژه را در فرمان، سورس یا چت ننویسید. فایل گذرواژه و keystore بیرون پروژه و با دسترسی محدود نگهداری شوند. متغیرهای `HEZAR_SIGN_KEYSTORE`، `HEZAR_SIGN_PASSFILE`، `HEZAR_SIGN_ALIAS` و `HEZAR_BUILD_TOOLS` در محیط خصوصیِ خودتان به مسیر/مقدار واقعی تنظیم شوند:

```sh
"$HEZAR_BUILD_TOOLS/zipalign" -P 16 -f 4 app/build/outputs/apk/release/app-release-unsigned.apk /absolute/output/hezarzist-aligned.apk
"$HEZAR_BUILD_TOOLS/apksigner" sign --ks "$HEZAR_SIGN_KEYSTORE" --ks-key-alias "$HEZAR_SIGN_ALIAS" --ks-pass "file:$HEZAR_SIGN_PASSFILE" --out /absolute/output/HezarZist-0.12.0-preview-release.apk /absolute/output/hezarzist-aligned.apk
"$HEZAR_BUILD_TOOLS/apksigner" verify --verbose --print-certs /absolute/output/HezarZist-0.12.0-preview-release.apk
sha256sum /absolute/output/HezarZist-0.12.0-preview-release.apk
adb install -r /absolute/output/HezarZist-0.12.0-preview-release.apk
```

در صورت نیاز مستقل به AAB، `./gradlew bundleRelease` خروجی bundle را می‌سازد؛ بسته‌ساز فقط فایل موجودِ صریح را می‌گیرد. پذیرش APK یا AAB در مایکت و مشخصات تصویرها هنوز از راهنمای جاری رسمی تأیید نشده است. کلید امضا، فایل گذرواژه و حساب توسعه‌دهنده داخل بستهٔ عمومی نیستند.

## اجرای آزمون روی خود Release امضاشده

برای آزمون‌های متصل به توابع `internal` کاتلین، test APK باید برای همان گونهٔ Release کامپایل شود؛ امضای دوبارهٔ test APK نوع Debug به‌تنهایی کافی نیست. override آزموده‌شده در `test-evidence/release-instrumentation.init.gradle` است و سورس برنامه را تغییر نمی‌دهد:

```sh
./gradlew -I docs/offline/test-evidence/release-instrumentation.init.gradle :app:assembleReleaseAndroidTest
"$HEZAR_BUILD_TOOLS/apksigner" sign --ks "$HEZAR_SIGN_KEYSTORE" --ks-key-alias "$HEZAR_SIGN_ALIAS" --ks-pass "file:$HEZAR_SIGN_PASSFILE" --out /absolute/private/test-release.apk app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb install -r -t /absolute/private/test-release.apk
adb shell am instrument -w -r ir.hezarzist.test/androidx.test.runner.AndroidJUnitRunner
```

APK اصلیِ امضاشده باید پیش‌تر روی **دستگاه آزمون** نصب و حالت پرواز روشن شده باشد. این آزمون‌ها دادهٔ آزمایشی می‌سازند و پاک می‌کنند؛ روی گوشی دارای پیشرفت شخصی بدون پشتیبان اجرا نشوند. نتیجهٔ واقعی JUnit و `INSTRUMENTATION_STATUS_CODE` را بررسی کنید؛ صفر بودن exit code فرمان ADB به‌تنهایی به‌معنای قبولی آزمون‌ها نیست. خروجی test APK برای تحویل به دانش‌آموز نیست و داخل بستهٔ انتشار قرار نمی‌گیرد.

## ساخت ZIP منظم

ابتدا بدون ایجاد خروجی، فهرست و محتوای برنامه‌ریزی‌شده را بررسی کنید:

```sh
python3 tools/package_delivery.py --version 0.12.0-preview --dist dist --dry-run --self-check
```

برای ساخت بستهٔ سورس و تحویل **بدون APK/AAB**:

```sh
python3 tools/package_delivery.py --version 0.12.0-preview --dist dist --self-check
```

فقط پس از ساخت و بررسی APK واقعی، مسیرش را صریح بدهید:

```sh
python3 tools/package_delivery.py --version 0.12.0-preview --dist dist --apk /absolute/output/HezarZist-0.12.0-preview-release.apk --self-check
```

پارامتر اختیاری `--aab /absolute/output/app-release.aab` نیز وجود دارد. ابزار هیچ APK/AAB قدیمی را از build یا dist خودکار انتخاب نمی‌کند؛ مسیر ناموجود یا فایل با ساختار ZIP اندرویدی نامعتبر رد می‌شود. بررسی ساختار فایل، اعتبار امضا، صحت نسخه یا قبولی دستگاه را ثابت نمی‌کند.

خروجی‌های نسخه‌دار:

- `HezarZist-0.12.0-preview-source.zip`: سورس فعال Kotlin/Compose و آزمون‌ها، Gradle Wrapper، ابزارهای جاری، بانک‌ها، تصاویر/قلم‌ها و پرونده‌های منبع/حقوق لازم.
- `HezarZist-0.12.0-preview-delivery.zip`: سورس ZIP در `source/` (شامل همهٔ تصاویر)، بانک و اسناد در مسیرهای اصلی، اسناد آفلاین، پیش‌نویس مایکت و `artifacts/` فقط برای فایل Android صریحاً داده‌شده.
- `HezarZist-0.12.0-preview-bank.zip`: بانک قابل ویرایش، اقلام منتظر/تکراری و تصاویر سؤال/گزینه/پاسخ.
- `HezarZist-0.12.0-preview-package-manifest.json` و `HezarZist-0.12.0-preview-SHA256SUMS.txt`: فهرست بایت/هش فایل‌ها و checksum همین خروجی‌ها؛ فایل‌های قدیمیِ dist در checksum جدید جمع نمی‌شوند.

`MANIFEST.json` داخل هر ZIP وضعیت شمار بانک، کامل‌نبودن آرشیو و هش هر فایل آن بسته را دارد. CRC، برابری بایت‌ها با snapshot، فهرست فایل لازم، همسانی بانک مرجع و assets و چند فیکسچر منع فایل خصوصی کنترل می‌شوند. فایل‌ها با allowlist جمع می‌شوند؛ `.git`، کلید/گذرواژه/credential، فایل تنظیم دستگاه، cache، log، build، دادهٔ قدیمی بخش آنلاین و آرشیوهای خروجی خودکار وارد نمی‌شوند. symlink در فایل کاندیدا رد می‌شود. محتوای کلید خصوصی و credential شناخته‌شده در فایل عمومی نیز موجب توقف می‌شود؛ این کنترل جای بازبینی دستی بستهٔ انتشار را نمی‌گیرد.

بستهٔ تولیدشده به‌تنهایی مجوز استفاده از منابع، صحت علمی، کامل‌بودن آرشیو، قبولی آزمون یا پذیرش مایکت را اعلام نمی‌کند. نام ناشر و تماس واقعی رسیدگی به اعتراض و شرایط رسمی فروشگاه پیش از ارسال باید تکمیل شوند. هیچ پرداخت یا انتشار خودکار توسط این ابزار انجام نمی‌شود.
