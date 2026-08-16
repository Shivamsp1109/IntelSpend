package com.spendwise.domain.usecase

import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringStatus
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What tracked commitments will still cost before the month is out.
 *
 * The case worth protecting hardest is the first one. Counting a commitment
 * that has already been paid, on top of the payment itself sitting in the
 * month's spending, understates the surplus by the whole amount — and the
 * result looks entirely reasonable, so nothing about the screen would suggest
 * it was wrong.
 */
class CommitmentProjectionTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 8, 13)
    private val now = today.atTime(LocalTime.of(10, 30)).atZone(zone).toInstant().toEpochMilli()
    private val monthEnd = LocalDate.of(2026, 8, 31)
        .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    private fun midnight(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun entry(
        id: Int = 1,
        title: String = "Rent",
        amount: Double = 22_000.0,
        cadence: RecurringCadence = RecurringCadence.MONTHLY,
        due: LocalDate? = null,
        status: RecurringStatus = RecurringStatus.ACTIVE,
        nature: TransactionNature = TransactionNature.Spending
    ) = RecurringEntry(
        id = id,
        title = title,
        amount = amount,
        cadence = cadence,
        type = RecurringType.FIXED,
        currency = Currency.INR,
        nature = nature,
        category = ExpenseCategory.RentHousing,
        status = status,
        nextDueDate = due?.let { midnight(it) },
        dueDayOfMonth = due?.dayOfMonth
    )

    private fun project(entries: List<RecurringEntry>) =
        CommitmentProjection.of(entries, from = now, until = monthEnd, zone = zone)

    @Test
    fun `a commitment already paid this month is not counted again`() {
        // Rent went out on 1 August, so reconciliation has already moved the
        // next due date into September. The payment is in this month's spending
        // total; adding the commitment on top would charge the user twice.
        val rent = entry(due = LocalDate.of(2026, 9, 1))

        val projection = project(listOf(rent))

        assertEquals(0.0, projection.stillDue, 0.01)
        assertEquals(0, projection.occurrences)
    }

    @Test
    fun `a commitment still to fall due this month is counted once`() {
        val emi = entry(
            title = "Car EMI",
            amount = 15_000.0,
            due = LocalDate.of(2026, 8, 20),
            nature = TransactionNature.LoanRepayment
        )

        val projection = project(listOf(emi))

        assertEquals(15_000.0, projection.stillDue, 0.01)
        assertEquals(1, projection.occurrences)
        // Tracked apart so the screen can say how much of what is coming is debt.
        assertEquals(15_000.0, projection.debtStillDue, 0.01)
    }

    @Test
    fun `a weekly commitment is counted for every occurrence left in the month`() {
        // 14th, 21st and 28th all fall before month end; the 4th of September
        // does not.
        val weekly = entry(
            title = "Cleaner",
            amount = 500.0,
            cadence = RecurringCadence.WEEKLY,
            due = LocalDate.of(2026, 8, 14)
        )

        val projection = project(listOf(weekly))

        assertEquals(3, projection.occurrences)
        assertEquals(1_500.0, projection.stillDue, 0.01)
    }

    @Test
    fun `a yearly premium falling this month costs its whole amount, not a twelfth`() {
        // The distinction the snapshot keeps two separate fields for. What
        // leaves the account in August is the full premium; the twelfth is a
        // description of its average monthly cost and would badly understate
        // what the user actually needs in the account this month.
        val premium = entry(
            title = "Term insurance",
            amount = 12_000.0,
            cadence = RecurringCadence.YEARLY,
            due = LocalDate.of(2026, 8, 25)
        )

        val projection = project(listOf(premium))

        assertEquals(12_000.0, projection.stillDue, 0.01)
        assertEquals(1_000.0, CommitmentProjection.monthlyLoad(listOf(premium)), 0.01)
    }

    @Test
    fun `a paused commitment is not projected`() {
        val paused = entry(due = LocalDate.of(2026, 8, 20), status = RecurringStatus.PAUSED)

        val projection = project(listOf(paused))

        assertEquals(0.0, projection.stillDue, 0.01)
    }

    @Test
    fun `a commitment with no observed payment is reported rather than guessed at`() {
        val neverSeen = entry(title = "Gym", due = null)

        val projection = project(listOf(neverSeen))

        assertEquals(0.0, projection.stillDue, 0.01)
        assertEquals(listOf(neverSeen), projection.unschedulable)
    }

    @Test
    fun `a payment past due with nothing recorded is reported separately`() {
        // Neither counted into this month's bill nor silently dropped: the user
        // needs to know the app has lost track of it.
        val missed = entry(amount = 3_000.0, due = LocalDate.of(2026, 8, 5))

        val projection = project(listOf(missed))

        assertEquals(1, projection.overdueCount)
        assertEquals(3_000.0, projection.overdueAmount, 0.01)
        assertTrue(projection.stillDue < 3_000.0)
    }

    @Test
    fun `monthly load normalises cadences before adding them up`() {
        // ₹1,000 a week and ₹12,000 a year are not ₹13,000 a month.
        val weekly = entry(id = 1, amount = 1_000.0, cadence = RecurringCadence.WEEKLY)
        val yearly = entry(id = 2, amount = 12_000.0, cadence = RecurringCadence.YEARLY)

        val load = CommitmentProjection.monthlyLoad(listOf(weekly, yearly))

        // 1000 × 52.1775 / 12 = 4348.13, plus 1000.
        assertEquals(5_348.13, load, 0.5)
    }

    @Test
    fun `debt load counts only what repays a debt`() {
        val emi = entry(id = 1, amount = 15_000.0, nature = TransactionNature.LoanRepayment)
        val rent = entry(id = 2, amount = 22_000.0, nature = TransactionNature.Spending)

        assertEquals(15_000.0, CommitmentProjection.monthlyDebtLoad(listOf(emi, rent)), 0.01)
    }

    @Test
    fun `a period already over has nothing left to fall due`() {
        val julyEnd = LocalDate.of(2026, 7, 31)
            .plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val rent = entry(due = LocalDate.of(2026, 9, 1))

        val projection = CommitmentProjection.of(
            entries = listOf(rent),
            from = now,
            until = julyEnd,
            zone = zone
        )

        assertEquals(0.0, projection.stillDue, 0.01)
    }
}
