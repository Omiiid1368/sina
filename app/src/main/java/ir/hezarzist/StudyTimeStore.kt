package ir.hezarzist

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private class DeviceStudyClock(private val context: Context) : StudyClock {
    override fun now() = StudyClockSnapshot(System.currentTimeMillis(), SystemClock.elapsedRealtime(),
        Settings.Global.getInt(context.contentResolver, "boot_count", -1).toString())
}

/** One JSON commit records completion and clears its active UUID together. */
class StudyTimeStore(context: Context, private val clock: StudyClock = DeviceStudyClock(context)) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val epoch = LocalProgress.profileEpoch(app)
    private fun current() = epoch == LocalProgress.profileEpoch(app)
    private fun read(): StudyTimeState = prefs.getString("state", null)?.let { StudyTimeCodec.decode(it) } ?: StudyTimeState()
    private fun write(state: StudyTimeState) {
        check(current()) { "اطلاعات بازگردانی شده‌اند؛ این صفحه را دوباره باز کنید." }
        val raw = StudyTimeCodec.encode(state)
        StudyTimeCodec.decode(raw)
        check(prefs.edit().putString("state", raw).commit()) { "ثبت روی حافظه انجام نشد؛ دوباره تلاش کنید." }
    }

    fun now(): StudyClockSnapshot = clock.now()
    fun today(): LocalDate = Instant.ofEpochMilli(clock.now().wallMillis).atZone(ZoneId.of(STUDY_ZONE)).toLocalDate()

    fun snapshot(): StudyTimeState = synchronized(lock) {
        val state = read()
        val active = state.active
        val anchor = active?.anchor
        if(active != null && anchor != null && (anchor.bootId != clock.now().bootId || clock.now().elapsedMillis < anchor.elapsedMillis) && current()) {
            // A reboot has no shared monotonic clock. Preserve only earned spans and pause for review.
            val recovered = state.copy(active = StudyTimeEngine.checkpoint(active, clock.now(), false).copy(needsReview = true))
            write(recovered); recovered
        } else state
    }

    fun addManual(date: LocalDate, subjectId: String, durationMillis: Long, note: String): StudyRecord = synchronized(lock) {
        require(durationMillis in 60_000..STUDY_DAY_MILLIS) { "مدت دستی باید بین یک دقیقه و ۲۴ ساعت باشد." }
        val state = read(); val now = clock.now().wallMillis
        val record = StudyRecord(subjectId = subjectId, note = note.trim(), manualDate = date, manualDurationMillis = durationMillis, createdAt = now)
        write(state.copy(records = state.records + record)); record
    }

    fun start(subjectId: String, note: String = ""): ActiveStudySession = synchronized(lock) {
        val state = read()
        check(state.active == null) { "ابتدا جلسهٔ فعال را پایان دهید." }
        val now = clock.now()
        val active = ActiveStudySession(subjectId = subjectId, note = note.trim(), startedAtMillis = now.wallMillis, anchor = now)
        write(state.copy(active = active)); active
    }

    fun pause(): StudyTimeState = synchronized(lock) {
        val state = read(); val active = state.active ?: return@synchronized state
        val updated = state.copy(active = StudyTimeEngine.checkpoint(active, clock.now(), false))
        write(updated); updated
    }

    fun resume(): StudyTimeState = synchronized(lock) {
        val state = read(); val active = state.active ?: return@synchronized state
        val updated = state.copy(active = StudyTimeEngine.resume(active, clock.now()))
        write(updated); updated
    }

    fun checkpoint(): StudyTimeState = synchronized(lock) {
        val state = read(); val active = state.active ?: return@synchronized state
        val now = clock.now()
        val sameBoot = active.anchor?.bootId == now.bootId
        val updated = state.copy(active = StudyTimeEngine.checkpoint(active, now, sameBoot))
        write(updated); updated
    }

    /** Repeating finish(id), including after process restore, never appends a second record. */
    fun finish(id: String): StudyRecord? = synchronized(lock) {
        val state = read()
        state.records.firstOrNull { it.id == id }?.let { return@synchronized it }
        val active = state.active?.takeIf { it.id == id } ?: return@synchronized null
        val record = StudyTimeEngine.complete(active, clock.now())
        write(state.copy(records = if(record == null) state.records else state.records + record, active = null))
        record
    }

    fun edit(id: String, date: LocalDate, subjectId: String, durationMillis: Long, note: String): StudyRecord = synchronized(lock) {
        val state = read(); val previous = state.records.first { it.id == id }
        val unchangedTime = date == previous.firstDate && durationMillis == previous.durationMillis
        require(unchangedTime || durationMillis in 60_000..STUDY_DAY_MILLIS) { "مدت ویرایش‌شده باید بین یک دقیقه و ۲۴ ساعت باشد." }
        val record = if(unchangedTime) previous.copy(subjectId = subjectId, note = note.trim(), updatedAt = clock.now().wallMillis)
        else previous.copy(subjectId = subjectId, note = note.trim(), source = if(previous.source == "manual") "manual" else "edited_timer",
            manualDate = date, manualDurationMillis = durationMillis, segments = emptyList(), updatedAt = clock.now().wallMillis, needsReview = false)
        write(state.copy(records = state.records.map { if(it.id == id) record else it })); record
    }

    fun delete(id: String) = synchronized(lock) { val state = read(); write(state.copy(records = state.records.filterNot { it.id == id })) }
    fun setGoal(minutes: Int) = synchronized(lock) { require(minutes in 0..1440); write(read().copy(goalMinutes = minutes)) }

    companion object {
        const val PREFS = "study_time_v1"
        private val lock = Any()
        fun checkpointForBackup(context: Context) { StudyTimeStore(context).checkpoint() }
        fun validateBackupState(raw: String) { StudyTimeCodec.decode(raw) }
        fun backupSummary(raw: String): StudyBackupSummary {
            val state = StudyTimeCodec.decode(raw)
            return StudyBackupSummary(state.records.size, state.active != null, state.records.sumOf { it.durationMillis } + (state.active?.earnedMillis ?: 0))
        }
        fun prepareImportedState(raw: String): String {
            val state = StudyTimeCodec.decode(raw)
            // Only checkpointed spans travel in a backup. A foreign clock can never accrue study time.
            val active = state.active?.let { it.copy(anchor = null, needsReview = it.needsReview || it.running) }
            return StudyTimeCodec.encode(state.copy(active = active))
        }
    }
}

