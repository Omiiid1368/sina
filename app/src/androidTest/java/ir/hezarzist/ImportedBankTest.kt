package ir.hezarzist

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportedBankTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private fun compare(expected: Any, actual: Any) {
        when(expected) {
            is JSONObject -> {
                assertTrue(actual is JSONObject); actual as JSONObject
                assertEquals(expected.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
                expected.keys().forEach { compare(expected.get(it), actual.get(it)) }
            }
            is JSONArray -> {
                assertTrue(actual is JSONArray); actual as JSONArray
                assertEquals(expected.length(), actual.length())
                (0 until expected.length()).forEach { compare(expected.get(it), actual.get(it)) }
            }
            else -> assertEquals(expected, actual)
        }
    }
    @Test fun allPreviousQuestionsAndFinalsRemainExactlyUnchanged() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        listOf("questions.json", "finals.json").forEach { name ->
            val old = JSONArray(testAssets.open("baseline-v011/$name").bufferedReader().use { it.readText() })
            val current = JSONArray(app.assets.open(name).bufferedReader().use { it.readText() })
            val byId = (0 until current.length()).associate { current.getJSONObject(it).getString("id") to current.getJSONObject(it) }
            (0 until old.length()).forEach { i -> val q = old.getJSONObject(i); compare(q, byId.getValue(q.getString("id"))) }
        }
    }
    @Test fun importedAnswersMatchSeparatelyExtractedSourceKeysAndUnfinishedItemsStayOut() {
        val keys = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("upload-compilation-keys.json").bufferedReader().use { it.readText() })
        assertEquals(1077, keys.length())
        val report = JSONObject(app.assets.open("imports/report.json").bufferedReader().use { it.readText() })
        assertEquals(report.getInt("receivedMcq"), report.getInt("addedMcq") + report.getInt("pendingQuestions") + report.getInt("duplicateQuestions"))
        val bank = BankRepository.load(app)
        val imported = bank.filter { it.origin.kind == "user_supplied_compilation" }
        assertEquals(report.getInt("addedMcq"), imported.size)
        imported.forEach { q ->
            assertEquals(keys.getInt(q.id), q.answer)
            assertEquals("user_declared_permission", q.license.status)
            assertTrue(q.origin.sourceId.isNotBlank())
            assertTrue(q.answerSourceLabel.contains("تأیید نشده"))
            assertTrue(q.optionExplanations.all { it.isNotBlank() })
            assertNotNull(q.image); assertNotNull(q.solutionImage)
        }
        assertEquals(3, imported.count { q -> q.optionImages.count { it != null } == 4 })
        assertFalse(report.getBoolean("complete"))
        assertFalse(report.getBoolean("finalQuestionCountKnown"))
    }
}
