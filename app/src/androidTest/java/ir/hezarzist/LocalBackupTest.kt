package ir.hezarzist

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class LocalBackupTest {
    private lateinit var app: Application
    private val currentPrefs = listOf("hezarzist_progress_v1", "final_study_v2_anonymous", LocalProfileStore.PREFS)
    private fun main(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun allLocal(): List<Map<String, *>> = currentPrefs.map { prefs(it).all.toMap() }

    @Before fun reset() = main {
        app = ApplicationProvider.getApplicationContext()
        (currentPrefs + listOf("hezarzist_progress_v2_anonymous", "account_cache_epochs_v2", "offline_upgrade_v3", "account_secrets_v2", "sync_v2_anonymous", "content_reports_v2_anonymous")).forEach { prefs(it).edit().clear().commit() }
        app.noBackupFilesDir.listFiles().orEmpty().filter { it.name.startsWith("offline-upgrade-") || it.name.startsWith("local-restore.rollback") }.forEach { it.delete() }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("hezarzist_account_v2") }
        LocalProgress.initialize(app)
    }

    @Test fun completeStudyProfileAndSelfAssessmentRoundTrip() = main {
        val question = BankRepository.load(app).first()
        val progress = ProgressStore(app)
        progress.record(question, AnswerRecord(question.answer, 0, 100))
        progress.bookmark(question.id)
        progress.settings(GameSettings(true, false, 1.25f))
        progress.complete(QuizSession(1, GameMode.PRACTICE, listOf(question), listOf(AnswerRecord(question.answer, 0, 100)), ended = true))
        val final = FinalsRepository.load(app).first()
        val attempt = FinalAttempt("roundtrip:${final.id}", final.id, "roundtrip", "practice", "پاسخ فارسیِ دانش‌آموز", submitted = true,
            scores = final.rubric.associate { it.id to it.points / 2 })
        val finals = FinalsProgressStore(app)
        finals.saveAttempt(attempt)
        finals.saveSession(FinalSession("roundtrip", "practice", listOf(final.id), drafts = mapOf(final.id to attempt)))
        finals.saveFilters(JSONObject().put("grade", 10).put("chapter", 1).put("topic", final.topic).put("type", final.type).put("mode", "practice").put("review", "weak").put("count", 3).put("minutes", 12))
        LocalProfileStore(app).save("دانش‌آموز", "dna")
        val backup = LocalBackup.prepare(app, LocalBackup.export(app))
        assertEquals(1, backup.preview.answeredQuestions)
        assertEquals(1, backup.preview.finalResponses)
        currentPrefs.forEach { prefs(it).edit().clear().commit() }
        val stale = ProgressStore(app)
        val staleFinals = FinalsProgressStore(app)
        LocalBackup.restore(app, backup)
        stale.record(question, AnswerRecord(-1, 0, 0))
        staleFinals.saveAttempt(attempt.copy(response = "نباید نوشته شود"))
        val restored = ProgressStore(app)
        assertEquals(100, restored.totalScore)
        assertEquals(1, restored.progress[question.id]?.correct)
        assertEquals(setOf(question.id), restored.bookmarks)
        assertEquals(1, restored.history.size)
        assertEquals(100, restored.bestScores[GameMode.PRACTICE.name])
        assertTrue(restored.settings.dark); assertFalse(restored.settings.sound)
        assertEquals(1.25f, restored.settings.textScale, .001f)
        val finalStore = FinalsProgressStore(app)
        assertEquals(attempt.response, finalStore.attempts().getValue(attempt.key).response)
        assertEquals(attempt.scores, finalStore.attempts().getValue(attempt.key).scores)
        assertEquals("weak", finalStore.filters().getString("review"))
        assertEquals(listOf(final.id), finalStore.session()!!.questionIds)
        assertEquals(LocalProfile("دانش‌آموز", "dna"), LocalProfileStore(app).profile)
    }

    @Test fun activeTimedQuizExportsNewClockFieldsWithoutResettingBudget() = main {
        val vm = GameViewModel(app)
        vm.configure(GameMode.QUICK)
        assertTrue(vm.start())
        val session = vm.session!!
        vm.answer(session.current.answer, session.deadlineMillis - 20_000)
        vm.next()
        val original = vm.session!!
        val raw = JSONObject(prefs("hezarzist_progress_v1").getString("activeSession", "{}")!!)
        assertTrue(raw.has("savedElapsed") && raw.has("remainingAtSave"))
        LocalBackup.restore(app, LocalBackup.prepare(app, LocalBackup.export(app)))
        val restored = GameViewModel(app).session!!
        assertEquals(original.questions.map { it.id }, restored.questions.map { it.id })
        assertEquals(original.answers, restored.answers)
        assertEquals(original.index, restored.index)
        assertTrue(restored.deadlineMillis <= original.deadlineMillis + 100)
        assertTrue(restored.remainingMillis <= 25_000)
    }

    @Test fun corruptionWrongApplicationFutureVersionAndDeepJsonAreRejectedWithoutMutation() = main {
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 77).commit()
        val bytes = LocalBackup.export(app)
        val before = allLocal()
        val changed = JSONObject(String(bytes, Charsets.UTF_8))
        changed.put("payload", changed.getString("payload") + " ") // Same JSON, different exact bytes.
        rejects { LocalBackup.prepare(app, changed.toString().toByteArray()) }
        val wrongApp = JSONObject(String(bytes)).put("applicationId", "other.app")
        rejects { LocalBackup.prepare(app, wrongApp.toString().toByteArray()) }
        val future = JSONObject(String(bytes)).put("schemaVersion", 999)
        rejects { LocalBackup.prepare(app, future.toString().toByteArray()) }
        rejects { LocalBackup.prepare(app, ("[".repeat(30) + "0" + "]".repeat(30)).toByteArray()) }
        rejects { LocalBackup.prepare(app, byteArrayOf(0xc3.toByte(), 0x28)) }
        rejects { LocalBackup.prepare(app, "{\"format\":\"a\",\"format\":\"b\"}".toByteArray()) }
        assertEquals(before, allLocal())
    }

    @Test fun entireSchemaIsValidatedEvenWithRecomputedChecksum() = main {
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 21).commit()
        val bytes = LocalBackup.export(app)
        val before = allLocal()
        val malformed = rewritePayload(bytes) { payload ->
            payload.getJSONObject("stores").getJSONObject("progress").put("totalScore", 1000)
            payload.getJSONObject("stores").getJSONObject("finals").put("bookmark:../../outside", true)
        }
        rejects { LocalBackup.prepare(app, malformed) }
        val contact = rewritePayload(bytes) { it.getJSONObject("stores").getJSONObject("profile").put("email", "should-never-import@example.test") }
        rejects { LocalBackup.prepare(app, contact) }
        val invalidScore = rewritePayload(bytes) { it.getJSONObject("stores").getJSONObject("progress").put("totalScore", -1) }
        rejects { LocalBackup.prepare(app, invalidScore) }
        assertEquals(before, allLocal())
    }

    @Test fun oversizedInputIsBoundedBeforeParsing() = main {
        val before = allLocal()
        var supplied = 0
        val input = object : InputStream() {
            override fun read(): Int { supplied++; return 'x'.code }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill('x'.code.toByte(), offset, offset + length); supplied += length; return length
            }
        }
        rejects { LocalBackup.read(input) }
        assertTrue(supplied <= LocalBackup.MAX_BYTES + 8192)
        assertEquals(before, allLocal())
    }

    @Test fun partialPreferenceWriteRollsBackEveryStoreAndPreservesUnownedKeys() = main {
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 700).commit()
        LocalProfileStore(app).save("از فایل", "cell")
        val backup = LocalBackup.prepare(app, LocalBackup.export(app))
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 33).putString("unowned-future-setting", "keep").commit()
        LocalProfileStore(app).save("قبل از بازگردانی", "leaf")
        val before = allLocal()
        rejects { LocalBackup.restoreWithFailureForTest(app, backup) { if (it == 1) error("simulated disk failure after first commit") } }
        assertEquals(before, allLocal())
        assertFalse(File(app.noBackupFilesDir, LocalBackup.ROLLBACK_FILE).exists())
    }

    @Test fun interruptedRestoreRecoversExactPreviousSnapshotBeforeStoresOpen() = main {
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 42).commit()
        LocalProfileStore(app).save("نسخهٔ قبلی", "neuron")
        val bytes = LocalBackup.export(app)
        val before = allLocal()
        File(app.noBackupFilesDir, LocalBackup.ROLLBACK_FILE).outputStream().use { it.write(bytes); it.fd.sync() }
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 999).commit()
        LocalProfileStore(app).save("نیمه‌نوشته", "leaf")
        assertTrue(LocalBackup.recoverInterruptedRestore(app))
        assertEquals(before, allLocal())
        assertFalse(LocalBackup.recoverInterruptedRestore(app))
    }

    @Test fun missingQuestionIdsAndOpaqueOldSessionsAreArchivedWithoutRemapping() = main {
        val legacy = "B10-legacy-001"
        prefs("hezarzist_progress_v1").edit()
            .putString("progress", JSONObject().put(legacy, JSONObject().put("attempts", 3).put("correct", 1).put("last", 0)).toString())
            .putStringSet("bookmarks", setOf(legacy)).putInt("totalScore", 900)
            .putString("archivedSession", "unparseable session from an older version")
            .putString("archivedSessions", JSONArray().put("{\"veryOld\":true}").toString()).commit()
        val prepared = LocalBackup.prepare(app, LocalBackup.export(app))
        assertEquals(0, prepared.preview.answeredQuestions)
        assertEquals(1, prepared.preview.archivedQuestions)
        LocalBackup.restore(app, prepared)
        val store = ProgressStore(app)
        assertTrue(store.progress.isEmpty()); assertTrue(store.bookmarks.isEmpty())
        assertEquals(1, store.archivedCount); assertEquals(900, store.totalScore)
        assertTrue(prefs("hezarzist_progress_v1").getStringSet("archivedBookmarks", emptySet())!!.contains(legacy))
        assertEquals("unparseable session from an older version", prefs("hezarzist_progress_v1").getString("archivedSession", ""))
        assertTrue(BankRepository.load(app).none { it.id == legacy })
    }

    @Test fun exportContainsOnlyWhitelistedStudyDataAndLocalProfile() = main {
        prefs("account_secrets_v2").edit().putString("data", "TOKEN-MUST-NOT-EXPORT").putString("email", "private@example.test").commit()
        prefs("sync_v2_anonymous").edit().putString("pending", "secret-account-identity").commit()
        prefs("content_reports_v2_anonymous").edit().putString("contact", "09123456789").commit()
        prefs("local_profile_v1").edit().putString("remoteAvatar", "https://private.example/avatar").commit()
        LocalProfileStore(app).save("نام محلی", "dna")
        val text = String(LocalBackup.export(app), Charsets.UTF_8)
        listOf("TOKEN-MUST-NOT-EXPORT", "private@example.test", "secret-account-identity", "09123456789", "https://private.example/avatar", "account_secrets", "remoteAvatar").forEach { assertFalse(text.contains(it)) }
        LocalBackup.prepare(app, text.toByteArray())
    }

    @Test fun optionalProfileUsesBundledAvatarAndLimitsOnlyDisplayName() = main {
        val profile = LocalProfileStore(app)
        profile.save("", "leaf")
        assertEquals(LocalProfile(), profile.profile)
        profile.save("\u202e  نام\n محلی  ", "dna")
        assertEquals("نام محلی", profile.profile.name)
        profile.save("الف".repeat(40), "cell")
        assertEquals(40, profile.profile.name.length)
        rejects { profile.save("نباید ذخیره شود", "https://remote/avatar") }
        assertEquals("cell", profile.profile.avatarId)
    }

    @Test fun anonymousPrototypeProgressMigratesOnlyWhenPublishedProfileHasNoStudy() = main {
        prefs("hezarzist_progress_v2_anonymous").edit().putInt("totalScore", 88).putString("history", "[]").commit()
        LocalBackup.prepareOfflineUpgrade(app)
        assertEquals(88, ProgressStore(app).totalScore)
        assertNotNull(LocalBackup.previousAnonymousBackup(app))
        prefs("offline_upgrade_v3").edit().clear().commit()
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 12).commit()
        LocalBackup.prepareOfflineUpgrade(app)
        assertEquals(12, ProgressStore(app).totalScore)
        assertEquals(88, LocalBackup.prepare(app, LocalBackup.previousAnonymousBackup(app)!!).stores.getValue("progress")["totalScore"])
    }

    @Test fun selectedLegacyAccountStudyIsPreservedWithoutIdentityOrOtherUsers() = main {
        val userId = "selected-user-private-identity"
        val namespace = "user_" + digest(userId.toByteArray()).take(32)
        val otherNamespace = "user_" + digest("other-user".toByteArray()).take(32)
        prefs("hezarzist_progress_v2_$namespace").edit().clear().putInt("totalScore", 55).commit()
        prefs("hezarzist_progress_v2_$otherNamespace").edit().clear().putInt("totalScore", 7777).commit()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("hezarzist_account_v2", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, generator.generateKey()) }
        val sensitive = JSONObject().put("access", "PRIVATE-TOKEN").put("refresh", "PRIVATE-REFRESH").put("user", JSONObject().put("id", userId).put("email", "private@example.test").put("avatar", "https://remote/avatar"))
        val ciphertext = cipher.doFinal(sensitive.toString().toByteArray())
        prefs("account_secrets_v2").edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).putString("data", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit()
        LocalBackup.prepareOfflineUpgrade(app)
        assertEquals(55, ProgressStore(app).totalScore)
        assertTrue(prefs("account_secrets_v2").all.isEmpty())
        val recovery = String(LocalBackup.previousStudyBackup(app)!!)
        listOf(userId, namespace, "private@example.test", "PRIVATE-TOKEN", "PRIVATE-REFRESH", "https://remote/avatar", "7777").forEach { assertFalse(recovery.contains(it)) }
        assertEquals(55, LocalBackup.prepare(app, recovery.toByteArray()).stores.getValue("progress")["totalScore"])
    }

    @Test fun malformedOldStudyIsRetainedForManualRecoveryWithoutCrashingUpgrade() = main {
        prefs("hezarzist_progress_v1").edit().putString("history", "broken historical JSON").commit()
        prefs("account_secrets_v2").edit().putString("data", "old opaque token").commit()
        LocalBackup.prepareOfflineUpgrade(app)
        assertEquals("broken historical JSON", prefs("hezarzist_progress_v1").getString("history", ""))
        assertTrue(prefs("account_secrets_v2").all.isEmpty())
        val raw = String(LocalBackup.unparsedStudyRecovery(app)!!)
        assertTrue(raw.contains("broken historical JSON")); assertFalse(raw.contains("old opaque token"))
        assertTrue(LocalBackup.recoveryNotice(app).isNotBlank())
        rejects { LocalBackup.prepare(app, raw.toByteArray()) }
    }

    private fun rewritePayload(bytes: ByteArray, edit: (JSONObject) -> Unit): ByteArray {
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = JSONObject(envelope.getString("payload"))
        edit(payload)
        val text = payload.toString()
        return envelope.put("payload", text).put("sha256", digest(text.toByteArray(Charsets.UTF_8))).toString().toByteArray(Charsets.UTF_8)
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun rejects(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: Exception) { rejected = true }
        assertTrue("Input or simulated failed transaction must be rejected", rejected)
    }
}
