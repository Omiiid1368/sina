package ir.hezarzist

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QuestionCollectionsTest {
    private fun question(id: String = "collection-main", collections: Set<String> = setOf(MAIN_QUESTION_COLLECTION)) = Question(
        id = id, grade = 12, chapter = 1, topic = "وراثت", difficulty = "hard", stem = "صورت سؤال",
        options = listOf("الف", "ب", "پ", "ت"), answer = 2, explanation = "پاسخ",
        optionExplanations = listOf("رد الف", "رد ب", "درستی پ", "رد ت"), reference = Reference("source", "بخش"),
        tags = listOf("red", "بسیار دشوار"), origin = QuestionOrigin("user_supplied_compilation", "منبع ارسالی", "uploaded"), collections = collections)

    @Test fun hardDifficultyAndRedTagsDoNotCreateSpecialMembership() {
        val row = question()
        assertEquals(0, veryDifficultQuestionCount(listOf(row)))
        assertFalse(questionMatchesSource(row, "very_difficult"))
        assertTrue(questionMatchesSource(row, "all"))
        assertTrue(questionMatchesSource(row, "uploaded"))
        assertFalse(availableQuestionSources(listOf(row)).any { it.first == "very_difficult" })
    }

    @Test fun hardOnlyMembershipIsExcludedFromEveryOrdinarySourcePool() {
        val row = question(collections = setOf(VERY_DIFFICULT_QUESTION_COLLECTION))
        listOf("all", "uploaded", "authored", "official_konkur").forEach { source -> assertFalse(source, questionMatchesSource(row, source)) }
        assertTrue(questionMatchesSource(row, "very_difficult"))
        assertEquals(1, veryDifficultQuestionCount(listOf(row)))
    }

    @Test fun sharedMembershipKeepsOneQuestionAndTheSameAnswerInBothCollections() {
        val shared = question("shared-id", setOf(MAIN_QUESTION_COLLECTION, VERY_DIFFICULT_QUESTION_COLLECTION))
        val mainOnly = question("main-only")
        val specialOnly = question("special-only", setOf(VERY_DIFFICULT_QUESTION_COLLECTION))
        val bank = listOf(shared, mainOnly, specialOnly)
        val main = bank.filter { questionMatchesSource(it, "all") }
        val special = bank.filter { questionMatchesSource(it, "very_difficult") }
        assertEquals(listOf("shared-id", "main-only"), main.map { it.id })
        assertEquals(listOf("shared-id", "special-only"), special.map { it.id })
        assertSame(main.first(), special.first())
        assertEquals(shared.options, special.first().options)
        assertEquals(shared.answer, special.first().answer)
        assertEquals(shared.optionExplanations, special.first().optionExplanations)
        assertEquals(3, bank.map { it.id }.distinct().size)
        val chosen = selectQuestions(special, emptySet(), 10, Random(2))
        assertEquals(2, chosen.size); assertEquals(2, chosen.map { it.id }.toSet().size)
    }

    @Test fun emptyCategoryAndZeroOfficialSourceNeverProduceOptions() {
        assertTrue(availableQuestionSources(emptyList()).isEmpty())
        val mainOnly = question()
        val options = availableQuestionSources(listOf(mainOnly)).map { it.first }
        assertEquals(listOf("all", "uploaded"), options)
        assertFalse("official_konkur" in options)
        assertFalse("very_difficult" in options)
        assertEquals("uploaded", defaultQuestionSource(listOf(mainOnly)))
    }

    @Test fun hardOnlyItemsCannotChangeTheDefaultMainSource() {
        val authored = question("authored").copy(origin = QuestionOrigin())
        val hardOnly = question("qb-only", setOf(VERY_DIFFICULT_QUESTION_COLLECTION))
        assertEquals("authored", defaultQuestionSource(listOf(authored, hardOnly)))
        assertEquals(listOf("all", "authored", "very_difficult"), availableQuestionSources(listOf(authored, hardOnly)).map { it.first })
    }
}
