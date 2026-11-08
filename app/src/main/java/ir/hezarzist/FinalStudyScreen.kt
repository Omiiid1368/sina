package ir.hezarzist

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun FinalStudyScreen(
    onBack: () -> Unit,
    model: FinalStudyViewModel = viewModel(key = "finals_local_${LocalProgress.dataVersion}")
) {
    val context = LocalContext.current
    DisposableEffect(model, context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.flush() }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); model.flush() }
    }
    val session = model.session
    LaunchedEffect(session?.id, session?.complete) {
        while (model.session?.let { it.mode == "exam" && !it.complete } == true) { model.tick(); delay(250) }
    }
    val back = {
        when {
            model.session == null -> onBack()
            model.session?.complete == true && model.reviewingResult -> model.returnToSummary()
            model.session?.complete == true -> model.closeResult()
            else -> model.showFinish = true
        }
    }
    BackHandler(onBack = back)
    when {
        session == null -> FinalSetup(model, onBack)
        session.complete && !model.reviewingResult -> FinalResults(model)
        else -> key(session.id, session.index) { FinalQuestionPage(model, back) }
    }
    if (model.showFinish) AlertDialog(onDismissRequest = { model.showFinish = false },
        title = { Text("پایان این نوبت؟") },
        text = { Text("نوشته‌های شما ذخیره می‌شوند. سؤال‌های بدون پاسخ، نزده ثبت می‌شوند و پاسخ‌های استاندارد پس از پایان در دسترس خواهند بود.") },
        confirmButton = { TextButton(onClick = model::finish, modifier = Modifier.semantics { contentDescription = "finals-confirm-finish" }) { Text("پایان و دیدن کارنامه") } },
        dismissButton = { TextButton(onClick = { model.showFinish = false }) { Text("ادامه می‌دهم") } })
}

@Composable
private fun FinalColumn(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp), content = content)

