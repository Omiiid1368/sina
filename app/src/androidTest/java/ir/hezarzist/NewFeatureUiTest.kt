package ir.hezarzist

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NewFeatureUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var app: Context
    private lateinit var scenario: ActivityScenario<MainActivity>
    @Before fun launch() {
        app = ApplicationProvider.getApplicationContext()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocalProgress.initialize(app); LocalProgress.invalidate(app)
            app.getSharedPreferences(StudyTimeStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            app.getSharedPreferences("hezarzist_progress_v1", Context.MODE_PRIVATE).edit().clear().commit()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.onNodeWithContentDescription("home-study-time").performScrollTo().performClick()
    }
    @After fun close() { scenario.close() }
    @Test fun manualRecordEditCalendarAndConfirmedDeleteUseTheVisibleControls() {
        ui.onNodeWithContentDescription("study-manual").performScrollTo().performClick()
        ui.onNodeWithContentDescription("study-entry-minutes").performTextReplacement("25")
        ui.onNodeWithContentDescription("study-entry-save").performClick()
        var record = StudyTimeStore(app).snapshot().records.single()
        assertEquals(25 * 60_000L, record.durationMillis)
        assertEquals("biology", record.subjectId)
        assertEquals(StudyTimeStore(app).today(), record.manualDate)
        ui.onNodeWithContentDescription("study-tab-2").performScrollTo().performClick()
        ui.onNodeWithContentDescription("study-calendar").performScrollTo().performClick()
        ui.onNodeWithText("تقویم شمسی").assertIsDisplayed()
        ui.onNodeWithText("بستن").performClick()
        ui.onNodeWithContentDescription("study-tab-1").performScrollTo().performClick()
        ui.onNodeWithContentDescription("study-edit-${record.id}").performScrollTo().performClick()
        ui.onNodeWithContentDescription("study-entry-minutes").performTextReplacement("30")
        ui.onNodeWithContentDescription("study-entry-save").performClick()
        record = StudyTimeStore(app).snapshot().records.single()
        assertEquals(30 * 60_000L, record.durationMillis)
        ui.onNodeWithContentDescription("study-delete-${record.id}").performScrollTo().performClick()
        assertEquals(1, StudyTimeStore(app).snapshot().records.size)
        ui.onNodeWithText("نگه می‌دارم").performClick()
        assertEquals(1, StudyTimeStore(app).snapshot().records.size)
        ui.onNodeWithContentDescription("study-delete-${record.id}").performScrollTo().performClick()
        ui.onNodeWithContentDescription("study-confirm-delete").performClick()
        assertTrue(StudyTimeStore(app).snapshot().records.isEmpty())
    }
    @Test fun timerSurvivesNavigationPauseAndActivityRecreation() {
        ui.onNodeWithContentDescription("study-start").performScrollTo().performClick()
        val id = StudyTimeStore(app).snapshot().active!!.id
        Thread.sleep(1100)
        ui.onNodeWithContentDescription("study-back").performScrollTo().performClick()
        assertTrue(StudyTimeStore(app).snapshot().active!!.running)
        scenario.recreate()
        ui.onNodeWithContentDescription("home-study-time").performScrollTo().performClick()
        ui.onNodeWithContentDescription("study-pause-resume").performScrollTo().performClick()
        val paused = StudyTimeStore(app).snapshot().active!!
        assertEquals(id, paused.id); assertFalse(paused.running)
        assertTrue(paused.earnedMillis >= 1000)
        Thread.sleep(600)
        assertEquals(paused.earnedMillis, StudyTimeStore(app).snapshot().active!!.earnedMillis)
        ui.onNodeWithContentDescription("study-finish").performScrollTo().performClick()
        scenario.recreate()
        val final = StudyTimeStore(app).snapshot()
        assertNull(final.active); assertEquals(1, final.records.size)
        assertEquals(id, final.records.single().id)
        assertEquals(paused.earnedMillis, final.records.single().durationMillis)
    }
    @Test fun sourceDiagramOptionsCanBeMagnifiedWithoutSubmittingAnAnswer() {
        var firstCaption = ""
        scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[GameViewModel::class.java]
            val question = vm.questions.first { it.grade == 11 && it.chapter == 2 && it.optionImages.count { p -> p != null } == 4 }
            firstCaption = question.options.first()
            vm.toggleBookmark(question.id)
            vm.configure(GameMode.REVIEW)
            vm.reviewWrong = false; vm.reviewBlank = false; vm.reviewMarked = true
            vm.reviewGrade = 0; vm.reviewChapter = 0; vm.selectedCount = 1
            assertTrue(vm.start())
        }
        ui.onNodeWithContentDescription("تصویر گزینهٔ ۱").performScrollTo().performClick()
        ui.onNodeWithText("بستن").assertIsDisplayed().performClick()
        scenario.onActivity { assertFalse(ViewModelProvider(it)[GameViewModel::class.java].session!!.revealed) }
        // The merged option Surface also contains the zoom button; its centre may
        // hit the image. Tap the actual caption bounds to exercise answer selection.
        ui.onNodeWithText(firstCaption, useUnmergedTree = true).performScrollTo().performClick()
        scenario.onActivity {
            val session = ViewModelProvider(it)[GameViewModel::class.java].session!!
            assertTrue(session.revealed)
            assertEquals(0, session.answers.single()!!.selected)
        }
    }
}
