package com.spendwise.presentation.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.spendwise.presentation.components.PurpleGradient
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    authViewModel: AuthViewModel,
    onLoggedIn: () -> Unit,
    onLoggedOut: () -> Unit
) {
    val user by authViewModel.currentUser.collectAsState()

    LaunchedEffect(user) {
        delay(900)
        if (user == null) onLoggedOut() else onLoggedIn()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PurpleGradient)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.08f), radius = 160f, center = Offset(size.width * 0.18f, size.height * 0.18f))
            drawCircle(Color.White.copy(alpha = 0.08f), radius = 120f, center = Offset(size.width * 0.88f, size.height * 0.78f))
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(116.dp)
                    .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(28.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, tint = Color.White, modifier = Modifier.size(72.dp))
            }
            Spacer(Modifier.height(26.dp))
            Text("SpendWise", color = Color.White, style = MaterialTheme.typography.headlineLarge)
            Text("Track. Analyze. Save Better.", color = Color.White.copy(alpha = 0.82f))
            Spacer(Modifier.height(70.dp))
            Button(
                onClick = { if (user == null) onLoggedOut() else onLoggedIn() },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = SpendWisePurple),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Get Started")
            }
        }
    }
}
