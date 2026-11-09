package ir.hezarzist

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class FinalRubric(val id: String, val criterion: String, val points: Double)
data class FinalSource(val kind: String, val book: String, val section: String, val year: String = "", val session: String = "", val url: String = "") {
    val label: String get() = when (kind) {
        "official_final" -> "سؤال رسمی نهایی"
        "coordinated" -> "سؤال هماهنگ"
        else -> "تألیفی بر پایهٔ کتاب"
    }
}
data class FinalQuestion(
    val id: String, val grade: Int, val chapter: Int, val topic: String, val type: String,
    val stem: String, val answer: String, val rubric: List<FinalRubric>, val totalPoints: Double,
    val marking: String, val source: FinalSource, val image: String? = null,
    val imageDescription: String = "شکل سؤال؛ پاسخ را از روی شکل بنویسید.", val truth: Boolean? = null
)

val finalTypeTitles = linkedMapOf(
    "true_false" to "درست و نادرست", "fill_blank" to "جای خالی", "short" to "پاسخ کوتاه",
    "definition_comparison" to "تعریف و مقایسه", "essay" to "تشریحی",
    "diagram_label" to "تحلیل و نام‌گذاری شکل", "matching" to "وصل‌کردنی"
)

object FinalsRepository {
    fun load(context: Context): List<FinalQuestion> {
        val text = StudyAssets.open(context, "finals.json").bufferedReader().use { it.readText() }
        val items = if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text).getJSONArray("questions")
        val result = (0 until items.length()).map { index ->
            val q = items.getJSONObject(index)
            val source = q.getJSONObject("source")
            val rows = q.getJSONArray("rubric")
            FinalQuestion(q.getString("id"), q.getInt("grade"), q.getInt("chapter"), q.getString("topic"),
                q.getString("type"), q.getString("stem"), q.getString("answer"),
                (0 until rows.length()).map { i -> rows.getJSONObject(i).let { FinalRubric(it.getString("id"), it.getString("criterion"), it.getDouble("points")) } },
                q.getDouble("totalPoints"), q.getString("marking"),
                FinalSource(source.getString("kind"), source.getString("book"), source.getString("section"), source.optString("year"), source.optString("session"), source.optString("url")),
                q.optString("image").takeIf { it.isNotBlank() && it != "null" },
                q.optString("imageDescription", "شکل سؤال؛ پاسخ را از روی شکل بنویسید."),
                if (q.has("truth") && !q.isNull("truth")) q.getBoolean("truth") else null)
        }
        require(result.map { it.id }.toSet().size == result.size) { "شناسه‌های بخش نهایی معتبر نیستند" }
        result.forEach { q ->
            require(q.type in finalTypeTitles && q.grade in 10..12 && q.chapter in 1..chapterTitles.getValue(q.grade).size)
            require(q.stem.isNotBlank() && q.answer.isNotBlank() && q.rubric.isNotEmpty())
            require(q.totalPoints.isFinite() && q.totalPoints > 0 && q.rubric.all { it.id.isNotBlank() && it.points.isFinite() && it.points > 0 && it.criterion.isNotBlank() } && q.rubric.map { it.id }.distinct().size == q.rubric.size)
            require(kotlin.math.abs(q.rubric.sumOf { it.points } - q.totalPoints) < .001)
            require(q.marking in listOf("suggested", "official") && q.source.kind in listOf("authored", "official_final", "coordinated"))
            require(q.source.kind != "authored" || q.marking == "suggested")
            if (q.type == "diagram_label") require(q.image != null) { "سؤال تصویری بدون تصویر: ${q.id}" }
            if (q.type == "true_false") require(q.truth != null) { "پاسخ درست و نادرست مشخص نیست: ${q.id}" }
            q.image?.let { path ->
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                StudyAssets.open(context, path).use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth in 1..8000 && bounds.outHeight in 1..8000 && bounds.outWidth.toLong() * bounds.outHeight <= 24_000_000) { "تصویر سؤال معتبر نیست: ${q.id}" }
            }
        }
        return result
    }
}

