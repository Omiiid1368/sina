package ir.hezarzist

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import java.time.LocalDate

@Composable
fun StudyTimeScreen(onBack: () -> Unit, onOpenBackup: (() -> Unit)? = null) {
    val context = LocalContext.current
    val version = LocalProgress.dataVersion
    val store = remember(context, version) { StudyTimeStore(context) }
    var state by remember(store) { mutableStateOf(runCatching { store.snapshot() }.getOrDefault(StudyTimeState())) }
    var now by remember(store) { mutableStateOf(store.now()) }
    var status by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf(0) }
    var historyPage by remember { mutableStateOf(0) }
    var subject by remember { mutableStateOf("biology") }
    var note by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StudyRecord?>(null) }
    var deleting by remember { mutableStateOf<StudyRecord?>(null) }
    var goalDialog by remember { mutableStateOf(false) }
    var calendar by remember { mutableStateOf(false) }
    var selectedDate by remember(store) { mutableStateOf(store.today()) }
    fun refresh() { state = store.snapshot(); now = store.now() }
    fun act(action: () -> Unit) {
        try { action(); refresh() } catch (e: Exception) { status = e.message ?: "ثبت انجام نشد؛ دوباره تلاش کن." }
    }
    LaunchedEffect(store) {
        var ticks = 0
        while(true) {
            try {
                if(state.active?.running == true && ++ticks % 15 == 0) store.checkpoint()
                refresh()
            } catch (e: Exception) { status = e.message ?: "دادهٔ زمان مطالعه نیاز به بررسی دارد." }
            delay(1000)
        }
    }
    DisposableEffect(store, context) {
        val lifecycle = (context as? ComponentActivity)?.lifecycle
        val observer = LifecycleEventObserver { _, event -> if(event == Lifecycle.Event.ON_STOP) runCatching { store.checkpoint() } }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer); runCatching { store.checkpoint() } }
    }
    val activeRecord = state.active?.let { StudyTimeEngine.complete(it, now) }
    val measured = state.records + listOfNotNull(activeRecord)
    val totals = studyDailyTotals(measured)
    val bioTotals = studyDailyTotals(measured, "biology")
    val today = store.today()
    val weekStart = StudyCalendar.weekStart(today)
    val monthStart = StudyCalendar.monthStart(today)
    val nextMonth = StudyCalendar.nextMonthStart(today)
    val todayTotal = totals[today] ?: 0
    val weekTotal = totals.filterKeys { !it.isBefore(weekStart) && it.isBefore(weekStart.plusDays(7)) }.values.sum()
    val monthTotal = totals.filterKeys { !it.isBefore(monthStart) && it.isBefore(nextMonth) }.values.sum()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "study-back" }) { Text("بازگشت") }
        Text("زمان مطالعه", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        Text("مطالعهٔ خودت را ثبت کن؛ زمان بازی به این مجموع اضافه نمی‌شود.", style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudyMetric("امروز", todayTotal, Modifier.weight(1f))
            StudyMetric("این هفته", weekTotal, Modifier.weight(1f))
            StudyMetric("این ماه", monthTotal, Modifier.weight(1f))
        }
        Text("${StudyCalendar.formatJalali(today)} • هفته از شنبه • ${StudyCalendar.monthTitle(today)}", style = MaterialTheme.typography.labelMedium)
        Text("مجموع‌ها به ساعت:دقیقه نمایش داده می‌شوند.", style = MaterialTheme.typography.labelSmall)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("هدف روزانه", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { goalDialog = true }, modifier = Modifier.semantics { contentDescription = "study-goal" }) { Text(if(state.goalMinutes == 0) "تعیین هدف" else "${fa(state.goalMinutes)} دقیقه • ویرایش") }
                }
                if(state.goalMinutes > 0) {
                    val progress = (todayTotal.toDouble() / (state.goalMinutes * 60_000L)).toFloat().coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${fa((todayTotal * 100 / (state.goalMinutes * 60_000L)).coerceAtMost(100))}٪ از هدف امروز", style = MaterialTheme.typography.labelMedium)
                }
                Text("زیست امروز: ${studyDurationLabel(bioTotals[today] ?: 0)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        if(status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = "study-status" })
        TabRow(selectedTabIndex = tab) {
            listOf("زمان‌سنج", "جلسه‌ها", "گزارش").forEachIndexed { index, title -> Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) }, modifier = Modifier.semantics { contentDescription = "study-tab-$index" }) }
        }
        when(tab) {
            0 -> {
                val active = state.active
                if(active == null) {
                    StudySubjectPicker(subject, { subject = it })
                    OutlinedTextField(value = note, onValueChange = { note = it.take(1000) }, label = { Text("یادداشت (اختیاری)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { act { store.start(subject, note); status = "جلسه شروع شد." } }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "study-start" }) { Text("▶ شروع مطالعه") }
                } else {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(studySubjectTitles.getValue(active.subjectId), fontWeight = FontWeight.Bold)
                            Text(studyDurationLabel(StudyTimeEngine.duration(active, now), true), fontSize = 35.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { contentDescription = "study-timer" })
                            Text(if(active.running) "● در حال مطالعه" else "Ⅱ مکث؛ این زمان محاسبه نمی‌شود", style = MaterialTheme.typography.labelLarge)
                            if(active.note.isNotBlank()) Text(active.note, style = MaterialTheme.typography.bodySmall)
                            if(active.needsReview) Text("جلسه بازیابی شده یا ساعت دستگاه تغییر کرده است. مدت ثبت‌شده را بررسی کن؛ پس از پایان می‌توانی آن را ویرایش کنی.", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = { act { if(active.running) store.pause() else store.resume() } }, modifier = Modifier.weight(1f).semantics { contentDescription = "study-pause-resume" }) { Text(if(active.running) "Ⅱ مکث" else "▶ ادامه") }
                                Button(onClick = { act { val row = store.finish(active.id); status = if(row == null) "جلسهٔ بدون زمان پایان یافت." else "✓ ${studyDurationLabel(row.durationMillis)} ثبت شد." } }, modifier = Modifier.weight(1f).semantics { contentDescription = "study-finish" }) { Text("■ پایان و ثبت") }
                            }
                        }
                    }
                    Text("با خروج از صفحه یا خاموش‌شدن نمایشگر، زمان‌سنج ادامه دارد. برای توقف موقت، «مکث» را بزن. پس از راه‌اندازی دوبارهٔ گوشی، زمان ثبت‌شده حفظ و جلسه مکث می‌شود؛ فاصلهٔ نامطمئن به مطالعه اضافه نمی‌شود.", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "study-manual" }) { Text("＋ ثبت دستی مطالعه") }
            }
            1 -> {
                OutlinedButton(onClick = { manual = true }, modifier = Modifier.fillMaxWidth()) { Text("＋ ثبت دستی جلسه") }
                Text("${fa(state.records.size)} جلسهٔ ثبت‌شده", fontWeight = FontWeight.Bold)
                if(state.records.isEmpty()) Text("هنوز جلسه‌ای ثبت نشده؛ از ثبت دستی یا زمان‌سنج شروع کن.")
                val lastPage = (state.records.size - 1).coerceAtLeast(0) / 50
                val page = historyPage.coerceAtMost(lastPage)
                state.records.sortedByDescending { it.createdAt }.drop(page * 50).take(50).forEach { record ->
                    StudyRecordCard(record, onEdit = { editing = record }, onDelete = { deleting = record })
                }
                if(lastPage > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { historyPage = (page - 1).coerceAtLeast(0) }, enabled = page > 0) { Text("صفحهٔ قبل") }
                    Text("${fa(page + 1)} از ${fa(lastPage + 1)}", Modifier.weight(1f), textAlign = TextAlign.Center)
                    TextButton(onClick = { historyPage = page + 1 }, enabled = page < lastPage) { Text("صفحهٔ بعد") }
                }
            }
            2 -> {
                Text("مطالعهٔ هفت روز اخیر", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                StudyChart(today, totals) { selectedDate = it }
                OutlinedButton(onClick = { calendar = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "study-calendar" }) { Text("تقویم شمسی • ${StudyCalendar.formatJalali(selectedDate)}") }
                Text("مطالعهٔ این روز: ${studyDurationLabel(totals[selectedDate] ?: 0)}", fontWeight = FontWeight.Bold)
                Text("زیست این روز: ${studyDurationLabel(bioTotals[selectedDate] ?: 0)}", color = MaterialTheme.colorScheme.primary)
                studySubjectTitles.forEach { (id, title) ->
                    val value = studyDailyTotals(measured, id)[selectedDate] ?: 0
                    if(value > 0) Row(Modifier.fillMaxWidth()) { Text(title, Modifier.weight(1f)); Text(studyDurationLabel(value), style = MaterialTheme.typography.bodySmall) }
                }
                state.records.filter { selectedDate in studyDailyAllocation(it) }.sortedByDescending { it.createdAt }.forEach { record -> StudyRecordCard(record, { editing = record }, { deleting = record }) }
                Text("جلسه‌های عبوری از نیمه‌شب، بر اساس ساعت تهران میان روزها تقسیم می‌شوند. زمان مکث محاسبه نمی‌شود.", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        if(onOpenBackup != null) OutlinedButton(onClick = { act { store.checkpoint() }; onOpenBackup() }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "study-backup" }) { Text("پشتیبان‌گیری و بازیابی با فایل محلی") }
        else Text("پشتیبان‌گیری و بازیابیِ زمان مطالعه در «نمایه و پشتیبان محلی» در دسترس است.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
    }
    if(manual || editing != null) StudyEntryDialog(store.today(), editing, onDismiss = { manual = false; editing = null }) { date, selectedSubject, duration, entryNote ->
        act { if(editing == null) store.addManual(date, selectedSubject, duration, entryNote) else store.edit(editing!!.id, date, selectedSubject, duration, entryNote); manual = false; editing = null; status = "✓ جلسه ذخیره شد." }
    }
    deleting?.let { record -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("این جلسه حذف شود؟") }, text = { Text("${studySubjectTitles.getValue(record.subjectId)} • ${StudyCalendar.formatJalali(record.firstDate)} • ${studyDurationLabel(record.durationMillis)}") }, confirmButton = { TextButton(onClick = { act { store.delete(record.id); deleting = null; status = "جلسه حذف شد." } }, modifier = Modifier.semantics { contentDescription = "study-confirm-delete" }) { Text("حذف جلسه") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("نگه می‌دارم") } }) }
    if(goalDialog) StudyGoalDialog(state.goalMinutes, { goalDialog = false }) { minutes -> act { store.setGoal(minutes); goalDialog = false } }
    if(calendar) StudyCalendarDialog(selectedDate, { calendar = false }) { selectedDate = it; calendar = false }
}

