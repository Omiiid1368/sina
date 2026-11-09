package ir.hezarzist

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject

data class QuestionProgress(val attempts: Int = 0, val correct: Int = 0, val last: Int = -1)
data class GameSettings(val dark: Boolean = false, val sound: Boolean = true, val textScale: Float = 1f)
data class MatchResult(val mode: String, val score: Int, val correct: Int, val wrong: Int, val blank: Int, val timestamp: Long)

/** Device-only progress retains the preference file used by the first published APK. */
class ProgressStore(private val context: Context, private val namespace: String = LocalProgress.namespace(context)) {
    init { require(namespace == LocalProgress.actor) { "پیشرفت این نسخه فقط روی دستگاه نگهداری می‌شود." } }
    private val prefs = context.getSharedPreferences("hezarzist_progress_v1", Context.MODE_PRIVATE)
    private val epoch = LocalProgress.profileEpoch(context, namespace)
    private fun writable() = LocalProgress.profileEpoch(context, namespace) == epoch
    val archivedCount: Int get() = runCatching { JSONObject(prefs.getString("archivedProgress", "{}")!!).length() }.getOrDefault(0)
    fun archiveLegacy(bank: List<Question>) {
        if(!writable()) return
        val ids = bank.map { it.id }.toSet()
        val removed = progress.filterKeys { it !in ids }
        val archive = runCatching { JSONObject(prefs.getString("archivedProgress", "{}")!!) }.getOrDefault(JSONObject())
        removed.forEach { (id, value) -> archive.put(id, progressJson(value)) }
        val archivedMarks = prefs.getStringSet("archivedBookmarks", emptySet())!!.toSet() + bookmarks.filter { it !in ids }
        progress = progress.filterKeys { it in ids }; bookmarks = bookmarks.filter { it in ids }.toSet()
        prefs.edit().putString("archivedProgress", archive.toString()).putStringSet("archivedBookmarks", archivedMarks)
            .putString("progress", JSONObject().also { j -> progress.forEach { (id, value) -> j.put(id, progressJson(value)) } }.toString()).putStringSet("bookmarks", bookmarks).commit()
        if(readActive(bank) == null) {
            val stale = prefs.getString("activeSession", null)
            val edit = prefs.edit().remove("activeSession")
            if(stale != null) {
                val previous = prefs.getString("archivedSession", null)
                if(previous != null && previous != stale) {
                    val sessions = runCatching { JSONArray(prefs.getString("archivedSessions", "[]")) }.getOrDefault(JSONArray())
                    if((0 until sessions.length()).none { sessions.getString(it) == previous }) sessions.put(previous)
                    edit.putString("archivedSessions", sessions.toString())
                }
                edit.putString("archivedSession", stale)
            }
            edit.commit()
        }
    }
    private fun progressJson(value: QuestionProgress) = JSONObject().put("attempts", value.attempts).put("correct", value.correct).put("last", value.last)
    private fun saveProgress() { if(writable()) prefs.edit().putString("progress", JSONObject().also { j -> progress.forEach { (id, value) -> j.put(id, progressJson(value)) } }.toString()).commit() }
    var progress: Map<String, QuestionProgress> = readProgress()
        private set
    var bookmarks: Set<String> = prefs.getStringSet("bookmarks", emptySet())!!.toSet()
        private set
    var settings = GameSettings(prefs.getBoolean("dark", false), prefs.getBoolean("sound", true), prefs.getFloat("textScale", 1f))
        private set
    var history: List<MatchResult> = readHistory()
        private set
    var bestScores: Map<String, Int> = readBestScores()
        private set
    var totalScore: Int = prefs.getInt("totalScore", 0)
        private set
    var bestStreak: Int = prefs.getInt("bestStreak", 0)
        private set

    private fun readProgress(): Map<String, QuestionProgress> = runCatching {
        val json = JSONObject(prefs.getString("progress", "{}")!!)
        json.keys().asSequence().associateWith { id ->
            val item = json.getJSONObject(id)
            QuestionProgress(item.getInt("attempts"), item.getInt("correct"), item.getInt("last"))
        }
    }.getOrDefault(emptyMap())

    private fun readHistory(): List<MatchResult> = runCatching {
        val json = JSONArray(prefs.getString("history", "[]")!!)
        (0 until json.length()).map { index ->
            val item = json.getJSONObject(index)
            MatchResult(item.getString("mode"), item.getInt("score"), item.getInt("correct"), item.getInt("wrong"), item.getInt("blank"), item.getLong("timestamp"))
        }
    }.getOrDefault(emptyList())