data class FinalAttempt(
    val key: String, val questionId: String, val sessionId: String, val mode: String,
    val response: String = "", val truthChoice: Boolean? = null, val submitted: Boolean = false,
    val scores: Map<String, Double> = emptyMap(), val updatedAt: Long = System.currentTimeMillis()
) {
    val hasResponse: Boolean get() = response.isNotBlank() || truthChoice != null
    fun json() = JSONObject().put("key", key).put("questionId", questionId).put("sessionId", sessionId).put("mode", mode)
        .put("response", response).put("truthChoice", truthChoice ?: JSONObject.NULL).put("submitted", submitted)
        .put("scores", JSONObject(scores)).put("updatedAt", updatedAt)
    companion object {
        fun parse(o: JSONObject): FinalAttempt {
            val points = o.optJSONObject("scores") ?: JSONObject()
            return FinalAttempt(o.getString("key"), o.getString("questionId"), o.getString("sessionId"), o.optString("mode", "practice"),
                o.optString("response").take(5000), if (o.has("truthChoice") && !o.isNull("truthChoice")) o.getBoolean("truthChoice") else null,
                o.optBoolean("submitted"), points.keys().asSequence().associateWith { points.getDouble(it) }, o.optLong("updatedAt"))
        }
    }
}

data class FinalSession(
    val id: String = UUID.randomUUID().toString(), val mode: String, val questionIds: List<String>,
    val index: Int = 0, val drafts: Map<String, FinalAttempt> = emptyMap(), val complete: Boolean = false,
    val startedAt: Long = System.currentTimeMillis(), val durationMillis: Long = 0,
    val deadlineWall: Long = 0, val deadlineElapsed: Long = 0, val remainingMillis: Long = durationMillis
) {
    val currentId: String get() = questionIds[index]
    fun attempt(id: String = currentId): FinalAttempt = drafts[id] ?: FinalAttempt("${this.id}:$id", id, this.id, mode)
    fun json(): JSONObject = JSONObject().put("id", id).put("mode", mode).put("questionIds", JSONArray(questionIds))
        .put("index", index).put("drafts", JSONArray(drafts.values.map { it.json() })).put("complete", complete)
        .put("startedAt", startedAt).put("durationMillis", durationMillis).put("deadlineWall", deadlineWall)
        .put("remainingMillis", remainingMillis).put("savedElapsed", SystemClock.elapsedRealtime())
    companion object {
        fun parse(o: JSONObject): FinalSession {
            val ids = o.getJSONArray("questionIds").let { a -> (0 until a.length()).map { a.getString(it) } }
            require(ids.isNotEmpty() && ids.size == ids.distinct().size)
            require(o.getString("mode") in setOf("practice", "exam"))
            val drafts = o.optJSONArray("drafts") ?: JSONArray()
            val savedRemaining = o.optLong("remainingMillis").coerceAtLeast(0)
            val now = SystemClock.elapsedRealtime()
            val elapsedDelta = now - o.optLong("savedElapsed", now)
            val monotonicRemaining = if (elapsedDelta >= 0) (savedRemaining - elapsedDelta).coerceAtLeast(0) else savedRemaining
            val remaining = if (o.optString("mode") == "exam") minOf(savedRemaining, monotonicRemaining, (o.optLong("deadlineWall") - System.currentTimeMillis()).coerceAtLeast(0)) else 0
            return FinalSession(o.getString("id"), o.getString("mode"), ids, o.optInt("index").coerceIn(0, ids.lastIndex),
                (0 until drafts.length()).map { FinalAttempt.parse(drafts.getJSONObject(it)) }.associateBy { it.questionId },
                o.optBoolean("complete"), o.optLong("startedAt"), o.optLong("durationMillis"), o.optLong("deadlineWall"), now + remaining, remaining)
        }
    }
}