@Composable
private fun StudyMetric(title: String, millis: Long, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 5.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${fa(millis / 3_600_000)}:${fa((millis / 60_000 % 60).toString().padStart(2, '0'))}", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
            Text(title, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun StudySubjectPicker(selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "study-subject" }) { Text("درس: ${studySubjectTitles[selected] ?: "انتخاب کن"}  ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { studySubjectTitles.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { onSelect(id); open = false }) } }
    }
}

@Composable
private fun StudyRecordCard(record: StudyRecord, onEdit: () -> Unit, onDelete: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${studySubjectTitles.getValue(record.subjectId)} • ${studyDurationLabel(record.durationMillis)}", fontWeight = FontWeight.Bold)
            Text("${StudyCalendar.formatJalali(record.firstDate)} • ${when(record.source) { "timer" -> "زمان‌سنج"; "edited_timer" -> "زمان‌سنج؛ زمان ویرایش‌شده"; else -> "ثبت دستی" }}", style = MaterialTheme.typography.labelMedium)
            if(record.durationMillis % 60_000L != 0L) Text("زمان دقیق: ${studyDurationLabel(record.durationMillis, true)}", style = MaterialTheme.typography.labelSmall)
            if(record.note.isNotBlank()) Text(record.note, style = MaterialTheme.typography.bodySmall)
            if(record.needsReview) Text("جلسهٔ بازیابی‌شده؛ مدت را بازبینی کن.", style = MaterialTheme.typography.labelSmall)
            if(studyDailyAllocation(record).size > 1) Text("این جلسه بین ${fa(studyDailyAllocation(record).size)} روز تقسیم شده است.", style = MaterialTheme.typography.labelSmall)
            Row { TextButton(onClick = onEdit, modifier = Modifier.semantics { contentDescription = "study-edit-${record.id}" }) { Text("ویرایش") }; TextButton(onClick = onDelete, modifier = Modifier.semantics { contentDescription = "study-delete-${record.id}" }) { Text("حذف") } }
        }
    }
}

