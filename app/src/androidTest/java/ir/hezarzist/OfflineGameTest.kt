package ir.hezarzist

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class OfflineGameTest {
    private lateinit var app: Application
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        main { LocalProgress.initialize(app); LocalProgress.invalidate(app) }
        app.getSharedPreferences("hezarzist_progress_v1", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("hezarzist_progress_v2_anonymous", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun packagedBankIsIndependentAndOfflineModesRemainAvailable() {
        val bank=BankRepository.load(app)
        val imported = org.json.JSONObject(app.assets.open("imports/report.json").bufferedReader().use { it.readText() }).getInt("addedMcq")
        assertEquals(96 + imported,bank.size)
        assertEquals(96, bank.count { it.origin.kind == "authored" })
        assertEquals(bank.size,bank.map { it.id }.toSet().size)
        assertEquals(mapOf(10 to 28,11 to 36,12 to 32),bank.filter { it.origin.kind == "authored" }.groupingBy { it.grade }.eachCount())
        assertEquals(setOf(10, 11, 12), bank.map { it.grade }.toSet())
        assertTrue(bank.all { it.options.size==4 && it.answer in 0..3 && it.optionExplanations.size==4 })
        @Suppress("DEPRECATION")
        val permissions=app.packageManager.getPackageInfo(app.packageName,4096).requestedPermissions.orEmpty()
        assertFalse(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in permissions)
        assertTrue(bank.all { it.id.startsWith("V2-") || it.id.startsWith("UP-") })
        assertTrue(bank.all { it.origin.kind in setOf("authored", "user_supplied_compilation") && it.license.distributionAllowed })
    }

    @Test fun practiceAnswersBookmarksAndSettingsSurviveNewStore() = main {
        val vm=GameViewModel(app)
        vm.configure(GameMode.PRACTICE);vm.selectedCount=1
        assertTrue(vm.start())
        val question=vm.session!!.current
        vm.toggleBookmark(question.id)
        vm.changeSettings(GameSettings(dark=true,sound=false,textScale=1.25f))
        vm.answer(question.answer)
        assertTrue(vm.session!!.revealed)
        assertEquals(1,vm.progress[question.id]?.last)
        vm.next()
        assertEquals("result",vm.route)
        val reloaded=ProgressStore(app)
        assertTrue(question.id in reloaded.bookmarks)
        assertEquals(1,reloaded.progress[question.id]?.correct)
        assertTrue(reloaded.settings.dark);assertFalse(reloaded.settings.sound)
        assertEquals(1.25f,reloaded.settings.textScale,0.001f)
        assertEquals(100,reloaded.totalScore)
        assertEquals(1,reloaded.history.size)
        vm.finish()
        assertEquals(1,ProgressStore(app).history.size)
    }

    @Test fun quickTimeoutCannotBeAnsweredLateAndNoRepeats() = main {
        val vm=GameViewModel(app)
        vm.configure(GameMode.QUICK)
        assertTrue(vm.start())
        val start=vm.session!!
        assertEquals(10,start.questions.size)
        assertEquals(10,start.questions.map { it.id }.toSet().size)
        vm.answer(start.current.answer,start.deadlineMillis+1)
        assertEquals(-1,vm.session!!.answers[0]!!.selected)
        assertEquals(0,vm.session!!.score)
        vm.next(start.deadlineMillis+10)
        val second=vm.session!!
        vm.answer(second.current.answer,second.deadlineMillis-25000)
        assertEquals(150,vm.session!!.answers[1]!!.score)
        vm.finish()
        assertEquals(1,vm.session!!.correct);assertEquals(9,vm.session!!.blank)
    }

    @Test fun examKeepsOneDeadlineAndEndsOnExpiry() = main {
        val vm=GameViewModel(app)
        vm.configure(GameMode.EXAM);vm.selectedCount=3;vm.examMinutes=1
        assertTrue(vm.start())
        val initial=vm.session!!
        vm.answer(initial.current.answer,initial.deadlineMillis-30000)
        assertFalse(vm.session!!.revealed)
        assertEquals(1,vm.session!!.index)
        assertEquals(initial.deadlineMillis,vm.session!!.deadlineMillis)
        vm.tick(initial.deadlineMillis+1)
        assertTrue(vm.session!!.ended)
        assertEquals(1,vm.session!!.correct)
        assertEquals(2,vm.session!!.blank)
        assertEquals(0,vm.session!!.wrong)
        assertEquals("result",vm.route)
    }

    @Test fun duelHasNineSharedQuestionsAndEqualTimeBudget() = main {
        val vm=GameViewModel(app)
        vm.configure(GameMode.DUEL);vm.computerLevel="سخت"
        assertTrue(vm.start())
        val s=vm.session!!
        assertEquals(9,s.questions.size);assertEquals(9,s.computerAnswers.size)
        assertEquals(25000L,s.durationMillis)
        assertTrue(s.computerAnswers.all { it.remainingMillis in 0..s.durationMillis })
        vm.finish()
        assertEquals(9,vm.session!!.blank)
    }

    @Test fun reviewFiltersWrongBlankAndMarkedByGradeAndChapter() = main {
        val vm=GameViewModel(app)
        vm.configure(GameMode.PRACTICE);vm.selectedGrade=10;vm.selectedChapter=2;vm.selectedCount=3
        assertTrue(vm.start())
        val s=vm.session!!
        vm.answer((s.current.answer+1)%4);vm.next()
        vm.toggleBookmark(vm.session!!.current.id)
        vm.finish()
        vm.configure(GameMode.REVIEW);vm.reviewGrade=10;vm.reviewChapter=2
        assertEquals(3,vm.pool().size)
        vm.reviewWrong=false;vm.reviewBlank=false
        assertEquals(1,vm.pool().size)
        vm.reviewChapter=1
        assertTrue(vm.pool().isEmpty())
    }

    @Test fun activeSessionRestoresAnswersTimerAndCompletesOnlyOnce() = main {
        val first = GameViewModel(app)
        first.configure(GameMode.QUICK)
        assertTrue(first.start())
        val initial = first.session!!
        first.answer(initial.current.answer, initial.deadlineMillis - 20000L)
        first.next()
        val before = first.session!!
        assertTrue(first.flushProgress())
        val restored = GameViewModel(app)
        val active = restored.session!!
        assertEquals("question", restored.route)
        assertEquals(before.id, active.id)
        assertEquals(before.questions.map { it.id }, active.questions.map { it.id })
        assertEquals(before.answers, active.answers)
        assertEquals(1, active.index)
        assertTrue(kotlin.math.abs(before.deadlineMillis - active.deadlineMillis) < 1000L)
        assertTrue(active.remainingMillis in 0..25000L)
        assertEquals(1, restored.progress[initial.current.id]?.attempts)
        restored.finish()
        assertNull(ProgressStore(app).readActive(restored.questions))
        val reopened = GameViewModel(app)
        assertEquals("home", reopened.route)
        assertEquals(1, reopened.history.size)
        assertEquals(restored.session!!.score, reopened.totalScore)
        assertEquals(1, reopened.progress[initial.current.id]?.attempts)
    }

    @Test fun personalBestSurvivesMoreThanTwoHundredRecentResults() = main {
        val bank = BankRepository.load(app)
        val question = bank.first()
        val store = ProgressStore(app)
        fun result(id: Long, score: Int) = QuizSession(id, GameMode.QUICK, listOf(question), listOf(AnswerRecord(question.answer, 0L, score)), ended = true)
        store.complete(result(1L, 190))
        repeat(205) { store.complete(result(it + 2L, 100)) }
        val reopened = ProgressStore(app)
        assertEquals(200, reopened.history.size)
        assertEquals(100, reopened.history.maxOf { it.score })
        assertEquals(190, reopened.bestScores[GameMode.QUICK.name])
        assertEquals(20690, reopened.totalScore)
    }

    @Test fun resumedTimerCannotGainTimeWhenWallClockMovesBackwards() = main {
        val first = GameViewModel(app)
        first.configure(GameMode.QUICK)
        assertTrue(first.start())
        val prefs = app.getSharedPreferences("hezarzist_progress_v1", Context.MODE_PRIVATE)
        val saved = JSONObject(prefs.getString("activeSession", null)!!)
            .put("remaining", 14_000L)
            .put("remainingAtSave", 14_000L)
            .put("savedElapsed", SystemClock.elapsedRealtime() - 4_000L)
            .put("deadlineWall", System.currentTimeMillis() + 3_600_000L)
        assertTrue(prefs.edit().putString("activeSession", saved.toString()).commit())
        val restored = GameViewModel(app).session!!
        assertEquals(first.session!!.id, restored.id)
        assertTrue("A restored timer must respect elapsed time and its saved remaining budget", restored.remainingMillis in 0L..10_000L)
    }

    @Test fun timerAfterDeviceRebootCannotExceedItsSavedRemainingBudget() = main {
        val first = GameViewModel(app)
        first.configure(GameMode.QUICK)
        assertTrue(first.start())
        val prefs = app.getSharedPreferences("hezarzist_progress_v1", Context.MODE_PRIVATE)
        val saved = JSONObject(prefs.getString("activeSession", null)!!)
            .put("remaining", 7_000L)
            .put("remainingAtSave", 7_000L)
            .put("savedElapsed", SystemClock.elapsedRealtime() + 3_600_000L)
            .put("deadlineWall", System.currentTimeMillis() + 3_600_000L)
        assertTrue(prefs.edit().putString("activeSession", saved.toString()).commit())
        val restored = GameViewModel(app).session!!
        assertEquals(first.session!!.id, restored.id)
        assertTrue("A changed monotonic clock must not reset the full question duration", restored.remainingMillis in 0L..7_000L)
    }
}