    private fun readBestScores(): Map<String, Int> = runCatching {
        val json = JSONObject(prefs.getString("bestScores", "{}")!!)
        val saved = json.keys().asSequence().associateWith { json.getInt(it) }
        val historical = history.groupBy { it.mode }.mapValues { (_, results) -> results.maxOf { it.score } }
        (saved.keys + historical.keys).associateWith { maxOf(saved[it] ?: 0, historical[it] ?: 0) }
    }.getOrDefault(emptyMap())

    fun readActive(bank: List<Question>): QuizSession? = runCatching {
        val raw = prefs.getString("activeSession", null) ?: return null
        val json = JSONObject(raw)
        val ids = json.getJSONArray("ids")
        val snapshot = json.optString("bankSnapshot").takeIf { it.isNotBlank() }
        require(snapshot == null || snapshot == "bundled")
        val byId = bank.associateBy { it.id }
        val questions = (0 until ids.length()).map { byId.getValue(ids.getString(it)) }
        require(questions.isNotEmpty() && questions.map { it.id }.toSet().size == questions.size)
        fun records(key: String): List<AnswerRecord?> {
            val values = json.getJSONArray(key)
            return (0 until values.length()).map { i ->
                if(values.isNull(i)) null else values.getJSONObject(i).let { item ->
                    AnswerRecord(item.getInt("selected"), item.getLong("remaining"), item.getInt("score")).also { require(it.selected in -1..3 && it.score >= 0) }
                }
            }
        }
        val mode = GameMode.valueOf(json.getString("mode"))
        val answers = records("answers")
        require(answers.size == questions.size)
        val revealed = json.getBoolean("revealed")
        val duration = json.getLong("duration")
        require(duration > 0)
        val wallRemaining = (json.getLong("deadlineWall") - System.currentTimeMillis()).coerceIn(0L, duration)
        val savedElapsed = json.optLong("savedElapsed", -1L)
        val elapsedDelta = SystemClock.elapsedRealtime() - savedElapsed
        val savedBudget = json.optLong("remainingAtSave", json.optLong("remaining", duration)).coerceIn(0L, duration)
        val elapsedRemaining = if(savedElapsed >= 0L && elapsedDelta >= 0L) (savedBudget - elapsedDelta).coerceAtLeast(0L) else savedBudget
        val remaining = minOf(wallRemaining, elapsedRemaining)
        QuizSession(json.getLong("id"), mode, questions, answers, records("computer").filterNotNull(), json.getString("computerLevel"),
            index = json.getInt("index").also { require(it in questions.indices) }, revealed = revealed,
            durationMillis = duration, deadlineMillis = if(mode == GameMode.PRACTICE || mode == GameMode.REVIEW) Long.MAX_VALUE else SystemClock.elapsedRealtime() + remaining,
            remainingMillis = if(revealed && mode != GameMode.EXAM) json.getLong("remaining") else remaining,
            streak = json.getInt("streak"), maxStreak = json.getInt("maxStreak"))
    }.getOrNull()

    fun saveActive(session: QuizSession?) {
        if(!writable()) return
        if(session == null || session.ended) { prefs.edit().remove("activeSession").commit(); return }
        val json = JSONObject().put("id", session.id).put("mode", session.mode.name)
            .put("ids", JSONArray(session.questions.map { it.id })).put("bankSnapshot", session.questions.first().contentSnapshot).put("computerLevel", session.computerLevel)
            .put("index", session.index).put("revealed", session.revealed).put("duration", session.durationMillis)
            .put("remaining", session.remainingMillis).put("streak", session.streak).put("maxStreak", session.maxStreak)
            .put("deadlineWall", if(session.timed) System.currentTimeMillis() + session.deadlineMillis - SystemClock.elapsedRealtime() else 0L)
            .put("savedElapsed", SystemClock.elapsedRealtime())
            .put("remainingAtSave", if(session.timed) (session.deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L) else 0L)
        fun records(values: List<AnswerRecord?>): JSONArray = JSONArray().also { array ->
            values.forEach { answer -> array.put(if(answer == null) JSONObject.NULL else JSONObject().put("selected", answer.selected).put("remaining", answer.remainingMillis).put("score", answer.score)) }
        }
        json.put("answers", records(session.answers)).put("computer", records(session.computerAnswers))
        // A session transition is a small durable checkpoint, including pending progress writes.
        prefs.edit().putString("activeSession", json.toString()).commit()
    }

    fun flush(): Boolean = writable() && prefs.edit().commit()

    fun record(question: Question, answer: AnswerRecord) {
        if(!writable()) return
        val old = progress[question.id] ?: QuestionProgress()
        val outcome = if (answer.selected < 0) -1 else if (answer.selected == question.answer) 1 else 0
        progress = progress + (question.id to QuestionProgress(old.attempts + 1, old.correct + if(outcome == 1) 1 else 0, outcome))
        saveProgress()
    }

