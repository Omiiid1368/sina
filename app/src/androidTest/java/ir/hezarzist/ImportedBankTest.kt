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
    @Test fun previousQuestionIdsAnswersTextMediaAndFinalsArePreserved() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val old = JSONArray(testAssets.open("baseline-v013/questions.json").bufferedReader().use { it.readText() })
        val current = JSONArray(app.assets.open("questions.json").bufferedReader().use { it.readText() })
        val byId = (0 until current.length()).associate { current.getJSONObject(it).getString("id") to current.getJSONObject(it) }
        assertEquals(766, old.length())
        val fields = listOf("grade", "chapter", "topic", "stem", "options", "answer", "explanation", "optionExplanations", "reference", "tags", "image", "optionImages", "solutionImage", "origin")
        (0 until old.length()).forEach { i ->
            val q = old.getJSONObject(i); val now = byId.getValue(q.getString("id"))
            fields.filter { q.has(it) }.forEach { name -> compare(q.get(name), now.get(name)) }
            if(q.getString("difficulty") != now.getString("difficulty")) {
                assertEquals("unknown", q.getString("difficulty")); assertEquals("hard", now.getString("difficulty"))
                assertTrue(now.getJSONObject("qbSelection").getBoolean("printedNumberRedVerified"))
            }
        }
        compare(JSONArray(testAssets.open("baseline-v013/finals.json").bufferedReader().use { it.readText() }),
            JSONArray(app.assets.open("finals.json").bufferedReader().use { it.readText() }))
    }
    @Test fun qbSourceKeysMapToCanonicalOptionsWithoutDuplicateQuestionRows() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val mappings = JSONArray(testAssets.open("qb-key-mappings.json").bufferedReader().use { it.readText() })
        val bank = BankRepository.load(app).associateBy { it.id }
        assertTrue(mappings.length() > 0)
        fun normalize(value: String): String = value.replace("ي", "ی").replace("ك", "ک").filter { it.isLetterOrDigit() }.map { if(it.isDigit()) Character.getNumericValue(it).toString() else it.toString() }.joinToString("")
        (0 until mappings.length()).forEach { i ->
            val m=mappings.getJSONObject(i); val q=bank.getValue(m.getString("id"))
            assertTrue(VERY_DIFFICULT_QUESTION_COLLECTION in q.collections)
            assertEquals(m.getInt("canonicalAnswer"), q.answer)
            assertEquals(normalize(m.getString("sourceCorrectOptionText")), normalize(q.options[q.answer]))
            assertTrue(q.optionExplanations.size==4 && q.optionExplanations.all { it.isNotBlank() })
        }
        assertEquals(bank.size, bank.keys.toSet().size)
        assertFalse(bank.values.any { it.id.endsWith("-4012") && it.id.startsWith("UP-QB-") })
    }
    @Test fun frozenPreviousSourceKeysAndQbReferencesStayAligned() {
        val keys = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("upload-compilation-keys.json").bufferedReader().use { it.readText() })
        val rows = JSONArray(app.assets.open("questions.json").bufferedReader().use { it.readText() })
        var matched=0
        (0 until rows.length()).forEach { i ->
            val q=rows.getJSONObject(i);val id=q.getString("id")
            if(keys.has(id)) { assertEquals(keys.getInt(id),q.getInt("answer"));matched++ }
            if(q.optJSONObject("answerSource")?.optString("kind")=="supplied_qb_key") {
                val proof=q.getJSONObject("qbSelection");val source=q.getJSONObject("answerSource")
                assertTrue(proof.getBoolean("printedNumberRedVerified"));assertTrue(source.getBoolean("suppliedKey"))
                assertFalse(source.getBoolean("officialKeyVerified"));assertTrue(source.getInt("pdfPage")>0)
                assertTrue(q.getJSONObject("review").getBoolean("allFourAnalysesApproved"))
            }
        }
        assertEquals(620,matched)
    }
    @Test fun withdrawingARealQuestionArchivesAttemptsBookmarksAndSessionWithoutLosingScores() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            LocalProgress.initialize(app); LocalProgress.invalidate(app)
            val prefs = app.getSharedPreferences("hezarzist_progress_v1", Context.MODE_PRIVATE)
            val retired = "UP-116f7af590c1-0027"
            val valid = BankRepository.load(app).first { it.origin.kind == "authored" }.id
            val record = JSONObject().put("attempts", 3).put("correct", 1).put("last", 0)
            val progress = JSONObject().put(retired, record).put(valid, record)
            val stale = "{\"ids\":[\"$retired\"],\"unmodifiedLegacySession\":true}"
            prefs.edit().clear().putString("progress", progress.toString())
                .putStringSet("bookmarks", setOf(retired, valid)).putString("activeSession", stale)
                .putInt("totalScore", 430).putInt("bestStreak", 5).putBoolean("dark", true)
                .putBoolean("sound", false).putFloat("textScale", 1.25f)
                .putString("history", "[{\"mode\":\"QUICK\",\"score\":430,\"correct\":3,\"wrong\":1,\"blank\":6,\"timestamp\":17}]")
                .putString("bestScores", "{\"QUICK\":430}").commit()
            val store = ProgressStore(app); store.archiveLegacy(BankRepository.load(app))
            assertFalse(retired in store.progress); assertTrue(valid in store.progress)
            compare(record, JSONObject(prefs.getString("archivedProgress", "{}")!!).getJSONObject(retired))
            assertTrue(retired in prefs.getStringSet("archivedBookmarks", emptySet())!!)
            assertTrue(valid in store.bookmarks); assertEquals(stale, prefs.getString("archivedSession", null))
            assertEquals(430, store.totalScore); assertEquals(430, store.bestScores["QUICK"])
            assertEquals(1, store.history.size); assertEquals(5, store.bestStreak)
            assertTrue(store.settings.dark); assertFalse(store.settings.sound)
            assertEquals(1.25f, store.settings.textScale, .001f)
            prefs.edit().clear().commit()
        }
    }
}
