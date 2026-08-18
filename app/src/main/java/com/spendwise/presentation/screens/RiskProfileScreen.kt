package com.spendwise.presentation.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.domain.model.RiskLevel
import com.spendwise.domain.model.RiskQuestionnaire
import com.spendwise.presentation.components.SpendWiseGreen
import com.spendwise.presentation.components.SpendWiseOrange
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.SpendWiseTextPrimary
import com.spendwise.presentation.viewmodel.RiskProfileUiState
import com.spendwise.presentation.viewmodel.RiskProfileViewModel

/**
 * The risk questionnaire.
 *
 * Two things about this screen are deliberate and both matter.
 *
 * The questions are grouped into what you can *live with* and what your finances
 * can *withstand*, and the two are scored separately and shown separately. They
 * are routinely collapsed into one "risk score", and the result describes
 * nobody — a comfortable investor with no savings and a mortgage has high
 * tolerance and low capacity, and averaging them to "moderate" is true of
 * neither. Where they disagree, the screen says so, because that gap is the most
 * useful thing the exercise produces.
 *
 * Nothing is used until the user presses confirm. Answers are kept as a draft so
 * a half-finished questionnaire is not lost, but no part of the app will read
 * them — a profile applied to somebody's money should be one they agreed to.
 */
@Composable
fun RiskProfileScreen(
    onNavigateUp: () -> Unit,
    viewModel: RiskProfileViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearMessage()
        }
    }

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
                    "Your Risk Profile",
                    style = MaterialTheme.typography.titleLarge,
                    color = SpendWiseTextPrimary
                )
                Text(
                    "${state.answeredCount} of ${state.totalQuestions} answered",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
        }

        LinearProgressIndicator(
            progress = {
                if (state.totalQuestions == 0) 0f
                else state.answeredCount.toFloat() / state.totalQuestions
            },
            modifier = Modifier.fillMaxWidth().height(3.dp),
            color = SpendWisePurple,
            trackColor = SpendWiseSoftPurple
        )

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Preamble() }

            item { SectionHeading("What you could live with", "How losses would actually sit with you.") }
            items(RiskQuestionnaire.toleranceQuestions, key = { it.id }) { question ->
                QuestionCard(question, state.answers[question.id], viewModel::answer)
            }

            item {
                SectionHeading(
                    "What your finances could withstand",
                    "A different question, and the one that usually binds."
                )
            }
            items(RiskQuestionnaire.capacityQuestions, key = { it.id }) { question ->
                QuestionCard(question, state.answers[question.id], viewModel::answer)
            }

            if (state.provisionalTolerance != null || state.provisionalCapacity != null) {
                item { ResultCard(state) }
            }

            item { Actions(state, viewModel) }
        }
    }
}

@Composable
private fun Preamble() {
    ProfileCard {
        Text(
            "These questions help work out what kind of investments would suit you. " +
                "Nothing is used until you confirm the result, and you can change it " +
                "whenever your circumstances do.",
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextMuted
        )
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = SpendWiseTextMuted)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(
    question: RiskQuestionnaire.Question,
    selected: String?,
    onAnswer: (String, String) -> Unit
) {
    ProfileCard {
        Text(
            question.prompt,
            style = MaterialTheme.typography.bodyLarge,
            color = SpendWiseTextPrimary
        )
        question.help?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = SpendWiseTextMuted)
        }
        Spacer(Modifier.height(10.dp))

        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            question.options.forEach { option ->
                FilterChip(
                    selected = option.id == selected,
                    onClick = { onAnswer(question.id, option.id) },
                    label = { Text(option.text, style = MaterialTheme.typography.bodySmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SpendWiseSoftPurple,
                        selectedLabelColor = SpendWisePurple
                    )
                )
            }
        }
    }
}

/**
 * The two levels, shown apart, with the mismatch called out.
 *
 * Never averaged into one figure — that is the whole reason the questionnaire is
 * split, and collapsing it here would undo it at the last step.
 */
@Composable
private fun ResultCard(state: RiskProfileUiState) {
    ProfileCard {
        Text(
            "What your answers suggest",
            style = MaterialTheme.typography.titleMedium,
            color = SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))

        LevelRow("Comfortable with", state.provisionalTolerance)
        LevelRow("Able to absorb", state.provisionalCapacity)

        if (state.toleranceExceedsCapacity) {
            Spacer(Modifier.height(10.dp))
            Text(
                "You're comfortable with more risk than your finances can currently " +
                    "absorb. What you can absorb is the one that should decide.",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseOrange
            )
        }

        if (!state.isComplete) {
            Spacer(Modifier.height(10.dp))
            Text(
                "Based on what you've answered so far.",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun LevelRow(label: String, level: RiskLevel?) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextMuted
        )
        Text(
            level?.label ?: "Not answered yet",
            style = MaterialTheme.typography.bodyMedium,
            color = if (level == null) SpendWiseTextMuted else SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun Actions(state: RiskProfileUiState, viewModel: RiskProfileViewModel) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = viewModel::saveDraft, modifier = Modifier.weight(1f)) {
                Text("Save for later", color = SpendWiseTextMuted)
            }
            TextButton(onClick = viewModel::confirm, modifier = Modifier.weight(1f)) {
                Text(
                    if (state.isConfirmed) "Update profile" else "Confirm profile",
                    color = SpendWisePurple,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (state.isConfirmed) {
            TextButton(onClick = viewModel::clear) {
                Text("Remove my profile", color = SpendWiseTextMuted)
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (state.isConfirmed) {
                "Confirmed. The app uses this when describing whether your holdings suit you."
            } else {
                "Not confirmed yet, so nothing in the app is using it."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (state.isConfirmed) SpendWiseGreen else SpendWiseTextMuted
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "This is your own assessment of yourself, not a regulated suitability " +
                "review, and it is not investment advice.",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
    }
}

@Composable
private fun ProfileCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}
