package com.spendwise.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolving a category label.
 *
 * The category column stores the *display label*, and the labels changed when
 * the list widened from seven entries to twenty-two. Every historical row is
 * therefore read through here, and getting it wrong does not fail loudly — it
 * quietly reclassifies a user's entire history as Other on the upgrade that
 * shipped it.
 */
class ExpenseCategoryTest {

    @Test
    fun `current labels resolve to themselves`() {
        for (category in ExpenseCategory.entries) {
            assertEquals(category, ExpenseCategory.fromLabel(category.label))
        }
    }

    /**
     * The learned-category store writes enum names while the database writes
     * labels. Both have to resolve, or a category the user corrected by hand
     * comes back as Other next time.
     */
    @Test
    fun `enum names resolve too`() {
        for (category in ExpenseCategory.entries) {
            assertEquals(category, ExpenseCategory.fromLabel(category.name))
        }
    }

    /** Every label the app used to store, and where it belongs now. */
    @Test
    fun `labels from the old seven-category list still resolve`() {
        assertEquals(ExpenseCategory.FoodDining, ExpenseCategory.fromLabel("Food"))
        assertEquals(ExpenseCategory.Travel, ExpenseCategory.fromLabel("Travel"))
        assertEquals(ExpenseCategory.Shopping, ExpenseCategory.fromLabel("Shopping"))
        assertEquals(ExpenseCategory.HealthMedical, ExpenseCategory.fromLabel("Health"))
        assertEquals(ExpenseCategory.Entertainment, ExpenseCategory.fromLabel("Entertainment"))
        assertEquals(ExpenseCategory.Other, ExpenseCategory.fromLabel("Other"))
    }

    /**
     * "Bills" covered utilities, phone bills and subscriptions indiscriminately.
     * Utilities is the closest single home; what matters is that it lands
     * somewhere deliberate rather than in Other.
     */
    @Test
    fun `the old Bills label lands somewhere deliberate`() {
        assertEquals(ExpenseCategory.Utilities, ExpenseCategory.fromLabel("Bills"))
        assertNotEquals(ExpenseCategory.Other, ExpenseCategory.fromLabel("Bills"))
    }

    /**
     * These two were offered by the extraction model but had no home in the app,
     * so a correctly-classified grocery run was thrown away into Other. That was
     * a live bug, not a hypothetical.
     */
    @Test
    fun `categories the model already returned now have a home`() {
        assertEquals(ExpenseCategory.Groceries, ExpenseCategory.fromLabel("Groceries"))
        assertEquals(ExpenseCategory.Education, ExpenseCategory.fromLabel("Education"))
    }

    @Test
    fun `matching ignores case and surrounding space`() {
        assertEquals(ExpenseCategory.FoodDining, ExpenseCategory.fromLabel("  food & dining  "))
        assertEquals(ExpenseCategory.Groceries, ExpenseCategory.fromLabel("GROCERIES"))
    }

    @Test
    fun `anything unrecognised falls to Other rather than throwing`() {
        assertEquals(ExpenseCategory.Other, ExpenseCategory.fromLabel("Nonsense"))
        assertEquals(ExpenseCategory.Other, ExpenseCategory.fromLabel(""))
        assertEquals(ExpenseCategory.Other, ExpenseCategory.fromLabel("   "))
    }

    /** Labels are what the user reads, so they must be distinct and non-empty. */
    @Test
    fun `labels are unique and present`() {
        val labels = ExpenseCategory.entries.map { it.label }
        assertEquals(labels.size, labels.distinct().size)
        assertTrue(labels.none { it.isBlank() })
    }
}
