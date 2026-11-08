package ir.hezarzist

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Browses only authenticated official records. Missing exams are visibly unknown. */
@Composable
fun KonkurArchiveScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val result = remember(context) { runCatching { KonkurRepository.load(context) } }
    val archive = result.getOrNull()
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var examId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    Surface {
        LazyColumn(modifier = Modifier.testTag("konkur_archive").padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("آرشیو کنکور واقعی", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(vertical = 16.dp))
                    TextButton(onClick = onBack) { Text("بازگشت") }
                }
            }
            if(archive == null) {
                item { Text("خواندن پروندهٔ رسمی انجام نشد. هیچ سؤال تألیفی به جای سؤال رسمی نمایش داده نمی‌شود.", color = MaterialTheme.colorScheme.error) }
                return@LazyColumn
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${fa(archive.summary.officialCount)} سؤال رسمی یکتا", modifier = Modifier.testTag("konkur_official_count"), style = MaterialTheme.typography.headlineSmall)
                        Text("قابل نمره‌دهی: ${fa(archive.summary.scorableCount)}؛ سؤال حذف‌شده در آرشیو باقی می‌ماند و نمره نمی‌گیرد.")
                        if(!archive.summary.complete) Text("⚠ پوشش ناقص است؛ این نسخه، آرشیو کامل کنکور ${fa(1398)} تا ${fa(1405)} نیست.", modifier = Modifier.testTag("konkur_incomplete"), style = MaterialTheme.typography.titleMedium)
                        Text("سؤال‌های تمرینی تألیفی و نهایی مستقل، در شمارش کنکور واقعی وارد نمی‌شوند.")
                    }
                }
            }
            item {
                Text("پوشش سال‌ها و نوبت‌ها", style = MaterialTheme.typography.titleMedium)
                Text("«نامعلوم» یعنی اصل فهرست آزمون‌ها یا دفترچه/کلید نهایی تأیید نشده است. تعداد مورد انتظار صفر نیست. هیچ نوبت یا کد دفترچه‌ای حدس زده نشده است.", style = MaterialTheme.typography.bodyMedium)
            }
            items(archive.years, key = { "coverage-${it.year}" }) { coverage ->
                Card(Modifier.fillMaxWidth().testTag("konkur_year_${coverage.year}")) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("سال ${fa(coverage.year)} · ${if(coverage.inventoryVerified) "فهرست تأییدشده" else "فهرست نوبت‌ها نامعلوم"}", style = MaterialTheme.typography.titleSmall)
                        Text("آزمون تأییدشده: ${fa(coverage.verifiedExams)} | مورد انتظار: ${coverage.expected?.let { fa(it) } ?: "نامعلوم"} | واردشده در دفترچه‌ها: ${fa(coverage.imported)}", style = MaterialTheme.typography.bodyMedium)
                        Text("بازبینی دفترچه‌ها: ${if(coverage.reviewVerified) "تأییدشده" else "انجام نشده"}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(archive.questions.isEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().testTag("konkur_empty")) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("هنوز سؤال کنکور واقعی وارد نشده است", style = MaterialTheme.typography.titleMedium)
                            Text("برای هر نوبت، اصل دفترچه، کلید نهایی و اصلاحات رسمی، تطبیق شماره‌ها و بازبینی علمی لازم است. فایل معتبر این منابع در محیط ساخت در دسترس نبوده است.")
                            Text("این صفحه آفلاین است. وضعیت پوشش و منابع همراه خود برنامه قرار دارند؛ برای نمایش آن حساب و اینترنت لازم نیست.")
                        }
                    }
                }
            } else {
                item {
                    Text("جست‌وجوی سؤال رسمی", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = year == null, onClick = { year = null; examId = null }, label = { Text("همهٔ سال‌ها") }) }
                        items(archive.years.map { it.year }) { value -> FilterChip(selected = year == value, onClick = { year = value; examId = null }, label = { Text(fa(value)) }) }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = examId == null, onClick = { examId = null }, label = { Text("همهٔ نوبت‌ها") }) }
                        items(archive.booklets.filter { year == null || it.year == year }.distinctBy { it.examId }) { exam ->
                            FilterChip(selected = examId == exam.examId, onClick = { examId = exam.examId }, label = { Text("${fa(exam.year)}، ${exam.session}، ${konkurLocationTitle(exam.location)}") })
                        }
                    }
                    OutlinedTextField(value = query, onValueChange = { query = it.take(120) }, label = { Text("متن، مبحث یا شمارهٔ سؤال") }, modifier = Modifier.fillMaxWidth())
                }
                val filtered = archive.questions.filter { row ->
                    val matched = row.occurrences.filter { (year == null || it.year == year) && (examId == null || it.examId == examId) }
                    matched.isNotEmpty() && (query.isBlank() || row.question.stem.contains(query) || row.question.topic.contains(query) || matched.any { fa(it.number) == query || it.number.toString() == query })
                }
                item { Text("${fa(filtered.size)} سؤال یکتا در این انتخاب", style = MaterialTheme.typography.labelLarge) }
                items(filtered, key = { it.question.id }) { record -> KonkurQuestionCard(record) }
            }
            if(archive.booklets.isNotEmpty()) {
                item { Text("اصل دفترچه‌ها و وضعیت منابع", style = MaterialTheme.typography.titleMedium) }
                items(archive.booklets, key = { "source-${it.examId}-${it.bookletId}" }) { booklet -> KonkurSourceCard(booklet) }
            }
            item {
                Text("حقوق منابع: مالک پروژه اجازهٔ بازنشر آزمون‌ها را اعلام کرده است؛ این اعلام، گواهی مستقل مؤسسه محسوب نمی‌شود. هر سؤال رسمی فقط پس از ثبت اصل منبع، کلید و بازبینی وارد می‌شود.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 28.dp))
            }
        }
    }
}