@Composable
private fun StudyEntryDialog(today: LocalDate, editing: StudyRecord?, onDismiss: () -> Unit, onSave: (LocalDate, String, Long, String) -> Unit) {
    var date by remember { mutableStateOf(StudyCalendar.formatJalali(editing?.firstDate ?: today)) }
    var subject by remember { mutableStateOf(editing?.subjectId ?: "biology") }
    val originalHours = (editing?.durationMillis?.div(3_600_000) ?: 0).toString()
    val originalMinutes = (editing?.durationMillis?.div(60_000)?.rem(60) ?: 0).toString()
    var hours by remember { mutableStateOf(originalHours) }; var minutes by remember { mutableStateOf(originalMinutes) }
    var note by remember { mutableStateOf(editing?.note ?: "") }
    var error by remember { mutableStateOf("") }; var calendar by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if(editing == null) "ثبت دستی مطالعه" else "ویرایش جلسه") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = date, onValueChange = { date = it.take(16) }, label = { Text("تاریخ شمسی: سال/ماه/روز") }, singleLine = true, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "study-entry-date" })
            TextButton(onClick = { calendar = true }) { Text("انتخاب از تقویم شمسی") }
            StudySubjectPicker(subject) { subject = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = fa(hours), onValueChange = { hours = studyDigits(it).take(3) }, label = { Text("ساعت") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f).semantics { contentDescription = "study-entry-hours" })
                OutlinedTextField(value = fa(minutes), onValueChange = { minutes = studyDigits(it).take(2) }, label = { Text("دقیقه") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.weight(1f).semantics { contentDescription = "study-entry-minutes" })
            }
            OutlinedTextField(value = note, onValueChange = { note = it.take(1000) }, label = { Text("یادداشت اختیاری") }, modifier = Modifier.fillMaxWidth())
            if(editing?.source == "timer") Text("زمان دقیق: ${studyDurationLabel(editing.durationMillis, true)}. با تغییر تاریخ یا مدت، کل زمان به روز انتخاب‌شده منتقل می‌شود؛ ویرایش فقط درس و یادداشت، تقسیم قبلی روزها را حفظ می‌کند.", style = MaterialTheme.typography.bodySmall)
            if(error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val selected = StudyCalendar.parseJalali(date)
            require(selected.year in 1900..2199) { "تاریخ انتخاب‌شده خارج از محدودهٔ ثبت است." }
            val h = hours.toIntOrNull() ?: 0; val m = minutes.toIntOrNull() ?: 0
            val unchangedTime = editing != null && selected == editing.firstDate && hours == originalHours && minutes == originalMinutes
            require(unchangedTime || (h in 0..24 && m in 0..59 && h * 60 + m in 1..1440)) { "مدت را بین یک دقیقه و ۲۴ ساعت وارد کن." }
            onSave(selected, subject, if(unchangedTime) editing!!.durationMillis else (h * 60L + m) * 60_000, note)
        } catch(e: Exception) { error = e.message ?: "تاریخ یا مدت معتبر نیست." }
    }, modifier = Modifier.semantics { contentDescription = "study-entry-save" }) { Text("ذخیره") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } })
    if(calendar) StudyCalendarDialog(runCatching { StudyCalendar.parseJalali(date) }.getOrDefault(today), { calendar = false }) { date = StudyCalendar.formatJalali(it); calendar = false }
}

