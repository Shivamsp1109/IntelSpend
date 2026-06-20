package com.spendwise.presentation.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.spendwise.presentation.theme.SpendWiseTheme
import org.junit.Rule
import org.junit.Test

class MetricCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun metricCardShowsLabelAndValue() {
        composeRule.setContent {
            SpendWiseTheme {
                MetricCard(label = "Total Expense", value = "₹12,500")
            }
        }

        composeRule.onNodeWithText("Total Expense").assertIsDisplayed()
        composeRule.onNodeWithText("₹12,500").assertIsDisplayed()
    }
}
