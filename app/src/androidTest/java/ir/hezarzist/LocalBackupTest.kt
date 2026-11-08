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
import java.time.LocalDate
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

@RunWith(AndroidJUnit4::class)
class LocalBackupTest {
    private lateinit var app: Application
    private val currentPrefs = listOf("hezarzist_progress_v1", "final_study_v2_anonymous", LocalProfileStore.PREFS, StudyTimeStore.PREFS)
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
        val retainedStudy = StudyTimeCodec.encode(studyFixture())
        prefs(StudyTimeStore.PREFS).edit().putString("state", retainedStudy).commit()
        LocalBackup.prepareOfflineUpgrade(app)
        assertEquals(55, ProgressStore(app).totalScore)
        assertEquals(retainedStudy, prefs(StudyTimeStore.PREFS).getString("state", null))
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

    @Test fun studySessionsGoalAndPausedTimerRoundTripWithoutDuplicateIdsOrTime() = main {
        val state = studyFixture()
        prefs(StudyTimeStore.PREFS).edit().putString("state", StudyTimeCodec.encode(state)).commit()
        val bytes = LocalBackup.export(app)
        assertEquals(2, JSONObject(String(bytes)).getInt("schemaVersion"))
        val backup = LocalBackup.prepare(app, bytes)
        assertTrue(backup.preview.containsStudyTime)
        assertEquals(3, backup.preview.studySessions)
        assertEquals(55 * 60_000L, backup.preview.studyMillis)
        assertTrue(backup.preview.importedStudyTimerPaused)
        currentPrefs.forEach { prefs(it).edit().clear().commit() }
        val stale = StudyTimeStore(app)
        LocalBackup.restore(app, backup)
        rejects { stale.setGoal(1) }
        LocalBackup.restore(app, backup)
        val store = StudyTimeStore(app)
        val restored = store.snapshot()
        assertEquals(state, restored)
        assertEquals(95, restored.goalMinutes)
        assertFalse(restored.active!!.running)
        val total = studyDailyTotals(restored.records)
        assertEquals(40 * 60_000L, total[LocalDate.of(2026, 11, 8)])
        assertEquals(10 * 60_000L, total[LocalDate.of(2026, 11, 9)])
        val id = restored.active!!.id
        store.finish(id); store.finish(id)
        val finished = store.snapshot()
        assertNull(finished.active)
        assertEquals(3, finished.records.size)
        assertEquals(3, finished.records.map { it.id }.toSet().size)
        assertEquals(55 * 60_000L, finished.records.sumOf { it.durationMillis })
    }

    @Test fun exportKeepsLiveTimerRunningAndImportedTimerContainsOnlyEarnedSegments() = main {
        val store = StudyTimeStore(app)
        val active = store.start("biology", "زمان‌سنج در حال اجرا")
        SystemClock.sleep(30)
        val bytes = LocalBackup.export(app)
        val live = store.snapshot().active!!
        assertTrue(live.running)
        assertEquals(active.id, live.id)
        assertTrue(live.earnedMillis > 0)
        val backup = LocalBackup.prepare(app, bytes)
        val imported = StudyTimeCodec.decode(backup.stores.getValue("studyTime").getValue("state") as String)
        assertEquals(live.id, imported.active!!.id)
        assertEquals(live.segments, imported.active!!.segments)
        assertNull(imported.active!!.anchor)
        assertTrue(imported.active!!.needsReview)
        LocalBackup.restore(app, backup)
        val restored = StudyTimeStore(app).snapshot().active!!
        assertEquals(live.earnedMillis, restored.earnedMillis)
        assertFalse(restored.running)
        LocalBackup.restore(app, backup)
        assertEquals(live.earnedMillis, StudyTimeStore(app).snapshot().active!!.earnedMillis)
    }

