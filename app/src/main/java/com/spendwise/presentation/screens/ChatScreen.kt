package com.spendwise.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.spendwise.presentation.components.SpendWiseOrange
import com.spendwise.presentation.components.SpendWisePurple
import com.spendwise.presentation.components.SpendWiseSoftPurple
import com.spendwise.presentation.components.SpendWiseTextMuted
import com.spendwise.presentation.components.SpendWiseTextPrimary
import androidx.compose.ui.platform.LocalUriHandler
import com.spendwise.presentation.viewmodel.ChatCitation
import com.spendwise.presentation.viewmodel.ChatRole
import com.spendwise.presentation.viewmodel.ChatTurn
import com.spendwise.presentation.viewmodel.ChatViewModel

/**
 * Asking about your own finances.
 *
 * Two things on this screen are load-bearing rather than decorative.
 *
 * The consent panel is shown before anything else and states what leaves the
 * device — a summary of the user's position, never individual transactions or
 * merchant names. It is off until they turn it on, because what an assistant is
 * allowed to see is a decision to make deliberately rather than discover later.
 *
 * An answer the engine wrote itself, because the assistant's wording referred to
 * a figure that does not exist, is labelled as such. It reads plainer than a
 * composed reply, and hiding why would leave the user wondering whether the app
 * had got worse.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    onNavigateUp: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val enabled by viewModel.enabled.collectAsState()
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) listState.animateScrollToItem(state.turns.size - 1)
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
                    "Ask About Your Money",
                    style = MaterialTheme.typography.titleLarge,
                    color = SpendWiseTextPrimary
                )
                Text(
                    if (enabled) "Answers come from your own figures" else "Off",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpendWiseTextMuted
                )
            }
            if (state.turns.isNotEmpty()) {
                TextButton(onClick = viewModel::clearConversation) {
                    Text("Clear", color = SpendWiseTextMuted)
                }
            }
        }

        if (!enabled) {
            ConsentPanel(onEnable = { viewModel.setEnabled(true) })
            return@Column
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.turns.isEmpty()) {
                item { Opener() }
            }
            itemsIndexed(state.turns) { _, turn -> TurnBubble(turn) }

            if (state.isSending) {
                item {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(16.dp).widthIn(16.dp, 16.dp),
                            strokeWidth = 2.dp,
                            color = SpendWisePurple
                        )
                        Spacer(Modifier.widthIn(8.dp, 8.dp))
                        Text(
                            "Working it out…",
                            style = MaterialTheme.typography.bodySmall,
                            color = SpendWiseTextMuted
                        )
                    }
                }
            }
        }

        state.error?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseOrange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clickable { viewModel.clearError() }
            )
        }

        if (state.suggestions.isNotEmpty() && !state.isSending) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                state.suggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = { viewModel.ask(suggestion) },
                        label = { Text(suggestion, style = MaterialTheme.typography.bodySmall) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            labelColor = SpendWisePurple
                        )
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("How much can I save this month?") },
                maxLines = 4
            )
            IconButton(
                onClick = {
                    viewModel.ask(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank() && !state.isSending
            ) {
                Icon(Icons.Default.Send, contentDescription = "Ask", tint = SpendWisePurple)
            }
        }
    }
}

/**
 * What the user is agreeing to, stated before they agree to it.
 *
 * Specific rather than reassuring. "We take your privacy seriously" tells
 * somebody nothing; naming what is sent and what is not lets them actually
 * decide.
 */
@Composable
private fun ConsentPanel(onEnable: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            "Ask questions about your own finances",
            style = MaterialTheme.typography.titleMedium,
            color = SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "The answers are worked out by the app from your records. A cloud model " +
                "is used only to put those figures into sentences — it cannot change " +
                "them, and any figure it makes up is rejected before you see it.",
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextMuted
        )
        Spacer(Modifier.height(16.dp))

        Text(
            "What gets sent",
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Your question, and a summary of your position: income and spending " +
                "totals, what you owe, what you hold, and how your goals stand. Your " +
                "last few messages go with it so follow-up questions make sense.",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
        Spacer(Modifier.height(12.dp))

        Text(
            "What never does",
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Individual transactions, merchant names, account numbers or who you " +
                "paid. Those are never put into the request.",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = false, onCheckedChange = { onEnable() })
            Spacer(Modifier.widthIn(12.dp, 12.dp))
            Text(
                "Turn the assistant on",
                style = MaterialTheme.typography.bodyMedium,
                color = SpendWiseTextPrimary
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "You can turn this off at any time. It is not financial advice.",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted
        )
    }
}

/**
 * Where a rules answer came from, tappable.
 *
 * Shown rather than footnoted. A claim about tax or regulation the user cannot
 * trace back to a publisher is one they have to take on trust, and taking a
 * financial app's word for the law is exactly what this is built to avoid
 * asking of them.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CitationChips(citations: List<ChatCitation>) {
    val uriHandler = LocalUriHandler.current

    Column {
        Text(
            "Source",
            style = MaterialTheme.typography.bodySmall,
            color = SpendWiseTextMuted,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(4.dp))

        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            citations.forEach { citation ->
                SuggestionChip(
                    onClick = { citation.url?.let { runCatching { uriHandler.openUri(it) } } },
                    enabled = citation.url != null,
                    label = {
                        Text(
                            citation.publisher,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        labelColor = SpendWisePurple
                    )
                )
            }
        }

        // The reviewer, named. An unattributed review is not one, and somebody
        // reading a regulatory claim deserves to know a person stood behind it.
        citations.firstOrNull()?.reviewer?.let { reviewer ->
            Spacer(Modifier.height(4.dp))
            Text(
                "Checked by $reviewer",
                style = MaterialTheme.typography.bodySmall,
                color = SpendWiseTextMuted
            )
        }
    }
}

@Composable
private fun Opener() {
    Column {
        Text(
            "Ask about anything the app already knows — what you have spare, " +
                "whether a goal is on track, how your loans compare.",
            style = MaterialTheme.typography.bodyMedium,
            color = SpendWiseTextMuted
        )
    }
}

@Composable
private fun TurnBubble(turn: ChatTurn) {
    val fromUser = turn.role == ChatRole.USER

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (fromUser) SpendWiseSoftPurple else Color.White
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (fromUser) 0.dp else 1.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    turn.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpendWiseTextPrimary
                )

                if (turn.isEngineWorded) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Written straight from your figures — the assistant's wording " +
                            "referred to a value that does not exist, so it was not used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SpendWiseOrange
                    )
                }

                if (turn.citations.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    CitationChips(turn.citations)
                }

                turn.caveats.forEach { caveat ->
                    Spacer(Modifier.height(6.dp))
                    Text(
                        caveat,
                        style = MaterialTheme.typography.bodySmall,
                        color = SpendWiseTextMuted
                    )
                }
            }
        }
    }
}