@Composable
private fun FinalCard(content: @Composable ColumnScope.() -> Unit) = Surface(
    shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
    modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinalSetup(model: FinalStudyViewModel, onBack: () -> Unit) {
    FinalColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("آمادگی نهایی", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("بازگشت ←") }
        }
        FinalCard {
            Text("فکر کن، بنویس، خودت را بسنج", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("پاسخ تشریحی خودت را بنویس و با معیارهای روشن، امتیاز بده. این بخش کاملاً روی همین دستگاه و بدون اینترنت کار می‌کند.")
            Text("${fa(model.bank.size)} سؤال • ${fa(model.attempts.values.map { it.questionId }.distinct().size)} سؤال تمرین‌شده • ${fa(model.weakIds.size)} سؤال برای مرور", style = MaterialTheme.typography.bodySmall)
            if (model.bank.isNotEmpty() && model.bank.all { it.source.kind == "authored" }) Text("سؤال‌های این بسته تألیفی و بر پایهٔ کتاب‌اند؛ آرشیو رسمی نهایی هنوز کامل نشده است. پاسخ‌ها و بارم‌ها پیشنهادی‌اند.", style = MaterialTheme.typography.bodySmall)
            Text("سؤال رسمی تأییدشده در این نسخه: ${fa(model.bank.count { it.source.kind == "official_final" })}", style = MaterialTheme.typography.bodySmall)
            if (model.bank.isEmpty()) Text("در این نسخه، سؤال تشریحی در بانک داخلی وجود ندارد.")
        }
        FinalCard {
            Text("شیوهٔ تمرین", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = model.mode == "practice", onClick = { model.chooseMode("practice") }, label = { Text("تمرین آموزشی") }, modifier = Modifier.semantics { contentDescription = "finals-mode-practice" })
                FilterChip(selected = model.mode == "exam", onClick = { model.chooseMode("exam") }, label = { Text("آزمون زمان‌دار") }, modifier = Modifier.semantics { contentDescription = "finals-mode-exam" })
            }
            Text(if (model.mode == "exam") "پاسخ استاندارد و بارم فقط پس از پایان آزمون نمایش داده می‌شوند. زمان در پس‌زمینه هم می‌گذرد." else "پاسخ استاندارد پس از ثبت تلاش شما نمایش داده می‌شود؛ سپس با بارم پیشنهادی خودارزیابی کنید.", style = MaterialTheme.typography.bodySmall)
            Text("انتخاب سؤال‌ها", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("all" to "همه", "weak" to "مرور ضعف‌ها", "bookmarked" to "نشان‌شده‌ها").forEach { (value, title) ->
                    FilterChip(model.reviewFilter == value, { model.chooseReview(value) }, { Text(title) }, modifier = Modifier.semantics { contentDescription = "finals-review-$value" })
                }
            }
            Text("سؤال‌های نزده و تلاش‌های بدون خودارزیابی نیز در مرور ضعف‌ها قرار می‌گیرند.", style = MaterialTheme.typography.bodySmall)
        }
        FinalCard {
            Text("پایه و فصل", fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 10, 11, 12).forEach { grade -> FilterChip(model.grade == grade, { model.chooseGrade(grade) }, { Text(if (grade == 0) "همهٔ پایه‌ها" else gradeTitle(grade)) }) }
            }
            if (model.grade != 0) FinalChoiceMenu("فصل", if (model.chapter == 0) "همهٔ فصل‌ها" else chapterTitle(model.grade, model.chapter),
                listOf(0 to "همهٔ فصل‌ها") + chapterTitles.getValue(model.grade).mapIndexed { index, title -> (index + 1) to title }) { model.chooseChapter(it) }
            FinalChoiceMenu("مبحث", model.topic.ifEmpty { "همهٔ مباحث" }, listOf("" to "همهٔ مباحث") + model.availableTopics.map { it to it }) { model.chooseTopic(it) }
            FinalChoiceMenu("نوع سؤال", finalTypeTitles[model.type] ?: "همهٔ نوع‌ها", listOf("" to "همهٔ نوع‌ها") + finalTypeTitles.toList()) { model.chooseType(it) }
        }
        FinalCard {
            Text("${fa(model.eligible.size)} سؤال با این فیلترها", fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "finals-eligible" })
            Text("تعداد سؤال", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 5, 10, 20, model.bank.size).filter { it > 0 }.distinct().forEach { n -> FilterChip(model.count == n, { model.chooseCount(n) }, { Text(if (n == model.bank.size) "همه" else fa(n)) }) }
            }
            Text("تعداد نهایی: ${fa(minOf(model.count, model.eligible.size))}؛ سؤال‌های تمرین‌نشده اولویت دارند.", style = MaterialTheme.typography.bodySmall)
            if (model.mode == "exam") {
                Text("زمان آزمون", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 20, 30, 60).forEach { n -> FilterChip(model.minutes == n, { model.chooseMinutes(n) }, { Text("${fa(n)} دقیقه") }) }
                }
            }
            Button(onClick = model::start, enabled = model.eligible.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = "finals-start" }) { Text("شروع ${if (model.mode == "exam") "آزمون" else "تمرین"}") }
            if (model.eligible.isEmpty()) Text("در این انتخاب سؤالی نیست؛ فیلترها را تغییر بدهید.")
        }
    }
}

