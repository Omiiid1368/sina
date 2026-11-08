package ir.hezarzist

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PersianUiTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before fun launch() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        LocalProgress.initialize(app)
        LocalProgress.invalidate(app)
        app.getSharedPreferences("hezarzist_progress_v1", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("hezarzist_progress_v2_anonymous", Context.MODE_PRIVATE).edit().clear().commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.waitForIdle()
    }

    @After fun close() { scenario.close() }

    private fun model(action: (GameViewModel) -> Unit) = scenario.onActivity { action(ViewModelProvider(it)[GameViewModel::class.java]) }

    @Test fun persianPracticeShowsExplanationsAndSavesResult() {
        ui.onNodeWithText("۱۰۰۰ زیست").assertIsDisplayed()
        ui.onNodeWithContentDescription("home-practice").performScrollTo().performClick()
        ui.onNodeWithContentDescription("setup-start").performScrollTo().performClick()
        var correct = 0
        model { correct = it.session!!.current.answer }
        ui.onNodeWithContentDescription("question-stem").assertIsDisplayed()
        ui.onNodeWithContentDescription("bookmark-question").performClick()
        ui.onNodeWithContentDescription("option-$correct").performScrollTo().performClick()
        ui.onNodeWithText("چرا گزینهٔ ${fa(correct + 1)}؟").performScrollTo().assertIsDisplayed()
        ui.onNodeWithContentDescription("next-question").performScrollTo().performClick()
        ui.onNodeWithContentDescription("finish-session").performScrollTo().performClick()
        ui.onNodeWithContentDescription("confirm-finish").performClick()
        ui.onNodeWithContentDescription("result-answers").performScrollTo().assertIsDisplayed()
        model { vm ->
            assertEquals("result", vm.route)
            assertEquals(1, vm.session!!.correct)
            assertEquals(9, vm.session!!.blank)
            assertEquals(1, vm.bookmarks.size)
            assertEquals(1, vm.history.size)
        }
    }

    @Test fun rotationKeepsQuickSessionAndDeadline() {
        ui.onNodeWithContentDescription("home-quick").performScrollTo().performClick()
        ui.onNodeWithContentDescription("setup-start").performScrollTo().performClick()
        var id = 0L; var deadline = 0L; var remaining = 0L
        model { id = it.session!!.id; deadline = it.session!!.deadlineMillis; remaining = it.session!!.remainingMillis }
        Thread.sleep(1200L)
        ui.waitForIdle()
        model { assertTrue(it.session!!.remainingMillis < remaining - 500L) }
        scenario.recreate()
        ui.onNodeWithContentDescription("question-timer").assertIsDisplayed()
        model { vm ->
            assertEquals(id, vm.session!!.id)
            // Restoring samples wall and monotonic clocks separately. Allow only
            // a tiny sampling difference; a restarted 25-second timer still fails.
            val restoredDeadline = vm.session!!.deadlineMillis
            assertTrue("Rotation must preserve the original deadline budget",
                restoredDeadline in (deadline - 100L)..(deadline + 5L))
            assertTrue("Time spent before rotation must remain consumed",
                vm.session!!.remainingMillis < remaining - 500L)
            assertEquals("question", vm.route)
            assertEquals(0, vm.session!!.index)
        }
    }

    @Test fun darkThemeAndLargeTextCanNavigateAndPersist() {
        ui.onNodeWithContentDescription("home-settings").performClick()
        ui.onAllNodes(isToggleable())[0].performClick()
        ui.onAllNodes(isToggleable())[1].performClick()
        // Set a large value through the exposed Slider semantic action, not internal state.
        ui.onNodeWithContentDescription("text-size").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.4f) }
        scenario.recreate()
        ui.onAllNodesWithText("تنظیمات")[0].assertExists()
        model { vm -> assertTrue(vm.settings.dark); assertFalse(vm.settings.sound); assertTrue(vm.settings.textScale >= 1.39f) }
        ui.onNodeWithContentDescription("home-stats").performClick()
        ui.onNodeWithText("ردّ رشد تو").assertIsDisplayed()
        ui.onNodeWithContentDescription("home-home").performClick()
        ui.onNodeWithContentDescription("home-practice").performScrollTo().performClick()
        ui.onNodeWithContentDescription("setup-start").performScrollTo().performClick()
        // Every option remains reachable at the largest in-app text size on a small screen.
        (0..3).forEach { index -> ui.onNodeWithContentDescription("option-$index").performScrollTo().assertIsDisplayed() }
    }
}
