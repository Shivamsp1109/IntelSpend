package com.spendwise.presentation.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

@Composable
fun ProfileScreen(
    authViewModel: AuthViewModel,
    onHome: () -> Unit,
    onTransactions: () -> Unit,
    onAddExpense: () -> Unit,
    onAnalytics: () -> Unit,
    onLogout: () -> Unit
) {
    val user by authViewModel.currentUser.collectAsState()

    SpendWiseScreen(
        selected = BottomDestination.Profile,
        onHome = onHome,
        onTransactions = onTransactions,
        onAdd = onAddExpense,
        onAnalytics = onAnalytics,
        onProfile = {}
    ) { screenModifier ->
        Column(
            modifier = screenModifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
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
            ProfileItem(Icons.Default.Person, "Personal Information")
            ProfileItem(Icons.Default.CreditCard, "Payment Methods")
            ProfileItem(Icons.Default.Category, "Categories")
            ProfileItem(Icons.Default.AccountBalanceWallet, "Budget")
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

@Composable
private fun ProfileItem(icon: ImageVector, title: String) {
    Card(
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
