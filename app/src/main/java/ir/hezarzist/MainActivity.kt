package ir.hezarzist

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

private val Forest = Color(0xFF073F36)
private val Emerald = Color(0xFF146D57)
private val Lime = Color(0xFFC5EF85)
private val Coral = Color(0xFFB33536)
private val Amber = Color(0xFF985C06)
private val Page = Color(0xFFF4F7F1)

private fun zistTypography(): Typography {
    val font = FontFamily(Font(R.font.zist_regular), Font(R.font.zist_bold, FontWeight.Bold))
    val t = Typography()
    return t.copy(
        displayLarge = t.displayLarge.copy(fontFamily = font), displayMedium = t.displayMedium.copy(fontFamily = font), displaySmall = t.displaySmall.copy(fontFamily = font),
        headlineLarge = t.headlineLarge.copy(fontFamily = font), headlineMedium = t.headlineMedium.copy(fontFamily = font), headlineSmall = t.headlineSmall.copy(fontFamily = font),
        titleLarge = t.titleLarge.copy(fontFamily = font), titleMedium = t.titleMedium.copy(fontFamily = font), titleSmall = t.titleSmall.copy(fontFamily = font),
        bodyLarge = t.bodyLarge.copy(fontFamily = font, lineHeight = 29.sp), bodyMedium = t.bodyMedium.copy(fontFamily = font, lineHeight = 25.sp), bodySmall = t.bodySmall.copy(fontFamily = font, lineHeight = 23.sp),
        labelLarge = t.labelLarge.copy(fontFamily = font), labelMedium = t.labelMedium.copy(fontFamily = font), labelSmall = t.labelSmall.copy(fontFamily = font)
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        LocalBackup.recoverInterruptedRestore(applicationContext)
        LocalBackup.prepareOfflineUpgrade(applicationContext)
        LocalProgress.initialize(applicationContext)
        setContent { ZistApp() }
    }

    override fun onStop() {
        super.onStop()
        ViewModelProvider(this)[GameViewModel::class.java].flushProgress()
    }
}

@Composable
fun ZistApp(model: GameViewModel = viewModel()) {
    val localVersion = LocalProgress.dataVersion
    LaunchedEffect(localVersion) { model.reloadProfile() }
    val density = LocalDensity.current
    val colors = if(model.settings.dark) darkColorScheme(primary = Lime, secondary = Color(0xFF8DDDBD), background = Color(0xFF101E1A), surface = Color(0xFF192C24), surfaceVariant = Color(0xFF213C32), onPrimary = Forest, onSurface = Color(0xFFECF3E9), onBackground = Color(0xFFECF3E9))
        else lightColorScheme(primary = Emerald, secondary = Forest, background = Page, surface = Color.White, surfaceVariant = Color(0xFFE7EFE4), onPrimary = Color.White, onSurface = Color(0xFF182B23), onBackground = Color(0xFF182B23))
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, LocalDensity provides Density(density.density, density.fontScale * model.settings.textScale)) {
        MaterialTheme(colorScheme = colors, typography = remember { zistTypography() }) {
            Surface(Modifier.fillMaxSize(), color = colors.background) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if(model.route != "question" && model.route != "finals") AppHeader(model)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when(model.route) {
                            "home" -> HomeScreen(model)
                            "setup" -> SetupScreen(model)
                            "question" -> QuestionScreen(model)
                            "result" -> ResultScreen(model)
                            "answers" -> AnswersScreen(model)
                            "review" -> ReviewScreen(model)
                            "stats" -> StatsScreen(model)
                            "settings" -> SettingsScreen(model)
                            "offline-tools" -> OfflineToolsScreen(onBack = { model.route = "home" })
                            "study-time" -> key(localVersion) { StudyTimeScreen(onBack = { model.route = "home" }, onOpenBackup = { model.route = "offline-tools" }) }
                            "about-creator" -> AboutCreatorScreen(onBack = { model.route = "home" })
                            "finals" -> key(localVersion) { FinalStudyScreen(onBack = { model.route = "home" }) }
                        }
                    }
                    if(model.route in listOf("home", "review", "stats", "settings")) BottomBar(model)
                }
                if(model.showExit) AlertDialog(onDismissRequest = { model.showExit = false }, title = { Text("پایان این نوبت؟") }, text = { Text("سؤال‌های باقی‌مانده نزده ثبت می‌شوند. پیشرفت پاسخ‌های فعلی ذخیره شده است.") }, confirmButton = { TextButton(onClick = { model.finish() }, modifier = Modifier.semantics { contentDescription = "confirm-finish" }) { Text("پایان و دیدن نتیجه") } }, dismissButton = { TextButton(onClick = { model.showExit = false }) { Text("ادامه می‌دهم") } })
            }
            BackHandler(enabled = model.route != "home" && model.route != "finals") { model.back() }
        }
    }
}

