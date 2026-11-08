package ir.hezarzist

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

const val CREATOR_SUPPORT_URL = "https://daramet.com/SinaRajabi"
const val CREATOR_SUPPORT_LABEL = "حمایت از سازنده ❤️"
val CREATOR_ABOUT_TEXT = """
    سلام، من سینا هستم، دانش‌آموز رشتهٔ تجربی.
    اگه این برنامه برات مفید بود، خوشحال می‌شم نظر و نقدت رو توی مایکت برام بنویسی. پیشنهادهای تو کمک می‌کنه ۱۰۰۰ زیست بهتر بشه.

    اگه دوست داشتی از ادامهٔ ساخت و بهبود برنامه حمایت کنی، می‌تونی از لینک زیر استفاده کنی ❤️
""".trimIndent()

/** The educational app does not fetch this page or supply identity/payment data. */
fun creatorSupportIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(CREATOR_SUPPORT_URL)).apply {
    addCategory(Intent.CATEGORY_BROWSABLE)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/** Called only by the support button; absence of a browser never blocks study. */
fun openCreatorSupport(context: Context): Boolean = try {
    context.startActivity(creatorSupportIntent())
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

@Composable
fun AboutCreatorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
            .semantics { contentDescription = "about-creator-screen" },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack, modifier = Modifier.semantics { contentDescription = "about-creator-back" }) {
            Text("بازگشت")
        }
        Text("دربارهٔ سازنده", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Text(
                CREATOR_ABOUT_TEXT,
                modifier = Modifier.padding(20.dp).semantics { contentDescription = "creator-biography" },
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Button(
            onClick = { openFailed = !openCreatorSupport(context) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .semantics { contentDescription = "creator-support" },
            shape = RoundedCornerShape(17.dp)
        ) { Text(CREATOR_SUPPORT_LABEL, fontWeight = FontWeight.Bold) }
        if (openFailed) {
            Text(
                "بازکردن مرورگر ممکن نشد. یک مرورگر را نصب یا فعال کن و دوباره روی دکمه بزن. امکانات آموزشی همچنان در دسترس‌اند.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { contentDescription = "creator-support-error" }
            )
        }
    }
}