@Composable
private fun <T> FinalChoiceMenu(label: String, selected: String, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp)) { Text("$label: $selected  ▾") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(value) }) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinalQuestionPage(model: FinalStudyViewModel, onBack: () -> Unit) {
    val q = model.current ?: return
    val s = model.session ?: return
    val a = model.currentAttempt ?: return
    val editable = if (s.mode == "exam") !s.complete else !a.submitted
    FinalColumn {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${if (s.mode == "exam") "آزمون تشریحی" else "تمرین تشریحی"} • ${fa(s.index + 1)} از ${fa(s.questionIds.size)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${gradeTitle(q.grade)} • ${chapterTitle(q.grade, q.chapter)}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onBack) { Text(if (s.complete) "کارنامه" else "پایان") }
        }
        if (s.mode == "exam" && !s.complete) Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp)) {
            Text("◷ زمان باقی‌مانده ${finalTimer(s.remainingMillis)}", Modifier.fillMaxWidth().padding(14.dp).semantics { contentDescription = "finals-timer" }, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(progress = { (s.index + 1f) / s.questionIds.size }, modifier = Modifier.fillMaxWidth())
        FinalCard {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(finalTypeTitles[q.type] ?: q.type, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = model::toggleBookmark, modifier = Modifier.semantics { contentDescription = "finals-bookmark" }) { Text(if (q.id in model.bookmarks) "★ نشان‌شده" else "☆ نشان‌کردن") }
            }
            Text(q.stem, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { contentDescription = "finals-stem" })
            q.image?.let { FinalDiagram(it, q.imageDescription) }
            Text("${q.source.label} • ${fa(finalPoint(q.totalPoints))} امتیاز ${if (q.marking == "official") "رسمی" else "پیشنهادی"}", style = MaterialTheme.typography.bodySmall)
            Text("${q.source.book}؛ ${q.source.section}", style = MaterialTheme.typography.bodySmall)
            if (q.source.kind != "authored") Text(listOf(q.source.year, q.source.session).filter { it.isNotBlank() }.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
        }
        FinalCard {
            Text("پاسخ شما", fontWeight = FontWeight.Bold)
            if (q.type == "true_false") {
                Text("درستی عبارت را مشخص کنید و اگر نادرست است، صورت درست آن را بنویسید.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "درست", false to "نادرست").forEach { (value, label) ->
                        FilterChip(a.truthChoice == value, { model.chooseTruth(value) }, { Text(label) }, enabled = editable, modifier = Modifier.semantics { contentDescription = "finals-truth-$value" })
                    }
                }
            }
            if (editable) OutlinedTextField(value = a.response, onValueChange = model::editResponse,
                label = { Text(finalResponseLabel(q.type)) }, minLines = if (q.type == "essay") 5 else 3,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "finals-response" })
            else Text(a.response.ifBlank { if (a.truthChoice == true) "درست" else if (a.truthChoice == false) "نادرست؛ اصلاحی نوشته نشده است." else "پاسخی نوشته نشده است." }, modifier = Modifier.semantics { contentDescription = "finals-saved-response" })
            if (editable) Text("${fa(a.response.length)} از ${fa(5000)} نویسه؛ نوشتهٔ شما روی همین دستگاه ذخیره می‌شود.", style = MaterialTheme.typography.bodySmall)
            if (s.mode == "practice" && !a.submitted) Button(onClick = model::submit, enabled = model.canSubmit(), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = "finals-submit" }) { Text("ثبت پاسخ و دیدن راهنما") }
            if (s.mode == "exam" && !s.complete) Text("پاسخ استاندارد تا پایان آزمون پنهان است. می‌توانید بین سؤال‌ها رفت‌وبرگشت کنید.", style = MaterialTheme.typography.bodySmall)
        }
        if (model.canReveal) FinalSolution(model, q, a)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = model::previous, enabled = s.index > 0, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("قبلی") }
            Button(onClick = {
                if (s.mode == "exam" && !s.complete && model.canSubmit()) model.submit()
                model.next()
            }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { contentDescription = "finals-next" }) {
                Text(if (s.index == s.questionIds.lastIndex) if (s.complete) "کارنامه" else "پایان نوبت" else if (a.hasResponse) "بعدی" else "نزده و بعدی")
            }
        }
    }
}

