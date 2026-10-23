package com.pushbound.game.engine

import java.util.Calendar
import java.util.TimeZone

/**
 * Deterministic selection of one level per calendar day.
 *
 * CONTRACT — the SAME date always maps to the SAME level, on any device, in any
 * time zone, in any locale, and with no network. That is what makes a daily
 * puzzle shared: everyone who opens the app on a given date plays the same
 * puzzle, whether they are online or not.
 *
 * HOW THAT IS GUARANTEED:
 *
 *  - Selection is a pure function of [epochDay] — an integer counting days since
 *    1970-01-01. Nothing else is consulted: no hash map iteration order, no
 *    locale, no device id, no random source, no clock reading inside the
 *    mapping itself.
 *  - `floorMod(epochDay, levelCount)` spreads days across the campaign evenly
 *    and never produces a negative index, so it is safe for dates before 1970
 *    as well as after.
 *  - The local calendar date is converted to an epoch day by an explicit,
 *    self-contained algorithm ([epochDay]) rather than by a library whose
 *    behaviour could differ across API levels. `java.time` is unavailable below
 *    API 26 and this app supports API 24, which is exactly why it is not used.
 *
 * SCOPE — the daily pool is the WHOLE campaign, deliberately independent of
 * progression. A daily puzzle is a shared, standing challenge: gating it behind
 * what a player has unlocked would give two people different dailies on the same
 * date, breaking the point of the mode.
 *
 * SEED — [seedFor] exposes the day number as a seed so a future generator mode
 * can build a puzzle from the same date without disturbing this mapping.
 */
object DailyPuzzle {

    /** Days from 1970-01-01 to 1970-01-01 is 0; this is the epoch's day number. */
    const val EPOCH_DAY_ZERO = 0L

    /**
     * Proleptic Gregorian day number for a calendar date.
     *
     * Days-from-civil: months are shifted so the year begins in March, which
     * makes leap days the last day of the year and removes every conditional
     * from the inner arithmetic. Pure integer maths, identical on every device.
     *
     * @param year full year (e.g. 2026).
     * @param month 1-based month.
     * @param day 1-based day of month.
     */
    fun epochDay(year: Int, month: Int, day: Int): Long {
        require(month in 1..12) { "month must be 1..12, was $month" }
        require(day in 1..31) { "day must be 1..31, was $day" }

        var y = year
        val m = month
        // Move the new year to March so leap day falls at the end of the year.
        if (m <= 2) y -= 1

        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val shiftedMonth = if (m > 2) m - 3 else m + 9
        val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear

        return era * 146097L + dayOfEra - 719468L
    }

