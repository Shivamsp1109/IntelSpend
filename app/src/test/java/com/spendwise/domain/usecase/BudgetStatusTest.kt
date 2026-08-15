package com.spendwise.domain.usecase

import com.spendwise.data.local.CategoryBudgetEntity
import com.spendwise.data.local.CategorySpendRow
import com.spendwise.domain.model.CategoryBudget
import com.spendwise.domain.model.BudgetMonth
import com.spendwise.domain.model.CategoryBudgetStatus
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.ExpenseCategory
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Budgets, and when to say something about them.
 *
 * The arithmetic is simple enough that the risk is not getting it wrong but
 * getting it silently wrong — a rupee limit measured against dollar spending, a
 * month boundary off by a day, an alert that repeats until it is ignored. Those
 * are what these pin.
 */
class BudgetStatusTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun budget(
        category: ExpenseCategory = ExpenseCategory.FoodDining,
        limit: Double = 8_000.0,
        currency: Currency = Currency.INR
    ) = CategoryBudget(id = 1, category = category, monthlyLimit = limit, currency = currency)

    private fun spend(
        category: ExpenseCategory = ExpenseCategory.FoodDining,
        amount: Double,
        currency: Currency = Currency.INR
    ) = CategorySpendRow(category.label, currency.code, amount)

    // ── The arithmetic ────────────────────────────────────────────────────────

    @Test
    fun `spending is measured against the limit`() {
        val status = GetCategoryBudgetStatusUseCase
            .statusesFor(listOf(budget(limit = 8_000.0)), listOf(spend(amount = 2_000.0)))
            .single()

        assertEquals(25, status.percentUsed)
        assertEquals(6_000.0, status.remaining, 0.01)
        assertFalse(status.isOverBudget)
    }

    /** Over is the thing worth knowing, so it is never clamped to a tidy 100%. */
    @Test
    fun `going over reports how far over`() {
        val status = GetCategoryBudgetStatusUseCase
            .statusesFor(listOf(budget(limit = 8_000.0)), listOf(spend(amount = 10_000.0)))
            .single()

        assertEquals(125, status.percentUsed)
        assertEquals(-2_000.0, status.remaining, 0.01)
        assertTrue(status.isOverBudget)
    }

    /**
     * A budget with nothing spent is the case the user most wants confirmed.
     * Dropping it would make the screen look like the budget had been lost.
     */
    @Test
    fun `a budget with no spending still appears`() {
        val status = GetCategoryBudgetStatusUseCase
            .statusesFor(listOf(budget()), emptyList())
            .single()

        assertEquals(0, status.percentUsed)
        assertEquals(8_000.0, status.remaining, 0.01)
    }

    @Test
    fun `spending in a category with no budget is ignored`() {
        val statuses = GetCategoryBudgetStatusUseCase.statusesFor(
            budgets = listOf(budget(category = ExpenseCategory.FoodDining)),
            spend = listOf(spend(category = ExpenseCategory.Shopping, amount = 50_000.0))
        )

        assertEquals(1, statuses.size)
        assertEquals(0.0, statuses.single().spent, 0.01)
    }

    /** Wrong in both directions if it slips: wildly over, or permanently untouched. */
    @Test
    fun `a rupee budget is not measured against dollar spending`() {
        val statuses = GetCategoryBudgetStatusUseCase.statusesFor(
            budgets = listOf(budget(currency = Currency.INR, limit = 8_000.0)),
            spend = listOf(spend(currency = Currency.USD, amount = 500.0))
        )

        assertEquals(0.0, statuses.single().spent, 0.01)
    }

    @Test
    fun `the most overspent budget comes first`() {
        val statuses = GetCategoryBudgetStatusUseCase.statusesFor(
            budgets = listOf(
                CategoryBudget(id = 1, category = ExpenseCategory.FoodDining, monthlyLimit = 10_000.0),
                CategoryBudget(id = 2, category = ExpenseCategory.Fuel, monthlyLimit = 5_000.0)
            ),
            spend = listOf(
                spend(category = ExpenseCategory.FoodDining, amount = 2_000.0),
                spend(category = ExpenseCategory.Fuel, amount = 4_800.0)
            )
        )

        assertEquals(listOf(ExpenseCategory.Fuel, ExpenseCategory.FoodDining), statuses.map { it.category })
    }

    @Test
    fun `a zero limit does not divide by zero`() {
        val status = GetCategoryBudgetStatusUseCase
            .statusesFor(listOf(budget(limit = 0.0)), listOf(spend(amount = 500.0)))
            .single()

        assertEquals(0.0, status.fractionUsed, 0.001)
    }

    @Test
    fun `approaching the limit is distinguishable from passing it`() {
        val near = CategoryBudgetStatus(budget(limit = 1_000.0), spent = 850.0)
        val over = CategoryBudgetStatus(budget(limit = 1_000.0), spent = 1_100.0)
        val fine = CategoryBudgetStatus(budget(limit = 1_000.0), spent = 200.0)

        assertTrue(near.isNearLimit)
        assertFalse(near.isOverBudget)
        assertFalse(over.isNearLimit)
        assertTrue(over.isOverBudget)
        assertFalse(fine.isNearLimit)
    }

    // ── The month it is measured over ─────────────────────────────────────────

    /**
     * A payment made on the evening of the 31st belongs to that month in the
     * user's own calendar. A UTC boundary would push it into the next one for
     * anyone east of Greenwich, quietly resetting their budget a few hours early.
     */
    @Test
    fun `the month runs from local midnight to local midnight`() {
        val august = BudgetMonth.containing(
            LocalDate.of(2026, 8, 15).atStartOfDay(zone).toInstant().toEpochMilli(),
            zone
        )

        assertEquals("2026-08", august.key)
        assertEquals(
            LocalDate.of(2026, 8, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            august.start
        )
        assertEquals(
            LocalDate.of(2026, 9, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            august.endExclusive
        )
    }

    @Test
    fun `the last moment of a month is still inside it`() {
        val august = BudgetMonth.containing(
            LocalDate.of(2026, 8, 31).atTime(23, 59).atZone(zone).toInstant().toEpochMilli(),
            zone
        )

        assertEquals("2026-08", august.key)
    }

    @Test
    fun `february is measured over its own length`() {
        val february = BudgetMonth.containing(
            LocalDate.of(2028, 2, 10).atStartOfDay(zone).toInstant().toEpochMilli(),
            zone
        )

        assertEquals(
            LocalDate.of(2028, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli(),
            february.endExclusive
        )
    }

    // ── When to say something ─────────────────────────────────────────────────

    private fun entity(
        id: Int = 1,
        category: ExpenseCategory = ExpenseCategory.FoodDining,
        limit: Double = 1_000.0,
        alertedThreshold: Int = 0,
        alertedMonth: String? = null
    ) = CategoryBudgetEntity(
        id = id,
        category = category.label,
        monthlyLimit = limit,
        currency = Currency.INR.code,
        lastAlertedThreshold = alertedThreshold,
        lastAlertedMonth = alertedMonth
    )

    private fun statusAt(spent: Double, limit: Double = 1_000.0) =
        CategoryBudgetStatus(budget(limit = limit), spent = spent)

    @Test
    fun `nothing is said well inside the budget`() {
        val alerts = BudgetAlertPolicy.alerts(
            budgets = listOf(entity()),
            statuses = listOf(statusAt(400.0)),
            month = "2026-08"
        )

        assertTrue(alerts.isEmpty())
    }

    @Test
    fun `approaching the limit is announced once`() {
        val alerts = BudgetAlertPolicy.alerts(
            budgets = listOf(entity()),
            statuses = listOf(statusAt(820.0)),
            month = "2026-08"
        )

        assertEquals(80, alerts.single().threshold)
    }

    @Test
    fun `the same threshold is not announced twice`() {
        val alerts = BudgetAlertPolicy.alerts(
            budgets = listOf(entity(alertedThreshold = 80, alertedMonth = "2026-08")),
            statuses = listOf(statusAt(850.0)),
            month = "2026-08"
        )

        assertTrue(alerts.isEmpty())
    }

    /** Passing the limit is a different fact, and worth its own word. */
    @Test
    fun `passing the limit is announced even after the warning was`() {
        val alerts = BudgetAlertPolicy.alerts(
            budgets = listOf(entity(alertedThreshold = 80, alertedMonth = "2026-08")),
            statuses = listOf(statusAt(1_100.0)),
            month = "2026-08"
        )

        assertEquals(100, alerts.single().threshold)
    }

    @Test
    fun `nothing more is said once the limit has been announced`() {
        val alerts = BudgetAlertPolicy.alerts(
            budgets = listOf(entity(alertedThreshold = 100, alertedMonth = "2026-08")),
            statuses = listOf(statusAt(3_000.0)),
            month = "2026-08"
        )

        assertTrue(alerts.isEmpty())
    }

    /**
     * Without the month check, someone who blew a budget in July would never be
     * warned again for the rest of the year.
     */
    @Test
    fun `a new month starts the alerting afresh`() {
        val alerts = BudgetAlertPolicy.alerts(
            budgets = listOf(entity(alertedThreshold = 100, alertedMonth = "2026-07")),
            statuses = listOf(statusAt(850.0)),
            month = "2026-08"
        )

        assertEquals(80, alerts.single().threshold)
    }

    /** Straight past both marks in one go: the higher one is the news. */
    @Test
    fun `crossing both thresholds at once announces the higher`() {
        val alerts = BudgetAlertPolicy.alerts(
            budgets = listOf(entity()),
            statuses = listOf(statusAt(1_500.0)),
            month = "2026-08"
        )

        assertEquals(100, alerts.single().threshold)
    }

    @Test
    fun `a budget with no status is skipped rather than assumed empty`() {
        val alerts = BudgetAlertPolicy.alerts(
            budgets = listOf(entity()),
            statuses = emptyList(),
            month = "2026-08"
        )

        assertTrue(alerts.isEmpty())
    }
}
