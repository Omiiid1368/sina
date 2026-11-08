package ir.hezarzist

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class KonkurSummary(val officialCount: Int, val scorableCount: Int, val complete: Boolean, val unknownYears: List<Int>)
data class KonkurYearCoverage(val year: Int, val inventoryVerified: Boolean, val verifiedExams: Int, val expected: Int?, val imported: Int, val reviewVerified: Boolean)
data class KonkurBookletCoverage(val examId: String, val year: Int, val session: String, val location: String, val syllabus: String, val bookletId: String, val expected: Int, val extracted: Int, val imported: Int, val missing: List<Int>, val reviewed: Boolean, val sourceUrl: String, val sourceSha: String, val finalKeyUrl: String, val finalKeySha: String, val rightsStatus: String)
data class KonkurOccurrence(val examId: String, val bookletId: String, val year: Int, val session: String, val location: String, val number: Int, val pdfPage: Int, val scorable: Boolean, val originalOptions: List<String>, val finalAnswer: Int?, val deletionReason: String)
data class KonkurQuestion(val question: Question, val scorable: Boolean, val occurrences: List<KonkurOccurrence>, val editionNotes: String, val correctionNotes: List<String>)
data class KonkurArchiveData(val summary: KonkurSummary, val years: List<KonkurYearCoverage>, val booklets: List<KonkurBookletCoverage>, val questions: List<KonkurQuestion>)

/** Immutable packaged archive: no fetch, account, downloaded snapshot or authored fallback. */
object KonkurRepository {
    private fun read(context: Context, name: String): String = context.assets.open("konkur/$name").bufferedReader().use { it.readText() }
    private fun JSONObject.strings(name: String): List<String> = getJSONArray(name).let { a -> (0 until a.length()).map { a.getString(it) } }
    private fun JSONObject.numbers(name: String): List<Int> = getJSONArray(name).let { a -> (0 until a.length()).map { a.getInt(it) } }

    fun loadSummary(context: Context): KonkurSummary {
        val report = JSONObject(read(context, "coverage-audit.json"))
        val rawQuestions = read(context, "questions.json")
        val rawCoverage = read(context, "coverage.json")
        require(StudyAssets.sha256(rawQuestions.toByteArray(Charsets.UTF_8)) == report.getString("questionFileSha256") && StudyAssets.sha256(rawCoverage.toByteArray(Charsets.UTF_8)) == report.getString("coverageFileSha256")) { "اثر انگشت بانک رسمی یا پروندهٔ پوشش با گزارش یکسان نیست." }
        val questions = JSONArray(rawQuestions)
        require(report.getBoolean("structurallyValid") && report.getInt("officialQuestionCount") == questions.length()) { "گزارش پوشش با بانک رسمی بسته تطبیق ندارد." }
        val scorable = (0 until questions.length()).count { questions.getJSONObject(it).getBoolean("scorable") }
        require(report.getInt("scorableQuestionCount") == scorable)
        val years = report.getJSONArray("years")
        val unknownYears = (0 until years.length()).map { years.getJSONObject(it) }.filter { it.getString("inventoryStatus") != "verified" }.map { it.getInt("year") }
        val complete = report.getBoolean("complete")
        require(!complete || (JSONObject(rawCoverage).getBoolean("inventoryComplete") && unknownYears.isEmpty() && questions.length() > 0 && report.getInt("verifiedExamCount") > 0))
        return KonkurSummary(questions.length(), scorable, complete, unknownYears)
    }