@Composable
private fun StudyGoalDialog(initial: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember { mutableStateOf(initial.toString()) }; var error by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("هدف روزانه") }, text = { Column { OutlinedTextField(value = fa(value), onValueChange = { value = studyDigits(it).take(4) }, label = { Text("دقیقه در روز؛ صفر یعنی بدون هدف") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)); if(error) Text("عددی بین صفر و ۱۴۴۰ وارد کن.", color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { val n = value.toIntOrNull(); if(n != null && n in 0..1440) onSave(n) else error = true }) { Text("ذخیره") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } })
}

@Composable
private fun StudyChart(today: LocalDate, totals: Map<LocalDate, Long>, onSelect: (LocalDate) -> Unit) {
    val dates = (6 downTo 0).map { today.minusDays(it.toLong()) }
    val max = dates.maxOf { totals[it] ?: 0 }.coerceAtLeast(60_000)
    Row(Modifier.fillMaxWidth().height(180.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        dates.forEach { date ->
            val value = totals[date] ?: 0
            Column(Modifier.weight(1f).fillMaxHeight().clickable { onSelect(date) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(fa(value / 60_000), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
                Box(Modifier.fillMaxWidth(.75f).height((value.toFloat() / max * 115).coerceAtLeast(3f).dp).clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.height(7.dp))
                Text(fa(StudyCalendar.gregorianToJalali(date).day), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    Text("عدد بالای ستون: دقیقه • عدد پایین: روز شمسی • برای دیدن جزئیات روی روز بزن.", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun StudyCalendarDialog(selected: LocalDate, onDismiss: () -> Unit, onSelect: (LocalDate) -> Unit) {
    var month by remember { mutableStateOf(StudyCalendar.monthStart(selected)) }
    val next = StudyCalendar.nextMonthStart(month)
    val offset = ((month.dayOfWeek.value + 1) % 7)
    val count = java.time.temporal.ChronoUnit.DAYS.between(month, next).toInt()
    AlertDialog(onDismissRequest = onDismiss, title = { Text("تقویم شمسی") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { month = StudyCalendar.monthStart(month.minusDays(1)) }) { Text("ماه قبل") }
                Text(StudyCalendar.monthTitle(month), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.Bold)
                TextButton(onClick = { month = next }) { Text("ماه بعد") }
            }
            Row { listOf("ش", "ی", "د", "س", "چ", "پ", "ج").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium) } }
            (0 until (offset + count + 6) / 7).forEach { row ->
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { col ->
                        val index = row * 7 + col - offset
                        if(index !in 0 until count) Spacer(Modifier.weight(1f).height(44.dp))
                        else {
                            val day = month.plusDays(index.toLong())
                            Surface(onClick = { onSelect(day) }, color = if(day == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(44.dp)) { Box(contentAlignment = Alignment.Center) { Text(fa(index + 1), style = MaterialTheme.typography.labelMedium) } }
                        }
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } })
}

private fun studyDigits(value: String): String = value.map { when(it) { in '۰'..'۹' -> '0' + (it - '۰'); in '٠'..'٩' -> '0' + (it - '٠'); else -> it } }.filter { it in '0'..'9' }.joinToString("")
