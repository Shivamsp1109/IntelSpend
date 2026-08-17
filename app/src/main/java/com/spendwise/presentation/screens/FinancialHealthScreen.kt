package com.spendwise.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.domain.model.Currency
import com.spendwise.domain.model.FinancialHealthSnapshot
import com.spendwise.domain.model.GoalFeasibility
import com.spendwise.domain.model.GoalStatus
import com.spendwise.presentation.components.Amount
import com.spendwise.presentation.components.SpendWiseGreen
import com.spendwise.presentation.components.SpendWiseOrange
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.SpendWiseTextPrimary
import com.spendwise.presentation.viewmodel.FinancialHealthViewModel
import com.spendwise.util.CurrencyFormatter
import com.spendwise.util.DateUtils
import kotlin.math.roundToInt

/**
 * Where the household stands this month, worked out on the device.
 *
 * Deliberately titled as a cash-flow snapshot rather than a full assessment. It
 * covers what this handset holds — recorded transactions, confirmed commitments,
 * goals — and it works with no network, which is why it exists. The complete
 * picture, taking in what the user owns, their real loan terms and their cover,
 * is computed on the server. Letting this screen present itself as that would
 * invite a decision on a narrower set of facts than the user would assume.
 *
 * Every figure is arithmetic over transactions the user can go and look at, and
 * the caveats are shown as prominently as the numbers rather than tucked at the
 * bottom. An assessment that quietly rests on an assumption — that the app's
 * idea of "essential" matches theirs, that a currency was left out — is worse
 * than no assessment, because it invites a decision the figures do not support.
 */
@Composable
fun FinancialHealthScreen(
    onNavigateUp: () -> Unit,
    viewModel: FinancialHealthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snapshot = state.snapshot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateUp) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Cash Flow Snapshot",
                    style = MaterialTheme.typography.titleLarge,
                    color = SpendWiseTextPrimary
                )
                Text(
                    snapshot?.periodLabel ?: "Working it out…",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
            IconButton(onClick = viewModel::showPreviousPeriod) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
            }
            IconButton(onClick = viewModel::showNextPeriod) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next month")
            }
        }

        when {
            !state.isLoaded -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = SpendWisePurple)
            }

            state.error != null || snapshot == null -> Box(
                Modifier.fillMaxSize().padding(24.dp),
                Alignment.Center
            ) {
                Text(
                    state.error ?: "Nothing to assess yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpendWiseTextMuted
                )
            }

            else -> HealthBody(snapshot)
        }
    }
}

@Composable
private fun HealthBody(snapshot: FinancialHealthSnapshot) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { CashFlowCard(snapshot) }
        item { CommitmentCard(snapshot) }

        if (snapshot.essentialExpense + snapshot.discretionaryExpense > 0.0) {
            item { CompositionCard(snapshot) }
        }

        if (snapshot.goals.isNotEmpty()) {
            item { SectionTitle("Goals") }
            items(snapshot.goals, key = { it.goal.id }) { feasibility ->
                GoalCard(feasibility, snapshot.currency)
            }
        }

        if (snapshot.upcomingPayments.isNotEmpty()) {
            item { SectionTitle("Coming up") }
            items(snapshot.upcomingPayments, key = { it.id }) { entry ->
                UpcomingRow(
                    title = entry.title,
                    amount = CurrencyFormatter.format(entry.amount, entry.currency),
                    due = entry.nextDueDate?.let { DateUtils.formatDate(it) } ?: ""
                )
            }
        }

        if (snapshot.caveats.isNotEmpty()) {
            item { SectionTitle("Worth knowing") }
            item { CaveatCard(snapshot.caveats) }
        }

        item { ScopeFooter(snapshot) }
    }
}

/**
 * Says plainly what this screen is and is not.
 *
 * Shown at the bottom of the figures rather than as a disclaimer nobody reads,
 * because the distinction is load-bearing: someone deciding whether they can
 * afford something needs to know this covers their cash flow and not what they
 * own or owe in full.
 */
