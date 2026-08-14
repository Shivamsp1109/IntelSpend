package com.spendwise.domain.usecase

import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringStatus
import com.spendwise.domain.model.RecurringType
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deciding when a payment reminder is worth showing.
 *
 * The value of these notifications is entirely in their being trustworthy. One
 * reminder about a bill the user paid last week teaches them the feature is
 * noise, they turn it off, and the genuinely useful reminders never arrive
 * either. Every rule here removes one specific way of being wrong, so each is
 * pinned separately.
 */
class RecurringReminderPolicyTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 8, 13)

    /** Mid-morning: late enough that a naive "start of day" would already be wrong. */
    private val now = today.atTime(LocalTime.of(10, 30)).atZone(zone).toInstant().toEpochMilli()

    private fun midnight(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun entry(
        id: Int = 1,
        title: String = "Rent",
        due: LocalDate? = today.plusDays(2),
        status: RecurringStatus = RecurringStatus.ACTIVE
    ) = RecurringEntry(
        id = id,
        title = title,
        amount = 22_000.0,
        cadence = RecurringCadence.MONTHLY,
        type = RecurringType.FIXED,
        currency = Currency.INR,
        category = ExpenseCategory.RentHousing,
        status = status,
        nextDueDate = due?.let { midnight(it) }
    )

    private fun due(
        entries: List<RecurringEntry>,
        reminded: Map<Int, Long> = emptyMap()
    ) = RecurringReminderPolicy.due(
        entries = entries,
        lastRemindedDueDate = { reminded[it] },
        now = now,
        zone = zone
    )

    // ── What should be said ───────────────────────────────────────────────────

    @Test
    fun `a payment falling due in two days is worth mentioning`() {
        assertEquals(1, due(listOf(entry(due = today.plusDays(2)))).size)
    }

    /**
     * A due date is stored as local midnight, so by mid-morning it is already in
     * the past as an instant. Comparing against the raw clock would silence the
     * reminder on the one day it matters most.
     */
    @Test
    fun `a payment due today is still mentioned in the afternoon`() {
        assertEquals(1, due(listOf(entry(due = today))).size)
    }

    @Test
    fun `several payments come back soonest first`() {
        val result = due(
            listOf(
                entry(id = 1, title = "Later", due = today.plusDays(3)),
                entry(id = 2, title = "Sooner", due = today.plusDays(1))
            )
        )

        assertEquals(listOf("Sooner", "Later"), result.map { it.title })
    }

    // ── What should be kept quiet ─────────────────────────────────────────────

    @Test
    fun `a payment further out than the lead time waits`() {
        assertTrue(due(listOf(entry(due = today.plusDays(10)))).isEmpty())
    }

    /**
     * The judgement call worth stating plainly. A payment that looks overdue is
     * just as likely to have been made and not yet imported — statements arrive
     * in batches, weeks late — and telling someone their rent is overdue when
     * they paid it on time costs more trust than the missed nudge is worth.
     */
    @Test
    fun `a payment that looks overdue is not announced`() {
        assertTrue(due(listOf(entry(due = today.minusDays(2)))).isEmpty())
    }

    /**
     * The worker runs daily. Without this, a bill three days out would be
     * announced on each of those three days.
     */
    @Test
    fun `the same due date is only announced once`() {
        val rent = entry(due = today.plusDays(2))
        val alreadyTold = mapOf(rent.id to midnight(today.plusDays(2)))

        assertTrue(due(listOf(rent), reminded = alreadyTold).isEmpty())
    }

    /** Next month's payment is a new fact, even though the commitment is the same. */
    @Test
    fun `the next cycle is announced again`() {
        val rent = entry(due = today.plusDays(2))
        val toldAboutLastMonth = mapOf(rent.id to midnight(today.minusMonths(1)))

        assertEquals(1, due(listOf(rent), reminded = toldAboutLastMonth).size)
    }

    @Test
    fun `paused and ended commitments are never announced`() {
        assertTrue(due(listOf(entry(status = RecurringStatus.PAUSED))).isEmpty())
        assertTrue(due(listOf(entry(status = RecurringStatus.ENDED))).isEmpty())
    }

    @Test
    fun `a commitment with no projected date is skipped`() {
        assertTrue(due(listOf(entry(due = null))).isEmpty())
    }

    @Test
    fun `nothing tracked produces nothing`() {
        assertTrue(due(emptyList()).isEmpty())
    }

    /**
     * The suppression that matters most, and the one reconciliation provides:
     * a settled payment has already moved its commitment's due date to the next
     * cycle, which puts it outside the window without any special case here.
     */
    @Test
    fun `a settled commitment falls out of the window on its own`() {
        val settled = entry(due = today.plusMonths(1))

        assertTrue(due(listOf(settled)).isEmpty())
    }
}
