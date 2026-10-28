package com.example.ptt.talk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.example.ptt.R
import com.example.ptt.crypto.EnvelopePayload

@Composable
fun FamilyHost(onBack: () -> Unit) = FamilyContent(hiltViewModel(), onBack)

/** The Family screen wired to [viewModel]: sending opens the sender's talk screen. */
@Composable
fun FamilyContent(viewModel: FamilyViewModel, onBack: () -> Unit) {
    val people by viewModel.people.collectAsState(initial = null)
    val confirm by viewModel.confirm.collectAsState()
    val context = LocalContext.current
    var message by remember { mutableStateOf<FamilyEvent.Message?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect {
            when (it) {
                is FamilyEvent.Open -> {
                    message = null
                    context.startActivity(TalkIntents.intent(context, it.pageId, TalkIntents.MODE_OUTGOING, it.name))
                }

                is FamilyEvent.Message -> message = it
            }
        }
    }
    FamilyScreen(people, confirm, message, viewModel::page, viewModel::confirmEnd, viewModel::dismissConfirm, onBack)
}

@Suppress("LongParameterList")
@Composable
fun FamilyScreen(
    people: List<FamilyPerson>?,
    confirm: FamilyConfirm?,
    message: FamilyEvent.Message?,
    onPage: (FamilyPerson, String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.family_title), style = MaterialTheme.typography.headlineMedium)
        message?.let { Text(messageText(it), color = MaterialTheme.colorScheme.error) }
        when {
            people == null -> Text(stringResource(R.string.family_not_loaded))
            people.isEmpty() -> Text(stringResource(R.string.family_empty))
            else -> people.forEach { PersonRow(it, onPage) }
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
    confirm?.let {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.family_confirm_title)) },
            text = {
                Text(
                    if (it.currentName != null) {
                        stringResource(R.string.family_confirm_text, it.currentName)
                    } else {
                        stringResource(R.string.family_confirm_text_unknown)
                    }
                )
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.family_confirm_yes)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.devices_cancel)) } }
        )
    }
}

@Composable
private fun messageText(message: FamilyEvent.Message): String =
    if (message.name != null) stringResource(message.res, message.name) else stringResource(message.res)

@Composable
private fun PersonRow(person: FamilyPerson, onPage: (FamilyPerson, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(person.name, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            // Spoken as "Page Mom" and "Urgent page to Mom": the name makes each button's purpose clear on its own.
            val page = stringResource(R.string.family_page_to, person.name)
            val urgent = stringResource(R.string.family_urgent_to, person.name)
            Button(
                onClick = { onPage(person, EnvelopePayload.LEVEL_NORMAL) },
                modifier = Modifier.weight(1f).semantics { contentDescription = page }
            ) { Text(stringResource(R.string.family_page)) }
            OutlinedButton(
                onClick = { onPage(person, EnvelopePayload.LEVEL_URGENT) },
                modifier = Modifier.weight(1f).semantics { contentDescription = urgent }
            ) { Text(stringResource(R.string.family_urgent)) }
        }
    }
}