    fun bookmark(id: String) {
        if(!writable()) return
        bookmarks = if(id in bookmarks) bookmarks - id else bookmarks + id
        prefs.edit().putStringSet("bookmarks", bookmarks).commit()
    }

    fun settings(value: GameSettings) {
        if(!writable()) return
        settings = value.copy(textScale = value.textScale.coerceIn(.85f, 1.4f))
        prefs.edit().putBoolean("dark", settings.dark).putBoolean("sound", settings.sound).putFloat("textScale", settings.textScale).commit()
    }

    fun complete(session: QuizSession) {
        if(!writable()) return
        val result = MatchResult(session.mode.name, session.score, session.correct, session.wrong, session.blank, System.currentTimeMillis())
        history = (listOf(result) + history).take(200)
        bestScores = bestScores + (session.mode.name to maxOf(bestScores[session.mode.name] ?: 0, session.score))
        totalScore += session.score
        bestStreak = maxOf(bestStreak, session.maxStreak)
        val array = JSONArray()
        history.forEach { row -> array.put(JSONObject().put("mode", row.mode).put("score", row.score).put("correct", row.correct).put("wrong", row.wrong).put("blank", row.blank).put("timestamp", row.timestamp)) }
        val best = JSONObject()
        bestScores.forEach { (mode, score) -> best.put(mode, score) }
        prefs.edit().putString("history", array.toString()).putString("bestScores", best.toString()).putInt("totalScore", totalScore).putInt("bestStreak", bestStreak).remove("activeSession").commit()
    }
}

class GameViewModel(application: Application) : AndroidViewModel(application) {
    var questions by mutableStateOf(loadPlayableQuestions(application))
        private set
    private var store = ProgressStore(application).also { it.archiveLegacy(questions) }
    var archivedCount by mutableStateOf(store.archivedCount)
        private set
    fun reloadProfile() {
        questions = loadPlayableQuestions(getApplication())
        store = ProgressStore(getApplication()).also { it.archiveLegacy(questions) }
        progress = store.progress; bookmarks = store.bookmarks; history = store.history; bestScores = store.bestScores; totalScore = store.totalScore; bestStreak = store.bestStreak; archivedCount = store.archivedCount
        settings = store.settings
        session = store.readActive(questions); showExit = false
        if(route == "question" && session == null) route = "home"
    }
    var progress by mutableStateOf(store.progress)
        private set
    var bookmarks by mutableStateOf(store.bookmarks)
        private set
    var settings by mutableStateOf(store.settings)
        private set
    var history by mutableStateOf(store.history)
        private set
    var bestScores by mutableStateOf(store.bestScores)
        private set
    var totalScore by mutableStateOf(store.totalScore)
        private set
    var bestStreak by mutableStateOf(store.bestStreak)
        private set
    var route by mutableStateOf("home")
    var chosenMode by mutableStateOf(GameMode.PRACTICE)
    var selectedSource by mutableStateOf(defaultQuestionSource(questions))
    var selectedGrade by mutableStateOf(10)
    var selectedChapter by mutableStateOf(0)
    var selectedDifficulty by mutableStateOf("all")
    var selectedCount by mutableStateOf(10)
    var examMinutes by mutableStateOf(15)
    var computerLevel by mutableStateOf("متوسط")
    var reviewWrong by mutableStateOf(true)
    var reviewBlank by mutableStateOf(true)
    var reviewMarked by mutableStateOf(true)
    var reviewGrade by mutableStateOf(0)
    var reviewChapter by mutableStateOf(0)
    var session by mutableStateOf(store.readActive(questions))
        private set
    var showExit by mutableStateOf(false)

    init { if(session != null) route = "question" }

    fun flushProgress(): Boolean = store.flush()

    fun changeSettings(value: GameSettings) { store.settings(value); settings = store.settings }
    fun toggleBookmark(id: String) { store.bookmark(id); bookmarks = store.bookmarks }
    fun configure(mode: GameMode) {
        if(selectedSource == "very_difficult") selectedSource = defaultQuestionSource(questions)
        chosenMode = mode; route = if(mode == GameMode.REVIEW) "review" else "setup"
    }

    fun configureVeryDifficult(): Boolean {
        val count = veryDifficultQuestionCount(questions)
        if(count == 0) return false
        chosenMode = GameMode.PRACTICE; selectedSource = "very_difficult"
        selectedGrade = 0; selectedChapter = 0; selectedDifficulty = "all"
        selectedCount = minOf(10, count); route = "setup"
        return true
    }

