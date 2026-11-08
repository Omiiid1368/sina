package ir.hezarzist

import kotlin.math.roundToInt
import kotlin.random.Random

enum class GameMode(val title: String, val subtitle: String, val glyph: String) {
    PRACTICE("تمرین فصل", "یادگیری، یک سؤال در هر قدم", "✦"),
    QUICK("مسابقهٔ سریع", "۱۰ سؤال؛ دقت و سرعت", "◷"),
    DUEL("دوئل زیستی", "سه دور با حریف رایانه‌ای", "⚔"),
    EXAM("آزمون آزمایشی", "آزمونی با زمان دلخواه تو", "▤"),
    REVIEW("مرور اشتباهات", "اشتباه امروز، یادگیری فردا", "↻")
}

data class AnswerRecord(val selected: Int = -1, val remainingMillis: Long = 0L, val score: Int = 0)

data class QuizSession(
    val id: Long,
    val mode: GameMode,
    val questions: List<Question>,
    val answers: List<AnswerRecord?>,
    val computerAnswers: List<AnswerRecord> = emptyList(),
    val computerLevel: String = "متوسط",
    val index: Int = 0,
    val revealed: Boolean = false,
    val ended: Boolean = false,
    val durationMillis: Long = 25_000L,
    val deadlineMillis: Long = Long.MAX_VALUE,
    val remainingMillis: Long = durationMillis,
    val streak: Int = 0,
    val maxStreak: Int = 0
) {
    val current: Question get() = questions[index]
    val score: Int get() = answers.filterNotNull().sumOf { it.score }
    val computerScore: Int get() = computerAnswers.sumOf { it.score }
    val correct: Int get() = questions.indices.count { answers[it]?.selected == questions[it].answer }
    val blank: Int get() = answers.count { it == null || it.selected < 0 }
    val wrong: Int get() = questions.size - correct - blank
    val timed: Boolean get() = mode == GameMode.QUICK || mode == GameMode.DUEL || mode == GameMode.EXAM
}

/** New questions first; no repeated IDs within a session, including combined questions. */
fun selectQuestions(pool: List<Question>, solvedIds: Set<String>, count: Int, random: Random = Random.Default): List<Question> {
    val distinct = pool.distinctBy { it.id }
    return (distinct.filterNot { it.id in solvedIds }.shuffled(random) +
        distinct.filter { it.id in solvedIds }.shuffled(random)).take(count.coerceAtLeast(0))
}

/** Accuracy 100, speed 0–50, consecutive-answer bonus 0–40. Wrong/blank earn zero. */
fun calculateScore(correct: Boolean, remainingMillis: Long, totalMillis: Long, streak: Int): Int {
    if (!correct) return 0
    val speed = if (totalMillis > 0) (50.0 * remainingMillis.coerceIn(0, totalMillis) / totalMillis).roundToInt() else 0
    return 100 + speed + ((streak - 1).coerceIn(0, 4) * 10)
}

fun simulateComputer(questions: List<Question>, durationMillis: Long, level: String, random: Random = Random.Default): List<AnswerRecord> {
    val accuracy = when (level) { "آسان" -> .48; "سخت" -> .86; else -> .68 }
    var streak = 0
    return questions.map { question ->
        val correct = random.nextDouble() < accuracy
        val blank = !correct && random.nextDouble() < .12
        val selected = if (correct) question.answer else if (blank) -1 else (0..3).filter { it != question.answer }.random(random)
        val remaining = (durationMillis * random.nextDouble(.10, .82)).toLong()
        streak = if (correct) streak + 1 else 0
        AnswerRecord(selected, remaining, calculateScore(correct, remaining, durationMillis, streak))
    }
}

val chapterTitles = mapOf(
    10 to listOf("دنیای زنده", "گوارش و جذب مواد", "تبادلات گازی", "گردش مواد در بدن", "تنظیم اسمزی و دفع مواد زائد", "از یاخته تا گیاه", "جذب و انتقال مواد در گیاهان"),
    11 to listOf("تنظیم عصبی", "حواس", "دستگاه حرکتی", "تنظیم شیمیایی", "ایمنی", "تقسیم یاخته", "تولید مثل", "تولید مثل نهاندانگان", "پاسخ گیاهان به محرک‌ها"),
    12 to listOf("مولکول‌های اطلاعاتی", "جریان اطلاعات در یاخته", "انتقال اطلاعات در نسل‌ها", "تغییر در اطلاعات وراثتی", "از ماده به انرژی", "از انرژی به ماده", "فناوری‌های نوین زیستی", "رفتارهای جانوران")
)

fun gradeTitle(grade: Int): String = when (grade) { 10 -> "دهم"; 11 -> "یازدهم"; 12 -> "دوازدهم"; else -> "همهٔ پایه‌ها" }
fun chapterTitle(grade: Int, chapter: Int): String = chapterTitles[grade]?.getOrNull(chapter - 1) ?: "همهٔ فصل‌ها"
fun difficultyTitle(value: String): String = when(value.lowercase()) { "easy", "آسان" -> "آسان"; "hard", "سخت" -> "سخت"; "medium", "متوسط" -> "متوسط"; else -> "همه" }
fun fa(value: Any): String = value.toString().map { if(it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it }.joinToString("")
