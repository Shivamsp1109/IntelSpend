package com.spendwise.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val SpendWisePurple = Color(0xFF7B00FF)
val SpendWiseDeepPurple = Color(0xFF4C00B8)
val SpendWiseSoftPurple = Color(0xFFF4ECFF)
val SpendWiseGreen = Color(0xFF00B875)
val SpendWiseOrange = Color(0xFFFF8A00)
val SpendWiseTextMuted = Color(0xFF7C748A)

/**
 * Body text on a white surface.
 *
 * Named rather than left to the theme because several cards and fields in this
 * app are explicitly white while the screen behind them is tinted: the theme's
 * default content colour is chosen for the background, and inheriting it there
 * leaves titles and typed text nearly invisible.
 */
val SpendWiseTextPrimary = Color(0xFF17102A)

val PurpleGradient = Brush.verticalGradient(
    colors = listOf(Color(0xFF9600FF), Color(0xFF5F00D8))
)

@Composable
fun SpendWiseScreen(
    selected: BottomDestination,
    onHome: () -> Unit,
    onTransactions: () -> Unit,
    onAdd: () -> Unit,
    onAnalytics: () -> Unit,
    onProfile: () -> Unit,
    /** Off on screens that read rather than capture, so nothing floats over the content. */
    showAddButton: Boolean = true,
    content: @Composable (Modifier) -> Unit
) {
    Scaffold(
        floatingActionButton = {
            if (showAddButton) {
                FloatingActionButton(
                    onClick = onAdd,
                    containerColor = SpendWisePurple,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.size(58.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Add expense",
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        },
        bottomBar = {
            SpendWiseBottomBar(
                selected = selected,
                onHome = onHome,
                onTransactions = onTransactions,
                onAnalytics = onAnalytics,
                onProfile = onProfile
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        content(Modifier.padding(padding))
    }
}

@Composable
fun SpendWiseBottomBar(
    selected: BottomDestination,
    onHome: () -> Unit,
    onTransactions: () -> Unit,
    onAnalytics: () -> Unit,
    onProfile: () -> Unit
) {
    NavigationBar(
        containerColor = Color.White,
        tonalElevation = 8.dp
    ) {
        BottomDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = {
                    when (destination) {
                        BottomDestination.Home -> onHome()
                        BottomDestination.Transactions -> onTransactions()
                        BottomDestination.Analytics -> onAnalytics()
                        BottomDestination.Profile -> onProfile()
                    }
                },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                label = { Text(destination.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SpendWisePurple,
                    selectedTextColor = SpendWisePurple,
                    indicatorColor = SpendWiseSoftPurple,
                    unselectedIconColor = SpendWiseTextMuted,
                    unselectedTextColor = SpendWiseTextMuted
                )
            )
        }
    }
}

enum class BottomDestination(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Transactions("Transactions", Icons.Default.List),
    Analytics("Analysis", Icons.Default.Analytics),
    Profile("Profile", Icons.Default.Person)
}

@Composable
fun HeaderRow(
    title: String,
    subtitle: String? = null,
    titleColor: Color = Color(0xFF17102A),
    subtitleColor: Color = SpendWiseTextMuted,
    action: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.layout.Column {
            Text(title, style = MaterialTheme.typography.titleLarge, color = titleColor)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = subtitleColor)
            }
        }
        action?.invoke()
    }
}

@Composable
fun PurplePillIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .background(SpendWiseSoftPurple, CircleShape)
            .padding(10.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = SpendWisePurple)
    }
}