    fun pool(review: Boolean = chosenMode == GameMode.REVIEW): List<Question> {
        val grade = if(review) reviewGrade else selectedGrade
        val chapter = if(review) reviewChapter else selectedChapter
        return questions.filter { q ->
            (review || questionMatchesSource(q, selectedSource)) &&
                (grade == 0 || q.grade == grade) && (chapter == 0 || q.chapter == chapter) &&
                (review || selectedDifficulty == "all" || difficultyTitle(q.difficulty) == difficultyTitle(selectedDifficulty)) &&
                (!review || ((reviewWrong && progress[q.id]?.last == 0) || (reviewBlank && progress[q.id]?.last == -1) || (reviewMarked && q.id in bookmarks)))
        }
    }

    fun requiredCount(): Int = when(chosenMode) { GameMode.QUICK -> 10; GameMode.DUEL -> 9; else -> selectedCount }

    fun start(): Boolean {
        val count = requiredCount()
        val eligible = pool()
        if(eligible.size < count || count < 1) return false
        val chosen = selectQuestions(eligible, progress.keys, count)
        val duration = if(chosenMode == GameMode.EXAM) examMinutes * 60_000L else 25_000L
        val deadline = if(chosenMode == GameMode.PRACTICE || chosenMode == GameMode.REVIEW) Long.MAX_VALUE else SystemClock.elapsedRealtime() + duration
        session = QuizSession(
            id = System.currentTimeMillis(), mode = chosenMode, questions = chosen,
            answers = List(chosen.size) { null }, computerAnswers = if(chosenMode == GameMode.DUEL) simulateComputer(chosen, duration, computerLevel) else emptyList(),
            computerLevel = computerLevel, durationMillis = duration, deadlineMillis = deadline, remainingMillis = duration
        )
        store.saveActive(session)
        route = "question"
        return true
    }

    fun tick(now: Long = SystemClock.elapsedRealtime()) {
        val value = session ?: return
        if(value.ended || !value.timed || (value.revealed && value.mode != GameMode.EXAM)) return
        val remaining = (value.deadlineMillis - now).coerceAtLeast(0L)
        session = value.copy(remainingMillis = remaining)
        if(remaining == 0L) {
            if(value.mode == GameMode.EXAM) finish() else answer(-1, now)
        }
    }

    fun answer(selected: Int, now: Long = SystemClock.elapsedRealtime()) {
        val initial = session ?: return
        if(initial.ended || initial.revealed || selected !in -1..3) return
        val remaining = if(initial.timed) (initial.deadlineMillis - now).coerceAtLeast(0) else 0L
        // A late tap cannot turn an expired question into a correct answer.
        if(initial.timed && remaining == 0L && selected >= 0) {
            if(initial.mode == GameMode.EXAM) finish() else answer(-1, now)
            return
        }
        val correct = selected == initial.current.answer
        val streak = if(correct) initial.streak + 1 else 0
        val score = calculateScore(correct, if(initial.mode == GameMode.EXAM) 0L else remaining, if(initial.mode == GameMode.EXAM || !initial.timed) 0L else initial.durationMillis, streak)
        val record = AnswerRecord(selected, remaining, score)
        store.record(initial.current, record)
        progress = store.progress
        session = initial.copy(answers = initial.answers.toMutableList().also { it[initial.index] = record }, revealed = true, remainingMillis = remaining, streak = streak, maxStreak = maxOf(initial.maxStreak, streak))
        store.saveActive(session)
        if(initial.mode == GameMode.EXAM) next(now)
    }

    fun next(now: Long = SystemClock.elapsedRealtime()) {
        val value = session ?: return
        if(value.ended || !value.revealed) return
        if(value.index == value.questions.lastIndex) { finish(); return }
        session = value.copy(index = value.index + 1, revealed = false, deadlineMillis = if(value.mode == GameMode.EXAM) value.deadlineMillis else if(value.timed) now + value.durationMillis else Long.MAX_VALUE, remainingMillis = if(value.mode == GameMode.EXAM) (value.deadlineMillis - now).coerceAtLeast(0) else value.durationMillis)
        store.saveActive(session)
    }

    fun finish() {
        val value = session ?: return
        if(value.ended) { route = "result"; return }
        val answers = value.answers.toMutableList()
        value.questions.indices.filter { answers[it] == null }.forEach { index ->
            val record = AnswerRecord()
            answers[index] = record
            store.record(value.questions[index], record)
        }
        val ended = value.copy(answers = answers, ended = true, revealed = true)
        session = ended
        store.complete(ended)
        progress = store.progress; history = store.history; bestScores = store.bestScores; totalScore = store.totalScore; bestStreak = store.bestStreak
        showExit = false
        route = "result"
    }

    fun back() {
        when(route) {
            "question" -> showExit = true
            "answers" -> route = "result"
            else -> route = "home"
        }
    }
}
