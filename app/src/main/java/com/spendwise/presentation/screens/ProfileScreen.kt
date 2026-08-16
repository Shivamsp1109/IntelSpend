package com.spendwise.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.spendwise.R
import com.spendwise.presentation.components.BottomDestination
import com.spendwise.presentation.components.PurpleGradient
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseScreen
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.viewmodel.AuthViewModel
import com.spendwise.presentation.viewmodel.SmartExtractionViewModel

@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    onHome: () -> Unit,
    onTransactions: () -> Unit,
    onAddExpense: () -> Unit,
    onAnalytics: () -> Unit,
    onFixCategories: () -> Unit = {},
    onRecurringPayments: () -> Unit = {},
    onBudgets: () -> Unit = {},
    onFinancialHealth: () -> Unit = {},
    onLogout: () -> Unit,
    smartExtractionViewModel: SmartExtractionViewModel = hiltViewModel()
) {
    val user by authViewModel.currentUser.collectAsState()
    val smartExtraction by smartExtractionViewModel.uiState.collectAsState()

    SpendWiseScreen(
        selected = BottomDestination.Profile,
        onHome = onHome,
        onTransactions = onTransactions,
        onAdd = onAddExpense,
        onAnalytics = onAnalytics,
        onProfile = {},
        // This screen is settings and account, not capture. A button for adding
        // a transaction floats over the list here for no reason and covers the
        // last row of it.
        showAddButton = false
    ) { screenModifier ->
        Column(
            modifier = screenModifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // The list has outgrown the screen — Logout and the last entries
                // were unreachable on shorter handsets.
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .background(PurpleGradient)
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .background(Color.White.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = user?.displayPhotoUrl ?: R.drawable.default_profile_avatar,
                            contentDescription = "Profile image",
                            modifier = Modifier
                                .size(52.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.14f), CircleShape)
                        )
                    }
                    Column {
                        Text(user?.name ?: "Shivam Kumar", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        Text(user?.email ?: "spendwise@example.com", color = Color.White.copy(alpha = 0.76f), style = MaterialTheme.typography.bodySmall)
                        Text(
                            providerLabel(
                                isGoogleUser = user?.isGoogleUser == true,
                                isEmailPasswordUser = user?.isEmailPasswordUser == true
                            ),
                            color = Color.White.copy(alpha = 0.70f),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            SmartExtractionCard(
                enabled = smartExtraction.enabled,
                callsThisMonth = smartExtraction.callsThisMonth,
                monthlyCallCap = smartExtraction.monthlyCallCap,
                estimatedCostUsd = smartExtraction.estimatedCostUsd,
                usageLoaded = smartExtraction.usageLoaded,
                onToggle = smartExtractionViewModel::setEnabled
            )
            NarrativeCard(
                enabled = smartExtraction.narrativeEnabled,
                onToggle = smartExtractionViewModel::setNarrativeEnabled
            )
            ProfileItem(Icons.Default.Person, "Personal Information")
            ProfileItem(Icons.Default.CreditCard, "Payment Methods")
            ProfileItem(
                Icons.Default.Category,
                "Fix Categories",
                onClick = onFixCategories
            )
            ProfileItem(
                Icons.Default.Autorenew,
                "Recurring Payments",
                onClick = onRecurringPayments
            )
            ProfileItem(
                Icons.Default.AccountBalanceWallet,
                "Budgets",
                onClick = onBudgets
            )
            ProfileItem(
                Icons.Default.Favorite,
                "Financial Health",
                onClick = onFinancialHealth
            )
            ProfileItem(Icons.Default.ReceiptLong, "Expense Data")
            ProfileItem(Icons.Default.Settings, "Settings")
            ProfileItem(Icons.Default.Help, "Help & Support")
            Button(
                onClick = {
                    authViewModel.logout()
                    onLogout()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Color(0xFFEF4444))
            ) {
                Icon(Icons.Default.Logout, contentDescription = null)
                Text("Logout", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun providerLabel(isGoogleUser: Boolean, isEmailPasswordUser: Boolean): String = when {
    isGoogleUser && isEmailPasswordUser -> "Google and Email account"
    isGoogleUser -> "Google account"
    isEmailPasswordUser -> "Email account"
    else -> "SpendWise account"
}

/**
 * Opt-in for cloud extraction.
 *
 * The subtitle states plainly that images leave the device — this sends bank
 * statements and receipts to a third party, so the consequence belongs on the
 * toggle itself rather than buried in a help page.
 */
@Composable
private fun SmartExtractionCard(
    enabled: Boolean,
    callsThisMonth: Int,
    monthlyCallCap: Int,
    estimatedCostUsd: Double,
    usageLoaded: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = SpendWisePurple)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Smart Extraction", color = Color(0xFF17102A))
                    Text(
                        // Naming Google matters. "Sent to our servers" implies the
                        // data stops there; the whole document is forwarded to a
                        // third-party model, and that is the part someone deciding
                        // whether to upload a bank statement needs to know.
                        "Reads receipts and statements your phone can't. The full " +
                            "image is uploaded and forwarded to Google's Gemini API " +
                            "to be read.",
                        color = SpendWiseTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = SpendWisePurple)
                )
            }

            if (enabled && usageLoaded) {
                Text(
                    text = buildString {
                        append("$callsThisMonth")
                        if (monthlyCallCap > 0) append(" of $monthlyCallCap")
                        append(" used this month")
                        if (estimatedCostUsd > 0) {
                            append(" · about $")
                            append(String.format("%.2f", estimatedCostUsd))
                        }
                    },
                    color = SpendWiseTextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 10.dp, start = 36.dp)
                )
            }
        }
    }
}

/**
 * Opt-in for written summaries.
 *
 * Separate from smart extraction because it sends something different, and the
 * subtitle says exactly what: totals and merchant names, not the transactions
 * themselves. "Uses AI" would tell the reader nothing they need in order to
 * decide.
 */
@Composable
private fun NarrativeCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Description, contentDescription = null, tint = SpendWisePurple)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Written Summaries", color = Color(0xFF17102A))
                    Text(
                        "Describes a period in plain English on the Analysis screen. " +
                            "Sends your totals and top merchant names to Google's " +
                            "Gemini API — not individual transactions.",
                        color = SpendWiseTextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(checkedTrackColor = SpendWisePurple)
                )
            }

            if (enabled) {
                Text(
                    // Stated because the button is the only thing that spends
                    // money here, and the user should know that before tapping it.
                    "Nothing is sent until you tap Summarise. Counts towards the same monthly limit.",
                    color = SpendWiseTextMuted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 10.dp, start = 36.dp)
                )
            }
        }
    }
}

@Composable
private fun ProfileItem(
    icon: ImageVector,
    title: String,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(icon, contentDescription = null, tint = SpendWisePurple)
            Text(title, modifier = Modifier.weight(1f), color = Color(0xFF17102A))
            Text(">", color = SpendWiseTextMuted)
        }
    }
}
