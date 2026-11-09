package ir.hezarzist

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class StudyCalendarTest {
    @Test fun knownNowruzDatesAndEsfandLeapDayMatchCivilCalendar() {
        val newYears = mapOf(
            1398 to LocalDate.of(2019, 3, 21),
            1399 to LocalDate.of(2020, 3, 20),
            1400 to LocalDate.of(2021, 3, 21),
            1403 to LocalDate.of(2024, 3, 20),
            1404 to LocalDate.of(2025, 3, 21),
            1405 to LocalDate.of(2026, 3, 21)
        )
        newYears.forEach { (year, gregorian) ->
            assertEquals(gregorian, StudyCalendar.jalaliToGregorian(StudyJalaliDate(year, 1, 1)))
            assertEquals(StudyJalaliDate(year, 1, 1), StudyCalendar.gregorianToJalali(gregorian))
            assertEquals(year - 1, StudyCalendar.gregorianToJalali(gregorian.minusDays(1)).year)
        }
        assertEquals(LocalDate.of(2021, 3, 20), StudyCalendar.jalaliToGregorian(StudyJalaliDate(1399, 12, 30)))
        assertEquals(LocalDate.of(2025, 3, 20), StudyCalendar.jalaliToGregorian(StudyJalaliDate(1403, 12, 30)))
        assertEquals(LocalDate.of(2026, 3, 20), StudyCalendar.jalaliToGregorian(StudyJalaliDate(1404, 12, 29)))
        assertFalse(StudyCalendar.isLeapYear(1404))
        assertTrue(StudyCalendar.isLeapYear(1403))
    }

    @Test fun everyDayAcrossRequestedStudyYearsRoundTripsWithoutGaps() {
        var day = LocalDate.of(2019, 3, 21)
        val end = StudyCalendar.jalaliToGregorian(StudyJalaliDate(1451, 1, 1))
        var previous: StudyJalaliDate? = null
        while (day.isBefore(end)) {
            val jalali = StudyCalendar.gregorianToJalali(day)
            assertEquals(day.toString(), day, StudyCalendar.jalaliToGregorian(jalali))
            assertTrue(jalali.month in 1..12)
            assertTrue(jalali.day in 1..StudyCalendar.daysInMonth(jalali.year, jalali.month))
            previous?.let { if (jalali.month == it.month && jalali.year == it.year) assertEquals(it.day + 1, jalali.day) }
            previous = jalali
            day = day.plusDays(1)
        }
    }

    @Test fun parserAcceptsPersianArabicAndAsciiDigitsAndRejectsImpossibleDates() {
        val expected = LocalDate.of(2026, 11, 8)
        assertEquals(expected, StudyCalendar.parseJalali("۱۴۰۵/۰۸/۱۷"))
        assertEquals(expected, StudyCalendar.parseJalali("١٤٠٥/٠٨/١٧"))
        assertEquals(expected, StudyCalendar.parseJalali(" 1405/8/17 "))
        assertEquals("۱۴۰۵/۰۸/۱۷", StudyCalendar.formatJalali(expected))
        listOf("1404/12/30", "1405/7/31", "1405/13/1", "1405/0/1", "1405/2/0", "1405/2/32", "0/1/1", "1405/8", "today").forEach { invalid ->
            assertThrows(invalid, IllegalArgumentException::class.java) { StudyCalendar.parseJalali(invalid) }
        }
    }

    @Test fun monthBoundsUseJalaliMonthsAndWeeksBeginOnSaturday() {
        val date = LocalDate.of(2026, 11, 8)
        assertEquals(LocalDate.of(2026, 10, 23), StudyCalendar.monthStart(date))
        assertEquals(LocalDate.of(2026, 11, 22), StudyCalendar.nextMonthStart(date))
        assertEquals("آبان ۱۴۰۵", StudyCalendar.monthTitle(date))
        assertEquals(LocalDate.of(2026, 11, 7), StudyCalendar.weekStart(date))
        assertEquals(LocalDate.of(2026, 11, 7), StudyCalendar.weekStart(LocalDate.of(2026, 11, 13)))
        assertEquals(LocalDate.of(2026, 11, 14), StudyCalendar.weekStart(LocalDate.of(2026, 11, 14)))
        assertEquals(LocalDate.of(2027, 3, 21), StudyCalendar.nextMonthStart(LocalDate.of(2027, 3, 10)))
    }
}