/** Strict, bounded schema shared by persistence and local backup validation. */
object StudyTimeCodec {
    private val stateKeys = setOf("schemaVersion", "records", "active", "goalMinutes")
    private val recordKeys = setOf("id", "subjectId", "note", "source", "manualDate", "manualDurationMillis", "segments", "createdAt", "updatedAt", "needsReview")
    private val activeKeys = setOf("id", "subjectId", "note", "startedAtMillis", "segments", "anchor", "needsReview")
    private const val MIN_WALL = -2_208_988_800_000L // 1900-01-01
    private const val MAX_WALL = 7_258_118_400_000L // 2200-01-01

    fun encode(state: StudyTimeState): String = JSONObject().put("schemaVersion", 1).put("goalMinutes", state.goalMinutes)
        .put("records", JSONArray(state.records.map { r -> JSONObject().put("id", r.id).put("subjectId", r.subjectId).put("note", r.note)
            .put("source", r.source).put("manualDate", r.manualDate?.toString() ?: JSONObject.NULL).put("manualDurationMillis", r.manualDurationMillis)
            .put("segments", segmentArray(r.segments)).put("createdAt", r.createdAt).put("updatedAt", r.updatedAt).put("needsReview", r.needsReview) }))
        .put("active", state.active?.let { a -> JSONObject().put("id", a.id).put("subjectId", a.subjectId).put("note", a.note)
            .put("startedAtMillis", a.startedAtMillis).put("segments", segmentArray(a.segments)).put("needsReview", a.needsReview)
            .put("anchor", a.anchor?.let { c -> JSONObject().put("wallMillis", c.wallMillis).put("elapsedMillis", c.elapsedMillis).put("bootId", c.bootId) } ?: JSONObject.NULL) } ?: JSONObject.NULL).toString()