    fun load(context: Context): KonkurArchiveData {
        val report = JSONObject(read(context, "coverage-audit.json"))
        val coverage = JSONObject(read(context, "coverage.json"))
        val bank = JSONArray(read(context, "questions.json"))
        val summary = loadSummary(context)
        val exams = coverage.getJSONArray("exams").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") } }
        val booklets = report.getJSONArray("booklets").let { a -> (0 until a.length()).map { i ->
            val b = a.getJSONObject(i)
            KonkurBookletCoverage(b.getString("examId"), b.getInt("year"), b.getString("session"), b.getString("location"), b.getString("syllabus"), b.getString("bookletId"), b.getInt("expected"), b.getInt("extracted"), b.getInt("imported"), b.numbers("missingNumbers"), b.getString("reviewStatus") == "verified", b.getJSONObject("questionSource").getString("url"), b.getJSONObject("questionSource").getString("sha256"), b.getJSONObject("finalKeySource").getString("url"), b.getJSONObject("finalKeySource").getString("sha256"), b.getJSONObject("rights").getString("status"))
        } }
        val years = report.getJSONArray("years").let { a -> (0 until a.length()).map { i ->
            val y = a.getJSONObject(i)
            KonkurYearCoverage(y.getInt("year"), y.getString("inventoryStatus") == "verified", y.getInt("verifiedExamCount"), if(y.isNull("expectedQuestionOccurrences")) null else y.getInt("expectedQuestionOccurrences"), y.getInt("importedQuestionOccurrences"), y.getString("reviewStatus") == "verified")
        } }
        val questions = (0 until bank.length()).map { i ->
            val q = bank.getJSONObject(i)
            require(q.getString("origin") == "official_konkur") { "سؤال غیررسمی در آرشیو رسمی وجود دارد." }
            val occurrences = q.getJSONArray("occurrences").let { a -> (0 until a.length()).map { n ->
                val o = a.getJSONObject(n); val exam = requireNotNull(exams[o.getString("examId")])
                KonkurOccurrence(o.getString("examId"), o.getString("bookletId"), exam.getInt("year"), exam.getString("session"), exam.getString("location"), o.getInt("number"), o.getInt("pdfPage"), o.getBoolean("scorable"), o.strings("originalOptions"), if(o.isNull("finalAnswer")) null else o.getInt("finalAnswer"), o.optString("deletionReason"))
            } }
            require(occurrences.isNotEmpty())
            val first = occurrences.firstOrNull { it.scorable } ?: occurrences.first()
            val book = requireNotNull(booklets.find { it.examId == first.examId && it.bookletId == first.bookletId })
            val refs = q.getJSONArray("references"); val ref = refs.getJSONObject(0)
            val answer = if(q.isNull("answer")) -1 else q.getInt("answer")
            val scorable = q.getBoolean("scorable")
            require(q.strings("options").size == 4 && q.strings("optionExplanations").size == 4 && if(scorable) answer in 0..3 else answer == -1)
            val image = q.optString("image").takeIf { it.isNotBlank() && it != "null" }
            if(q.optBoolean("requiresImage")) require(image != null)
            image?.let { path -> require(StudyAssets.safePath(path) && path.startsWith("konkur/images/")); context.assets.open(path).use { require(it.read() >= 0) } }
            val question = Question(id = q.getString("id"), grade = q.getInt("grade"), chapter = q.getInt("chapter"), topic = q.getString("topic"), difficulty = q.getString("difficulty"), stem = q.getString("stem"), options = q.strings("options"), answer = answer, explanation = q.getString("explanation"), optionExplanations = q.strings("optionExplanations"), reference = Reference(ref.getString("sourceId"), ref.getString("section") + "؛ چاپ " + ref.getString("edition")), tags = q.strings("tags"), image = image, origin = QuestionOrigin("official_konkur", "کنکور واقعی ${fa(first.year)}؛ ${first.session}؛ ${konkurLocationTitle(first.location)}؛ سؤال ${fa(first.number)}", first.examId, first.year, first.session, first.number), license = ContentLicense(book.rightsStatus, true, "اصل دفترچه/کلید در بستهٔ منابع؛ اجازهٔ بازنشر طبق پروندهٔ حقوق منابع"), trap = q.getString("trap"), contentSnapshot = "bundled")
            val corrections = q.getJSONArray("correctionHistory").let { a -> (0 until a.length()).map { n -> val h = a.getJSONObject(n); h.getString("publishedAt") + "؛ " + h.getString("reason") } }
            KonkurQuestion(question, scorable, occurrences, q.getString("editionNotes"), corrections)
        }
        require(questions.map { it.question.id }.distinct().size == questions.size)
        return KonkurArchiveData(summary, years, booklets, questions)
    }

    /** Deleted occurrences remain in the archive and are never added to the playable bank. */
    fun loadScorableQuestions(context: Context): List<Question> = load(context).questions.filter { it.scorable && it.occurrences.any(KonkurOccurrence::scorable) }.map { it.question }
}

fun konkurLocationTitle(location: String): String = when(location) { "inside_iran" -> "داخل کشور"; "outside_iran" -> "خارج کشور"; else -> "نامعلوم" }
