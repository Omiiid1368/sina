package ir.hezarzist

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The system document picker grants access only to the file selected by the learner. */
@Composable
fun OfflineToolsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { LocalProfileStore(context) }
    var name by remember { mutableStateOf(store.profile.name) }
    var avatar by remember { mutableStateOf(store.profile.avatarId) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<ByteArray?>(null) }
    var prepared by remember { mutableStateOf<PreparedLocalBackup?>(null) }
    val scope = rememberCoroutineScope()
    val previousStudyAvailable = remember { runCatching { LocalBackup.previousStudyBackup(context) != null }.getOrDefault(false) }
    val previousAnonymousAvailable = remember { runCatching { LocalBackup.previousAnonymousBackup(context) != null }.getOrDefault(false) }
    val rawRecoveryAvailable = remember { runCatching { LocalBackup.unparsedStudyRecovery(context) != null }.getOrDefault(false) }
    val recoveryNotice = remember { LocalBackup.recoveryNotice(context) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val bytes = pendingExport
        pendingExport = null
        if (uri == null || bytes == null) {
            busy = false
        } else {
            scope.launch {
                status = try {
                    withContext(Dispatchers.IO) {
                        val output = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                        output.use { it.write(bytes); it.flush() }
                    }
                    "✓ پشتیبان ذخیره شد. فایل را برای بازگردانی نگه دارید."
                } catch (_: Exception) {
                    "ذخیرهٔ فایل انجام نشد. دوباره یک مسیر محلی انتخاب کنید؛ داده‌های بازی محفوظ‌اند."
                }
                busy = false
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            busy = false
        } else {
            scope.launch {
                try {
                    prepared = withContext(Dispatchers.IO) {
                        val input = requireNotNull(context.contentResolver.openInputStream(uri))
                        val bytes = input.use { LocalBackup.read(it) }
                        LocalBackup.prepare(context, bytes)
                    }
                    status = "✓ ساختار و بررسی یکپارچگی فایل تأیید شد. جزئیات را پیش از بازگردانی ببینید."
                } catch (_: Exception) {
                    status = "فایل پذیرفته نشد: پشتیبانِ معتبرِ ۱۰۰۰ زیست با حجم حداکثر ۱۶ مگابایت انتخاب کنید. داده‌های فعلی تغییر نکردند."
                }
                busy = false
            }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack, enabled = !busy, modifier = Modifier.semantics { contentDescription = "offline-tools-back" }) { Text("بازگشت") }
        Text("نمایه و پشتیبان محلی", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("بازی بدون ثبت‌نام کار می‌کند. این نام و نماد اختیاری فقط روی دستگاه و در پشتیبانِ خودت نگهداری می‌شوند.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(LocalProfileStore.MAX_NAME_LENGTH) },
            label = { Text("نام نمایشی (اختیاری)") },
            singleLine = true,
            enabled = !busy,
            supportingText = { Text("حداکثر ۴۰ نویسه؛ می‌توانی خالی بگذاری.") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "local-profile-name" }
        )
        Text("نماد محلی", fontWeight = FontWeight.Bold)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            localAvatars.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    pair.forEach { item ->
                        OutlinedButton(
                            onClick = { avatar = item.id }, enabled = !busy,
                            border = BorderStroke(if (avatar == item.id) 2.dp else 1.dp, if (avatar == item.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "local-avatar-${item.id}" }
                        ) { Text("${if (avatar == item.id) "✓ " else ""}${item.symbol}  ${item.title}") }
                    }
                }
            }
        }
        Button(onClick = {
            status = try { store.save(name, avatar); name = store.profile.name; LocalProgress.notifyChanged(context); "✓ نمایه روی همین دستگاه ذخیره شد." }
            catch (_: Exception) { "ذخیرهٔ نمایه انجام نشد؛ دوباره تلاش کنید." }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "local-profile-save" }) { Text("ذخیرهٔ نمایه") }

        HorizontalDivider()
        Text("پشتیبان پیشرفت", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("پاسخ‌ها، اشتباهات، نشان‌ها، تاریخچه، رکوردها، تنظیمات، نوشته‌های نهایی و نمره‌های خودارزیابی در فایل JSON ذخیره می‌شوند. محتوای سؤال‌ها در خود برنامه قرار دارد.", style = MaterialTheme.typography.bodyMedium)
        Text("فایل پشتیبان رمزگذاری نشده و شامل نوشته‌های شماست. آن را در مسیر محلی دلخواه نگه دارید. حذف برنامه داده‌های روی دستگاه را پاک می‌کند.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            try {
                pendingExport = LocalBackup.export(context)
                busy = true
                exportLauncher.launch("HezarZist-backup-${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.json")
            } catch (_: Exception) { status = "ساخت فایل پشتیبان انجام نشد؛ داده‌های اصلی محفوظ‌اند."; busy = false }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "local-backup-export" }) { Text("ذخیرهٔ پشتیبان در فایل") }
        OutlinedButton(onClick = {
            busy = true
            try { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }
            catch (_: Exception) { busy = false; status = "انتخاب‌گر فایل دستگاه در دسترس نیست." }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "local-backup-import" }) { Text("انتخاب فایل برای بازگردانی") }
        if (previousStudyAvailable) {
            OutlinedButton(onClick = {
                try {
                    pendingExport = LocalBackup.previousStudyBackup(context)
                    busy = true
                    exportLauncher.launch("HezarZist-previous-study.json")
                } catch (_: Exception) { busy = false; status = "خواندن پشتیبان مطالعهٔ نسخهٔ قبل انجام نشد." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "local-backup-previous-study" }) { Text("ذخیرهٔ پشتیبان مطالعهٔ نسخهٔ قبل") }
        }
        if (previousAnonymousAvailable) {
            OutlinedButton(onClick = {
                try {
                    pendingExport = LocalBackup.previousAnonymousBackup(context)
                    busy = true
                    exportLauncher.launch("HezarZist-previous-anonymous-study.json")
                } catch (_: Exception) { busy = false; status = "خواندن پشتیبان مطالعهٔ ناشناس نسخهٔ قبل انجام نشد." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "local-backup-previous-anonymous" }) { Text("ذخیرهٔ مطالعهٔ ناشناس نسخهٔ قبل") }
        }
        if (recoveryNotice.isNotBlank()) Text(recoveryNotice, style = MaterialTheme.typography.bodySmall)
        if (rawRecoveryAvailable) {
            OutlinedButton(onClick = {
                try {
                    pendingExport = LocalBackup.unparsedStudyRecovery(context)
                    busy = true
                    exportLauncher.launch("HezarZist-study-manual-recovery.json")
                } catch (_: Exception) { busy = false; status = "خواندن پروندهٔ بازیابی دستی انجام نشد." }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "local-backup-manual-recovery" }) { Text("ذخیرهٔ پروندهٔ مطالعه برای اصلاح دستی") }
        }
        if (busy) Text("در حال خواندن یا ذخیرهٔ فایل…")
        if (status.isNotBlank()) Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Text(status, Modifier.padding(16.dp).semantics { contentDescription = "local-backup-status" })
        }
        Text("بازگردانی، پیشرفت فعلی را جایگزین می‌کند. شناسه‌های بانک قبلی بایگانی می‌شوند و به سؤال تازه نسبت داده نمی‌شوند. زمان آزمون‌های زمان‌دار از نو شروع نمی‌شود.", style = MaterialTheme.typography.bodySmall)
    }

    prepared?.let { backup ->
        AlertDialog(onDismissRequest = { prepared = null }, title = { Text("بازگردانی این پشتیبان؟") }, text = {
            val p = backup.preview
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("${fa(p.answeredQuestions)} سؤال تمرین‌شده • ${fa(p.archivedQuestions)} سؤال بایگانی‌شده")
                Text("${fa(p.bookmarks)} نشان • ${fa(p.matches)} نتیجهٔ مسابقه")
                Text("${fa(p.finalResponses)} پاسخ نهایی • ${fa(p.archivedFinalQuestions)} شناسهٔ نهایی بایگانی‌شده")
                Text("نام نمایشی: ${p.displayName.ifEmpty { "خالی" }}")
                if(p.containsStudyTime) {
                    Text("زمان مطالعه: ${fa(p.studySessions)} جلسه • ${fa(p.studyMillis / 60_000)} دقیقه")
                    Text("جلسه‌ها و هدف مطالعه با فایل جایگزین می‌شوند. زمان‌سنجِ داخل فایل با حالت مکث بازمی‌گردد تا زمانِ بیرون از برنامه دوباره شمرده نشود.")
                } else Text("این پشتیبان قدیمی شامل زمان مطالعه نیست؛ جلسه‌ها و هدف مطالعهٔ فعلی حفظ می‌شوند.")
                Text("پیشرفت و تنظیمات فعلی با این فایل جایگزین می‌شوند. برای نگهداری هر دو، ابتدا از وضعیت فعلی پشتیبان بگیر.")
            }
        }, confirmButton = {
            TextButton(onClick = {
                // No suspension between invalidation and replacement; old view models cannot write.
                busy = true
                try {
                    LocalBackup.restore(context, backup)
                    name = store.profile.name; avatar = store.profile.avatarId
                    status = "✓ بازگردانی کامل شد. پیشرفت و تنظیمات از فایل خوانده شدند."
                } catch (_: Exception) { status = "بازگردانی انجام نشد. داده‌های قبلی بازیابی شدند؛ در صورت خطای حافظه برنامه را دوباره باز کنید." }
                prepared = null
                busy = false
            }, modifier = Modifier.semantics { contentDescription = "local-backup-confirm" }) { Text("جایگزینی و بازگردانی") }
        }, dismissButton = { TextButton(onClick = { prepared = null }) { Text("انصراف") } })
    }
}
