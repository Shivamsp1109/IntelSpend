package com.spendwise.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spendwise.presentation.components.HeaderRow
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseTextMuted

@Composable
fun NotificationsScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        HeaderRow(
            title = "Notifications",
            action = {
                OutlinedButton(onClick = onBack) {
                    Text("Back")
                }
            }
        )
        NotificationCard(
            title = "Daily reminder",
            body = "Don't forget to track today's expenses."
        )
        NotificationCard(
            title = "Sync status",
            body = "Offline expenses will sync automatically when internet is available."
        )
    }
}

@Composable
private fun NotificationCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.Notifications, contentDescription = null, tint = SpendWisePurple)
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color(0xFF17102A))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = SpendWiseTextMuted)
        }
    }
}