    /**
     * The device's CURRENT local calendar date as an epoch day.
     *
     * This is the only place the clock is read, and its output is only ever fed
     * into [epochDay]-based selection, so the mapping date -> puzzle stays a
     * pure function. The caller chooses the date; the selection never chooses a
     * time zone behind the player's back.
     */
    fun todayEpochDay(): Long {
        val calendar = Calendar.getInstance(TimeZone.getDefault())
        return epochDay(
            year = calendar.get(Calendar.YEAR),
            month = calendar.get(Calendar.MONTH) + 1,
            day = calendar.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * The level id assigned to [epochDay], or null when the campaign is empty.
     *
     * Stable for the life of a given campaign: adding levels changes which day
     * maps to which puzzle, which is why the mapping is only ever consulted for
     * the current day rather than being persisted as a long-term key.
     */
    fun levelIdFor(epochDay: Long, levelIds: List<String>): String? {
        if (levelIds.isEmpty()) return null

        // Floor modulo, expressed directly rather than delegated: it must stay
        // non-negative for dates BEFORE the epoch too, which a plain `%` does
        // not guarantee on the JVM.
        val size = levelIds.size.toLong()
        val index = ((epochDay % size) + size) % size
        return levelIds[index.toInt()]
    }

    /**
     * The daily CONTENT identity for [epochDay]: its ISO-8601 date.
     *
     * This is deliberately NOT a campaign level id. The board a day plays is
     * still picked by [levelIdFor], but the day's identity — the thing progress
     * and the resumable session are keyed by — is the date itself. That is what
     * lets two modes share a grid without ever sharing a record.
     */
    fun contentIdFor(epochDay: Long): String = isoDate(epochDay)

    /**
     * Inverse of [isoDate]: the epoch day of a `yyyy-MM-dd` string.
     *
     * Days-from-civil, the exact inverse of the arithmetic in [isoDate], so a
     * round trip through encode and decode is lossless for every representable
     * date. Written by hand for the same reason [isoDate] is: the app's minSdk
     * is 24 and `java.time.LocalDate` needs 26.
     *
     * @return null when [text] is not a well-formed ISO-8601 calendar date.
     *   Malformed input is rejected rather than coerced, because this value
     *   becomes a persisted identity and guessing it would silently merge two
     *   different days.
     */
    fun parseIsoDate(text: String): Long? {
        val parts = text.split('-')
        // ISO years outside 0000..9999 carry a sign and are four or more digits,
        // so a well-formed date has exactly three '-'-separated fields only when
        // the year is unsigned; a signed year still yields three fields because
        // the sign belongs to the first one.
        if (parts.size != 3) return null

        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31) return null
        // Reject text that only LOOKS like a date, e.g. "20261-01-01" parsed as
        // year 20261, or "2026-001-01": the encoder never emits those shapes.
        if (parts[1].length != 2 || parts[2].length != 2) return null
        val minYear = if (parts[0].startsWith("-")) -9999 else 0
        if (year < minYear || year > 9999) return null

        return epochDay(year, month, day)
    }

    /**
     * [epochDay] as an ISO-8601 calendar date, `yyyy-MM-dd`.
     *
     * This is the identity a player reads on the daily-puzzle entry, so it has
     * to be a fixed string for a given day: no locale, no time zone, no
     * formatting rules that a device could decide differently. It is written as
     * civil-date arithmetic rather than `java.time` because the app's minSdk is
     * 24 and `java.time.LocalDate` needs 26 — this must never be the reason the
     * title screen crashes on an older phone.
     */
    fun isoDate(epochDay: Long): String {
        // Howard Hinnant's days-from-civil inverse: shift so 0000-03-01 is the
        // epoch, which puts leap day at the END of the year and makes the
        // era blocks of exactly 146097 days (400 years, 146096 days).
        val z = epochDay + 719468L
        val era = (if (z >= 0L) z else z - 146096L) / 146097L
        val dayOfEra = z - era * 146097L
        val yearOfEra =
            (dayOfEra - dayOfEra / 1460L + dayOfEra / 36524L - dayOfEra / 146096L) / 365L
        val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
        val monthPart = (5L * dayOfYear + 2L) / 153L
        val day = dayOfYear - (153L * monthPart + 2L) / 5L + 1L
        val month = if (monthPart < 10L) monthPart + 3L else monthPart - 9L
        val year = yearOfEra + era * 400L + (if (month <= 2L) 1L else 0L)

        // ISO-8601 expands the year field with a sign once it leaves four
        // digits. Days that far out will never appear in a daily puzzle, but
        // emitting anything else would make the formatter disagree with the
        // calendar for no gain — and this is a fixed identity string, so it is
        // worth getting exactly right rather than approximately right.
        val yearText = when {
            year in 0..9999 -> "%04d".format(year)
            year > 9999 -> "+%d".format(year)
            else -> "-%04d".format(-year)
        }
        return "$yearText-%02d-%02d".format(month, day)
    }

    /** Seed a future generator would use for this day. */
    fun seedFor(epochDay: Long): Long = epochDay

    /** True when [epochDay] falls on a date that repeats every 7 days. */
    fun isWeekend(epochDay: Long): Boolean {
        // 1970-01-01 was a Thursday: day 4 of a week where Sunday = 0.
        val dayOfWeek = ((epochDay + 4) % 7 + 7) % 7
        return dayOfWeek == 0L || dayOfWeek == 6L
    }
}