@Composable
private fun AppHeader(model: GameViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(37.dp).clip(RoundedCornerShape(12.dp)).background(Forest), contentAlignment = Alignment.Center) { Text("✦", color = Lime, fontSize = 24.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("۱۰۰۰ زیست", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("هر سؤال، یک قدم جلوتر", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if(model.route == "home") Pill("آفلاین", "●") else TextButton(onClick = { model.back() }, modifier = Modifier.semantics { contentDescription = "navigate-back" }) { Text("بازگشت ←") }
    }
}

@Composable
private fun PageColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
}

@Composable
private fun HomeScreen(model: GameViewModel) {
    val veryDifficultCount = veryDifficultQuestionCount(model.questions)
    val authoredCount = model.questions.count { it.origin.kind in setOf("authored", "authored_independent") && MAIN_QUESTION_COLLECTION in it.collections }
    val suppliedCount = model.questions.count { it.origin.kind in setOf("user_supplied_compilation", "user_supplied_exam_booklet") && MAIN_QUESTION_COLLECTION in it.collections }
    PageColumn {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Forest)) {
            CellArt(Modifier.align(Alignment.CenterEnd).width(150.dp).height(215.dp))
            Column(Modifier.padding(23.dp).padding(end = 52.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("وقتِ رشد کردنه!", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text("زیست را یاد بگیر،\nبا خودت رقابت کن.", color = Color(0xFFE0ECDD), style = MaterialTheme.typography.bodyLarge)
                FilledTonalButton(onClick = { model.configure(GameMode.QUICK) }, colors = ButtonDefaults.filledTonalButtonColors(containerColor = Lime, contentColor = Forest), modifier = Modifier.semantics { contentDescription = "hero-quick" }) { Text("یک تمرین کوتاه  ←", fontWeight = FontWeight.Bold) }
            }
        }
        Text("${fa(model.questions.count { MAIN_QUESTION_COLLECTION in it.collections })} تست • ${fa(authoredCount)} تألیفی مستقل • ${fa(suppliedCount)} از منابع ارسالی", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniMetric("امتیاز کل", fa(model.totalScore), Modifier.weight(1f))
            MiniMetric("تمرین‌شده", "${fa(model.progress.size)} / ${fa(model.questions.size)}", Modifier.weight(1f))
            MiniMetric("بهترین زنجیره", fa(model.bestStreak), Modifier.weight(1f))
        }
        FilledTonalButton(onClick = { model.route = "study-time" }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "home-study-time" }, shape = RoundedCornerShape(18.dp)) { Text("◷ زمان مطالعه • هدف روزانه و تقویم شمسی") }
        if(veryDifficultCount > 0) OutlinedButton(onClick = { model.configureVeryDifficult() }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "home-very-difficult" }, shape = RoundedCornerShape(18.dp)) { Text("تست‌های بسیار دشوار • ${fa(veryDifficultCount)} سؤال") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("حالت‌های تمرین", fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
            Text("تمرین روزانه", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        GameMode.entries.filter { it != GameMode.REVIEW }.chunked(2).forEach { modes ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                modes.forEach { mode -> ModeCard(mode, Modifier.weight(1f)) { model.configure(mode) } }
            }
        }
        Surface(Modifier.fillMaxWidth().clickable { model.configure(GameMode.REVIEW) }.semantics { contentDescription = "home-review" }, shape = RoundedCornerShape(21.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("↻", fontSize = 31.sp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { Text("اشتباه‌ها را به فرصت تبدیل کن", fontWeight = FontWeight.Bold); Text("غلط‌ها، نزده‌ها و نشان‌شده‌ها", style = MaterialTheme.typography.bodySmall) }
                Text("←", fontSize = 24.sp)
            }
        }
        OutlinedButton(onClick = { model.route = "finals" }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "home-finals" }, shape = RoundedCornerShape(17.dp)) { Text("▤ آمادگی نهایی • پاسخ تشریحی و بارم‌بندی") }
        OutlinedButton(onClick = { model.route = "offline-tools" }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "home-offline-tools" }, shape = RoundedCornerShape(17.dp)) { Text("پروفایل محلی و پشتیبان‌گیری با فایل") }
        OutlinedButton(onClick = { model.route = "about-creator" }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "home-about-creator" }, shape = RoundedCornerShape(17.dp)) { Text("دربارهٔ سازنده") }
        Text("تمرین بدون اینترنت و ثبت‌نام • دوئل با حریف رایانه‌ای", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CellArt(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawCircle(Color(0xFF1F6550), w * .48f, Offset(w * .76f, h * .47f))
        drawCircle(Color(0xFF3D8D68), w * .31f, Offset(w * .76f, h * .47f), style = Stroke(2.dp.toPx()))
        drawCircle(Lime.copy(alpha = .8f), w * .13f, Offset(w * .76f, h * .47f))
        repeat(8) { i ->
            val angle = i * Math.PI / 4
            drawCircle(Color(0xFF80BF82), 3.dp.toPx(), Offset(w * .76f + cos(angle).toFloat() * w * .4f, h * .47f + sin(angle).toFloat() * w * .4f))
        }
        val path = Path().apply { moveTo(w * .1f, h * .1f); cubicTo(w * .7f, h * .3f, -w * .4f, h * .7f, w * .25f, h * .9f) }
        drawPath(path, Lime.copy(alpha = .2f), style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun ModeCard(mode: GameMode, modifier: Modifier, onClick: () -> Unit) {
    val label = when(mode) { GameMode.PRACTICE -> "home-practice"; GameMode.QUICK -> "home-quick"; GameMode.DUEL -> "home-duel"; GameMode.EXAM -> "home-exam"; else -> "home-review" }
    Surface(modifier.clickable(onClick = onClick).semantics { contentDescription = label }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(mode.glyph, fontSize = 24.sp, color = MaterialTheme.colorScheme.primary) }
            Text(mode.title, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(mode.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
        }
    }
}

@Composable
private fun MiniMetric(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp)) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(value, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 19.sp)
            Text(title, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Pill(text: String, glyph: String = "") {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) { Text("$glyph $text", Modifier.padding(horizontal = 11.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(title: String, values: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            values.forEach { (value, label) -> FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) }) }
        }
    }
}

@Composable
private fun GradeChapter(grade: Int, chapter: Int, onGrade: (Int) -> Unit, onChapter: (Int) -> Unit) {
    Choices("پایه", listOf("0" to "همه", "10" to "دهم", "11" to "یازدهم", "12" to "دوازدهم"), grade.toString()) { onGrade(it.toInt()); onChapter(0) }
    if(grade != 0) Choices("فصل", listOf("0" to "همهٔ فصل‌ها") + chapterTitles.getValue(grade).mapIndexed { i, name -> "${i + 1}" to "${fa(i + 1)}. $name" }, chapter.toString()) { onChapter(it.toInt()) }
}

@Composable
private fun SetupScreen(model: GameViewModel) {
    val available = model.pool().size
    val required = model.requiredCount()
    PageColumn {
        PageTitle(model.chosenMode.title, model.chosenMode.subtitle)
        Choices("منبع سؤال‌ها", availableQuestionSources(model.questions), model.selectedSource) { model.selectedSource = it }
        Text(when(model.selectedSource) { "official_konkur" -> "سؤال‌های رسمی کنکور؛ سال و منبع همراه هر سؤال آمده است."; "uploaded" -> "سؤال‌های منابع ارسالی؛ منشأ هر سؤال همراه آن آمده است."; "very_difficult" -> "مجموعهٔ مستقل تست‌های بسیار دشوار؛ پایه و فصل دلخواهت را انتخاب کن."; "all" -> "بانک اصلی سؤال‌ها؛ منشأ هر سؤال هنگام تمرین مشخص است."; else -> "سؤال‌های تألیفی مستقل بر پایهٔ کتاب درسی." }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(18.dp)) {
            Text(when(model.chosenMode) {
                GameMode.PRACTICE -> "پس از هر پاسخ، توضیح علمی و تحلیل هر چهار گزینه را می‌بینی. محدودیت زمانی نداری."
                GameMode.QUICK -> "۱۰ سؤال، برای هر سؤال ۲۵ ثانیه. پاسخ درست ۱۰۰ امتیاز، پاداش سرعت تا ۵۰ و زنجیره تا ۴۰ امتیاز دارد."
                GameMode.DUEL -> "حریف رایانه‌ای • آفلاین\nسه دورِ سه‌سؤالی؛ سؤال‌های یکسان و ۲۵ ثانیه برای هر دو. پاسخ حریف شبیه‌سازی می‌شود."
                GameMode.EXAM -> "پاسخ‌ها و تحلیل گزینه‌ها فقط پس از پایان آزمون نمایش داده می‌شوند. زمان کل آزمون پیوسته است."
                else -> "مرور سؤال‌های قبلی"
            }, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        GradeChapter(model.selectedGrade, model.selectedChapter, { model.selectedGrade = it }, { model.selectedChapter = it })
        Choices("دشواری", listOf("all" to "همه", "easy" to "آسان", "medium" to "متوسط", "hard" to "سخت", "unknown" to "تعیین‌نشده"), model.selectedDifficulty) { model.selectedDifficulty = it }
        if(model.chosenMode == GameMode.PRACTICE || model.chosenMode == GameMode.EXAM) {
            val counts = (listOf(1, 3, 5, 10, 20, 30, 50) + available).filter { it > 0 && it <= available }.distinct().sorted()
            LaunchedEffect(available, model.chosenMode) { if(model.selectedCount > available && available > 0) model.selectedCount = counts.last() }
            Choices("تعداد سؤال", counts.map { "$it" to fa(it) }, "${model.selectedCount}") { model.selectedCount = it.toInt() }
        }
        if(model.chosenMode == GameMode.EXAM) Choices("زمان کل آزمون (دقیقه)", listOf(1, 5, 10, 15, 30, 60).map { "$it" to fa(it) }, "${model.examMinutes}") { model.examMinutes = it.toInt() }
        if(model.chosenMode == GameMode.DUEL) Choices("سطح حریف رایانه‌ای", listOf("آسان", "متوسط", "سخت").map { it to it }, model.computerLevel) { model.computerLevel = it }
        Text("${fa(available)} سؤال در این انتخاب • سؤال‌های تمرین‌نشده در اولویت‌اند.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        if(available < required) Text("برای این نوبت ${fa(required)} سؤال لازم است. فیلتر را گسترده‌تر یا تعداد را کمتر کن.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(onClick = { model.start() }, enabled = available >= required, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics { contentDescription = "setup-start" }, shape = RoundedCornerShape(17.dp)) { Text("شروع ${model.chosenMode.title}  ←", fontWeight = FontWeight.Bold, fontSize = 17.sp) }
    }
}

@Composable
private fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, fontSize = 25.sp, fontWeight = FontWeight.Black)
        Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun QuestionScreen(model: GameViewModel) {
    val session = model.session ?: return
    val question = session.current
    val scroll = rememberScrollState()
    LaunchedEffect(session.id, session.index) { scroll.scrollTo(0) }
    LaunchedEffect(session.id, session.ended) {
        while(!session.ended) { model.tick(); delay(100) }
    }
    val tone = remember { runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 30) }.getOrNull() }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(session.index, session.revealed) {
        if(session.revealed && session.mode != GameMode.EXAM && model.settings.sound) {
            tone?.startTone(if(session.answers[session.index]?.selected == question.answer) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK, 100)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.showExit = true }, modifier = Modifier.semantics { contentDescription = "finish-session" }) { Text("پایان") }
            Text(session.mode.title, Modifier.weight(1f), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Pill("${fa(session.index + 1)} از ${fa(session.questions.size)}")
        }
        LinearProgressIndicator(progress = { (session.index + if(session.revealed) 1f else 0f) / session.questions.size }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if(session.timed) {
                val seconds = (session.remainingMillis + 999) / 1000
                val label = if(session.mode == GameMode.EXAM) "${fa(seconds / 60)}:${fa((seconds % 60).toString().padStart(2, '0'))}" else "${fa(seconds)} ثانیه"
                Text("◷ $label", color = if(seconds < 6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { contentDescription = "question-timer" })
            } else Pill("بدون زمان‌سنج", "∞")
            if(session.mode != GameMode.EXAM) Text("${fa(session.score)} امتیاز  •  زنجیره ${fa(session.streak)}", style = MaterialTheme.typography.labelMedium)
        }
        if(session.mode == GameMode.DUEL) Text("حریف رایانه‌ای (${session.computerLevel}) • دور ${fa(session.index / 3 + 1)} از ۳ • زمان برابر", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${gradeTitle(question.grade)} / ${chapterTitle(question.grade, question.chapter)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text("${question.topic} • ${difficultyTitle(question.difficulty)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(question.origin.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { model.toggleBookmark(question.id) }, modifier = Modifier.semantics { contentDescription = "bookmark-question" }) { Text(if(question.id in model.bookmarks) "★ نشان‌شده" else "☆ نشان کن") }
        }
        Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(question.stem, fontSize = 20.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { contentDescription = "question-stem" })
                if(question.image != null) QuestionImage(question.image!!, question.contentSnapshot)
            }
        }
        question.options.forEachIndexed { index, option ->
            val selected = session.answers[session.index]?.selected == index
            val correct = session.revealed && index == question.answer
            val wrong = session.revealed && selected && !correct
            val border = when { correct -> Emerald; wrong -> Coral; else -> MaterialTheme.colorScheme.outlineVariant }
            Surface(onClick = { model.answer(index) }, enabled = !session.revealed, shape = RoundedCornerShape(17.dp), color = if(correct) Color(0xFFE0F4E6) else if(wrong) Color(0xFFFBE6E5) else MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(if(correct || wrong) 2.dp else 1.dp, border), modifier = Modifier.fillMaxWidth().animateContentSize().semantics { contentDescription = "option-$index" }) {
                Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(31.dp).clip(CircleShape).background(if(correct) Emerald else if(wrong) Coral else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text(if(correct) "✓" else if(wrong) "×" else fa(index + 1), color = if(correct || wrong) Color.White else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        question.optionImages.getOrNull(index)?.let { image -> ZoomableQuestionImage(image, question.contentSnapshot, "تصویر گزینهٔ ${fa(index + 1)}") }
                        Text(option, fontSize = 17.sp, lineHeight = 29.sp, color = if(correct || wrong) Color(0xFF20392B) else MaterialTheme.colorScheme.onSurface)
                        if(correct || wrong) Text(if(correct) "پاسخ صحیح" else "پاسخ انتخابی تو؛ نادرست", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if(correct) Forest else Coral)
                    }
                }
            }
        }
        if(session.revealed) {
            val record = session.answers[session.index]!!
            Text(if(record.selected < 0) "○ نزده ثبت شد" else if(record.selected == question.answer) "✓ درست! ${fa(record.score)} امتیاز" else "× این پاسخ درست نبود", fontWeight = FontWeight.Bold, color = if(record.selected == question.answer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            Explanation(question)
            if(session.mode == GameMode.DUEL && (session.index + 1) % 3 == 0) {
                val range = (session.index - 2)..session.index
                val user = range.sumOf { session.answers[it]?.score ?: 0 }
                val bot = range.sumOf { session.computerAnswers[it].score }
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp)) { Text("پایان دور ${fa(session.index / 3 + 1)} • تو: ${fa(user)} | رایانه: ${fa(bot)}", Modifier.padding(16.dp), fontWeight = FontWeight.Bold) }
            }
            Button(onClick = { model.next() }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = "next-question" }) { Text(if(session.index == session.questions.lastIndex) "دیدن نتیجه" else "سؤال بعدی  ←") }
        } else OutlinedButton(onClick = { model.answer(-1) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "skip-question" }) { Text("فعلاً نمی‌دانم؛ نزده ثبت کن") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun QuestionImage(asset: String, snapshot: String) {
    ZoomableQuestionImage(asset, snapshot)
}

@Composable
private fun Explanation(question: Question) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("چرا گزینهٔ ${fa(question.answer + 1)}؟", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(question.explanation, style = MaterialTheme.typography.bodyMedium)
            if(question.answerSourceLabel.isNotBlank()) Text(question.answerSourceLabel.substringBefore("؛"), style = MaterialTheme.typography.labelMedium)
            HorizontalDivider()
            question.optionExplanations.forEachIndexed { i, reason ->
                Text("${if(i == question.answer) "✓" else "×"} گزینهٔ ${fa(i + 1)}: $reason", style = MaterialTheme.typography.bodySmall)
                if(question.optionAnalysisKinds.getOrNull(i) == "educational_generated") Text("تحلیل آموزشی", style = MaterialTheme.typography.labelSmall)
            }
            question.solutionImage?.let { ZoomableQuestionImage(it, question.contentSnapshot, "تصویر پاسخ‌نامهٔ منبع ارسالی") }
            Text("شناسهٔ سؤال: ${question.id}", style = MaterialTheme.typography.labelSmall)
            if(question.tags.isNotEmpty()) Text("مباحث مرتبط: ${question.tags.distinct().joinToString("، ")}", style = MaterialTheme.typography.labelMedium)
            if(question.trap.isNotBlank()) Text("دام آموزشی: ${question.trap}", style = MaterialTheme.typography.bodySmall)
            Text(question.origin.label, style = MaterialTheme.typography.labelMedium)
            Text("ارجاع: ${question.reference.section}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResultScreen(model: GameViewModel) {
    val session = model.session ?: return
    PageColumn {
        Surface(color = Forest, shape = RoundedCornerShape(25.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text("✦", fontSize = 40.sp, color = Lime)
                Text(if(session.mode == GameMode.DUEL) when { session.score > session.computerScore -> "بردی! یادگیری ادامه داره"; session.score == session.computerScore -> "برابر شد؛ آمادهٔ دور بعد؟"; else -> "این بار رایانه جلو افتاد" } else "یک قدم رشد کردی!", fontSize = 23.sp, fontWeight = FontWeight.Black, color = Color.White, textAlign = TextAlign.Center)
                Text(fa(session.score), fontSize = 47.sp, fontWeight = FontWeight.Black, color = Lime)
                Text("امتیاز این نوبت", color = Color.White)
                if(session.mode == GameMode.DUEL) Text("حریف رایانه‌ای (${session.computerLevel}): ${fa(session.computerScore)} امتیاز", color = Color.White)
                Text("رکورد ${session.mode.title}: ${fa(model.bestScores[session.mode.name] ?: session.score)}", color = Color(0xFFBFDEC8), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniMetric("✓ درست", fa(session.correct), Modifier.weight(1f))
            MiniMetric("× غلط", fa(session.wrong), Modifier.weight(1f))
            MiniMetric("○ نزده", fa(session.blank), Modifier.weight(1f))
        }
        Text("زنجیرهٔ این نوبت: ${fa(session.maxStreak)} پاسخ درست پیاپی", fontWeight = FontWeight.Medium)
        Text("عملکرد به تفکیک مبحث", fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Text("سؤال‌های ترکیبی در مباحث مرتبط دیده می‌شوند؛ در امتیاز کل فقط یک‌بار شمرده شده‌اند.", style = MaterialTheme.typography.labelSmall)
        session.questions.indices.flatMap { index -> (listOf(session.questions[index].topic) + session.questions[index].tags).distinct().map { topic -> topic to index } }.groupBy({ it.first }, { it.second }).forEach { (topic, indices) ->
            val correct = indices.count { session.answers[it]?.selected == session.questions[it].answer }
            val blank = indices.count { session.answers[it]?.selected == -1 }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row { Text(topic, Modifier.weight(1f), fontWeight = FontWeight.Medium); Text("${fa(correct)} / ${fa(indices.size)}", color = MaterialTheme.colorScheme.primary) }
                    LinearProgressIndicator(progress = { correct.toFloat() / indices.size }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                    Text("درست ${fa(correct)} • غلط ${fa(indices.size - correct - blank)} • نزده ${fa(blank)}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Button(onClick = { model.route = "answers" }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = "result-answers" }) { Text("بررسی پاسخ‌ها و تحلیل‌ها") }
        OutlinedButton(onClick = { if(session.mode == GameMode.REVIEW) model.configure(GameMode.REVIEW) else model.configure(session.mode) }, modifier = Modifier.fillMaxWidth()) { Text("یک نوبت تازه") }
        TextButton(onClick = { model.route = "home" }, modifier = Modifier.fillMaxWidth()) { Text("بازگشت به خانه") }
    }
}

@Composable
private fun AnswersScreen(model: GameViewModel) {
    val session = model.session ?: return
    PageColumn {
        PageTitle("دفتر پاسخ‌ها", "با تحلیل گزینه‌ها، هر پاسخ را دوباره بساز.")
        session.questions.forEachIndexed { index, question ->
            var expanded by remember(session.id, index) { mutableStateOf(false) }
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val selected = session.answers[index]?.selected ?: -1
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("سؤال ${fa(index + 1)} • ${if(selected < 0) "○ نزده" else if(selected == question.answer) "✓ درست" else "× غلط"}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { model.toggleBookmark(question.id) }) { Text(if(question.id in model.bookmarks) "★" else "☆") }
                    }
                    Text(question.stem, lineHeight = 28.sp)
                    if(question.image != null) QuestionImage(question.image!!, question.contentSnapshot)
                    Text("پاسخ تو: ${if(selected < 0) "نزده" else fa(selected + 1)} • پاسخ درست: ${fa(question.answer + 1)}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                    TextButton(onClick = { expanded = !expanded }) { Text(if(expanded) "بستن تحلیل ↑" else "گزینه‌ها و تحلیل پاسخ ↓") }
                    if(expanded) {
                        question.options.forEachIndexed { optionIndex, option -> Text("${fa(optionIndex + 1)}. $option", style = MaterialTheme.typography.bodyMedium) }
                        Explanation(question)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewScreen(model: GameViewModel) {
    val pool = model.pool(review = true)
    PageColumn {
        PageTitle("مرور اشتباهات", "قرار نیست اشتباه‌ها را تکرار کنیم؛ قرار است از آن‌ها یاد بگیریم.")
        GradeChapter(model.reviewGrade, model.reviewChapter, { model.reviewGrade = it }, { model.reviewChapter = it })
        ToggleLine("پاسخ‌های غلط", model.reviewWrong) { model.reviewWrong = it }
        ToggleLine("سؤال‌های نزده", model.reviewBlank) { model.reviewBlank = it }
        ToggleLine("سؤال‌های نشان‌شده", model.reviewMarked) { model.reviewMarked = it }
        Text("${fa(pool.size)} سؤال برای مرور", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        if(pool.isEmpty()) Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("دفتر مرور هنوز خالی است", fontWeight = FontWeight.Bold); Text("بعد از تمرین، سؤال‌های غلط و نزده اینجا می‌آیند. با ☆ هم می‌توانی هر سؤال را برای بعد نگه داری.") } }
        Button(onClick = { model.chosenMode = GameMode.REVIEW; model.selectedCount = pool.size.coerceAtMost(50); model.start() }, enabled = pool.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).semantics { contentDescription = "review-start" }) { Text("شروع مرور ${fa(pool.size.coerceAtMost(50))} سؤال") }
        if(pool.size > 50) Text("هر نوبت تا ۵۰ سؤال؛ سؤال‌های باقی‌مانده در نوبت بعد قابل مرورند.", style = MaterialTheme.typography.bodySmall)
        pool.take(25).forEach { q -> Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(15.dp), modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Text("${gradeTitle(q.grade)} • ${chapterTitle(q.grade, q.chapter)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(q.stem, style = MaterialTheme.typography.bodySmall); Text("${if(q.id in model.bookmarks) "★ نشان‌شده  " else ""}${when(model.progress[q.id]?.last) { 0 -> "× آخرین پاسخ: غلط"; -1 -> "○ آخرین پاسخ: نزده"; 1 -> "✓ آخرین پاسخ: درست"; else -> "" }}", style = MaterialTheme.typography.labelSmall) } } }
    }
}

@Composable
private fun StatsScreen(model: GameViewModel) {
    val attempts = model.progress.values.sumOf { it.attempts }
    val correct = model.progress.values.sumOf { it.correct }
    val accuracy = if(attempts == 0) 0 else correct * 100 / attempts
    PageColumn {
        PageTitle("ردّ رشد تو", "رقیب اصلی، خودِ دیروز توست.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { MiniMetric("دقت کل", "${fa(accuracy)}٪", Modifier.weight(1f)); MiniMetric("تلاش‌ها", fa(attempts), Modifier.weight(1f)); MiniMetric("امتیاز", fa(model.totalScore), Modifier.weight(1f)) }
        Text("نشان‌های مسیر", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        listOf(Triple("✦ نخستین قدم", "یک سؤال تمرین کن", model.progress.isNotEmpty()), Triple("◈ پژوهشگر", "${fa(minOf(50, model.questions.size))} سؤال متفاوت تمرین کن", model.questions.isNotEmpty() && model.progress.size >= minOf(50, model.questions.size)), Triple("❋ پیوستگی", "۵ پاسخ درست پیاپی", model.bestStreak >= 5), Triple("✧ زیست‌شناس", "همهٔ ${fa(model.questions.size)} سؤال بانک فعلی را تمرین کن", model.questions.isNotEmpty() && model.questions.all { it.id in model.progress })).forEach { (title, subtitle, achieved) ->
            Surface(color = if(achieved) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodySmall) }; Text(if(achieved) "✓ دریافت شد" else "هنوز در مسیر", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) } }
        }
        Text("پیشرفت پایه‌ها", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        listOf(10, 11, 12).forEach { grade ->
            val total = model.questions.count { it.grade == grade }
            val done = model.questions.count { it.grade == grade && it.id in model.progress }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Row { Text(gradeTitle(grade), Modifier.weight(1f)); Text("${fa(done)} از ${fa(total)}") }; LinearProgressIndicator(progress = { if(total == 0) 0f else done.toFloat() / total }, modifier = Modifier.fillMaxWidth().height(7.dp).clip(CircleShape)) }
        }
        Text("رکوردهای شخصی", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        GameMode.entries.forEach { mode -> val best = model.bestScores[mode.name]; Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Text(mode.title, Modifier.weight(1f)); Text(if(best == null) "هنوز ثبت نشده" else "${fa(best)} امتیاز", color = MaterialTheme.colorScheme.primary) } }
        Text("${fa(model.history.size)} نوبت اخیر ذخیره شده • بهترین زنجیره ${fa(model.bestStreak)}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsScreen(model: GameViewModel) {
    PageColumn {
        PageTitle("تنظیمات", "به سبک خودت یاد بگیر.")
        ToggleLine("حالت تاریک", model.settings.dark) { model.changeSettings(model.settings.copy(dark = it)) }
        ToggleLine("صدای پاسخ‌ها", model.settings.sound) { model.changeSettings(model.settings.copy(sound = it)) }
        Text("اندازهٔ متن: ${fa((model.settings.textScale * 100).toInt())}٪", fontWeight = FontWeight.Bold)
        Slider(value = model.settings.textScale, onValueChange = { model.changeSettings(model.settings.copy(textScale = it)) }, valueRange = .85f..1.4f, steps = 10, modifier = Modifier.semantics { contentDescription = "text-size" })
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) { Text("نمونهٔ متن: در زیست‌شناسی، ارتباط میان ساختار و کار به درک بهتر پدیده‌های زنده کمک می‌کند.", Modifier.padding(18.dp), style = MaterialTheme.typography.bodyLarge) }
        HorizontalDivider()
        Text("دربارهٔ ۱۰۰۰ زیست", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text("بازی تمرین زیست‌شناسی برای دانش‌آموزان تجربی. منشأ سؤال‌ها همراه آن‌ها آمده است. آموزش، دوئل با حریف رایانه‌ای و زمان مطالعه آفلاین کار می‌کنند.", style = MaterialTheme.typography.bodyMedium)
        Text("قانون امتیاز", fontWeight = FontWeight.Bold)
        Text("درست: ۱۰۰ امتیاز؛ غلط و نزده: صفر. مسابقهٔ سریع و دوئل برای سرعت تا ۵۰ امتیاز پاداش دارند. از دومین پاسخ درست پیاپی، ۱۰ امتیاز به پاداش زنجیره افزوده می‌شود؛ سقف آن ۴۰ است. تمرین و آزمون پاداش سرعت ندارند.", style = MaterialTheme.typography.bodySmall)
        Text("منابع و ویرایش بانک", fontWeight = FontWeight.Bold)
        Text("پایهٔ دهم: زیست‌شناسی ۱ • یازدهم: زیست‌شناسی ۲ • دوازدهم: زیست‌شناسی ۳. منشأ و ارجاع هر سؤال همراه پاسخ آن آمده است.", style = MaterialTheme.typography.bodySmall)
        Text("حریم خصوصی", fontWeight = FontWeight.Bold)
        Text("همهٔ سؤال‌ها، شکل‌ها و فونت‌ها داخل برنامه‌اند. پیشرفت، زمان مطالعه، تنظیمات و پروفایل اختیاری فقط روی دستگاه ذخیره می‌شوند. برنامه دسترسی اینترنت ندارد. فقط با لمس دکمهٔ حمایت، صفحهٔ اختیاری حمایت در مرورگر بیرون برنامه باز می‌شود و اینترنت مرورگر لازم است. هیچ امکان آموزشی به حمایت مالی وابسته نیست. پیش از حذف برنامه از فایل محلی پشتیبان بگیرید.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ model.route = "offline-tools" }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "settings-offline-tools" }) { Text("پروفایل محلی، پشتیبان‌گیری و بازیابی") }
        OutlinedButton({ model.route = "study-time" }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "settings-study-time" }) { Text("زمان مطالعه") }
        OutlinedButton({ model.route = "about-creator" }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "settings-about-creator" }) { Text("دربارهٔ سازنده") }
        Text("درخواست اصلاح یا حذف محتوا", fontWeight = FontWeight.Bold)
        Text("نام مؤسسه، شناسهٔ سؤال و دلیل اعتراض یا درخواست حذف را از مسیر ارتباطی ناشر در صفحهٔ انتشار برنامه اعلام کنید. منشأ و شناسهٔ هر سؤال در صفحهٔ پاسخ نمایش داده می‌شود.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToggleLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), fontWeight = FontWeight.Medium); Switch(checked, onCheckedChange = onChange) }
    }
}

@Composable
private fun BottomBar(model: GameViewModel) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        listOf(Triple("home", "خانه", "⌂"), Triple("review", "مرور", "↻"), Triple("stats", "آمار", "▥"), Triple("settings", "تنظیمات", "⚙")).forEach { (route, title, glyph) ->
            NavigationBarItem(selected = model.route == route, onClick = { if(route == "review") model.configure(GameMode.REVIEW) else model.route = route }, icon = { Text(glyph, fontSize = 23.sp) }, label = { Text(title) }, modifier = Modifier.semantics { contentDescription = if(route == "review") "nav-review" else "home-$route" })
        }
    }
}
