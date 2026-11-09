package ir.hezarzist

import android.content.Context
import org.json.JSONArray

data class Reference(val sourceId: String, val section: String)
data class QuestionOrigin(val kind: String = "authored", val label: String = "تألیفی مستقل", val sourceId: String = "", val year: Int? = null, val session: String? = null, val number: Int? = null)
data class ContentLicense(val status: String = "", val distributionAllowed: Boolean = false, val evidence: String = "")

data class Question(
    val id: String,
    val grade: Int,
    val chapter: Int,
    val topic: String,
    val difficulty: String,
    val stem: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String,
    val optionExplanations: List<String>,
    val reference: Reference,
    val tags: List<String> = emptyList(),
    val image: String? = null,
    val origin: QuestionOrigin = QuestionOrigin(),
    val license: ContentLicense = ContentLicense(),
    val trap: String = "",
    val contentSnapshot: String = "bundled",
    val optionImages: List<String?> = emptyList(),
    val solutionImage: String? = null,
    val answerSourceLabel: String = "",
    val optionAnalysisKinds: List<String> = emptyList(),
    val collections: Set<String> = setOf(MAIN_QUESTION_COLLECTION)
)

object BankRepository {
    fun load(context: Context, snapshot: String? = null): List<Question> {
        val items = JSONArray(StudyAssets.open(context, "questions.json", snapshot).bufferedReader().use { it.readText() })
        val bank = (0 until items.length()).map { i ->
            val q = items.getJSONObject(i)
            val ref = q.getJSONObject("reference")
            fun strings(key: String): List<String> = q.optJSONArray(key)?.let { a ->
                (0 until a.length()).map { a.getString(it) }
            } ?: emptyList()
            Question(q.getString("id"), q.getInt("grade"), q.getInt("chapter"),
                q.getString("topic"), q.getString("difficulty"), q.getString("stem"),
                strings("options"), q.getInt("answer"), q.getString("explanation"),
                strings("optionExplanations"), Reference(ref.getString("sourceId"), ref.getString("section")),
                strings("tags"), q.optString("image").takeIf { it.isNotBlank() && it != "null" },
                q.optJSONObject("origin")?.let { o -> QuestionOrigin(o.optString("kind", "authored"), o.optString("label", "تألیفی مستقل"), o.optString("sourceId"), o.optInt("examYear").takeIf { it > 0 }, o.optString("examSession").takeIf { it.isNotBlank() && it != "null" }, o.optInt("questionNumber").takeIf { it > 0 }) } ?: QuestionOrigin(),
                q.optJSONObject("license")?.let { l -> ContentLicense(l.optString("status"), l.optBoolean("distributionAllowed"), l.optString("evidence")) } ?: ContentLicense(), q.optString("trap"), snapshot ?: StudyAssets.snapshot(context),
                q.optJSONArray("optionImages")?.let { a -> (0 until a.length()).map { a.optString(it).takeIf { p -> p.isNotBlank() && p != "null" } } } ?: emptyList(),
                q.optString("solutionImage").takeIf { it.isNotBlank() && it != "null" },
                q.optJSONObject("answerSource")?.optString("label").orEmpty(), strings("optionAnalysisKinds"),
                if(q.has("collections")) strings("collections").toSet() else setOf(MAIN_QUESTION_COLLECTION))
        }
        require(bank.isNotEmpty() && bank.map { it.id }.toSet().size == bank.size) { "بانک سؤال معتبر نیست" }
        require(bank.all { q ->
            q.id.isNotBlank() && q.grade in 10..12 && q.chapter in 1..chapterTitles.getValue(q.grade).size &&
                q.stem.isNotBlank() && q.topic.isNotBlank() && q.options.size == 4 && q.options.all { it.isNotBlank() } &&
                q.answer in 0..3 && q.optionExplanations.size == 4 && q.optionExplanations.all { it.isNotBlank() } &&
                q.explanation.isNotBlank() && q.reference.section.isNotBlank() && q.collections.isNotEmpty() &&
                q.collections.all { it in setOf(MAIN_QUESTION_COLLECTION, VERY_DIFFICULT_QUESTION_COLLECTION) }
        })
        val images = bank.flatMap { q -> listOfNotNull(q.image, q.solutionImage) + q.optionImages.filterNotNull() }
        images.groupBy { it.substringBeforeLast('/', "") }.forEach { (folder, paths) ->
            val names = context.assets.list(folder).orEmpty().toSet()
            require(paths.all { StudyAssets.safePath(it) && it.substringAfterLast('/') in names }) { "تصویر لازم در بستهٔ برنامه موجود نیست." }
        }
        return bank
    }
}

/** Only separately verified, scorable official items may join the independent practice bank. */
fun loadPlayableQuestions(context: Context): List<Question> {
    val bank = BankRepository.load(context) + KonkurRepository.loadScorableQuestions(context)
    require(bank.map { it.id }.distinct().size == bank.size) { "شناسه‌های بانک‌های آموزشی هم‌پوشانی دارند." }
    return bank
}

const val MAIN_QUESTION_COLLECTION = "main"
const val VERY_DIFFICULT_QUESTION_COLLECTION = "qb_very_difficult"

/** Membership comes from reviewed content metadata, independently of difficulty, color or tags. */
fun questionMatchesSource(question: Question, source: String): Boolean {
    if(source == "very_difficult") return VERY_DIFFICULT_QUESTION_COLLECTION in question.collections
    if(MAIN_QUESTION_COLLECTION !in question.collections) return false
    return when(source) {
        "all" -> true
        "uploaded" -> question.origin.kind in setOf("user_supplied_compilation", "user_supplied_exam_booklet")
        "official_konkur" -> question.origin.kind == "official_konkur"
        else -> question.origin.kind in setOf("authored", "authored_independent")
    }
}

fun veryDifficultQuestionCount(questions: List<Question>): Int = questions.count { VERY_DIFFICULT_QUESTION_COLLECTION in it.collections }

fun defaultQuestionSource(questions: List<Question>): String = when {
    questions.any { questionMatchesSource(it, "uploaded") } -> "uploaded"
    questions.any { questionMatchesSource(it, "official_konkur") } -> "official_konkur"
    questions.any { questionMatchesSource(it, "authored") } -> "authored"
    else -> "all"
}

fun availableQuestionSources(questions: List<Question>): List<Pair<String, String>> = listOf(
    "all" to "همهٔ سؤال‌ها", "uploaded" to "منابع ارسالی", "authored" to "تألیفی مستقل",
    "official_konkur" to "کنکور رسمی", "very_difficult" to "تست‌های بسیار دشوار"
).filter { (source, _) -> questions.any { questionMatchesSource(it, source) } }
