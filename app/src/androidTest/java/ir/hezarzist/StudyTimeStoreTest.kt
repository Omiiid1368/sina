package ir.hezarzist

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class StudyTimeStoreTest {
    private lateinit var app: Application
    private val date = LocalDate.of(2026, 11, 8)
    private val wall = date.atTime(23, 40).atZone(ZoneId.of(STUDY_ZONE)).toInstant().toEpochMilli()
    private var reading = StudyClockSnapshot(wall, 100_000, "test-boot")
    private val clock = StudyClock { reading }
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun advance(minutes: Int) { reading = reading.copy(wallMillis = reading.wallMillis + minutes * 60_000L, elapsedMillis = reading.elapsedMillis + minutes * 60_000L) }

    @Before fun reset() = main {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(StudyTimeStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        reading = StudyClockSnapshot(wall, 100_000, "test-boot")
    }

    @Test fun processRestorePauseContinueFinishAreDurableAndIdempotent() = main {
        val first = StudyTimeStore(app, clock)
        val active = first.start("biology", "مطالعهٔ فصل")
        advance(30)
        val restored = StudyTimeStore(app, clock)
        assertEquals(30 * 60_000L, StudyTimeEngine.duration(restored.snapshot().active!!, reading))
        restored.pause(); advance(20)
        assertEquals(30 * 60_000L, StudyTimeEngine.duration(StudyTimeStore(app, clock).snapshot().active!!, reading))
        restored.resume(); advance(10)
        val record = restored.finish(active.id)!!
        assertEquals(40 * 60_000L, record.durationMillis)
        assertEquals(record, restored.finish(active.id))
        val final = StudyTimeStore(app, clock).snapshot()
        assertNull(final.active); assertEquals(1, final.records.size)
        assertEquals(20 * 60_000L, studyDailyTotals(final.records)[date])
        assertEquals(20 * 60_000L, studyDailyTotals(final.records)[date.plusDays(1)])
    }

    @Test fun onlyOneActiveSessionExistsAndRebootRecoveryDoesNotAddWallGap() = main {
        val store = StudyTimeStore(app, clock); val active = store.start("biology")
        assertTrue(runCatching { store.start("chemistry") }.isFailure)
        advance(5); store.checkpoint()
        reading = StudyClockSnapshot(wall + STUDY_DAY_MILLIS, 1000, "next-boot")
        val restored = StudyTimeStore(app, clock).snapshot().active!!
        assertEquals(active.id, restored.id); assertFalse(restored.running); assertTrue(restored.needsReview)
        assertEquals(5 * 60_000L, restored.earnedMillis)
    }

    @Test fun manualEditDeleteGoalAndEpochInvalidationPreserveCorrectValues() = main {
        val store = StudyTimeStore(app, clock)
        val row = store.addManual(date, "biology", 90 * 60_000L, "یادداشت")
        store.setGoal(180)
        val edited = store.edit(row.id, date.plusDays(1), "chemistry", 45 * 60_000L, "اصلاح")
        assertEquals(row.id, edited.id); assertEquals(45 * 60_000L, edited.durationMillis)
        assertEquals(mapOf(date.plusDays(1) to 45 * 60_000L), studyDailyAllocation(edited))
        LocalProgress.invalidate(app)
        assertTrue(runCatching { store.delete(row.id) }.isFailure)
        val fresh = StudyTimeStore(app, clock); assertEquals(180, fresh.snapshot().goalMinutes)
        fresh.delete(row.id); assertTrue(fresh.snapshot().records.isEmpty())
    }

    @Test fun backupNormalizationPausesForeignAnchorWithoutDuplicatingEarnedSegments() = main {
        val store = StudyTimeStore(app, clock); val active = store.start("biology")
        advance(7); store.checkpoint()
        val raw = app.getSharedPreferences(StudyTimeStore.PREFS, Context.MODE_PRIVATE).getString("state", null)!!
        StudyTimeStore.validateBackupState(raw)
        val imported = StudyTimeCodec.decode(StudyTimeStore.prepareImportedState(raw))
        assertEquals(active.id, imported.active!!.id)
        assertNull(imported.active.anchor)
        assertEquals(7 * 60_000L, imported.active.earnedMillis)
        assertTrue(imported.records.isEmpty())
        assertEquals(7 * 60_000L, StudyTimeStore.backupSummary(raw).earnedMillis)
    }

    @Test fun duplicateUuidsAndOverlappingSpansAreRejected() = main {
        val record = StudyRecord(subjectId = "biology", manualDate = date, manualDurationMillis = 60_000, createdAt = wall)
        assertTrue(runCatching { StudyTimeCodec.decode(StudyTimeCodec.encode(StudyTimeState(records = listOf(record, record)))) }.isFailure)
        val timed = record.copy(source = "timer", manualDate = null, manualDurationMillis = 0,
            segments = listOf(StudySegment(wall, 60_000), StudySegment(wall + 30_000, 60_000)))
        assertTrue(runCatching { StudyTimeStore.validateBackupState(StudyTimeCodec.encode(StudyTimeState(records = listOf(timed)))) }.isFailure)
        val raw = JSONObject(StudyTimeCodec.encode(StudyTimeState(records = listOf(record))))
        raw.getJSONArray("records").getJSONObject(0).put("subjectId", "unknown-subject")
        assertTrue(runCatching { StudyTimeStore.validateBackupState(raw.toString()) }.isFailure)
    }

    @Test fun editingOnlyMetadataKeepsTimerSecondsAndCrossDayAllocation() = main {
        val store = StudyTimeStore(app, clock); val active = store.start("biology")
        reading = reading.copy(wallMillis = reading.wallMillis + 30_500, elapsedMillis = reading.elapsedMillis + 30_500)
        val record = store.finish(active.id)!!
        val edited = store.edit(record.id, record.firstDate, "chemistry", record.durationMillis, "نام درس اصلاح شد")
        assertEquals(30_500L, edited.durationMillis)
        assertEquals(record.segments, edited.segments)
        assertEquals("timer", edited.source)
    }
}
