package ir.hezarzist

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A civil Iranian calendar date; conversion validates the actual month length. */
data class StudyJalaliDate(val year: Int, val month: Int, val day: Int)

/**
 * Civil Jalali dates using the integer break-year calculation described by
 * K. M. Borkowski (1996). java.time supplies Gregorian day arithmetic, so the
 * study calendar does not depend on the device locale or an internet service.
 */
object StudyCalendar {
    private const val MIN_YEAR = 1
    private const val MAX_YEAR = 3177
    private val breaks = intArrayOf(-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181,
        1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178)
    private val monthNames = listOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
    private data class YearInfo(val gregorianYear: Int, val marchDay: Int, val leap: Boolean)

    private fun yearInfo(year: Int): YearInfo {
        require(year in MIN_YEAR..MAX_YEAR) { "سال شمسی خارج از محدودهٔ پشتیبانی است." }
        val gregorianYear = year + 621
        var leapJalali = -14
        var previous = breaks[0]
        var jump = 0
        for (index in 1 until breaks.size) {
            val next = breaks[index]
            jump = next - previous
            if (year < next) break
            leapJalali += (jump / 33) * 8 + (jump % 33) / 4
            previous = next
        }
        var yearsSinceBreak = year - previous
        leapJalali += (yearsSinceBreak / 33) * 8 + (yearsSinceBreak % 33 + 3) / 4
        if (jump % 33 == 4 && jump - yearsSinceBreak == 4) leapJalali++
        val leapGregorian = gregorianYear / 4 - ((gregorianYear / 100 + 1) * 3) / 4 - 150
        val march = 20 + leapJalali - leapGregorian
        if (jump - yearsSinceBreak < 6) {
            yearsSinceBreak = yearsSinceBreak - jump + ((jump + 4) / 33) * 33
        }
        var leapRemainder = ((yearsSinceBreak + 1) % 33 - 1) % 4
        if (leapRemainder == -1) leapRemainder = 4
        return YearInfo(gregorianYear, march, leapRemainder == 0)
    }

    fun isLeapYear(year: Int): Boolean = yearInfo(year).leap

    fun daysInMonth(year: Int, month: Int): Int {
        require(month in 1..12) { "ماه شمسی باید بین ۱ و ۱۲ باشد." }
        val leap = isLeapYear(year)
        return when (month) { in 1..6 -> 31; in 7..11 -> 30; else -> if (leap) 30 else 29 }
    }

    private fun yearStart(year: Int): LocalDate = yearInfo(year).let {
        LocalDate.of(it.gregorianYear, 3, it.marchDay)
    }

    fun jalaliToGregorian(date: StudyJalaliDate): LocalDate {
        require(date.day in 1..daysInMonth(date.year, date.month)) { "روز انتخاب‌شده در این ماه شمسی وجود ندارد." }
        val previousDays = if (date.month <= 6) (date.month - 1) * 31 else 186 + (date.month - 7) * 30
        return yearStart(date.year).plusDays((previousDays + date.day - 1).toLong())
    }

    fun gregorianToJalali(date: LocalDate): StudyJalaliDate {
        require(date.year in (MIN_YEAR + 621)..(MAX_YEAR + 622)) { "تاریخ خارج از محدودهٔ تقویم شمسی است." }
        var year = (date.year - 621).coerceIn(MIN_YEAR, MAX_YEAR)
        var start = yearStart(year)
        if (date.isBefore(start)) {
            require(year > MIN_YEAR) { "تاریخ خارج از محدودهٔ تقویم شمسی است." }
            year--
            start = yearStart(year)
        }
        val offset = ChronoUnit.DAYS.between(start, date).toInt()
        val yearLength = if (isLeapYear(year)) 366 else 365
        require(offset in 0 until yearLength) { "تاریخ خارج از محدودهٔ تقویم شمسی است." }
        return if (offset < 186) StudyJalaliDate(year, offset / 31 + 1, offset % 31 + 1)
        else StudyJalaliDate(year, (offset - 186) / 30 + 7, (offset - 186) % 30 + 1)
    }

    /** Persian, Arabic-Indic and ASCII digits are accepted; invalid dates are rejected. */
    fun parseJalali(value: String): LocalDate {
        val normalized = value.trim().map { digit -> when (digit) {
            in '۰'..'۹' -> '0' + (digit - '۰')
            in '٠'..'٩' -> '0' + (digit - '٠')
            else -> digit
        } }.joinToString("")
        val match = Regex("^([0-9]{1,4})\\s*[/\\-]\\s*([0-9]{1,2})\\s*[/\\-]\\s*([0-9]{1,2})$").matchEntire(normalized)
        requireNotNull(match) { "تاریخ شمسی را به شکل سال/ماه/روز وارد کنید." }
        return jalaliToGregorian(StudyJalaliDate(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt()))
    }

    private fun persianDigits(value: String): String = value.map {
        if (it in '0'..'9') "۰۱۲۳۴۵۶۷۸۹"[it - '0'] else it
    }.joinToString("")

    fun formatJalali(date: LocalDate): String = gregorianToJalali(date).let {
        persianDigits("${it.year}/${it.month.toString().padStart(2, '0')}/${it.day.toString().padStart(2, '0')}")
    }

    fun monthStart(date: LocalDate): LocalDate = gregorianToJalali(date).let {
        jalaliToGregorian(it.copy(day = 1))
    }

    fun nextMonthStart(date: LocalDate): LocalDate = gregorianToJalali(date).let {
        jalaliToGregorian(if (it.month == 12) StudyJalaliDate(it.year + 1, 1, 1) else StudyJalaliDate(it.year, it.month + 1, 1))
    }

    /** Iranian study weeks run from Saturday through Friday. */
    fun weekStart(date: LocalDate): LocalDate = date.minusDays(((date.dayOfWeek.value + 1) % 7).toLong())

    fun monthTitle(date: LocalDate): String = gregorianToJalali(date).let {
        "${monthNames[it.month - 1]} ${persianDigits(it.year.toString())}"
    }
}
