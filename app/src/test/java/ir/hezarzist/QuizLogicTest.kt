package ir.hezarzist

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QuizLogicTest {
    private fun q(id: Int) = Question("test-$id", 10, 1, "topic", "easy", "stem-$id",
        listOf("a", "b", "c", "d"), id % 4, "reason", List(4) { "reason-$it" }, Reference("bio10", "section"))

    @Test fun selectionDeduplicatesAndPrioritizesUnseen() {
        val pool=(0..19).map(::q)
        val solved=(0..14).map { q(it).id }.toSet()
        val chosen=selectQuestions(pool+pool, solved, 10, Random(7))
        assertEquals(10,chosen.size)
        assertEquals(10,chosen.map { it.id }.toSet().size)
        assertTrue(chosen.take(5).none { it.id in solved })
        assertEquals(20,selectQuestions(pool, solved, 999,Random(1)).size)
        assertTrue(selectQuestions(pool,solved,-1).isEmpty())
    }

    @Test fun scoringRewardsOnlyCorrectAndClampsTime() {
        assertEquals(0, calculateScore(false,25000,25000,99))
        assertEquals(100, calculateScore(true,0,25000,1))
        assertEquals(150, calculateScore(true,25000,25000,1))
        assertEquals(190, calculateScore(true,99999,25000,99))
        assertEquals(100, calculateScore(true,-10,25000,0))
        assertEquals(100, calculateScore(true,0,0,1))
    }

    @Test fun resultCountsIncludeUnansweredAndSkipped() {
        val questions=(0..3).map(::q)
        val s=QuizSession(1,GameMode.QUICK,questions,listOf(AnswerRecord(0), AnswerRecord(3),AnswerRecord(-1),null))
        assertEquals(1,s.correct);assertEquals(1,s.wrong);assertEquals(2,s.blank)
    }

    @Test fun computerUsesSameQuestionsAndTimeAndDifficultyMatters() {
        val pool=(0..9999).map(::q)
        val easy=simulateComputer(pool,25000,"آسان",Random(99))
        val hard=simulateComputer(pool,25000,"سخت",Random(99))
        assertEquals(pool.size,hard.size)
        assertTrue(hard.all { it.selected in -1..3 && it.remainingMillis in 0..25000 })
        val e=easy.indices.count { easy[it].selected==pool[it].answer }
        val h=hard.indices.count { hard[it].selected==pool[it].answer }
        assertTrue(e in 4500..5100);assertTrue(h in 8300..8900);assertTrue(h>e)
    }
}
