package ir.hezarzist

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionCollectionBankTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)

    @Test fun bundledMembershipDefaultsAndExplicitCollectionsMatchTheirOriginalIds() {
        val rows = JSONArray(app.assets.open("questions.json").bufferedReader().use { it.readText() })
        val bank = BankRepository.load(app).associateBy { it.id }
        var specialCount = 0
        (0 until rows.length()).forEach { index ->
            val original = rows.getJSONObject(index)
            val collections = original.optJSONArray("collections")?.let { values -> (0 until values.length()).map { values.getString(it) }.toSet() } ?: setOf(MAIN_QUESTION_COLLECTION)
            val loaded = bank.getValue(original.getString("id"))
            assertEquals(collections, loaded.collections)
            assertEquals(original.getInt("answer"), loaded.answer)
            assertEquals(original.getString("stem"), loaded.stem)
            if(VERY_DIFFICULT_QUESTION_COLLECTION in collections) specialCount++
            if(MAIN_QUESTION_COLLECTION !in collections) assertFalse(questionMatchesSource(loaded, "all"))
        }
        assertEquals(rows.length(), bank.size)
        assertEquals(specialCount, veryDifficultQuestionCount(bank.values.toList()))
        assertEquals(specialCount > 0, availableQuestionSources(bank.values.toList()).any { it.first == "very_difficult" })
    }

    @Test fun categoryEntryNeverOpensAnEmptyPoolAndOrdinaryEntryKeepsUserFilters() = main {
        val model = GameViewModel(app)
        model.route = "home"; model.selectedGrade = 11; model.selectedChapter = 1; model.selectedDifficulty = "hard"; model.selectedCount = 3
        val hasSpecial = veryDifficultQuestionCount(model.questions) > 0
        assertEquals(hasSpecial, model.configureVeryDifficult())
        if(hasSpecial) {
            assertEquals("setup", model.route); assertEquals("very_difficult", model.selectedSource)
            assertEquals(0, model.selectedGrade); assertEquals(0, model.selectedChapter); assertEquals("all", model.selectedDifficulty)
            assertEquals(minOf(10, veryDifficultQuestionCount(model.questions)), model.selectedCount)
            assertTrue(model.pool().all { VERY_DIFFICULT_QUESTION_COLLECTION in it.collections })
        } else {
            assertEquals("home", model.route); assertEquals(11, model.selectedGrade); assertEquals(1, model.selectedChapter)
            assertEquals("hard", model.selectedDifficulty); assertEquals(3, model.selectedCount)
        }
        model.selectedSource = "very_difficult"; model.selectedGrade = 11; model.selectedChapter = 1; model.selectedDifficulty = "hard"
        model.configure(GameMode.PRACTICE)
        assertNotEquals("very_difficult", model.selectedSource)
        assertEquals(11, model.selectedGrade); assertEquals(1, model.selectedChapter); assertEquals("hard", model.selectedDifficulty)
        assertTrue(model.pool().all { MAIN_QUESTION_COLLECTION in it.collections })
    }
    @Test fun realQbEntryHasReviewedQuestionsAndDoesNotChangeOrdinaryScoring() = main {
        LocalProgress.initialize(app);LocalProgress.invalidate(app)
        app.getSharedPreferences("hezarzist_progress_v1", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val vm=GameViewModel(app);assertTrue(vm.configureVeryDifficult());vm.selectedCount=1
        assertTrue(vm.start());val q=vm.session!!.current
        assertTrue(VERY_DIFFICULT_QUESTION_COLLECTION in q.collections)
        vm.answer(q.answer);vm.next();assertEquals("result",vm.route)
        assertEquals(100,vm.session!!.score);assertEquals(1,vm.session!!.correct)
        assertEquals(100,ProgressStore(app).totalScore)
        val restored=GameViewModel(app);assertEquals(1,restored.progress[q.id]?.correct)
    }
}
