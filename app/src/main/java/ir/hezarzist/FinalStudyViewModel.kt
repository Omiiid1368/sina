package ir.hezarzist

import android.app.Application
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import org.json.JSONObject

class FinalStudyViewModel(application: Application) : AndroidViewModel(application) {
    private val localNamespace = LocalProgress.namespace(application)
    private val store = FinalsProgressStore(application)
    val bank = FinalsRepository.load(application)
    private val byId = bank.associateBy { it.id }
    private val restoredSession = store.session(byId.keys)
    private val savedFilters = store.filters()
    var attempts by mutableStateOf(store.attempts()); private set
    var bookmarks by mutableStateOf(store.bookmarks()); private set
    var session by mutableStateOf(restoredSession?.takeIf { s -> s.questionIds.isNotEmpty() && s.questionIds.all { it in byId } }); private set
    var grade by mutableStateOf(savedFilters.optInt("grade").takeIf { it in 10..12 } ?: 0); private set
    var chapter by mutableStateOf(savedFilters.optInt("chapter").takeIf { grade != 0 && it in 1..chapterTitles.getValue(grade).size } ?: 0); private set
    var topic by mutableStateOf(savedFilters.optString("topic").takeIf { selected -> bank.any { it.topic == selected && (grade == 0 || it.grade == grade) && (chapter == 0 || it.chapter == chapter) } } ?: ""); private set
    var type by mutableStateOf(savedFilters.optString("type").takeIf { it in finalTypeTitles } ?: ""); private set
    var mode by mutableStateOf(savedFilters.optString("mode").takeIf { it in setOf("practice", "exam") } ?: "practice"); private set
    var reviewFilter by mutableStateOf(savedFilters.optString("review").takeIf { it in setOf("all", "weak", "bookmarked") } ?: "all"); private set
    var count by mutableStateOf(savedFilters.optInt("count", 10).coerceIn(1, maxOf(1, bank.size))); private set
    var minutes by mutableStateOf(savedFilters.optInt("minutes", 10).coerceIn(1, 180)); private set
    var reviewingResult by mutableStateOf(false); private set
    var showFinish by mutableStateOf(false)
    private var lastTimerSave = SystemClock.elapsedRealtime()
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (ownsProfile()) {
            if (key?.startsWith("answer:") == true) {
                attempts = store.attempts()
                val changedKey = key.removePrefix("answer:")
                val active = session
                val previous = active?.drafts?.values?.firstOrNull { it.key == changedKey }
                if (active != null && previous != null) {
                    val incoming = attempts[changedKey]
                    val drafts = if (incoming == null) active.drafts - previous.questionId else active.drafts + (incoming.questionId to incoming)
                    session = active.copy(drafts = drafts)
                }
            }
            if (key?.startsWith("bookmark:") == true) bookmarks = store.bookmarks()
        }
    }
    init {
        // Only the newest local view model may checkpoint the active session.
        owners.put(localNamespace, this)?.let { previous -> previous.store.unregister(previous.prefListener) }
        store.register(prefListener)
        if (session?.let { it.mode == "exam" && !it.complete && it.remainingMillis <= 0 } == true) finish()
    }
    private fun ownsProfile() = owners[localNamespace] === this && store.isCurrent()

    val current: FinalQuestion? get() = session?.let { byId[it.currentId] }
    val currentAttempt: FinalAttempt? get() = session?.attempt()
    val canReveal: Boolean get() = session?.let { if (it.mode == "exam") it.complete else it.attempt().let { a -> a.submitted && a.hasResponse } } == true
    val latestAttempts: Map<String, FinalAttempt> get() = attempts.values.groupBy { it.questionId }.mapValues { (_, list) -> list.maxBy { it.updatedAt } }
    val weakIds: Set<String> get() = latestAttempts.filter { (id, value) -> byId[id]?.let { finalNeedsReview(value, it) } == true }.keys
    val availableTopics: List<String> get() = bank.filter { (grade == 0 || it.grade == grade) && (chapter == 0 || it.chapter == chapter) }.map { it.topic }.distinct().sorted()
    val eligible: List<FinalQuestion> get() = bank.filter {
        (grade == 0 || it.grade == grade) && (chapter == 0 || it.chapter == chapter) && (topic.isEmpty() || it.topic == topic) &&
            (type.isEmpty() || it.type == type) && when (reviewFilter) { "weak" -> it.id in weakIds; "bookmarked" -> it.id in bookmarks; else -> true }
    }
    private fun saveFilters() {
        store.saveFilters(JSONObject().put("grade", grade).put("chapter", chapter).put("topic", topic)
            .put("type", type).put("mode", mode).put("review", reviewFilter).put("count", count).put("minutes", minutes))
    }
    fun chooseGrade(value: Int) { if (!ownsProfile()) return; grade = value.takeIf { it in 10..12 } ?: 0; chapter = 0; topic = ""; saveFilters() }
    fun chooseChapter(value: Int) { if (!ownsProfile()) return; chapter = value.takeIf { grade != 0 && it in 1..chapterTitles.getValue(grade).size } ?: 0; topic = ""; saveFilters() }
    fun chooseTopic(value: String) { if (!ownsProfile()) return; topic = value.takeIf { it in availableTopics } ?: ""; saveFilters() }
    fun chooseType(value: String) { if (!ownsProfile()) return; type = value.takeIf { it in finalTypeTitles } ?: ""; saveFilters() }
    fun chooseMode(value: String) { if (!ownsProfile()) return; mode = value.takeIf { it in setOf("practice", "exam") } ?: "practice"; saveFilters() }
    fun chooseReview(value: String) { if (!ownsProfile()) return; reviewFilter = value.takeIf { it in setOf("all", "weak", "bookmarked") } ?: "all"; saveFilters() }
    fun chooseCount(value: Int) { if (!ownsProfile()) return; count = value.coerceIn(1, maxOf(1, bank.size)); saveFilters() }
    fun chooseMinutes(value: Int) { if (!ownsProfile()) return; minutes = value.coerceIn(1, 180); saveFilters() }

    fun start() {
        if (!ownsProfile()) return
        if (eligible.isEmpty()) return
        val solved = latestAttempts.filterValues { it.submitted }.keys
        // Shuffle within each group; new practice prioritizes unseen questions without duplicating an ID.
        val selected = eligible.shuffled().sortedBy { if (it.id in solved) 1 else 0 }.take(count.coerceAtMost(eligible.size))
        val duration = if (mode == "exam") minutes * 60_000L else 0L
        session = FinalSession(mode = mode, questionIds = selected.map { it.id }, durationMillis = duration,
            deadlineWall = if (duration > 0) System.currentTimeMillis() + duration else 0,
            deadlineElapsed = if (duration > 0) SystemClock.elapsedRealtime() + duration else 0, remainingMillis = duration)
        reviewingResult = false
        store.saveSession(session)
    }

    fun editResponse(value: String) {
        tick()
        val s = session ?: return
        if ((s.mode == "exam" && s.complete) || (s.mode == "practice" && s.attempt().submitted)) return
        if (!ownsProfile()) return
        val updated = s.attempt().copy(response = value.take(5000), updatedAt = System.currentTimeMillis())
        updateDraft(updated)
        store.saveAttempt(updated)
    }
    fun chooseTruth(value: Boolean) {
        if (!ownsProfile()) return
        tick()
        val s = session ?: return
        if ((s.mode == "exam" && s.complete) || (s.mode == "practice" && s.attempt().submitted)) return
        val updated = s.attempt().copy(truthChoice = value, updatedAt = System.currentTimeMillis())
        updateDraft(updated)
        store.saveAttempt(updated)
    }
    private fun updateDraft(attempt: FinalAttempt) {
        if (!ownsProfile()) return
        val s = session ?: return
        session = s.copy(drafts = s.drafts + (attempt.questionId to attempt))
        store.saveSession(session)
    }
    fun canSubmit(): Boolean {
        val q = current ?: return false
        val a = currentAttempt ?: return false
        return if (q.type == "true_false") a.truthChoice != null && (a.truthChoice == true || a.response.isNotBlank()) else a.response.isNotBlank()
    }
    fun submit() {
        if (!ownsProfile()) return
        tick()
        if (session?.let { it.mode == "exam" && it.complete } == true || !canSubmit()) return
        val a = currentAttempt ?: return
        val updated = a.copy(submitted = true, updatedAt = System.currentTimeMillis())
        updateDraft(updated)
        store.saveAttempt(updated)
    }
    fun toggleBookmark() {
        if (!ownsProfile()) return
        val id = current?.id ?: return
        store.bookmark(id, id !in bookmarks)
        bookmarks = store.bookmarks()
    }
    fun next() {
        if (!ownsProfile()) return
        val s = session ?: return
        persistCurrent()
        if (s.index == s.questionIds.lastIndex) {
            if (s.complete) reviewingResult = false else if (s.mode == "practice") finish() else showFinish = true
        } else {
            session = s.copy(index = s.index + 1)
            store.saveSession(session)
        }
    }
    fun previous() {
        if (!ownsProfile()) return
        val s = session ?: return
        if (s.index > 0) { persistCurrent(); session = s.copy(index = s.index - 1); store.saveSession(session) }
    }
    fun tick() {
        if (!ownsProfile()) return
        val s = session ?: return
        if (s.complete || s.mode != "exam") return
        val remaining = minOf(s.remainingMillis, (s.deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0),
            (s.deadlineWall - System.currentTimeMillis()).coerceAtLeast(0))
        session = s.copy(remainingMillis = remaining)
        if (remaining == 0L) finish() else if (SystemClock.elapsedRealtime() - lastTimerSave > 5_000) {
            store.saveSession(session); lastTimerSave = SystemClock.elapsedRealtime()
        }
    }
    fun finish() {
        if (!ownsProfile()) return
        val s = session ?: return
        if (s.complete) return
        val rows = s.questionIds.associateWith { id ->
            val a = s.attempt(id)
            a.copy(submitted = a.hasResponse && (s.mode == "exam" || a.submitted), updatedAt = System.currentTimeMillis())
        }
        rows.values.forEach { store.saveAttempt(it) }
        session = s.copy(drafts = rows, complete = true)
        reviewingResult = false; showFinish = false
        store.saveSession(session)
    }
    fun assess(criterionId: String, points: Double) {
        if (!ownsProfile()) return
        if (!canReveal || !points.isFinite()) return
        val q = current ?: return
        val criterion = q.rubric.firstOrNull { it.id == criterionId } ?: return
        val a = currentAttempt ?: return
        if (!a.hasResponse) return
        val updated = a.copy(scores = a.scores + (criterionId to points.coerceIn(0.0, criterion.points)), updatedAt = System.currentTimeMillis())
        updateDraft(updated)
        store.saveAttempt(updated)
    }
    fun reviewResult(index: Int = 0) {
        if (!ownsProfile()) return
        val s = session ?: return
        if (!s.complete) return
        session = s.copy(index = index.coerceIn(0, s.questionIds.lastIndex)); reviewingResult = true
        store.saveSession(session)
    }
    fun closeResult() { if (!ownsProfile()) return; session = null; reviewingResult = false; store.saveSession(null) }
    fun returnToSummary() { reviewingResult = false }
    fun flush() { if (!ownsProfile()) return; tick(); persistCurrent(); store.saveSession(session) }
    private fun persistCurrent() {
        val s = session ?: return
        val a = s.attempt()
        if (a.hasResponse || a.submitted || s.complete) {
            if (attempts[a.key] != a) store.saveAttempt(a)
        }
    }
    override fun onCleared() {
        flush(); store.unregister(prefListener)
        if (owners[localNamespace] === this) owners.remove(localNamespace)
        super.onCleared()
    }
    private companion object { val owners = mutableMapOf<String, FinalStudyViewModel>() }
}
