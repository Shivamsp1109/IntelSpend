package com.spendwise.presentation.screens

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import coil.compose.AsyncImage
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.spendwise.R
import com.spendwise.domain.repository.Gender
import com.spendwise.presentation.components.PurpleGradient
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.viewmodel.AuthViewModel
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(
    authViewModel: AuthViewModel,
    onLoggedIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user by authViewModel.currentUser.collectAsState()
    val state by authViewModel.loginState.collectAsState()
    var isCreateAccount by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf<Gender?>(null) }
    var profileImageUri by remember { mutableStateOf<Uri?>(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) {
        profileImageUri = it
    }

    LaunchedEffect(user) {
        if (user != null) onLoggedIn()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PurpleGradient)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = "SpendWise logo",
            modifier = Modifier.height(82.dp)
        )
        Spacer(Modifier.height(10.dp))
        Text("SpendWise", color = Color.White, style = MaterialTheme.typography.headlineLarge)
        Text("Track. Analyze. Save Better.", color = Color.White.copy(alpha = 0.78f))
        Spacer(Modifier.height(18.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(0.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (isCreateAccount) "Create your account" else "Welcome back",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFF17102A)
                )
                if (isCreateAccount) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Full name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = loginFieldColors()
                    )
                    Text("Gender", style = MaterialTheme.typography.labelLarge, color = SpendWiseTextMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Gender.entries.forEach { option ->
                            FilterChip(
                                selected = gender == option,
                                onClick = { gender = option },
                                label = { Text(option.label) }
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AsyncImage(
                            model = profileImageUri ?: R.drawable.default_profile_avatar,
                            contentDescription = "Selected profile image",
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                        )
                        OutlinedButton(
                            onClick = { imagePicker.launch("image/*") },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Text(if (profileImageUri == null) "Add profile image" else "Change image")
                        }
                    }
                }
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = loginFieldColors()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = loginFieldColors()
                )
                Button(
                    onClick = {
                        if (isCreateAccount) {
                            authViewModel.register(email, password, name, gender, profileImageUri)
                        } else {
                            authViewModel.login(email, password)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SpendWisePurple)
                ) {
                    Text(if (isCreateAccount) "Create Account" else "Email Login")
                }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                val activity = context as? Activity
                                    ?: error("Google sign-in needs an Activity context.")
                                val googleIdOption = GetGoogleIdOption.Builder()
                                    .setFilterByAuthorizedAccounts(false)
                                    .setServerClientId(context.getString(R.string.default_web_client_id))
                                    .setAutoSelectEnabled(false)
                                    .build()
                                val request = GetCredentialRequest.Builder()
                                    .addCredentialOption(googleIdOption)
                                    .build()
                                val result = CredentialManager.create(context)
                                    .getCredential(activity, request)
                                val credential = result.credential
                                if (
                                    credential is CustomCredential &&
                                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                                ) {
                                    GoogleIdTokenCredential.createFrom(credential.data).idToken
                                } else {
                                    error("Unsupported Google credential.")
                                }
                            }.onSuccess { idToken ->
                                authViewModel.loginWithGoogleIdToken(idToken)
                            }.onFailure {
                                authViewModel.showAuthError(it.message ?: "Google sign-in failed.")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Continue with Google")
                }
                OutlinedButton(
                    onClick = { isCreateAccount = !isCreateAccount },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(if (isCreateAccount) "I already have an account" else "Create Account")
                }
                Text(
                    "One Firebase UID is used for the user. If Google and email are linked in Firebase, both sign-in methods open the same account.",
                    color = SpendWiseTextMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun loginFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = SpendWisePurple.copy(alpha = 0.42f),
    unfocusedBorderColor = Color(0xFFE8E1F5),
    focusedContainerColor = Color.White,
    unfocusedContainerColor = Color.White
)
