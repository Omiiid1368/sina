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
    @Test fun previousBankChangesMatchTheExplicitWithdrawalAndTranscriptionLedger() {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val old = JSONArray(testAssets.open("baseline-v012/questions.json").bufferedReader().use { it.readText() })
        val retired = JSONArray(testAssets.open("v013-retired.json").bufferedReader().use { it.readText() })
        val corrections = JSONArray(testAssets.open("v013-corrections.json").bufferedReader().use { it.readText() })
        val retiredIds = (0 until retired.length()).map { retired.getJSONObject(it).getString("id") }.toSet()
        val current = JSONArray(app.assets.open("questions.json").bufferedReader().use { it.readText() })
        val byId = (0 until current.length()).associate { current.getJSONObject(it).getString("id") to current.getJSONObject(it) }
        assertEquals(9, retiredIds.size)
        (0 until old.length()).forEach { i ->
            val q = old.getJSONObject(i); val id = q.getString("id")
            if (id in retiredIds) assertFalse(id in byId) else {
                (0 until corrections.length()).forEach { j ->
                    val c = corrections.getJSONObject(j)
                    if (c.getString("id") == id) {
                        val field = c.getString("field").split("/")
                        assertEquals(c.getString("before"), q.getJSONArray(field[0]).getString(field[1].toInt()))
                        q.getJSONArray(field[0]).put(field[1].toInt(), c.getString("after"))
                    }
                }
                compare(q, byId.getValue(id))
            }
        }
    }
    @Test fun existingAnswersMatchFrozenSourceKeysAndNewEducationalKeysAreExplicit() {
        val keys = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("upload-compilation-keys.json").bufferedReader().use { it.readText() })
        assertEquals(1077, keys.length())
        val json = JSONArray(app.assets.open("questions.json").bufferedReader().use { it.readText() })
        var oldKeys = 0; var newKeys = 0
        (0 until json.length()).forEach { i ->
            val q = json.getJSONObject(i); val id = q.getString("id")
            if (keys.has(id)) { assertEquals(keys.getInt(id), q.getInt("answer")); oldKeys++ }
            else if (id.startsWith("UP-")) {
                val source = q.getJSONObject("answerSource")
                assertEquals("educational_answer", source.getString("kind"))
                assertFalse(source.getBoolean("suppliedKey")); assertFalse(source.getBoolean("officialKeyVerified"))
                assertTrue(q.getJSONObject("review").getBoolean("allFourAnalysesApproved"))
                newKeys++
            }
        }
        val bank = BankRepository.load(app)
        val counts = JSONObject(app.assets.open("content-counts.json").bufferedReader().use { it.readText() })
        assertEquals(620, oldKeys); assertEquals(counts.getInt("unique") - 96 - oldKeys, newKeys)
        val imported = bank.filter { it.id.startsWith("UP-") }
        imported.forEach { q ->
            assertEquals("user_declared_permission", q.license.status)
            assertTrue(q.origin.sourceId.isNotBlank())
            assertTrue(q.optionExplanations.all { it.isNotBlank() })
            assertNotNull(q.image)
            if (keys.has(q.id)) assertNotNull(q.solutionImage)
        }
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
