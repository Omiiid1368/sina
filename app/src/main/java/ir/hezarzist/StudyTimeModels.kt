package ir.hezarzist

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.abs

val studySubjectTitles = linkedMapOf(
    "biology" to "زیست‌شناسی", "chemistry" to "شیمی", "physics" to "فیزیک",
    "math" to "ریاضی", "geology" to "زمین‌شناسی", "persian" to "فارسی",
    "arabic" to "عربی", "religion" to "دین و زندگی", "english" to "زبان انگلیسی", "other" to "سایر درس‌ها"
)

const val STUDY_ZONE = "Asia/Tehran"
const val STUDY_DAY_MILLIS = 86_400_000L
const val STUDY_MAX_MILLIS = 366 * STUDY_DAY_MILLIS

data class StudyClockSnapshot(val wallMillis: Long, val elapsedMillis: Long, val bootId: String)
fun interface StudyClock { fun now(): StudyClockSnapshot }

data class StudySegment(val startedAtMillis: Long, val durationMillis: Long, val zoneId: String = STUDY_ZONE) {
    val endedAtMillis: Long get() = Math.addExact(startedAtMillis, durationMillis)
}

data class StudyRecord(
    val id: String = UUID.randomUUID().toString(), val subjectId: String, val note: String = "",
    val source: String = "manual", val manualDate: LocalDate? = null, val manualDurationMillis: Long = 0,
    val segments: List<StudySegment> = emptyList(), val createdAt: Long, val updatedAt: Long = createdAt,
    val needsReview: Boolean = false
) {
    val durationMillis: Long get() = if(manualDate != null) manualDurationMillis else segments.sumOf { it.durationMillis }
    val firstDate: LocalDate get() = manualDate ?: Instant.ofEpochMilli(segments.first().startedAtMillis).atZone(ZoneId.of(segments.first().zoneId)).toLocalDate()
}

data class ActiveStudySession(
    val id: String = UUID.randomUUID().toString(), val subjectId: String, val note: String = "",
    val startedAtMillis: Long, val segments: List<StudySegment> = emptyList(),
    val anchor: StudyClockSnapshot? = null, val needsReview: Boolean = false
) {
    val running: Boolean get() = anchor != null
    val earnedMillis: Long get() = segments.sumOf { it.durationMillis }
}

data class StudyTimeState(val records: List<StudyRecord> = emptyList(), val active: ActiveStudySession? = null, val goalMinutes: Int = 120)
data class StudyBackupSummary(val recordCount: Int, val hasActive: Boolean, val earnedMillis: Long)

/** Durations use the monotonic clock; wall time is used only to place spans on the calendar. */
object StudyTimeEngine {
    fun elapsed(anchor: StudyClockSnapshot, now: StudyClockSnapshot): Long {
        val delta = if(anchor.bootId == now.bootId && now.elapsedMillis >= anchor.elapsedMillis) now.elapsedMillis - anchor.elapsedMillis else 0L
        return delta.coerceIn(0, STUDY_MAX_MILLIS)
    }

    fun duration(active: ActiveStudySession, now: StudyClockSnapshot): Long =
        (active.earnedMillis + (active.anchor?.let { elapsed(it, now) } ?: 0)).coerceAtMost(STUDY_MAX_MILLIS)

    fun checkpoint(active: ActiveStudySession, now: StudyClockSnapshot, keepRunning: Boolean): ActiveStudySession {
        val anchor = active.anchor ?: return active
        if(anchor.bootId != now.bootId || now.elapsedMillis < anchor.elapsedMillis) {
            // A reboot cannot prove how much of a wall-clock gap was study. Keep earned time only.
            return active.copy(anchor = null, needsReview = true)
        }
        val available = (STUDY_MAX_MILLIS - active.earnedMillis).coerceAtLeast(0)
        val duration = elapsed(anchor, now).coerceAtMost(available)
        val segment = StudySegment(anchor.wallMillis, duration)
        val segments = if(duration > 0) active.segments + segment else active.segments
        val clockChanged = anchor.bootId != now.bootId || abs((now.wallMillis - anchor.wallMillis).toDouble() - duration) > 120_000
        val nextAnchor = if(keepRunning && duration < available) StudyClockSnapshot(anchor.wallMillis + duration, now.elapsedMillis, now.bootId) else null
        return active.copy(segments = coalesce(segments), anchor = nextAnchor, needsReview = active.needsReview || clockChanged)
    }

    fun resume(active: ActiveStudySession, now: StudyClockSnapshot): ActiveStudySession {
        if(active.running || active.earnedMillis >= STUDY_MAX_MILLIS) return active
        val lastEnd = active.segments.lastOrNull()?.endedAtMillis ?: active.startedAtMillis
        return active.copy(anchor = now.copy(wallMillis = maxOf(now.wallMillis, lastEnd)), needsReview = active.needsReview || now.wallMillis < lastEnd)
    }

    fun complete(active: ActiveStudySession, now: StudyClockSnapshot): StudyRecord? {
        val ended = checkpoint(active, now, false)
        if(ended.earnedMillis == 0L) return null
        return StudyRecord(id = ended.id, subjectId = ended.subjectId, note = ended.note, source = "timer",
            segments = ended.segments, createdAt = ended.startedAtMillis, updatedAt = now.wallMillis, needsReview = ended.needsReview)
    }

    private fun coalesce(segments: List<StudySegment>): List<StudySegment> {
        if(segments.size < 2) return segments
        val result = mutableListOf<StudySegment>()
        segments.forEach { segment ->
            val previous = result.lastOrNull()
            if(previous != null && previous.zoneId == segment.zoneId && previous.endedAtMillis == segment.startedAtMillis) {
                result[result.lastIndex] = previous.copy(durationMillis = previous.durationMillis + segment.durationMillis)
            } else result.add(segment)
        }
        return result
    }
}

/** Splits real elapsed spans at local midnight, including historical time-zone offset changes. */
fun studyDailyAllocation(record: StudyRecord): Map<LocalDate, Long> {
    record.manualDate?.let { return mapOf(it to record.manualDurationMillis) }
    val result = linkedMapOf<LocalDate, Long>()
    record.segments.forEach { segment ->
        val zone = ZoneId.of(segment.zoneId)
        var cursor = segment.startedAtMillis
        var left = segment.durationMillis
        while(left > 0) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val nextMidnight = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val portion = minOf(left, nextMidnight - cursor)
            require(portion > 0)
            result[date] = (result[date] ?: 0) + portion
            cursor += portion; left -= portion
        }
    }
    return result
}

fun studyDailyTotals(records: List<StudyRecord>, subjectId: String? = null): Map<LocalDate, Long> {
    val result = linkedMapOf<LocalDate, Long>()
    records.filter { subjectId == null || it.subjectId == subjectId }.forEach { record ->
        studyDailyAllocation(record).forEach { (date, duration) -> result[date] = (result[date] ?: 0) + duration }
    }
    return result
}

fun studyDurationLabel(millis: Long, seconds: Boolean = false): String {
    val total = millis.coerceAtLeast(0) / 1000
    val hours = total / 3600; val minutes = total / 60 % 60
    return if(seconds) "${fa(hours.toString().padStart(2, '0'))}:${fa(minutes.toString().padStart(2, '0'))}:${fa((total % 60).toString().padStart(2, '0'))}"
    else "${fa(hours)} ساعت و ${fa(minutes)} دقیقه"
}
