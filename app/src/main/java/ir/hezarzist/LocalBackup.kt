package ir.hezarzist

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.util.AtomicFile
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class LocalBackupPreview(
    val createdAt: Long,
    val answeredQuestions: Int,
    val archivedQuestions: Int,
    val bookmarks: Int,
    val matches: Int,
    val finalResponses: Int,
    val archivedFinalQuestions: Int,
    val displayName: String
)

/** Only validated, immutable preference values can reach restore(). */
class PreparedLocalBackup internal constructor(
    internal val stores: Map<String, Map<String, Any>>,
    val preview: LocalBackupPreview
)

/**
 * UTF-8 JSON, bounded before parsing. The exact payload string is checksummed, so
 * JSON property order does not affect integrity. This detects corruption; it is
 * not encryption or proof of the file's author. No paths or executable data are imported.
 *
 * Restore runs on the main thread, with a durable rollback journal written before
 * any preferences change. Startup recovers that journal before opening stores.
 * Epoch invalidation prevents retained view models from re-saving their old cache.
 */
object LocalBackup {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val FORMAT = "hezarzist-local-backup"
    private const val VERSION = 1
    private const val APP_ID = "ir.hezarzist"
    internal const val ROLLBACK_FILE = "local-restore.rollback.json"
    private const val UPGRADE_BEFORE = "offline-upgrade-before.json"
    private const val UPGRADE_SELECTED = "offline-upgrade-selected-study.json"
    private const val UPGRADE_ANONYMOUS = "offline-upgrade-anonymous-study.json"
    private const val UPGRADE_RAW = "offline-upgrade-unparsed-study.json"
    private val storeNames = linkedMapOf(
        "progress" to "hezarzist_progress_v1",
        "finals" to "final_study_v2_anonymous",
        "profile" to LocalProfileStore.PREFS
    )
    private val progressKeys = setOf("progress", "archivedProgress", "bookmarks", "archivedBookmarks", "dark", "sound", "textScale", "history", "totalScore", "bestStreak", "bestScores", "activeSession", "archivedSession", "archivedSessions")
    private val finalKeys = setOf("session", "archivedSession", "archivedSessions", "filters")
    private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_:.-]{0,199}")
    private val modes = setOf("PRACTICE", "QUICK", "DUEL", "EXAM", "REVIEW")

    /** Call on the main thread after any active session has been checkpointed. */
    @Synchronized fun export(context: Context): ByteArray {
        requireMainThread()
        return encode(context, capture(context))
    }

    fun read(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= MAX_BYTES) { "حجم پشتیبان بیشتر از ۱۶ مگابایت است." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /** This performs every format/schema/integrity check without writing any data. */
    fun prepare(context: Context, bytes: ByteArray): PreparedLocalBackup = prepareInternal(context, bytes, true)

    private fun prepareInternal(context: Context, bytes: ByteArray, archiveUnknown: Boolean): PreparedLocalBackup {
        require(bytes.size in 1..MAX_BYTES) { "حجم فایل پشتیبان معتبر نیست؛ حداکثر ۱۶ مگابایت." }
        val envelope = parseObject(decodeUtf8(bytes))
        exactKeys(envelope, setOf("format", "schemaVersion", "applicationId", "payload", "sha256"))
        require(envelope.get("format") == FORMAT && envelope.get("applicationId") == APP_ID) { "این فایل پشتیبانِ ۱۰۰۰ زیست نیست." }
        require(integer(envelope, "schemaVersion", 1, VERSION) == VERSION) { "نسخهٔ پشتیبان پشتیبانی نمی‌شود. برنامه را به‌روز کنید." }
        val payloadText = string(envelope, "payload", MAX_BYTES)
        val expected = string(envelope, "sha256", 64)
        require(expected.matches(Regex("[0-9a-f]{64}")) && MessageDigest.isEqual(expected.toByteArray(Charsets.US_ASCII), sha256(payloadText.toByteArray(Charsets.UTF_8)).toByteArray(Charsets.US_ASCII))) {
            "فایل پشتیبان آسیب دیده یا تغییر کرده است؛ هیچ داده‌ای بازگردانی نشد."
        }
        val payload = parseObject(payloadText)
        exactKeys(payload, setOf("createdAt", "appVersionCode", "catalog", "stores"))
        val createdAt = long(payload, "createdAt", 0, Long.MAX_VALUE)
        integer(payload, "appVersionCode", 1, Int.MAX_VALUE)
        val catalog = payload.getJSONObject("catalog")
        exactKeys(catalog, setOf("multipleChoice", "finals"))
        ids(catalog.getJSONArray("multipleChoice")); ids(catalog.getJSONArray("finals"))
        val encodedStores = payload.getJSONObject("stores")
        exactKeys(encodedStores, storeNames.keys)
        val stores = linkedMapOf<String, Map<String, Any>>()
        storeNames.keys.forEach { logical -> stores[logical] = decodeStore(logical, encodedStores.getJSONObject(logical)) }
        val currentIds = loadPlayableQuestions(context).map { it.id }.toSet()
        val finalIds = FinalsRepository.load(context).map { it.id }.toSet()
        val normalized = if (archiveUnknown) archiveMissing(stores, currentIds, finalIds) else stores
        return PreparedLocalBackup(normalized, preview(normalized, createdAt, finalIds))
    }

    @Synchronized fun restore(context: Context, backup: PreparedLocalBackup) {
        restoreInternal(context, backup, null)
    }

    internal fun restoreWithFailureForTest(context: Context, backup: PreparedLocalBackup, beforeStoreWrite: (Int) -> Unit) {
        restoreInternal(context, backup, beforeStoreWrite)
    }

    @Synchronized private fun restoreInternal(context: Context, backup: PreparedLocalBackup, beforeStoreWrite: ((Int) -> Unit)?) {
        requireMainThread()
        check(!journal(context).baseFile.exists()) { "بازیابی پشتیبانِ ناتمام باید ابتدا انجام شود." }
        val before = capture(context)
        writeAtomic(journal(context), encode(context, before))
        LocalProgress.invalidate(context)
        try {
            applyStores(context, backup.stores, beforeStoreWrite)
            journal(context).delete()
            check(!journal(context).baseFile.exists()) { "پایان بازگردانی روی حافظه ثبت نشد." }
        } catch (failure: Exception) {
            try {
                applyStores(context, before)
                journal(context).delete()
                check(!journal(context).baseFile.exists())
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
                throw IllegalStateException("نوشتن حافظه متوقف شد. برنامه را دوباره باز کنید تا نسخهٔ قبلی از فایل بازیابی داخلی برگردد.", failure)
            } finally {
                LocalProgress.notifyChanged(context)
            }
            throw IllegalStateException("بازگردانی انجام نشد؛ داده‌های قبلی محفوظ ماندند.", failure)
        }
        LocalProgress.notifyChanged(context)
    }

    /** Must be called before any progress store/view model is created. */
    @Synchronized fun recoverInterruptedRestore(context: Context): Boolean {
        requireMainThread()
        val file = journal(context)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return false
        val backup = file.openRead().use { prepareInternal(context, read(it), false) }
        LocalProgress.invalidate(context)
        applyStores(context, backup.stores)
        file.delete()
        check(!file.baseFile.exists()) { "حذف پروندهٔ بازیابی انجام نشد." }
        LocalProgress.notifyChanged(context)
        return true
    }

    /**
     * One-time transition from the canceled account prototype. Read only the
     * currently selected account's encrypted ID to locate its local study cache.
     * Neither that ID nor any token/contact/remote avatar enters a recovery file.
     * Other account caches are never merged into the local learner's progress.
     */
    @Synchronized fun prepareOfflineUpgrade(context: Context) {
        requireMainThread()
        val migration = context.getSharedPreferences("offline_upgrade_v3", Context.MODE_PRIVATE)
        if (migration.getBoolean("complete", false)) return
        try {
            prepareOfflineUpgradeChecked(context)
        } catch (failure: Exception) {
            if (journal(context).baseFile.exists()) throw failure
            // Malformed legacy study data is retained verbatim, never silently reset.
            // It is deliberately not accepted as an automatic restore until repaired.
            val raw = JSONObject().put("format", "hezarzist-raw-study-recovery").put("version", 1)
            val stores = JSONObject()
            fun copy(logical: String, name: String, label: String) {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                stores.put(label, JSONObject().also { target -> prefs.all.filterKeys { allowed(logical, it) }.forEach { (key, value) ->
                    target.put(key, if (value is Set<*>) JSONArray(value.filterIsInstance<String>().sorted()) else value)
                } })
            }
            copy("progress", "hezarzist_progress_v1", "progress")
            copy("finals", "final_study_v2_anonymous", "finals")
            copy("progress", "hezarzist_progress_v2_anonymous", "previousAnonymousProgress")
            legacySelectedNamespace(context)?.let { namespace ->
                copy("progress", "hezarzist_progress_v2_$namespace", "previousSelectedProgress")
                copy("finals", "final_study_v2_$namespace", "previousSelectedFinals")
            }
            raw.put("stores", stores)
            val bytes = raw.toString(2).toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_BYTES) { "حجم پروندهٔ بازیابی قدیمی بیش از حد مجاز است." }
            writeIfAbsent(File(context.noBackupFilesDir, UPGRADE_RAW), bytes)
            scrubCanceledOnlineData(context)
            check(migration.edit().putBoolean("complete", true).putString("notice", "بخشی از مطالعهٔ نسخهٔ قبل ساختار نامعتبر دارد. دادهٔ خام بدون اطلاعات حساب در پروندهٔ بازیابی نگهداری شده و به سؤال تازه نسبت داده نشده است. پرونده را برای اصلاح دستی ذخیره کنید.").commit())
        }
    }

    private fun prepareOfflineUpgradeChecked(context: Context) {
        val migration = context.getSharedPreferences("offline_upgrade_v3", Context.MODE_PRIVATE)
        val before = capture(context)
        writeIfAbsent(File(context.noBackupFilesDir, UPGRADE_BEFORE), encode(context, before))
        val previousAnonymous = before.toMutableMap().also { values ->
            values["progress"] = captureStore(context.getSharedPreferences("hezarzist_progress_v2_anonymous", Context.MODE_PRIVATE), "progress")
        }
        val anonymousHasProgress = hasStudyData(previousAnonymous + ("finals" to emptyMap<String, Any>()))
        if (anonymousHasProgress) writeIfAbsent(File(context.noBackupFilesDir, UPGRADE_ANONYMOUS), encode(context, previousAnonymous))
        val selectedNamespace = legacySelectedNamespace(context)
        var selectedMigrated = false
        if (selectedNamespace != null) {
            val selected = linkedMapOf<String, Map<String, Any>>(
                "progress" to captureStore(context.getSharedPreferences("hezarzist_progress_v2_$selectedNamespace", Context.MODE_PRIVATE), "progress"),
                "finals" to captureStore(context.getSharedPreferences("final_study_v2_$selectedNamespace", Context.MODE_PRIVATE), "finals"),
                "profile" to before.getValue("profile")
            )
            if (hasStudyData(selected)) {
                val safeBytes = encode(context, selected)
                // Validate the complete study-only snapshot before touching source caches.
                val safe = prepare(context, safeBytes)
                writeIfAbsent(File(context.noBackupFilesDir, UPGRADE_SELECTED), safeBytes)
                if (!hasStudyData(before)) {
                    val values = safe.stores.toMutableMap()
                    // Device appearance preferences remain the learner's original choices.
                    val progress = values.getValue("progress").toMutableMap()
                    listOf("dark", "sound", "textScale").forEach { key -> before.getValue("progress")[key]?.let { progress[key] = it } }
                    values["progress"] = progress
                    restore(context, PreparedLocalBackup(values, safe.preview))
                    selectedMigrated = true
                }
            }
        }
        if (!selectedMigrated && !hasStudyData(before) && anonymousHasProgress) {
            val safe = prepare(context, encode(context, previousAnonymous))
            restore(context, safe)
        }
        scrubCanceledOnlineData(context)
        check(migration.edit().putBoolean("complete", true).commit()) { "ثبت انتقال به نسخهٔ آفلاین انجام نشد." }
    }

    /** A study-only recovery file is offered when a previous account cache existed. */
    fun previousStudyBackup(context: Context): ByteArray? = File(context.noBackupFilesDir, UPGRADE_SELECTED).takeIf { it.isFile }?.inputStream()?.use { read(it) }
    fun previousAnonymousBackup(context: Context): ByteArray? = File(context.noBackupFilesDir, UPGRADE_ANONYMOUS).takeIf { it.isFile }?.inputStream()?.use { read(it) }
    fun unparsedStudyRecovery(context: Context): ByteArray? = File(context.noBackupFilesDir, UPGRADE_RAW).takeIf { it.isFile }?.inputStream()?.use { read(it) }
    fun recoveryNotice(context: Context): String = context.getSharedPreferences("offline_upgrade_v3", Context.MODE_PRIVATE).getString("notice", "").orEmpty()

    private fun scrubCanceledOnlineData(context: Context) {
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        directory.listFiles().orEmpty().filter { file ->
            val name = file.name.removeSuffix(".xml")
            name == "account_secrets_v2" || name == "sync_device_v2" || name == "published_content_v2" || name.startsWith("sync_v2_") || name.startsWith("content_reports_v2_")
        }.forEach { file ->
            check(context.getSharedPreferences(file.name.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().clear().commit()) { "پاک‌سازی داده‌های حساب قدیمی کامل نشد." }
        }
        // Delete the canceled prototype's keystore entry after its study cache is safe.
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("hezarzist_account_v2") }
        listOf("published_content_v2", "published_content", "content-cache-v2", "avatars").forEach { name -> File(context.filesDir, name).deleteRecursively() }
    }

    private fun legacySelectedNamespace(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences("account_secrets_v2", Context.MODE_PRIVATE)
        val encrypted = prefs.getString("data", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("hezarzist_account_v2", null) as? SecretKey ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        val decrypted = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
        val id = parseObject(decodeUtf8(decrypted)).getJSONObject("user").getString("id")
        "user_" + sha256(id.toByteArray(Charsets.UTF_8)).take(32)
    }.getOrNull()

    private fun hasStudyData(stores: Map<String, Map<String, Any>>): Boolean {
        val p = stores.getValue("progress")
        if ((p["totalScore"] as? Int ?: 0) > 0 || (p["bestStreak"] as? Int ?: 0) > 0) return true
        if (listOf("progress", "archivedProgress", "bestScores").any { key -> (p[key] as? String)?.let { JSONObject(it).length() > 0 } == true }) return true
        if ((p["history"] as? String)?.let { JSONArray(it).length() > 0 } == true) return true
        if (listOf("bookmarks", "archivedBookmarks").any { (p[it] as? Set<*>)?.isNotEmpty() == true }) return true
        if (listOf("activeSession", "archivedSession", "archivedSessions").any { it in p }) return true
        return stores.getValue("finals").keys.any { it.startsWith("answer:") || it.startsWith("bookmark:") || it in setOf("session", "archivedSession", "archivedSessions") }
    }

    private fun capture(context: Context): Map<String, Map<String, Any>> = storeNames.mapValues { (logical, name) -> captureStore(context.getSharedPreferences(name, Context.MODE_PRIVATE), logical) }

    private fun allowed(logical: String, key: String) = when (logical) {
        "progress" -> key in progressKeys
        "finals" -> key in finalKeys || key.startsWith("answer:") || key.startsWith("bookmark:")
        "profile" -> key in setOf("name", "avatarId")
        else -> false
    }

    private fun captureStore(prefs: SharedPreferences, logical: String): Map<String, Any> {
        val json = JSONObject()
        prefs.all.filterKeys { allowed(logical, it) }.forEach { (key, value) ->
            json.put(key, when {
                value is Set<*> -> JSONArray(value.filterIsInstance<String>().sorted())
                value is String && isJsonKey(logical, key) -> parseAny(value)
                else -> value
            })
        }
        return decodeStore(logical, json)
    }

    private fun isJsonKey(logical: String, key: String): Boolean = when (logical) {
        "progress" -> key in setOf("progress", "archivedProgress", "history", "bestScores", "activeSession", "archivedSessions")
        "finals" -> key.startsWith("answer:") || key in finalKeys - "archivedSession"
        else -> false
    }

    private fun encode(context: Context, stores: Map<String, Map<String, Any>>): ByteArray {
        val values = JSONObject()
        stores.forEach { (logical, prefs) ->
            values.put(logical, JSONObject().also { obj -> prefs.toSortedMap().forEach { (key, value) ->
                obj.put(key, when { value is Set<*> -> JSONArray(value.filterIsInstance<String>().sorted()); value is String && isJsonKey(logical, key) -> parseAny(value); else -> value })
            } })
        }
        val catalog = JSONObject().put("multipleChoice", JSONArray(loadPlayableQuestions(context).map { it.id })).put("finals", JSONArray(FinalsRepository.load(context).map { it.id }))
        @Suppress("DEPRECATION")
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        val payload = JSONObject().put("createdAt", System.currentTimeMillis()).put("appVersionCode", appVersion).put("catalog", catalog).put("stores", values).toString()
        val bytes = JSONObject().put("format", FORMAT).put("schemaVersion", VERSION).put("applicationId", APP_ID).put("payload", payload).put("sha256", sha256(payload.toByteArray(Charsets.UTF_8))).toString(2).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "دادهٔ پشتیبان بیشتر از ۱۶ مگابایت است." }
        return bytes
    }

    private fun decodeStore(logical: String, json: JSONObject): Map<String, Any> {
        val output = linkedMapOf<String, Any>()
        require(json.length() <= 30_000) { "تعداد رکوردهای پشتیبان بیش از حد مجاز است." }
        json.keys().asSequence().forEach { key ->
            require(allowed(logical, key)) { "کلید ناشناخته در دادهٔ پشتیبان." }
            val value = json.get(key)
            output[key] = when (logical) {
                "progress" -> when (key) {
                    "progress", "archivedProgress" -> progressObject(value).toString()
                    "bookmarks", "archivedBookmarks" -> ids(value as? JSONArray ?: invalid()).toSet()
                    "dark", "sound" -> value as? Boolean ?: invalid()
                    "textScale" -> number(value, .85, 1.4).toFloat()
                    "totalScore", "bestStreak" -> intValue(value, 0, Int.MAX_VALUE)
                    "history" -> history(value).toString()
                    "bestScores" -> bestScores(value).toString()
                    "activeSession" -> quizSession(value).toString()
                    "archivedSession" -> archivedRaw(value)
                    "archivedSessions" -> sessionArray(value, false).toString()
                    else -> invalid()
                }
                "finals" -> when {
                    key.startsWith("answer:") -> {
                        requireId(key.removePrefix("answer:"))
                        val record = finalAttempt(value)
                        require(record.getString("key") == key.removePrefix("answer:")) { "شناسهٔ پاسخ تشریحی ناسازگار است." }
                        record.toString()
                    }
                    key.startsWith("bookmark:") -> { requireId(key.removePrefix("bookmark:")); value as? Boolean ?: invalid() }
                    key == "filters" -> filters(value).toString()
                    key == "archivedSession" -> archivedRaw(value)
                    key == "archivedSessions" -> sessionArray(value, true).toString()
                    else -> finalSession(value).toString()
                }
                "profile" -> when (key) {
                    "name" -> (value as? String ?: invalid()).also { require(it.length <= LocalProfileStore.MAX_NAME_LENGTH && it == LocalProfileStore.normalizeName(it)) { "نام محلی معتبر نیست." } }
                    "avatarId" -> (value as? String ?: invalid()).also { id -> require(localAvatars.any { it.id == id }) { "نماد محلی معتبر نیست." } }
                    else -> invalid()
                }
                else -> invalid()
            }
        }
        return output.toMap()
    }

    private fun progressObject(value: Any): JSONObject {
        val json = value as? JSONObject ?: invalid()
        require(json.length() <= 30_000)
        json.keys().asSequence().forEach { id ->
            requireId(id)
            val item = json.getJSONObject(id)
            exactKeys(item, setOf("attempts", "correct", "last"))
            val attempts = integer(item, "attempts", 0, Int.MAX_VALUE)
            integer(item, "correct", 0, attempts)
            integer(item, "last", -1, 1)
        }
        return json
    }

    private fun history(value: Any): JSONArray {
        val array = value as? JSONArray ?: invalid()
        require(array.length() <= 10_000)
        repeat(array.length()) { i ->
            val row = array.getJSONObject(i)
            exactKeys(row, setOf("mode", "score", "correct", "wrong", "blank", "timestamp"))
            require(string(row, "mode", 30) in modes)
            listOf("score", "correct", "wrong", "blank").forEach { integer(row, it, 0, Int.MAX_VALUE) }
            long(row, "timestamp", 0, Long.MAX_VALUE)
        }
        return array
    }

    private fun bestScores(value: Any): JSONObject {
        val json = value as? JSONObject ?: invalid()
        json.keys().asSequence().forEach { require(it in modes); integer(json, it, 0, Int.MAX_VALUE) }
        return json
    }

    private fun answerRecord(value: Any?) {
        if (value == null || value == JSONObject.NULL) return
        val json = value as? JSONObject ?: invalid()
        exactKeys(json, setOf("selected", "remaining", "score"))
        integer(json, "selected", -1, 3); long(json, "remaining", 0, Long.MAX_VALUE); integer(json, "score", 0, Int.MAX_VALUE)
    }

    private fun quizSession(value: Any): JSONObject {
        val json = value as? JSONObject ?: invalid()
        exactKeys(json, setOf("id", "mode", "ids", "computerLevel", "index", "revealed", "duration", "remaining", "streak", "maxStreak", "deadlineWall", "answers", "computer"), setOf("bankSnapshot", "savedElapsed", "remainingAtSave"))
        long(json, "id", 0, Long.MAX_VALUE)
        val mode = string(json, "mode", 30).also { require(it in modes) }
        val ids = ids(json.getJSONArray("ids")).also { require(it.isNotEmpty() && it.size <= 1000) }
        integer(json, "index", 0, ids.lastIndex)
        require(json.get("revealed") is Boolean)
        string(json, "computerLevel", 40)
        listOf("duration", "remaining", "deadlineWall").forEach { long(json, it, 0, Long.MAX_VALUE) }
        listOf("savedElapsed", "remainingAtSave").filter { json.has(it) }.forEach { long(json, it, 0, Long.MAX_VALUE) }
        listOf("streak", "maxStreak").forEach { integer(json, it, 0, Int.MAX_VALUE) }
        val answers = json.getJSONArray("answers"); require(answers.length() == ids.size)
        repeat(answers.length()) { answerRecord(answers.opt(it)) }
        val computer = json.getJSONArray("computer"); require(computer.length() == if (mode == "DUEL") ids.size else 0)
        repeat(computer.length()) { require(!computer.isNull(it)); answerRecord(computer.get(it)) }
        if (json.has("bankSnapshot")) string(json, "bankSnapshot", 200)
        return json
    }

    private fun finalAttempt(value: Any): JSONObject {
        val json = value as? JSONObject ?: invalid()
        exactKeys(json, setOf("key", "questionId", "sessionId", "mode", "response", "truthChoice", "submitted", "scores", "updatedAt"))
        listOf("key", "questionId", "sessionId").forEach { requireId(string(json, it, 200)) }
        require(json.getString("key") == "${json.getString("sessionId")}:${json.getString("questionId")}")
        require(string(json, "mode", 30) in setOf("practice", "exam", "review"))
        string(json, "response", 5000)
        require(json.isNull("truthChoice") || json.get("truthChoice") is Boolean)
        require(json.get("submitted") is Boolean)
        val scores = json.getJSONObject("scores"); require(scores.length() <= 100)
        scores.keys().asSequence().forEach { id -> requireId(id); number(scores.get(id), 0.0, 1000.0) }
        long(json, "updatedAt", 0, Long.MAX_VALUE)
        return json
    }

    private fun finalSession(value: Any): JSONObject {
        val json = value as? JSONObject ?: invalid()
        exactKeys(json, setOf("id", "mode", "questionIds", "index", "drafts", "complete", "startedAt", "durationMillis", "deadlineWall", "remainingMillis", "savedElapsed"), setOf("bankSnapshot"))
        val sessionId = string(json, "id", 200).also { requireId(it) }
        require(string(json, "mode", 30) in setOf("practice", "exam", "review"))
        val questionIds = ids(json.getJSONArray("questionIds")).also { require(it.isNotEmpty() && it.size <= 1000) }
        integer(json, "index", 0, questionIds.lastIndex)
        require(json.get("complete") is Boolean)
        listOf("startedAt", "durationMillis", "deadlineWall", "remainingMillis", "savedElapsed").forEach { long(json, it, 0, Long.MAX_VALUE) }
        val drafts = json.getJSONArray("drafts"); require(drafts.length() <= questionIds.size)
        val seen = mutableSetOf<String>()
        repeat(drafts.length()) { i ->
            val attempt = finalAttempt(drafts.get(i))
            require(attempt.getString("sessionId") == sessionId && attempt.getString("questionId") in questionIds && seen.add(attempt.getString("questionId")))
        }
        if (json.has("bankSnapshot")) string(json, "bankSnapshot", 200)
        return json
    }

    private fun filters(value: Any): JSONObject {
        val json = value as? JSONObject ?: invalid()
        exactKeys(json, emptySet(), setOf("grade", "chapter", "topic", "type", "mode", "review", "count", "minutes"))
        json.keys().asSequence().forEach { key -> when (key) {
            "grade" -> require(integer(json, key, 0, 12) in setOf(0, 10, 11, 12))
            "chapter" -> integer(json, key, 0, 20)
            "count" -> integer(json, key, 1, 1000)
            "minutes" -> integer(json, key, 1, 1000)
            "topic" -> string(json, key, 300)
            "type" -> string(json, key, 100)
            "mode" -> require(string(json, key, 30) in setOf("practice", "exam", "review"))
            "review" -> require(string(json, key, 30) in setOf("all", "weak", "bookmarked"))
        } }
        return json
    }

    private fun archivedRaw(value: Any): String = (value as? String ?: invalid()).also { require(it.length <= 500_000) }

    private fun sessionArray(value: Any, @Suppress("UNUSED_PARAMETER") finals: Boolean): JSONArray {
        val array = value as? JSONArray ?: invalid()
        require(array.length() <= 1000)
        repeat(array.length()) { archivedRaw(array.get(it)) }
        return array
    }

    private fun archiveMissing(stores: Map<String, Map<String, Any>>, currentIds: Set<String>, finalIds: Set<String>): Map<String, Map<String, Any>> {
        val output = stores.mapValues { it.value.toMutableMap() }.toMutableMap()
        val progress = output.getValue("progress")
        val active = JSONObject(progress["progress"] as? String ?: "{}")
        val archived = JSONObject(progress["archivedProgress"] as? String ?: "{}")
        active.keys().asSequence().toList().filter { it !in currentIds }.forEach { id -> archived.put(id, active.get(id)); active.remove(id) }
        if ("progress" in progress) progress["progress"] = active.toString()
        if (archived.length() > 0 || "archivedProgress" in progress) progress["archivedProgress"] = archived.toString()
        val marks = (progress["bookmarks"] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
        val oldMarks = (progress["archivedBookmarks"] as? Set<*>)?.filterIsInstance<String>().orEmpty().toSet()
        if ("bookmarks" in progress) progress["bookmarks"] = marks.filter { it in currentIds }.toSet()
        if (oldMarks.isNotEmpty() || marks.any { it !in currentIds }) progress["archivedBookmarks"] = oldMarks + marks.filter { it !in currentIds }
        archiveSession(progress, "activeSession", "ids", currentIds)
        archiveSession(output.getValue("finals"), "session", "questionIds", finalIds)
        return output.mapValues { it.value.toMap() }
    }

    private fun archiveSession(values: MutableMap<String, Any>, activeKey: String, idsKey: String, currentIds: Set<String>) {
        val raw = values[activeKey] as? String ?: return
        val session = JSONObject(raw)
        val snapshot = session.optString("bankSnapshot", "bundled")
        if (ids(session.getJSONArray(idsKey)).any { it !in currentIds } || snapshot !in setOf("", "bundled")) {
            val archived = JSONArray(values["archivedSessions"] as? String ?: "[]")
            (values["archivedSession"] as? String)?.let { archived.put(it); values.remove("archivedSession") }
            archived.put(raw)
            require(archived.length() <= 1000)
            values["archivedSessions"] = archived.toString()
            values.remove(activeKey)
        }
    }

    private fun preview(stores: Map<String, Map<String, Any>>, createdAt: Long, finalIds: Set<String>): LocalBackupPreview {
        val p = stores.getValue("progress")
        val finals = stores.getValue("finals")
        val finalRecords = finals.filterKeys { it.startsWith("answer:") }.values.map { JSONObject(it as String) }
        return LocalBackupPreview(createdAt,
            JSONObject(p["progress"] as? String ?: "{}").length(),
            JSONObject(p["archivedProgress"] as? String ?: "{}").length(),
            ((p["bookmarks"] as? Set<*>)?.size ?: 0) + ((p["archivedBookmarks"] as? Set<*>)?.size ?: 0),
            JSONArray(p["history"] as? String ?: "[]").length(), finalRecords.size,
            finalRecords.map { it.getString("questionId") }.filter { it !in finalIds }.toSet().size,
            stores.getValue("profile")["name"] as? String ?: "")
    }

    private fun applyStores(context: Context, stores: Map<String, Map<String, Any>>, beforeWrite: ((Int) -> Unit)? = null) {
        storeNames.entries.forEachIndexed { index, (logical, name) ->
            beforeWrite?.invoke(index)
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            prefs.all.keys.filter { allowed(logical, it) }.forEach { editor.remove(it) }
            stores.getValue(logical).forEach { (key, value) -> put(editor, key, value) }
            check(editor.commit()) { "نوشتن پشتیبان روی حافظه انجام نشد." }
        }
    }

    private fun put(editor: SharedPreferences.Editor, key: String, value: Any) {
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> invalid()
        }
    }

    private fun journal(context: Context) = AtomicFile(File(context.noBackupFilesDir, ROLLBACK_FILE))
    private fun writeAtomic(file: AtomicFile, bytes: ByteArray) {
        file.baseFile.parentFile?.mkdirs()
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) } catch (failure: Exception) { file.failWrite(output); throw failure }
    }
    private fun writeIfAbsent(file: File, bytes: ByteArray) { if (!file.exists()) writeAtomic(AtomicFile(file), bytes) }
    private fun requireMainThread() { check(Looper.myLooper() == Looper.getMainLooper()) { "بازگردانی محلی باید در رشتهٔ اصلی اجرا شود." } }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun decodeUtf8(bytes: ByteArray) = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun requireId(value: String) { require(idPattern.matches(value)) { "شناسهٔ پشتیبان معتبر نیست." } }
    private fun ids(array: JSONArray): List<String> {
        require(array.length() <= 30_000)
        val values = (0 until array.length()).map { (array.get(it) as? String ?: invalid()).also(::requireId) }
        require(values.toSet().size == values.size) { "شناسهٔ تکراری در پشتیبان." }
        return values
    }
    private fun exactKeys(json: JSONObject, required: Set<String>, optional: Set<String> = emptySet()) {
        val actual = json.keys().asSequence().toSet()
        require(actual.containsAll(required) && actual.all { it in required || it in optional }) { "ساختار فایل پشتیبان معتبر نیست." }
    }
    private fun string(json: JSONObject, key: String, max: Int): String = (json.get(key) as? String ?: invalid()).also { require(it.length <= max) { "متن پشتیبان بیش از حد مجاز است." } }
    private fun integer(json: JSONObject, key: String, min: Int, max: Int) = intValue(json.get(key), min, max)
    private fun intValue(value: Any, min: Int, max: Int): Int = longValue(value, min.toLong(), max.toLong()).toInt()
    private fun long(json: JSONObject, key: String, min: Long, max: Long) = longValue(json.get(key), min, max)
    private fun longValue(value: Any, min: Long, max: Long): Long {
        require(value is Int || value is Long) { "عدد صحیح پشتیبان معتبر نیست." }
        return (value as Number).toLong().also { require(it in min..max) { "عدد پشتیبان بیرون از بازهٔ مجاز است." } }
    }
    private fun number(value: Any, min: Double, max: Double): Double {
        require(value is Number)
        return value.toDouble().also { require(it.isFinite() && it in min..max) { "عدد پشتیبان معتبر نیست." } }
    }
    private fun invalid(): Nothing = throw IllegalArgumentException("ساختار فایل پشتیبان معتبر نیست؛ داده‌های فعلی تغییر نکردند.")
    private fun parseObject(text: String) = parseAny(text) as? JSONObject ?: invalid()
    private fun parseAny(text: String): Any {
        val reader = JsonReader(StringReader(text)).apply { isLenient = false }
        return reader.use {
            val value = readJson(reader, 0)
            require(reader.peek() == JsonToken.END_DOCUMENT) { "دادهٔ اضافه در انتهای پشتیبان." }
            value
        }
    }
    private fun readJson(reader: JsonReader, depth: Int): Any {
        require(depth <= 24) { "ساختار پشتیبان بیش از حد تو در تو است." }
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                val value = JSONObject(); val names = mutableSetOf<String>(); reader.beginObject()
                while (reader.hasNext()) {
                    val name = reader.nextName(); require(name.length <= 300 && names.add(name) && names.size <= 50_000) { "کلید تکراری یا نامعتبر در پشتیبان." }
                    value.put(name, readJson(reader, depth + 1))
                }
                reader.endObject(); value
            }
            JsonToken.BEGIN_ARRAY -> {
                val value = JSONArray(); reader.beginArray()
                while (reader.hasNext()) { require(value.length() < 50_000); value.put(readJson(reader, depth + 1)) }
                reader.endArray(); value
            }
            JsonToken.STRING -> reader.nextString().also { require(it.length <= MAX_BYTES) }
            JsonToken.NUMBER -> {
                val text = reader.nextString(); require(text.length <= 100)
                val decimal = BigDecimal(text)
                try { decimal.longValueExact() } catch (_: ArithmeticException) { decimal.toDouble().also { require(it.isFinite()) } }
            }
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> { reader.nextNull(); JSONObject.NULL }
            else -> invalid()
        }
    }
}
