package ir.hezarzist

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StudyTimeEngineTest {
    private val wall = LocalDate.of(2026, 11, 8).atTime(20, 0).atZone(ZoneId.of(STUDY_ZONE)).toInstant().toEpochMilli()
    private fun clock(minutes: Int, wallMinutes: Int = minutes, boot: String = "boot-a") = StudyClockSnapshot(wall + wallMinutes * 60_000L, 10_000 + minutes * 60_000L, boot)
    private fun active() = ActiveStudySession(subjectId = "biology", startedAtMillis = wall, anchor = clock(0))

    @Test fun pauseAndContinueExcludeTheEntirePausedGap() {
        val paused = StudyTimeEngine.checkpoint(active(), clock(5), false)
        assertEquals(300_000L, StudyTimeEngine.duration(paused, clock(25)))
        assertFalse(paused.running)
        val resumed = StudyTimeEngine.resume(paused, clock(25))
        val result = StudyTimeEngine.complete(resumed, clock(28))!!
        assertEquals(480_000L, result.durationMillis)
        assertEquals(listOf(300_000L, 180_000L), result.segments.map { it.durationMillis })
    }

    @Test fun processRecreationAndWallClockChangesKeepMonotonicDuration() {
        val restored = active().copy()
        val afterForwardWallChange = StudyTimeEngine.checkpoint(restored, clock(10, wallMinutes = 24 * 60), false)
        assertEquals(600_000L, afterForwardWallChange.earnedMillis)
        assertTrue(afterForwardWallChange.needsReview)
        val afterBackwardWallChange = StudyTimeEngine.checkpoint(active(), clock(10, wallMinutes = -30), false)
        assertEquals(600_000L, afterBackwardWallChange.earnedMillis)
        assertEquals(wall, afterBackwardWallChange.segments.single().startedAtMillis)
    }

    @Test fun rebootPreservesEarnedTimeAndNeverCreditsAnUntrustedPoweredOffGap() {
        val checkpointed = StudyTimeEngine.checkpoint(active(), clock(5), true)
        val rebooted = StudyClockSnapshot(wall + STUDY_DAY_MILLIS, 1000, "boot-b")
        val recovered = StudyTimeEngine.checkpoint(checkpointed, rebooted, true)
        assertFalse(recovered.running)
        assertTrue(recovered.needsReview)
        assertEquals(300_000L, recovered.earnedMillis)
        assertEquals(300_000L, StudyTimeEngine.duration(recovered, rebooted))
    }

    @Test fun elapsedRegressionAlsoPausesWhenBootIdentifierIsUnavailable() {
        val session = ActiveStudySession(subjectId = "biology", startedAtMillis = wall, anchor = StudyClockSnapshot(wall, 100_000, "-1"))
        val recovered = StudyTimeEngine.checkpoint(session, StudyClockSnapshot(wall + STUDY_DAY_MILLIS, 100, "-1"), true)
        assertFalse(recovered.running)
        assertTrue(recovered.needsReview)
        assertEquals(0L, recovered.earnedMillis)
    }

    @Test fun aMidnightCrossingIsAllocatedToBothTehranDatesWithoutLoss() {
        val date = LocalDate.of(2026, 11, 8)
        val start = date.atTime(23, 50).atZone(ZoneId.of(STUDY_ZONE)).toInstant().toEpochMilli()
        val record = StudyRecord(subjectId = "biology", source = "timer", segments = listOf(StudySegment(start, 40 * 60_000L)), createdAt = start)
        val days = studyDailyAllocation(record)
        assertEquals(10 * 60_000L, days[date])
        assertEquals(30 * 60_000L, days[date.plusDays(1)])
        assertEquals(record.durationMillis, days.values.sum())
    }

    @Test fun pausedMidnightGapIsNotAllocatedToEitherDay() {
        val day = LocalDate.of(2026, 11, 8)
        val before = day.atTime(23, 30).atZone(ZoneId.of(STUDY_ZONE)).toInstant().toEpochMilli()
        val after = day.plusDays(1).atTime(0, 15).atZone(ZoneId.of(STUDY_ZONE)).toInstant().toEpochMilli()
        val record = StudyRecord(subjectId = "biology", source = "timer", segments = listOf(StudySegment(before, 15 * 60_000L), StudySegment(after, 30 * 60_000L)), createdAt = before)
        assertEquals(mapOf(day to 15 * 60_000L, day.plusDays(1) to 30 * 60_000L), studyDailyAllocation(record))
    }

    @Test fun biologyAndOtherSubjectsRemainSeparateAndManualDatesStayExplicit() {
        val day = LocalDate.of(2026, 11, 8)
        val records = listOf(StudyRecord(subjectId = "biology", manualDate = day, manualDurationMillis = 60 * 60_000L, createdAt = wall),
            StudyRecord(subjectId = "chemistry", manualDate = day, manualDurationMillis = 30 * 60_000L, createdAt = wall))
        assertEquals(90 * 60_000L, studyDailyTotals(records)[day])
        assertEquals(60 * 60_000L, studyDailyTotals(records, "biology")[day])
    }
}