    fun decode(raw: String): StudyTimeState {
        require(raw.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024) { "فایل زمان مطالعه بیش از حد بزرگ است." }
        boundedJson(raw)
        val root = JSONObject(raw); exact(root, stateKeys); require(number(root, "schemaVersion", 1, 1) == 1L)
        val rows = root.getJSONArray("records"); require(rows.length() <= 10_000)
        val used = mutableSetOf<String>()
        val records = (0 until rows.length()).map { index ->
            val q = rows.getJSONObject(index); exact(q, recordKeys)
            val id = uuid(q); require(used.add(id)) { "شناسهٔ جلسه تکراری است." }
            val subject = subject(q); val note = text(q, "note", 1000)
            val source = text(q, "source", 20); require(source in setOf("manual", "timer", "edited_timer"))
            val date = if(q.isNull("manualDate")) null else LocalDate.parse(text(q, "manualDate", 10)).also { require(it.year in 1900..2199) }
            val manual = number(q, "manualDurationMillis", 0, STUDY_DAY_MILLIS)
            val segments = segments(q.getJSONArray("segments"))
            require(if(date != null) manual in 60_000..STUDY_DAY_MILLIS && segments.isEmpty() && source != "timer" else manual == 0L && segments.isNotEmpty() && source == "timer")
            StudyRecord(id, subject, note, source, date, manual, segments, wall(q, "createdAt"), wall(q, "updatedAt"), bool(q, "needsReview"))
        }
        val active = if(root.isNull("active")) null else root.getJSONObject("active").let { q ->
            exact(q, activeKeys); val id = uuid(q); require(used.add(id)) { "جلسهٔ فعال قبلاً ثبت شده است." }
            val segments = segments(q.getJSONArray("segments"))
            val started = wall(q, "startedAtMillis")
            require(segments.isEmpty() || segments.first().startedAtMillis >= started)
            val anchor = if(q.isNull("anchor")) null else q.getJSONObject("anchor").let { c ->
                exact(c, setOf("wallMillis", "elapsedMillis", "bootId"))
                StudyClockSnapshot(wall(c, "wallMillis"), number(c, "elapsedMillis", 0, Long.MAX_VALUE), text(c, "bootId", 80).also { require(it.isNotBlank()) })
            }
            require(anchor == null || anchor.wallMillis >= (segments.lastOrNull()?.endedAtMillis ?: started))
            ActiveStudySession(id, subject(q), text(q, "note", 1000), started, segments, anchor, bool(q, "needsReview"))
        }
        return StudyTimeState(records, active, number(root, "goalMinutes", 0, 1440).toInt())
    }

    private fun boundedJson(raw: String) {
        var depth = 0; var quoted = false; var escaped = false
        raw.forEach { c ->
            if(quoted) {
                if(escaped) escaped = false else if(c == '\\') escaped = true else if(c == '"') quoted = false
            } else when(c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 12) { "ساختار فایل بیش از حد پیچیده است." } }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
    }

    private fun segmentArray(values: List<StudySegment>) = JSONArray(values.map { s -> JSONObject().put("startedAtMillis", s.startedAtMillis).put("durationMillis", s.durationMillis).put("zoneId", s.zoneId) })
    private fun segments(rows: JSONArray): List<StudySegment> {
        require(rows.length() <= 10_000)
        val values = (0 until rows.length()).map { i -> rows.getJSONObject(i).let { s ->
            exact(s, setOf("startedAtMillis", "durationMillis", "zoneId"))
            StudySegment(wall(s, "startedAtMillis"), number(s, "durationMillis", 1, STUDY_MAX_MILLIS), text(s, "zoneId", 80).also { ZoneId.of(it) })
        } }
        require(values.sumOf { it.durationMillis } <= STUDY_MAX_MILLIS)
        values.forEachIndexed { index, segment ->
            require(segment.endedAtMillis <= MAX_WALL)
            if(index > 0) require(segment.startedAtMillis >= values[index - 1].endedAtMillis) { "بازه‌های مطالعه هم‌پوشانی دارند." }
        }
        return values
    }
    private fun uuid(q: JSONObject): String = text(q, "id", 36).also { require(UUID.fromString(it).toString() == it) }
    private fun subject(q: JSONObject) = text(q, "subjectId", 40).also { require(it in studySubjectTitles) }
    private fun exact(q: JSONObject, keys: Set<String>) { require(q.keys().asSequence().toSet() == keys) { "ساختار زمان مطالعه معتبر نیست." } }
    private fun text(q: JSONObject, key: String, max: Int): String = (q.get(key) as? String ?: error("متن نامعتبر")).also { require(it.length <= max && it.none { c -> c == '\u0000' }) }
    private fun bool(q: JSONObject, key: String) = q.get(key) as? Boolean ?: error("مقدار منطقی نامعتبر")
    private fun wall(q: JSONObject, key: String) = number(q, key, MIN_WALL, MAX_WALL)
    private fun number(q: JSONObject, key: String, min: Long, max: Long): Long {
        val value = q.get(key); require(value is Int || value is Long) { "عدد صحیح لازم است." }
        return (value as Number).toLong().also { require(it in min..max) }
    }
}
