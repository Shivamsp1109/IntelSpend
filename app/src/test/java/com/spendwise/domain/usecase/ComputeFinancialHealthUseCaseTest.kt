package com.spendwise.domain.usecase

import com.spendwise.domain.model.AnalyticsPeriod
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import com.spendwise.domain.model.FinancialHealthSnapshot
import com.spendwise.domain.model.Goal
import com.spendwise.domain.model.GoalStatus
import com.spendwise.domain.model.GoalType
import com.spendwise.domain.model.PeriodType
import com.spendwise.domain.model.RecurringCadence
import com.spendwise.domain.model.RecurringEntry
import com.spendwise.domain.model.RecurringType
import com.spendwise.domain.model.TransactionNature
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The figures the app is willing to state about a household's position.
 *
 * Every assertion here is a claim the assistant will later repeat to a user as
 * fact, which is why the engine is deterministic and tested this closely. The
 * failures that matter are not crashes — they are numbers that look plausible
 * and are wrong, and a user has no way to check them.
 */
class ComputeFinancialHealthUseCaseTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 8, 13)
    private val now = today.atTime(LocalTime.of(10, 30)).atZone(zone).toInstant().toEpochMilli()
    private val august = AnalyticsPeriod(PeriodType.MONTH, anchor = today)

    private fun midnight(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun commitment(
        id: Int = 1,
        title: String = "Rent",
        amount: Double = 22_000.0,
        cadence: RecurringCadence = RecurringCadence.MONTHLY,
        due: LocalDate?,
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
        nextDueDate = due?.let { midnight(it) },
        dueDayOfMonth = due?.dayOfMonth
    )

    private fun goal(
        id: Int = 1,
        type: GoalType = GoalType.VACATION,
        target: Double,
        saved: Double = 0.0,
        targetDate: LocalDate
    ) = Goal(
        id = id,
        type = type,
        targetAmount = target,
        targetDate = midnight(targetDate),
        currentSaved = saved
    )

    private fun assemble(
        income: Double = 100_000.0,
        natureTotals: Map<TransactionNature, Double> = mapOf(
            TransactionNature.Spending to 40_000.0
        ),
        spendByCategory: Map<ExpenseCategory, Double> = mapOf(
            ExpenseCategory.RentHousing to 40_000.0
        ),
        commitments: List<RecurringEntry> = emptyList(),
        goals: List<Goal> = emptyList(),
        excludedCurrencies: List<Currency> = emptyList()
    ): FinancialHealthSnapshot = ComputeFinancialHealthUseCase.assemble(
        period = august,
        currency = Currency.INR,
        excludedCurrencies = excludedCurrencies,
        income = income,
        natureTotals = natureTotals,
        spendByCategory = spendByCategory,
        commitments = commitments,
        goals = goals,
        trendFlags = emptyList(),
        now = now,
        zone = zone
    )

    // ── Actual against projected ──────────────────────────────────────────────

    @Test
    fun `rent already paid is not also subtracted as a commitment`() {
        // ₹1,00,000 in, ₹40,000 of spending which includes the ₹22,000 rent
        // paid on the 1st. The naive engine subtracts the ₹22,000 commitment
        // again and reports ₹38,000 left instead of ₹60,000.
        val snapshot = assemble(
            commitments = listOf(commitment(due = LocalDate.of(2026, 9, 1)))
        )

        assertEquals(0.0, snapshot.commitmentsStillDue, 0.01)
        assertEquals(60_000.0, snapshot.unallocatedSurplus, 0.01)
        assertEquals(60_000.0, snapshot.projectedUnallocatedSurplus, 0.01)
    }

    @Test
    fun `a commitment still to fall due reduces the projection but not the actual`() {
        val snapshot = assemble(
            commitments = listOf(
                commitment(title = "Car EMI", amount = 15_000.0, due = LocalDate.of(2026, 8, 20))
            )
        )

        // What has actually happened is unchanged by what is expected.
        assertEquals(60_000.0, snapshot.unallocatedSurplus, 0.01)
        assertEquals(45_000.0, snapshot.projectedUnallocatedSurplus, 0.01)
    }

    @Test
    fun `money put into savings leaves the balance but not the unallocated surplus`() {
        // The two surpluses answer different questions and must not agree here:
        // the balance fell by the ₹20,000 invested, but that was a choice, so
        // it is still "available" in the sense that matters for affordability.
        val snapshot = assemble(
            natureTotals = mapOf(
                TransactionNature.Spending to 40_000.0,
                TransactionNature.Investment to 20_000.0
            )
        )

        assertEquals(40_000.0, snapshot.cashFlowSurplus, 0.01)
        assertEquals(60_000.0, snapshot.unallocatedSurplus, 0.01)
        assertEquals(0.2, snapshot.savedShareOfIncome!!, 0.001)
    }

    @Test
    fun `debt repayment is counted as an outflow but never as spending`() {
        val snapshot = assemble(
            natureTotals = mapOf(
                TransactionNature.Spending to 40_000.0,
                TransactionNature.LoanRepayment to 15_000.0
            )
        )

        assertEquals(40_000.0, snapshot.actualSpending, 0.01)
        assertEquals(15_000.0, snapshot.actualDebtRepayment, 0.01)
        assertEquals(45_000.0, snapshot.cashFlowSurplus, 0.01)
        assertEquals(0.15, snapshot.debtToIncomeRatio!!, 0.001)
    }

    @Test
    fun `transfers between the user's own accounts never reach the totals`() {
        // A statement import picks these up as debits. Counted, one transfer
        // makes the month look ruinous.
        val snapshot = assemble(
            natureTotals = mapOf(
                TransactionNature.Spending to 40_000.0,
                TransactionNature.SelfTransfer to 50_000.0,
                TransactionNature.CreditCardPayment to 30_000.0
            )
        )

        assertEquals(40_000.0, snapshot.actualOutflow, 0.01)
        assertEquals(60_000.0, snapshot.cashFlowSurplus, 0.01)
    }

    // ── Cash due against monthly cost ─────────────────────────────────────────

    @Test
    fun `an annual premium is charged in full this month but averaged in the load`() {
        val premium = commitment(
            title = "Term insurance",
            amount = 12_000.0,
            cadence = RecurringCadence.YEARLY,
            due = LocalDate.of(2026, 8, 25)
        )

        val snapshot = assemble(commitments = listOf(premium))

        assertEquals(12_000.0, snapshot.commitmentsStillDue, 0.01)
        assertEquals(1_000.0, snapshot.monthlyCommitmentLoad, 0.01)
    }

    // ── Missing data ──────────────────────────────────────────────────────────

    @Test
    fun `a period with no income reports nothing rather than zero`() {
        val snapshot = assemble(income = 0.0)

        assertNull(snapshot.savedShareOfIncome)
        assertNull(snapshot.debtToIncomeRatio)
        assertNull(snapshot.committedShareOfIncome)
        assertTrue(snapshot.caveats.any { it.contains("No income is recorded") })
    }

    @Test
    fun `activity in another currency is excluded and said to be excluded`() {
        val snapshot = assemble(excludedCurrencies = listOf(Currency.USD))

        assertTrue(snapshot.caveats.any { it.contains("USD") })
    }

    @Test
    fun `the essential split always says it is the app's own view`() {
        val snapshot = assemble(
            spendByCategory = mapOf(
                ExpenseCategory.RentHousing to 22_000.0,
                ExpenseCategory.Groceries to 8_000.0,
                ExpenseCategory.Entertainment to 6_000.0,
                ExpenseCategory.Shopping to 4_000.0
            )
        )

        assertEquals(30_000.0, snapshot.essentialExpense, 0.01)
        assertEquals(10_000.0, snapshot.discretionaryExpense, 0.01)
        assertEquals(0.75, snapshot.essentialShare!!, 0.001)
        assertTrue(snapshot.caveats.any { it.contains("not your own") })
    }

    @Test
    fun `a commitment with a price change waiting keeps the agreed amount`() {
        // The user was asked and has not answered. Using the new figure would
        // decide for them.
        val spotify = commitment(
            title = "Spotify",
            amount = 199.0,
            due = LocalDate.of(2026, 8, 20)
        ).copy(pendingAmount = 139.0)

        val snapshot = assemble(commitments = listOf(spotify))

        assertEquals(199.0, snapshot.commitmentsStillDue, 0.01)
        assertTrue(snapshot.caveats.any { it.contains("price change waiting") })
    }

    // ── Goals ─────────────────────────────────────────────────────────────────

    @Test
    fun `a goal already saved for needs nothing more`() {
        val snapshot = assemble(
            goals = listOf(
                goal(target = 50_000.0, saved = 50_000.0, targetDate = LocalDate.of(2026, 12, 1))
            )
        )

        val assessed = snapshot.goals.single()
        assertEquals(GoalStatus.COMPLETED, assessed.status)
        assertEquals(0.0, assessed.requiredMonthlyContribution, 0.01)
        assertEquals(0.0, snapshot.totalRequiredMonthlyContribution, 0.01)
    }

    @Test
    fun `a goal past its date is overdue and needs the whole shortfall`() {
        val snapshot = assemble(
            goals = listOf(
                goal(target = 50_000.0, saved = 20_000.0, targetDate = LocalDate.of(2026, 5, 1))
            )
        )

        val assessed = snapshot.goals.single()
        assertEquals(GoalStatus.OVERDUE, assessed.status)
        assertEquals(30_000.0, assessed.requiredMonthlyContribution, 0.01)
    }

    @Test
    fun `a goal due this month is not divided by a fraction of a month`() {
        // Dividing by the days left would report a monthly requirement several
        // times larger than the goal.
        val snapshot = assemble(
            goals = listOf(
                goal(target = 50_000.0, saved = 20_000.0, targetDate = LocalDate.of(2026, 8, 28))
            )
        )

        val assessed = snapshot.goals.single()
        assertEquals(GoalStatus.DUE_THIS_MONTH, assessed.status)
        assertEquals(30_000.0, assessed.requiredMonthlyContribution, 0.01)
    }

    @Test
    fun `a goal within reach of the surplus is on track`() {
        // ₹60,000 spare; ₹60,000 over 6 months is ₹10,000 a month.
        val snapshot = assemble(
            goals = listOf(
                goal(target = 60_000.0, targetDate = LocalDate.of(2027, 2, 1))
            )
        )

        val assessed = snapshot.goals.single()
        assertEquals(GoalStatus.ON_TRACK, assessed.status)
        assertEquals(10_000.0, assessed.requiredMonthlyContribution, 0.01)
        assertTrue(assessed.individuallyFeasible)
    }

    @Test
    fun `a goal beyond the surplus is at risk`() {
        val snapshot = assemble(
            goals = listOf(
                goal(target = 600_000.0, targetDate = LocalDate.of(2027, 2, 1))
            )
        )

        assertEquals(GoalStatus.AT_RISK, snapshot.goals.single().status)
    }

    @Test
    fun `goals affordable one at a time can still be impossible together`() {
        // Three goals at ₹25,000 a month each against a ₹60,000 surplus. Nothing
        // that looks at one goal at a time can see the problem.
        val goals = (1..3).map { index ->
            goal(
                id = index,
                target = 150_000.0,
                targetDate = LocalDate.of(2027, 2, 1)
            )
        }

        val snapshot = assemble(goals = goals)

        assertTrue(snapshot.goals.all { it.individuallyFeasible })
        assertEquals(75_000.0, snapshot.totalRequiredMonthlyContribution, 0.01)
        assertFalse(snapshot.goalsCollectivelyFeasible)
        assertTrue(snapshot.caveats.any { it.contains("not all of them together") })
    }

    @Test
    fun `goal feasibility is measured after this month's commitments are met`() {
        // ₹60,000 spare, but ₹40,000 of it is already committed to an EMI still
        // to go out. A goal needing ₹30,000 a month is affordable against the
        // raw surplus and not against what is really left.
        val snapshot = assemble(
            commitments = listOf(
                commitment(title = "EMI", amount = 40_000.0, due = LocalDate.of(2026, 8, 20))
            ),
            goals = listOf(
                goal(target = 60_000.0, targetDate = LocalDate.of(2026, 10, 1))
            )
        )

        assertEquals(20_000.0, snapshot.projectedUnallocatedSurplus, 0.01)
        assertEquals(GoalStatus.AT_RISK, snapshot.goals.single().status)
    }

    @Test
    fun `the most urgent goals are listed first`() {
        val snapshot = assemble(
            goals = listOf(
                goal(id = 1, target = 10_000.0, saved = 10_000.0, targetDate = LocalDate.of(2027, 1, 1)),
                goal(id = 2, target = 90_000.0, targetDate = LocalDate.of(2026, 4, 1)),
                goal(id = 3, target = 60_000.0, targetDate = LocalDate.of(2027, 2, 1))
            )
        )

        // Completed first, then overdue, then the rest — the enum's own order,
        // so the screen and any later summary rank them the same way.
        assertEquals(
            listOf(GoalStatus.COMPLETED, GoalStatus.OVERDUE, GoalStatus.ON_TRACK),
            snapshot.goals.map { it.status }
        )
    }

    // ── Context ───────────────────────────────────────────────────────────────

    @Test
    fun `what is coming up is listed soonest first and stops at a fortnight`() {
        val soon = commitment(id = 1, title = "EMI", due = LocalDate.of(2026, 8, 20))
        val alsoSoon = commitment(id = 2, title = "Broadband", due = LocalDate.of(2026, 8, 15))
        val tooFar = commitment(id = 3, title = "Rent", due = LocalDate.of(2026, 9, 30))

        val snapshot = assemble(commitments = listOf(soon, alsoSoon, tooFar))

        assertEquals(listOf("Broadband", "EMI"), snapshot.upcomingPayments.map { it.title })
    }

    @Test
    fun `a commitment never yet paid is flagged rather than quietly ignored`() {
        val snapshot = assemble(commitments = listOf(commitment(title = "Gym", due = null)))

        assertEquals(0.0, snapshot.commitmentsStillDue, 0.01)
        assertTrue(snapshot.caveats.any { it.contains("no recorded payment yet") })
    }
}
