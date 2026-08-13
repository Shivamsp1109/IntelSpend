package com.spendwise.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Converting between a stored transaction date and what Material's date picker
 * works in.
 *
 * The picker is UTC throughout: it wants the selected day as UTC midnight and
 * hands it back the same way. A transaction date is a local timestamp. Passing
 * one to the other unconverted is wrong by a day for anything recorded near
 * midnight, and silently so — the picker still opens on a perfectly plausible
 * date, just not the right one.
 *
 * Kept out of the composable file so the arithmetic can be tested directly.
 * Mirroring it in a test instead would verify a copy rather than the thing the
 * screen actually calls.
 */
object PickerDates {

    /** The calendar day this local timestamp falls on, as the UTC midnight the picker expects. */
    fun toPickerValue(localMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(localMillis).atZone(zone).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** The start of the day the picker means, in the device's own zone. */
    fun fromPickerValue(pickerMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        pickedDate(pickerMillis).atStartOfDay(zone).toInstant().toEpochMilli()

    /** The calendar day a picker value denotes, for range checks. */
    fun pickedDate(pickerMillis: Long): LocalDate =
        Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate()
}