    @Test fun oldFormatOneImportPreservesCurrentStudyGoalRecordsAndRunningTimer() = main {
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 330).commit()
        val old = asVersionOne(LocalBackup.export(app))
        val store = StudyTimeStore(app)
        store.addManual(LocalDate.of(2026, 11, 8), "biology", 45 * 60_000L, "پس از فایل قدیمی")
        val active = store.start("chemistry")
        store.setGoal(42)
        val beforeStudy = prefs(StudyTimeStore.PREFS).getString("state", null)
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 1).commit()
        val prepared = LocalBackup.prepare(app, old)
        assertEquals(1, prepared.preview.backupSchemaVersion)
        assertFalse(prepared.preview.containsStudyTime)
        assertFalse("studyTime" in prepared.stores)
        LocalBackup.restore(app, prepared)
        assertEquals(330, ProgressStore(app).totalScore)
        assertEquals(beforeStudy, prefs(StudyTimeStore.PREFS).getString("state", null))
        val retained = StudyTimeStore(app).snapshot()
        assertEquals(42, retained.goalMinutes)
        assertEquals(1, retained.records.size)
        assertEquals(active.id, retained.active!!.id)
        assertTrue(retained.active!!.running)
    }

    @Test fun malformedStudyDataRejectsWholeBackupEvenWithValidOuterChecksum() = main {
        prefs(StudyTimeStore.PREFS).edit().putString("state", StudyTimeCodec.encode(studyFixture())).commit()
        val bytes = LocalBackup.export(app)
        val before = allLocal()
        val duplicate = rewritePayload(bytes) { payload ->
            payload.getJSONObject("stores").getJSONObject("progress").put("totalScore", 999)
            val state = payload.getJSONObject("stores").getJSONObject("studyTime").getJSONObject("state")
            state.getJSONArray("records").put(JSONObject(state.getJSONArray("records").getJSONObject(0).toString()))
        }
        rejects { LocalBackup.prepare(app, duplicate) }
        val activeDuplicate = rewritePayload(bytes) { payload ->
            val state = payload.getJSONObject("stores").getJSONObject("studyTime").getJSONObject("state")
            state.getJSONObject("active").put("id", state.getJSONArray("records").getJSONObject(0).getString("id"))
        }
        rejects { LocalBackup.prepare(app, activeDuplicate) }
        val overlap = rewritePayload(bytes) { payload ->
            val state = payload.getJSONObject("stores").getJSONObject("studyTime").getJSONObject("state")
            val spans = state.getJSONObject("active").getJSONArray("segments")
            spans.put(JSONObject(spans.getJSONObject(0).toString()))
        }
        rejects { LocalBackup.prepare(app, overlap) }
        val futureStudy = rewritePayload(bytes) { it.getJSONObject("stores").getJSONObject("studyTime").getJSONObject("state").put("schemaVersion", 99) }
        rejects { LocalBackup.prepare(app, futureStudy) }
        assertEquals(before, allLocal())
    }

    @Test fun failureAfterFourthStoreCommitRollsBackStudyStateAndRunningAnchorExactly() = main {
        prefs(StudyTimeStore.PREFS).edit().putString("state", StudyTimeCodec.encode(studyFixture())).commit()
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 800).commit()
        val backup = LocalBackup.prepare(app, LocalBackup.export(app))
        prefs(StudyTimeStore.PREFS).edit().clear().commit()
        val study = StudyTimeStore(app)
        study.addManual(LocalDate.of(2026, 11, 8), "physics", 60_000, "قبل از بازگردانی")
        study.start("biology")
        study.setGoal(15)
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 12).commit()
        val before = allLocal()
        var reachedFourthCommit = false
        rejects {
            LocalBackup.restoreWithFailureForTest(app, backup) { boundary ->
                if (boundary == 4) {
                    reachedFourthCommit = true
                    assertEquals(backup.stores.getValue("studyTime").getValue("state"), prefs(StudyTimeStore.PREFS).getString("state", null))
                    error("simulated interruption before removing rollback journal")
                }
            }
        }
        assertTrue(reachedFourthCommit)
        assertEquals(before, allLocal())
        assertFalse(File(app.noBackupFilesDir, LocalBackup.ROLLBACK_FILE).exists())
    }

    @Test fun interruptedNewJournalRestoresOriginalRunningStudyAnchorWithoutImportNormalization() = main {
        val store = StudyTimeStore(app)
        store.addManual(LocalDate.of(2026, 11, 8), "biology", 60_000, "مطالعهٔ قبلی")
        store.start("math")
        val journalBytes = LocalBackup.export(app)
        val before = allLocal()
        File(app.noBackupFilesDir, LocalBackup.ROLLBACK_FILE).outputStream().use { it.write(journalBytes); it.fd.sync() }
        prefs(StudyTimeStore.PREFS).edit().putString("state", StudyTimeCodec.encode(StudyTimeState(goalMinutes = 0))).commit()
        prefs("hezarzist_progress_v1").edit().putInt("totalScore", 999).commit()
        assertTrue(LocalBackup.recoverInterruptedRestore(app))
        assertEquals(before, allLocal())
        assertTrue(StudyTimeStore(app).snapshot().active!!.running)
    }

    @Test fun explicitEmptyStudyStoreInFormatTwoReplacesExistingStudyData() = main {
        val emptyBackup = LocalBackup.prepare(app, LocalBackup.export(app))
        assertTrue(emptyBackup.preview.containsStudyTime)
        assertEquals(0, emptyBackup.preview.studySessions)
        val study = StudyTimeStore(app)
        study.addManual(LocalDate.of(2026, 11, 8), "biology", 60_000, "بعد از فایل خالی")
        study.setGoal(5)
        LocalBackup.restore(app, emptyBackup)
        assertFalse(prefs(StudyTimeStore.PREFS).contains("state"))
        assertEquals(StudyTimeState(), StudyTimeStore(app).snapshot())
    }

    private fun studyFixture(): StudyTimeState {
        val zone = ZoneId.of(STUDY_ZONE)
        val morning = LocalDate.of(2026, 11, 8).atStartOfDay(zone).toInstant().toEpochMilli()
        val midnight = LocalDate.of(2026, 11, 9).atStartOfDay(zone).toInstant().toEpochMilli()
        return StudyTimeState(records = listOf(
            StudyRecord(subjectId = "biology", note = "ثبت دستی فارسی", manualDate = LocalDate.of(2026, 11, 8), manualDurationMillis = 30 * 60_000L, createdAt = morning),
            StudyRecord(subjectId = "chemistry", note = "عبور از نیمه‌شب", source = "timer", segments = listOf(StudySegment(midnight - 10 * 60_000L, 20 * 60_000L)), createdAt = midnight - 10 * 60_000L)
        ), active = ActiveStudySession(subjectId = "biology", note = "جلسهٔ ناتمامِ مکث‌شده", startedAtMillis = morning + 60 * 60_000L,
            segments = listOf(StudySegment(morning + 60 * 60_000L, 5 * 60_000L))), goalMinutes = 95)
    }

    private fun asVersionOne(bytes: ByteArray): ByteArray {
        val envelope = JSONObject(String(bytes, Charsets.UTF_8))
        val payload = JSONObject(envelope.getString("payload"))
        payload.getJSONObject("stores").remove("studyTime")
        val text = payload.toString()
        return envelope.put("schemaVersion", 1).put("payload", text).put("sha256", digest(text.toByteArray(Charsets.UTF_8))).toString().toByteArray(Charsets.UTF_8)
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
