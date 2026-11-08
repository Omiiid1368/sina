package ir.hezarzist

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AboutCreatorTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun biographyHasOnlyOneSupportActionAndOpensExactUrlOnlyOnTap() {
        var captured: Intent? = null
        var launches = 0
        var backs = 0
        val interception = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { captured = intent; launches++ }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalContext provides interception, LocalLayoutDirection provides LayoutDirection.Rtl) {
                            AboutCreatorScreen { backs++ }
                        }
                    }
                }
            }
            val requestedBiography = """
                سلام، من سینا هستم، دانش‌آموز رشتهٔ تجربی.
                اگه این برنامه برات مفید بود، خوشحال می‌شم نظر و نقدت رو توی مایکت برام بنویسی. پیشنهادهای تو کمک می‌کنه ۱۰۰۰ زیست بهتر بشه.

                اگه دوست داشتی از ادامهٔ ساخت و بهبود برنامه حمایت کنی، می‌تونی از لینک زیر استفاده کنی ❤️
            """.trimIndent()
            ui.onNodeWithContentDescription("creator-biography").assertTextEquals(requestedBiography)
            ui.onAllNodes(hasClickAction()).assertCountEquals(2) // Navigation above the text, and only support below it.
            ui.onAllNodesWithText(CREATOR_SUPPORT_LABEL).assertCountEquals(1)
            ui.onNodeWithText("ثبت نظر در مایکت", substring = true).assertDoesNotExist()
            ui.onNodeWithContentDescription("creator-support-error").assertDoesNotExist()
            ui.runOnIdle { assertNull(captured); assertEquals(0, launches) }
            ui.onNodeWithContentDescription("creator-support").performScrollTo().performClick()
            ui.runOnIdle {
                assertEquals(1, launches)
                assertEquals(Intent.ACTION_VIEW, captured?.action)
                assertEquals("https://daramet.com/SinaRajabi", captured?.dataString)
                assertTrue(captured?.categories?.contains(Intent.CATEGORY_BROWSABLE) == true)
                assertTrue((captured?.flags ?: 0) and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
                assertNull(captured?.component)
                assertNull(captured?.getPackage())
                assertNull(captured?.extras)
            }
            ui.onNodeWithContentDescription("about-creator-back").performScrollTo().performClick()
            ui.runOnIdle { assertEquals(1, backs); assertEquals(1, launches) }
        }
    }

    @Test fun missingBrowserShowsReadableErrorWithoutLaunchingOrBlockingBack() {
        var launches = 0
        var backs = 0
        val noBrowser = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { launches++; throw ActivityNotFoundException("TEST_ONLY no handler") }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme {
                        CompositionLocalProvider(LocalContext provides noBrowser, LocalLayoutDirection provides LayoutDirection.Rtl) {
                            AboutCreatorScreen { backs++ }
                        }
                    }
                }
            }
            ui.runOnIdle { assertEquals(0, launches) }
            ui.onNodeWithContentDescription("creator-support").performScrollTo().performClick()
            ui.onNodeWithContentDescription("creator-support-error").performScrollTo().assertIsDisplayed()
            ui.onAllNodesWithText(CREATOR_SUPPORT_LABEL).assertCountEquals(1)
            ui.onNodeWithText("ثبت نظر در مایکت", substring = true).assertDoesNotExist()
            ui.onNodeWithContentDescription("about-creator-back").performScrollTo().performClick()
            ui.runOnIdle { assertEquals(1, backs); assertEquals(1, launches) }
        }
    }

    @Test fun supportIntentHasNoTrackingOrPaymentPayloadAndSecurityDenialIsHandled() {
        val intent = creatorSupportIntent()
        assertEquals("https", intent.data?.scheme)
        assertEquals("daramet.com", intent.data?.host)
        assertEquals("/SinaRajabi", intent.data?.path)
        assertNull(intent.data?.query)
        assertNull(intent.data?.fragment)
        assertNull(intent.extras)
        val denied = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun startActivity(intent: Intent) { throw SecurityException("TEST_ONLY denied") }
        }
        assertFalse(openCreatorSupport(denied))
    }
}
