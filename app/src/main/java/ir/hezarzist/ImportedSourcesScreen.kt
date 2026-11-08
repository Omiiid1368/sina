package ir.hezarzist

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ImportedSourcesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val report = remember { runCatching { JSONObject(context.assets.open("imports/report.json").bufferedReader().use { it.readText() }) }.getOrNull() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onBack, Modifier.semantics { contentDescription = "imported-sources-back" }) { Text("بازگشت") }
        Text("کنکور و منابع ارسالی", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("تستی‌ها در تمرین، مسابقه و آزمون با فیلتر «منابع ارسالی» قرار می‌گیرند. سؤال‌های تشریحی در «آمادگی نهایی» هستند. نام آزمون درج‌شده در یک مجموعه، تأیید مستقلِ اصل دفترچه و کلید رسمی نیست.")
        if (report == null) {
            Text("گزارش منابع این نسخه در دسترس نیست.", color = MaterialTheme.colorScheme.error)
        } else {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${fa(report.optInt("receivedFiles"))} فایل دریافتی • ${fa(report.optInt("uniqueFiles"))} فایل یکتا", fontWeight = FontWeight.Bold)
                    Text("${fa(report.optInt("addedMcq"))} تست و ${fa(report.optInt("addedFinals"))} سؤال نهایی افزوده‌شده")
                    Text("${fa(report.optInt("duplicateQuestions"))} تکراری • ${fa(report.optInt("pendingQuestions"))} مورد نیازمند تکمیل")
                    if(!report.optBoolean("finalQuestionCountKnown", true)) Text("شمارش و تطبیق ریزسؤال‌های ${fa(report.optInt("unreconciledFinalPages"))} صفحهٔ تشریحی هنوز تکمیل نشده است؛ این موارد افزون بر تعداد تست‌های بالا هستند.")
                }
            }
            val files = report.optJSONArray("files") ?: JSONArray()
            (0 until files.length()).forEach { index ->
                val item = files.getJSONObject(index)
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.getString("name"), fontWeight = FontWeight.Bold)
                        Text("${fa(item.optInt("pages"))} صفحه • ${if(item.optString("family") == "mcq") "تستی" else "تشریحی و امتحانی"}")
                        if(item.optString("duplicateOf").isNotBlank()) Text("نسخهٔ یکسان با ${item.getString("duplicateOf")}؛ دوباره شمرده نشده است.")
                        else if(!item.optBoolean("questionCountKnown", true)) Text("ریزسؤال‌ها هنوز کامل شمارش و تطبیق نشده‌اند؛ به بانک فعال اضافه نشده است.")
                        else Text("آماده: ${fa(item.optInt("readyQuestions"))} • نیازمند تکمیل: ${fa(item.optInt("pendingQuestions"))}")
                        if(item.optString("note").isNotBlank()) Text(item.getString("note"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("وضعیت حق استفاده", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("اجازهٔ بازنشر منابع ارسالی طبق اعلام مالک پروژه ثبت شده است؛ این ثبت، گواهی مستقل از ناشر منبع نیست. شناسهٔ سؤال و نام و صفحهٔ فایل برای پیگیری منبع و درخواست اصلاح نگهداری می‌شوند.", style = MaterialTheme.typography.bodySmall)
    }
}
