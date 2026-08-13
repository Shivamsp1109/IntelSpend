package com.spendwise.presentation.screens

import com.spendwise.util.PickerDates

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Converting between a stored transaction date and what Material's date picker
 * works in.
 *
 * The picker is UTC throughout: it wants the selected day as UTC midnight and
 * hands it back the same way. A transaction date is a local timestamp. Feeding
 * one to the other unconverted is off by a day for anything recorded near
 * midnight — and silently, since the picker still shows a perfectly plausible
 * date.
 *
 * These call the conversions the screen itself uses, so a change there fails
 * here. An earlier version of this file reimplemented the arithmetic instead,
 * which would have verified a copy and let the real one drift.
 */
class PickedDateTest {

    private val kolkata = ZoneId.of("Asia/Kolkata")   // UTC+5:30
    private val newYork = ZoneId.of("America/New_York") // UTC-5

    private fun localMillis(date: LocalDate, hour: Int, zone: ZoneId): Long =
        date.atStartOfDay(zone).plusHours(hour.toLong()).toInstant().toEpochMilli()

    /**
     * The case that breaks a naive conversion: 02:00 in Kolkata is still the
     * previous day in UTC, so passing the raw timestamp opens the picker on the
     * wrong date.
     */
    @Test
    fun `an early-morning transaction opens on its own day`() {
        val earlyMorning = localMillis(LocalDate.of(2026, 8, 13), hour = 2, zone = kolkata)

        assertEquals(LocalDate.of(2026, 8, 13), PickerDates.pickedDate(PickerDates.toPickerValue(earlyMorning, kolkata)))
    }

    /** And the mirror case, for a zone behind UTC rather than ahead of it. */
    @Test
    fun `a late-evening transaction opens on its own day`() {
        val lateEvening = localMillis(LocalDate.of(2026, 8, 13), hour = 22, zone = newYork)

        assertEquals(LocalDate.of(2026, 8, 13), PickerDates.pickedDate(PickerDates.toPickerValue(lateEvening, newYork)))
    }

    /**
     * Coming back the other way. UTC midnight read in a zone behind Greenwich is
     * the previous evening, so storing the picker's value unconverted dates the
     * transaction a day early.
     */
    @Test
    fun `a picked day is stored as that day locally`() {
        val picked = LocalDate.of(2026, 8, 13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        for (zone in listOf(kolkata, newYork, ZoneOffset.UTC)) {
            val stored = PickerDates.fromPickerValue(picked, zone)
            assertEquals(
                "picked day must survive into $zone",
                LocalDate.of(2026, 8, 13),
                Instant.ofEpochMilli(stored).atZone(zone).toLocalDate()
            )
        }
    }

    @Test
    fun `a date survives a full round trip through the picker`() {
        for (zone in listOf(kolkata, newYork)) {
            for (hour in listOf(0, 2, 12, 22, 23)) {
                val original = localMillis(LocalDate.of(2026, 8, 13), hour, zone)
                val roundTripped = PickerDates.fromPickerValue(PickerDates.toPickerValue(original, zone), zone)

                assertEquals(
                    "hour $hour in $zone",
                    LocalDate.of(2026, 8, 13),
                    Instant.ofEpochMilli(roundTripped).atZone(zone).toLocalDate()
                )
            }
        }
    }
}