@Composable
private fun FinalDiagram(path: String, description: String) {
    val context = LocalContext.current
    val bitmap = remember(path) { runCatching { StudyAssets.decodeBitmap(context, path) }.getOrNull() }
    if (bitmap != null) Surface(color = androidx.compose.ui.graphics.Color.White, shape = RoundedCornerShape(12.dp)) {
        Image(bitmap.asImageBitmap(), description, Modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height).padding(8.dp))
    } else Text("تصویر این سؤال قابل نمایش نیست؛ این سؤال را رد کنید.")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinalSolution(model: FinalStudyViewModel, q: FinalQuestion, a: FinalAttempt) {
    FinalCard {
        Text("پاسخ استاندارد", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "finals-standard-answer" })
        Text(q.answer, style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider()
        Text(if (q.marking == "official") "راهنمای تصحیح رسمی" else "راهنمای تصحیح پیشنهادی", fontWeight = FontWeight.Bold)
        Text("این امتیاز خودارزیابی شماست. متن پاسخ شما به‌صورت خودکار با پاسخ استاندارد تطبیق داده نمی‌شود.", style = MaterialTheme.typography.bodySmall)
        q.rubric.forEach { row ->
            Text("${row.criterion} (${fa(finalPoint(row.points))} امتیاز)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.0 to "کسب نشده", row.points / 2 to "نیمه", row.points to "کامل").forEach { (score, title) ->
                    FilterChip(a.scores[row.id] == score, { model.assess(row.id, score) }, { Text("$title: ${fa(finalPoint(score))}") }, enabled = a.hasResponse,
                        modifier = Modifier.semantics { contentDescription = "finals-rubric-${row.id}-${if (score == 0.0) "zero" else if (score == row.points) "full" else "half"}" })
                }
            }
        }
        val score = finalAssessment(a, q)
        Text(if (!a.hasResponse) "این سؤال نزده است؛ برای تلاش دوباره در مرور ضعف‌ها می‌ماند." else if (score == null) "برای ثبت خودارزیابی، امتیاز هر معیار را انتخاب کنید." else "امتیاز خودارزیابی: ${fa(finalPoint(score))} از ${fa(finalPoint(q.totalPoints))}", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun FinalResults(model: FinalStudyViewModel) {
    val s = model.session ?: return
    val questions = s.questionIds.mapNotNull { id -> model.bank.firstOrNull { it.id == id } }
    val answered = questions.count { s.attempt(it.id).hasResponse }
    val assessed = questions.filter { finalAssessment(s.attempt(it.id), it) != null && s.attempt(it.id).hasResponse }
    val scored = assessed.sumOf { finalAssessment(s.attempt(it.id), it) ?: 0.0 }
    val assessedTotal = assessed.sumOf { it.totalPoints }
    FinalColumn {
        Text("کارنامهٔ تشریحی", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "finals-result" })
        FinalCard {
            Text("پاسخ‌هایت ذخیره شدند", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text("${fa(answered)} پاسخ‌داده • ${fa(questions.size - answered)} نزده • ${fa(assessed.size)} خودارزیابی‌شده")
            Text("امتیاز بخش خودارزیابی‌شده: ${fa(finalPoint(scored))} از ${fa(finalPoint(assessedTotal))}")
            Text("در این بخش پاسخ نوشتاری نمره‌دهی خودکار ندارد. ابتدا پاسخ و بارم هر سؤال را ببینید و معیارها را ارزیابی کنید.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { model.reviewResult() }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = "finals-review-answers" }) { Text("پاسخ‌ها و خودارزیابی") }
        }
        FinalCard {
            Text("عملکرد هر مبحث", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            questions.groupBy { it.topic }.forEach { (topic, rows) ->
                val attempted = rows.count { s.attempt(it.id).hasResponse }
                val weak = rows.count { finalNeedsReview(s.attempt(it.id), it) }
                Text("$topic: ${fa(attempted)} پاسخ از ${fa(rows.size)}؛ ${fa(weak)} نیازمند مرور")
            }
        }
        questions.forEachIndexed { index, q ->
            val a = s.attempt(q.id)
            val score = finalAssessment(a, q)
            OutlinedButton(onClick = { model.reviewResult(index) }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("${fa(index + 1)}. ${q.topic}", fontWeight = FontWeight.Bold)
                    Text(if (!a.hasResponse) "○ نزده" else if (score == null) "✎ منتظر خودارزیابی" else "✓ خودارزیابی: ${fa(finalPoint(score))} از ${fa(finalPoint(q.totalPoints))}")
                }
            }
        }
        Button(onClick = model::closeResult, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("تمرین تازه") }
    }
}

private fun finalResponseLabel(type: String): String = when (type) {
    "true_false" -> "اصلاح عبارت یا دلیل شما"
    "fill_blank" -> "واژه‌ها را به ترتیب جاهای خالی بنویسید"
    "definition_comparison" -> "تعریف و تفاوت‌ها یا شباهت‌ها"
    "diagram_label" -> "پاسخ برچسب‌ها و تحلیل شکل"
    "matching" -> "جفت‌ها را با شماره یا حرف بنویسید"
    else -> "پاسخ خود را اینجا بنویسید"
}
fun finalPoint(value: Double): String = String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
private fun finalTimer(millis: Long): String {
    val seconds = (millis + 999) / 1000
    return fa("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
}