@Composable
private fun KonkurQuestionCard(record: KonkurQuestion) {
    val q = record.question
    var expanded by rememberSaveable(q.id) { mutableStateOf(false) }
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(q.origin.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(q.stem, style = MaterialTheme.typography.bodyLarge)
            q.image?.let { path ->
                val bitmap = remember(path) { StudyAssets.decodeBitmap(context, path, "bundled") }
                if(bitmap != null) Image(bitmap.asImageBitmap(), "شکل اصلی سؤال کنکور", modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp))
                else Text("شکل سؤال خوانده نشد؛ این سؤال نباید بدون شکل تمرین شود.", color = MaterialTheme.colorScheme.error)
            }
            q.options.forEachIndexed { i, option -> Text("${fa(i + 1)}. $option") }
            if(!record.scorable) Text("⊘ حذف رسمی؛ در آزمون و نمره‌دهی استفاده نمی‌شود.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { expanded = !expanded }) { Text(if(expanded) "بستن پاسخ و مشخصات" else "پاسخ، تحلیل و مشخصات منبع") }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if(record.scorable) Text("✓ پاسخ صحیح: گزینهٔ ${fa(q.answer + 1)}", style = MaterialTheme.typography.titleMedium)
                    Text(q.explanation)
                    q.optionExplanations.forEachIndexed { i, reason -> Text("${if(i == q.answer) "✓" else "×"} تحلیل گزینهٔ ${fa(i + 1)}: $reason") }
                    Text("دام آموزشی: ${q.trap}")
                    Text("${gradeTitle(q.grade)}؛ ${chapterTitle(q.grade, q.chapter)}؛ ${q.topic}")
                    Text("مباحث مرتبط: ${q.tags.joinToString("، ")}")
                    Text("ارجاع: ${q.reference.sourceId}؛ ${q.reference.section}")
                    Text("تفاوت چاپ‌ها: ${record.editionNotes}")
                    record.occurrences.forEach { o ->
                        Text("${fa(o.year)}؛ ${o.session}؛ ${konkurLocationTitle(o.location)}؛ دفترچه ${o.bookletId}؛ سؤال ${fa(o.number)}؛ صفحهٔ PDF ${fa(o.pdfPage)}")
                        if(!o.scorable) Text("⊘ حذف رسمی در این دفترچه: ${o.deletionReason}", color = MaterialTheme.colorScheme.error)
                        Text("ترتیب اصلی گزینه‌ها: " + o.originalOptions.mapIndexed { i, text -> "${fa(i + 1)}. $text" }.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        Text(if(o.finalAnswer == null) "کلید نهایی: حذف" else "کلید نهایی در ترتیب اصلی: ${fa(o.finalAnswer + 1)}", style = MaterialTheme.typography.bodySmall)
                    }
                    record.correctionNotes.forEach { Text("اصلاح رسمی: $it") }
                }
            }
        }
    }
}

@Composable
private fun KonkurSourceCard(b: KonkurBookletCoverage) {
    var expanded by rememberSaveable(b.examId, b.bookletId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${fa(b.year)}؛ ${b.session}؛ ${konkurLocationTitle(b.location)}؛ دفترچه ${b.bookletId}", style = MaterialTheme.typography.titleSmall)
            Text("مورد انتظار ${fa(b.expected)} · استخراج ${fa(b.extracted)} · واردشده ${fa(b.imported)}")
            Text("شماره‌های مفقود: ${if(b.missing.isEmpty()) "ندارد" else b.missing.joinToString("، ") { fa(it) }}")
            Text("بازبینی: ${if(b.reviewed) "تأییدشده" else "ناقص"}؛ نظام/نسخهٔ آزمون: ${b.syllabus}")
            TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "بستن منبع" else "نشانی و اثر انگشت منبع") }
            AnimatedVisibility(expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("نشانی‌ها فقط برای شناسایی منبع نمایش داده می‌شوند؛ صفحهٔ آرشیو ارتباط شبکه برقرار نمی‌کند.", style = MaterialTheme.typography.bodySmall)
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column {
                            Text(b.sourceUrl, style = MaterialTheme.typography.bodySmall)
                            Text("PDF SHA-256: ${b.sourceSha}", style = MaterialTheme.typography.bodySmall)
                            Text(b.finalKeyUrl, style = MaterialTheme.typography.bodySmall)
                            Text("KEY SHA-256: ${b.finalKeySha}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("اجازهٔ بازنشر: ${if(b.rightsStatus == "user_declared_permission") "اعلام‌شده توسط مالک؛ تأیید مستقل نشده" else "ثبت اجازهٔ بررسی‌شده"}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