@Composable
private fun ScopeFooter(snapshot: FinancialHealthSnapshot) {
    HealthCard {
        Text(
            "Worked out on this device from your recorded transactions, tracked " +
                "commitments and goals. It does not yet include what you own, your " +
                "loan terms or your insurance cover.",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${snapshot.engineVersion} · ${DateUtils.formatDate(snapshot.computedAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
    }
}

@Composable
private fun CashFlowCard(snapshot: FinancialHealthSnapshot) {
    val currency = snapshot.currency
    HealthCard {
        Text(
            "This month",
            style = MaterialTheme.typography.titleMedium,
            color = SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))

        Figure("Income", snapshot.actualIncome, currency, SpendWiseGreen)
        Figure("Spending", snapshot.actualSpending, currency)
        if (snapshot.actualDebtRepayment > 0.0) {
            Figure("Loan and EMI", snapshot.actualDebtRepayment, currency)
        }
        if (snapshot.actualSavingsAndInvestment > 0.0) {
            Figure("Saved and invested", snapshot.actualSavingsAndInvestment, currency)
        }

        Spacer(Modifier.height(10.dp))
        Figure(
            label = "Left after obligations",
            value = snapshot.unallocatedSurplus,
            currency = currency,
            color = if (snapshot.unallocatedSurplus >= 0.0) SpendWiseGreen else SpendWiseOrange,
            emphasise = true
        )

        // Shown only when it actually differs, so the screen never carries two
        // near-identical figures that the reader has to tell apart.
        if (snapshot.commitmentsStillDue > 0.0) {
            Figure(
                label = "After what is still due",
                value = snapshot.projectedUnallocatedSurplus,
                currency = currency,
                color = if (snapshot.projectedUnallocatedSurplus >= 0.0) {
                    SpendWiseGreen
                } else {
                    SpendWiseOrange
                }
            )
        }

        snapshot.savedShareOfIncome?.let {
            Spacer(Modifier.height(6.dp))
            Text(
                "You put aside ${percent(it)} of what came in.",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun CommitmentCard(snapshot: FinancialHealthSnapshot) {
    val currency = snapshot.currency
    HealthCard {
        Text(
            "Commitments",
            style = MaterialTheme.typography.titleMedium,
            color = SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "What your tracked subscriptions, rent and EMIs cost in an average month.",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
        Spacer(Modifier.height(10.dp))

        Figure("Every month", snapshot.monthlyCommitmentLoad, currency, emphasise = true)
        if (snapshot.monthlyDebtCommitmentLoad > 0.0) {
            Figure("Of which debt", snapshot.monthlyDebtCommitmentLoad, currency)
        }
        if (snapshot.commitmentsStillDue > 0.0) {
            Figure("Still to go out", snapshot.commitmentsStillDue, currency, SpendWiseOrange)
        }

        snapshot.committedShareOfIncome?.let { share ->
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { share.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (share > 0.5) SpendWiseOrange else SpendWisePurple,
                trackColor = SpendWiseSoftPurple
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${percent(share)} of your income is already spoken for.",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
        }

        snapshot.debtToIncomeRatio?.takeIf { it > 0.0 }?.let {
            Text(
                "Loan repayment is ${percent(it)} of your income.",
                style = MaterialTheme.typography.bodySmall,
                color = if (it > 0.4) SpendWiseOrange else SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun CompositionCard(snapshot: FinancialHealthSnapshot) {
    HealthCard {
        Text(
            "Where it went",
            style = MaterialTheme.typography.titleMedium,
            color = SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        Figure("Essentials", snapshot.essentialExpense, snapshot.currency)
        Figure("Everything else", snapshot.discretionaryExpense, snapshot.currency)

        snapshot.essentialShare?.let { share ->
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { share.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = SpendWisePurple,
                trackColor = SpendWiseSoftPurple
            )
        }
    }
}

@Composable
private fun GoalCard(feasibility: GoalFeasibility, currency: Currency) {
    val (label, tint) = when (feasibility.status) {
        GoalStatus.COMPLETED -> "Reached" to SpendWiseGreen
        GoalStatus.OVERDUE -> "Past its date" to SpendWiseOrange
        GoalStatus.DUE_THIS_MONTH -> "Due this month" to SpendWiseOrange
        GoalStatus.ON_TRACK -> "On track" to SpendWiseGreen
        GoalStatus.AT_RISK -> "Needs more than you have spare" to SpendWiseOrange
    }

    HealthCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    feasibility.goal.type.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SpendWiseTextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = tint
                )
            }
            if (feasibility.requiredMonthlyContribution > 0.0) {
                Column(horizontalAlignment = Alignment.End) {
                    Amount(
                        CurrencyFormatter.format(
                            feasibility.requiredMonthlyContribution,
                            currency
                        ),
                        color = SpendWiseTextPrimary
                    )
                    Text(
                        if (feasibility.monthsRemaining > 0) "a month" else "needed now",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpendWiseTextMuted
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { feasibility.goal.progressFraction.toFloat() },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = tint,
            trackColor = SpendWiseSoftPurple
        )
    }
}

@Composable
private fun UpcomingRow(title: String, amount: String, due: String) {
    HealthCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = SpendWiseTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(due, style = MaterialTheme.typography.bodySmall, color = SpendWiseTextMuted)
            }
            Amount(amount, color = SpendWiseTextPrimary)
        }
    }
}

@Composable
private fun CaveatCard(caveats: List<String>) {
    HealthCard {
        caveats.forEachIndexed { index, caveat ->
            if (index > 0) Spacer(Modifier.height(8.dp))
            Text(
                caveat,
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = SpendWiseTextPrimary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun HealthCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/**
 * Label on the left, figure on the right, the figure never wrapping.
 *
 * The label takes the slack deliberately — a `SpaceBetween` row with no weight
 * measures the amount at zero width when the label is long, and an amount
 * wrapped to one character per line is what that looks like on screen.
 */
@Composable
private fun Figure(
    label: String,
    value: Double,
    currency: Currency,
    color: Color = SpendWiseTextPrimary,
    emphasise: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = if (emphasise) {
                MaterialTheme.typography.bodyLarge
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (emphasise) SpendWiseTextPrimary else SpendWiseTextMuted,
            fontWeight = if (emphasise) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Amount(CurrencyFormatter.format(value, currency), color = color)
    }
}

private fun percent(fraction: Double): String = "${(fraction * 100).roundToInt()}%"
