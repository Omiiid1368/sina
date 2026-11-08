package ir.hezarzist

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FinalStudyTest {
    @get:Rule val ui = createEmptyComposeRule()
    private lateinit var app: Application
    private var scenario: ActivityScenario<MainActivity>? = null
    private var staleDirectory: File? = null
    private val modelStores = mutableListOf<ViewModelStore>()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun newModel(): FinalStudyViewModel {
        val store = ViewModelStore().also { modelStores.add(it) }
        return ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[FinalStudyViewModel::class.java]
    }
    private fun activityModel(activity: MainActivity) = ViewModelProvider(activity)
        .get("finals_local_${LocalProgress.dataVersion}", FinalStudyViewModel::class.java)

    @Before fun reset() {
        app = ApplicationProvider.getApplicationContext()
        main {
            LocalProgress.initialize(app)
            LocalProgress.invalidate(app)
            listOf("final_study_v2_anonymous", "published_content_v2", "hezarzist_progress_v1").forEach {
                app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }
    @After fun cleanup() {
        scenario?.close()
        main { modelStores.forEach { it.clear() } }
        app.getSharedPreferences("published_content_v2", Context.MODE_PRIVATE).edit().clear().commit()
        staleDirectory?.deleteRecursively()
    }

    @Test fun practiceHidesSolutionUntilWrittenAttemptAndUsesExplicitRubric() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { activity ->
            ViewModelProvider(activity)[GameViewModel::class.java].route = "finals"
            val vm = activityModel(activity)
            vm.chooseType("essay"); vm.chooseCount(1); vm.start()
        }
        ui.onNodeWithContentDescription("finals-standard-answer").assertDoesNotExist()
        ui.onNodeWithContentDescription("finals-submit").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithContentDescription("finals-response").performScrollTo().performTextInput("پاسخ نوشته‌شدهٔ دانش‌آموز برای مقایسه با معیارها")
        ui.onNodeWithContentDescription("finals-standard-answer").assertDoesNotExist()
        ui.onNodeWithContentDescription("finals-submit").performScrollTo().performClick()
        ui.onNodeWithContentDescription("finals-standard-answer").performScrollTo().assertIsDisplayed()
        val rubricIds = mutableListOf<String>()
        scenario!!.onActivity { activity ->
            val vm = activityModel(activity)
            assertNull(finalAssessment(vm.currentAttempt!!, vm.current!!))
            rubricIds.addAll(vm.current!!.rubric.map { it.id })
        }
        rubricIds.forEach { id -> ui.onNodeWithContentDescription("finals-rubric-$id-full").performScrollTo().performClick() }
        scenario!!.onActivity { activity ->
            val vm = activityModel(activity)
            assertEquals(vm.current!!.totalPoints, finalAssessment(vm.currentAttempt!!, vm.current!!)!!, .001)
            assertFalse(finalNeedsReview(vm.currentAttempt!!, vm.current!!))
        }
    }

    @Test fun examKeepsAnswersHiddenAcrossNavigationAndDurableDeadlineRestore() = main {
        val vm = newModel()
        vm.chooseMode("exam"); vm.chooseType("essay"); vm.chooseCount(3); vm.chooseMinutes(5); vm.start()
        val id = vm.session!!.id
        assertEquals(3, vm.session!!.questionIds.distinct().size)
        vm.editResponse("پاسخ آزمون که نباید راهنما را آشکار کند")
        vm.submit(); assertFalse(vm.canReveal)
        vm.assess(vm.current!!.rubric.first().id, vm.current!!.rubric.first().points)
        assertTrue(vm.currentAttempt!!.scores.isEmpty())
        vm.next(); assertFalse(vm.canReveal)
        vm.previous(); assertEquals("پاسخ آزمون که نباید راهنما را آشکار کند", vm.currentAttempt!!.response)
        vm.flush()
        val restored = newModel()
        assertEquals(id, restored.session!!.id)
        assertTrue(restored.session!!.remainingMillis <= vm.session!!.remainingMillis)
        assertFalse(restored.canReveal)
        // Process reconstruction must finish an expired exam instead of granting fresh time.
        FinalsProgressStore(app).saveSession(restored.session!!.copy(deadlineWall = System.currentTimeMillis() - 1, remainingMillis = 0))
        val expired = newModel()
        assertTrue(expired.session!!.complete)
        assertEquals(2, expired.session!!.drafts.values.count { !it.hasResponse })
        expired.reviewResult(); assertTrue(expired.canReveal)
        assertNull(finalAssessment(expired.currentAttempt!!, expired.current!!))
    }

    @Test fun skippedPracticeStillRequiresAttemptAfterOpeningCompletedSession() = main {
        val vm = newModel()
        vm.chooseType("short"); vm.chooseCount(1); vm.start(); vm.finish(); vm.reviewResult()
        assertFalse(vm.canReveal)
        vm.editResponse("تلاش واقعی پس از برگشت به سؤال نزده")
        assertFalse(vm.canReveal)
        vm.submit(); assertTrue(vm.canReveal)
        assertNull(finalAssessment(vm.currentAttempt!!, vm.current!!))
    }

    @Test fun localDraftBookmarksFiltersAndEpochSurviveOnlyTheirValidStore() = main {
        val vm = newModel()
        vm.chooseGrade(12); vm.chooseChapter(2); vm.chooseType("short"); vm.chooseCount(1); vm.chooseMinutes(20)
        // A type need not exist in this single chapter; keep a valid nonempty pool.
        if (vm.eligible.isEmpty()) vm.chooseType("")
        vm.start(); vm.editResponse("ز".repeat(6000)); vm.toggleBookmark(); vm.flush()
        val questionId = vm.current!!.id
        val restored = newModel()
        assertEquals(5000, restored.currentAttempt!!.response.length)
        assertEquals(questionId, restored.current!!.id)
        assertTrue(questionId in restored.bookmarks)
        assertEquals(12, restored.grade); assertEquals(2, restored.chapter); assertEquals(20, restored.minutes)
        // This is the restore protocol: invalidate BEFORE replacing local preferences.
        LocalProgress.invalidate(app)
        app.getSharedPreferences("final_study_v2_anonymous", Context.MODE_PRIVATE).edit().clear().commit()
        val afterRestore = newModel()
        vm.flush(); restored.flush()
        modelStores.take(2).forEach { it.clear() }
        assertNull(afterRestore.session)
        assertTrue(FinalsProgressStore(app).attempts().isEmpty())
        assertTrue(FinalsProgressStore(app).bookmarks().isEmpty())
        assertNull(FinalsProgressStore(app).session())
    }

    @Test fun bundledCatalogIgnoresOldDownloadedBankAndRestoresExistingQuestionIds() = main {
        val vm = newModel()
        vm.chooseMode("exam"); vm.chooseCount(1); vm.start(); vm.editResponse("پاسخ محلی ذخیره‌شده"); vm.flush()
        val originalStem = vm.current!!.stem
        val name = UUID.randomUUID().toString()
        val directory = File(File(app.filesDir, "published_content_v2"), name).apply { mkdirs() }
        staleDirectory = directory
        File(directory, "finals.json").writeText("[]")
        File(directory, "questions.json").writeText("[]")
        File(directory, "bank.json").writeText("{}")
        app.getSharedPreferences("published_content_v2", Context.MODE_PRIVATE).edit().putString("active", name).commit()
        val restored = newModel()
        assertEquals(originalStem, restored.current!!.stem)
        assertEquals("پاسخ محلی ذخیره‌شده", restored.currentAttempt!!.response)
        assertEquals(FinalsRepository.load(app).map { it.id }, restored.bank.map { it.id })
        assertTrue(restored.bank.isNotEmpty())
        vm.flush() // Older retained models cannot replace the current local session.
        assertEquals(restored.session!!.id, FinalsProgressStore(app).session()!!.id)
    }

    @Test fun oldDownloadedAndUnavailableSessionsAreArchivedWithoutRemappingWrittenWork() = main {
        val vm = newModel()
        vm.chooseCount(1); vm.start(); vm.editResponse("نوشته‌ای که نباید به سؤال دیگری نسبت داده شود")
        vm.toggleBookmark(); vm.flush()
        val key = vm.currentAttempt!!.key
        val prefs = app.getSharedPreferences("final_study_v2_anonymous", Context.MODE_PRIVATE)
        val old = JSONObject(prefs.getString("session", null)!!).put("bankSnapshot", UUID.randomUUID().toString())
        prefs.edit().putString("session", old.toString()).commit()
        val afterUpdate = newModel()
        assertNull(afterUpdate.session)
        assertEquals(vm.currentAttempt!!.response, afterUpdate.attempts[key]!!.response)
        assertTrue(vm.current!!.id in afterUpdate.bookmarks)
        val archived = JSONArray(prefs.getString("archivedSessions", "[]"))
        assertEquals(1, archived.length())
        assertEquals(old.toString(), archived.getString(0))
        // A valid-shaped session with an unavailable ID also stays preserved as an archive.
        val missingSession = JSONObject(old.toString()).apply {
            remove("bankSnapshot"); put("questionIds", JSONArray().put("unavailable-question-id"))
        }
        prefs.edit().putString("session", missingSession.toString()).commit()
        val afterRemoval = newModel()
        assertNull(afterRemoval.session)
        assertEquals(2, JSONArray(prefs.getString("archivedSessions", "[]")).length())
        assertEquals(vm.currentAttempt!!.response, afterRemoval.attempts[key]!!.response)
    }

    @Test fun allSevenFormatsAndReadableDiagramsAreBundledWithoutNetworkPermission() = main {
        val bank = FinalsRepository.load(app)
        assertEquals(finalTypeTitles.keys, bank.map { it.type }.toSet())
        assertEquals(bank.size, bank.map { it.id }.distinct().size)
        assertTrue(bank.all { it.source.kind == "authored" && it.marking == "suggested" })
        assertTrue(bank.any { it.type == "diagram_label" })
        bank.filter { it.image != null }.forEach { question ->
            val bitmap = StudyAssets.decodeBitmap(app, question.image!!)
            assertNotNull(question.id, bitmap)
            assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
            bitmap.recycle()
        }
        @Suppress("DEPRECATION")
        val permissions = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(Manifest.permission.INTERNET in permissions)
    }

    @Test fun falseStatementNeedsCorrectionAndSelfAssessmentRejectsNonFiniteScores() = main {
        val vm = newModel()
        vm.chooseType("true_false"); vm.chooseCount(1); vm.start()
        vm.chooseTruth(false)
        assertFalse(vm.canSubmit()); vm.submit(); assertFalse(vm.canReveal)
        vm.editResponse("صورت اصلاح‌شدهٔ عبارت به زبان خودم")
        assertTrue(vm.canSubmit()); vm.submit(); assertTrue(vm.canReveal)
        vm.current!!.rubric.forEach { row ->
            vm.assess(row.id, Double.NaN)
            assertFalse(row.id in vm.currentAttempt!!.scores)
            vm.assess(row.id, row.points / 2)
        }
        assertEquals(vm.current!!.totalPoints / 2, finalAssessment(vm.currentAttempt!!, vm.current!!)!!, .001)
        assertTrue(finalNeedsReview(vm.currentAttempt!!, vm.current!!))
    }

    @Test fun reviewFiltersUseLocalUnassessedAttemptsAndBookmarksWithoutRepeats() = main {
        val vm = newModel()
        vm.chooseCount(1); vm.start()
        val practicedId = vm.current!!.id
        vm.editResponse("پاسخ تمرینی که هنوز خودارزیابی نشده")
        if (vm.current!!.type == "true_false") vm.chooseTruth(false)
        assertTrue(vm.canSubmit())
        vm.submit()
        assertTrue(vm.currentAttempt!!.submitted)
        vm.toggleBookmark(); vm.finish(); vm.closeResult()
        assertTrue(practicedId in vm.weakIds)
        vm.chooseReview("weak"); assertEquals(listOf(practicedId), vm.eligible.map { it.id })
        vm.chooseReview("bookmarked"); assertEquals(listOf(practicedId), vm.eligible.map { it.id })
        vm.chooseReview("all"); vm.chooseCount(vm.bank.size); vm.start()
        assertEquals(vm.bank.size, vm.session!!.questionIds.distinct().size)
        assertEquals(practicedId, vm.session!!.questionIds.last())
    }
}