/** Device-local storage. A restore invalidates old stores before replacing preferences. */
class FinalsProgressStore(private val context: Context) {
    private val namespace = LocalProgress.namespace(context)
    private val profileEpoch = LocalProgress.profileEpoch(context, namespace)
    private val prefs = context.getSharedPreferences("final_study_v2_$namespace", Context.MODE_PRIVATE)
    private val epoch = prefs.getString("storeEpoch", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("storeEpoch", it).commit()
    }
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()
    fun isCurrent(): Boolean {
        val active = LocalProgress.profileEpoch(context, namespace) == profileEpoch && prefs.getString("storeEpoch", null) == epoch
        if (!active) { listeners.toList().forEach { prefs.unregisterOnSharedPreferenceChangeListener(it) }; listeners.clear() }
        return active
    }
    fun session(availableIds: Set<String>? = null): FinalSession? {
        val raw = prefs.getString("session", null) ?: return null
        val data = runCatching { JSONObject(raw) }.getOrNull()
        val parsed = data?.let { runCatching { FinalSession.parse(it) }.getOrNull() }
        val snapshot = data?.optString("bankSnapshot", "bundled") ?: "bundled"
        if (parsed == null || snapshot !in setOf("", "bundled") || (availableIds != null && parsed.questionIds.any { it !in availableIds })) {
            // Never reinterpret an old downloaded question or silently discard its written work.
            if (isCurrent()) {
                val archive = runCatching { JSONArray(prefs.getString("archivedSessions", "[]")) }.getOrDefault(JSONArray())
                archive.put(raw)
                prefs.edit().putString("archivedSessions", archive.toString()).remove("session").commit()
            }
            return null
        }
        return parsed
    }
    fun saveSession(session: FinalSession?) {
        if (!isCurrent()) return
        val edit = prefs.edit()
        if (session == null) edit.remove("session") else edit.putString("session", session.json().toString())
        edit.commit()
    }
    fun filters(): JSONObject = runCatching { JSONObject(prefs.getString("filters", "{}").orEmpty()) }.getOrDefault(JSONObject())
    fun saveFilters(value: JSONObject) { if (isCurrent()) prefs.edit().putString("filters", value.toString()).commit() }
    fun attempts(): Map<String, FinalAttempt> = prefs.all.filterKeys { it.startsWith("answer:") }.mapNotNull { (key, value) ->
        runCatching { key.removePrefix("answer:") to FinalAttempt.parse(JSONObject(value as String)) }.getOrNull()
    }.toMap()
    fun bookmarks(): Set<String> = prefs.all.filter { it.key.startsWith("bookmark:") && it.value == true }.keys.map { it.removePrefix("bookmark:") }.toSet()
    fun saveAttempt(attempt: FinalAttempt) {
        if (isCurrent()) prefs.edit().putString("answer:${attempt.key}", attempt.json().toString()).commit()
    }
    fun bookmark(id: String, selected: Boolean) {
        if (isCurrent()) prefs.edit().putBoolean("bookmark:$id", selected).commit()
    }
    fun register(listener: SharedPreferences.OnSharedPreferenceChangeListener) { if (isCurrent()) { listeners.add(listener); prefs.registerOnSharedPreferenceChangeListener(listener) } }
    fun unregister(listener: SharedPreferences.OnSharedPreferenceChangeListener) { listeners.remove(listener); prefs.unregisterOnSharedPreferenceChangeListener(listener) }
}

/** Partial credit is assigned by the learner against explicit criteria, never by text matching. */
fun finalAssessment(attempt: FinalAttempt, question: FinalQuestion): Double? =
    if (question.rubric.all { attempt.scores[it.id]?.isFinite() == true }) question.rubric.sumOf { (attempt.scores[it.id] ?: 0.0).coerceIn(0.0, it.points) } else null

fun finalNeedsReview(attempt: FinalAttempt, question: FinalQuestion): Boolean =
    !attempt.submitted || !attempt.hasResponse || finalAssessment(attempt, question)?.let { it < question.totalPoints * .6 } != false
