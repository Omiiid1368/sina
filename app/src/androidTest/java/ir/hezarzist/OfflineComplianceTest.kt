package ir.hezarzist

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Release gates for the device-only edition. Run the UI case with airplane mode enabled. */
@RunWith(AndroidJUnit4::class)
class OfflineComplianceTest {
    @get:Rule val ui = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After fun closeActivity() { scenario?.close() }

    @Test fun installedManifestHasNoNetworkContactOrBroadStoragePermissions() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val forbidden = setOf(
            Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE, Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.GET_ACCOUNTS, Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS, Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, "android.permission.MANAGE_EXTERNAL_STORAGE"
        )
        assertTrue("Unexpected permission: ${info.requestedPermissions.orEmpty().toSet() intersect forbidden}",
            info.requestedPermissions.orEmpty().none { it in forbidden })
        assertEquals("System/cloud backup must not silently export device-local learner data", 0,
            context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
        assertTrue(context.applicationInfo.flags and ApplicationInfo.FLAG_SUPPORTS_RTL != 0)
    }

    @Test fun everyBundledQuestionExplanationImageAndPersianFontIsReadable() {
        val mcq = BankRepository.load(context)
        val finals = FinalsRepository.load(context)
        assertEquals("Supplementary authored MCQ count, never the official archive count", 96, mcq.size)
        assertEquals(72, finals.size)
        assertEquals(mcq.size + finals.size, (mcq.map { it.id } + finals.map { it.id }).toSet().size)
        assertTrue(mcq.all { it.origin.kind == "authored" && it.license.distributionAllowed })
        assertTrue(mcq.all { q ->
            q.stem.isNotBlank() && q.options.size == 4 && q.options.all { it.isNotBlank() } &&
                q.options.distinct().size == 4 && q.answer in 0..3 && q.explanation.isNotBlank() &&
                q.optionExplanations.size == 4 && q.optionExplanations.all { it.isNotBlank() } &&
                q.trap.isNotBlank() && q.reference.sourceId.isNotBlank() && q.reference.section.isNotBlank()
        })
        assertTrue(finals.all { it.source.kind == "authored" && it.marking == "suggested" })
        assertTrue(finals.all { q -> q.answer.isNotBlank() && q.rubric.isNotEmpty() &&
            kotlin.math.abs(q.rubric.sumOf { it.points } - q.totalPoints) < .001 })
        assertTrue(finals.filter { it.type == "diagram_label" }.all { !it.image.isNullOrBlank() })

        val requiredImages = (mcq.mapNotNull { it.image } + finals.mapNotNull { it.image }).toSet()
        val packagedImages = context.assets.list("diagrams").orEmpty().map { "diagrams/$it" }.toSet()
        assertTrue(requiredImages.isNotEmpty())
        assertTrue("Every referenced image must be inside the APK", packagedImages.containsAll(requiredImages))
        packagedImages.forEach { path ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
            assertTrue("Unreadable image: $path", bounds.outWidth > 0 && bounds.outHeight > 0)
            val image = StudyAssets.decodeBitmap(context, path)
            assertNotNull("Image cannot be rendered offline: $path", image)
            image?.recycle()
        }
        assertNotNull(context.resources.getFont(R.font.zist_regular))
        assertNotNull(context.resources.getFont(R.font.zist_bold))
    }

    @Test fun officialCoverageDoesNotInventPapersOrReplaceThemWithAuthoredQuestions() {
        val official = JSONArray(context.assets.open("konkur/questions.json").bufferedReader().use { it.readText() })
        val coverage = JSONObject(context.assets.open("konkur/coverage.json").bufferedReader().use { it.readText() })
        assertEquals("No official paper/key has been verified and imported in this preview", 0, official.length())
        assertFalse(coverage.getBoolean("inventoryComplete"))
        assertEquals(0, coverage.getJSONArray("exams").length())
        val years = coverage.getJSONArray("years")
        assertEquals(8, years.length())
        assertEquals((1398..1405).toSet(), (0 until years.length()).map { years.getJSONObject(it).getInt("year") }.toSet())
        (0 until years.length()).forEach { index ->
            val year = years.getJSONObject(index)
            assertEquals("unknown", year.getString("inventoryStatus"))
            assertTrue("Unknown inventory must not be reported as an empty completed year", year.isNull("heldExamIds"))
            assertEquals(0, year.getJSONArray("inventoryEvidence").length())
        }
    }

    @Test fun legacySnapshotsAndRemotePathsCannotOverrideBundledStudyContent() {
        listOf("https://example.invalid/questions.json", "../questions.json", "/questions.json").forEach { path ->
            assertTrue("Unsafe/remote path was accepted: $path", runCatching { StudyAssets.open(context, path).close() }.isFailure)
        }
        assertTrue("Downloaded content from an earlier edition must not override bundled content",
            runCatching { StudyAssets.open(context, "questions.json", "downloaded-legacy-cache").close() }.isFailure)
        StudyAssets.open(context, "questions.json", "bundled").use { assertTrue(it.read() >= 0) }
    }

    @Test fun airplaneModeLaunchShowsLearningAndLocalToolsWithoutContactOrLoginGate() {
        assertEquals("Enable airplane mode before this release-gate test", 1,
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        LocalProgress.initialize(context)
        LocalProgress.invalidate(context)
        listOf("hezarzist_progress_v1", "hezarzist_progress_v2_anonymous").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        ui.waitForIdle()
        ui.onNodeWithText("۱۰۰۰ زیست").assertIsDisplayed()
        ui.onNodeWithContentDescription("home-practice").assertExists()
        ui.onNodeWithContentDescription("home-duel").assertExists()
        ui.onNodeWithContentDescription("home-finals").assertExists()
        ui.onNodeWithContentDescription("home-konkur-archive").assertExists()
        ui.onNodeWithContentDescription("home-account").assertDoesNotExist()
        ui.onNodeWithContentDescription("home-content-update").assertDoesNotExist()
        ui.onNodeWithContentDescription("home-konkur-archive").performScrollTo().performClick()
        ui.onNodeWithTag("konkur_incomplete").assertExists()
        ui.onNodeWithTag("konkur_archive").performScrollToNode(hasTestTag("konkur_empty"))
        ui.onNodeWithTag("konkur_empty").assertExists()
        ui.onNodeWithContentDescription("navigate-back").performClick()
        ui.onNodeWithContentDescription("home-settings").performClick()
        // Restrict contact/login assertions to settings/profile UI, never scientific question text.
        listOf("ورود با ایمیل", "شمارهٔ موبایل", "کد تأیید", "ارسال کد", "خطای اتصال", "ورود به حساب").forEach {
            ui.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        ui.onNodeWithContentDescription("settings-offline-tools").performScrollTo().performClick()
        ui.waitForIdle()
        listOf("ورود با ایمیل", "شمارهٔ موبایل", "کد تأیید", "ارسال کد", "خطای اتصال", "ورود به حساب").forEach {
            ui.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
        ui.onNodeWithContentDescription("local-profile-name").assertExists()
        ui.onNodeWithContentDescription("local-backup-export").assertExists()
        ui.onNodeWithContentDescription("local-backup-import").assertExists()
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }
}
