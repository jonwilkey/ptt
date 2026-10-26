package com.example.ptt.setup

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.ptt.R

private const val GREEN_ARGB = 0xFF1B7F3BL
private const val RED_ARGB = 0xFFB3261EL
private val Green = Color(GREEN_ARGB)
private val Red = Color(RED_ARGB)

@Composable
fun ChecklistScreen(viewModel: SetupViewModel, onBack: () -> Unit) {
    // Recheck whenever the user comes back from a Settings page.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshChecklist() }
    val state by viewModel.checklistState.collectAsState()
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.setup_intro))
        state.rows.forEach { row -> ChecklistRowView(row) { open(context, row.item) } }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}

@Composable
private fun ChecklistRowView(row: ChecklistRow, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(row.item.title), style = MaterialTheme.typography.titleMedium)
        // The status is always spelled out: colour is never the only signal.
        val (label, color) = when (row.status) {
            RowStatus.PASS -> R.string.status_ok to Green
            RowStatus.FAIL -> R.string.status_fail to Red
            RowStatus.UNKNOWN -> R.string.status_unknown to MaterialTheme.colorScheme.onSurfaceVariant
            RowStatus.INFO -> R.string.status_info to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Text(stringResource(label), color = color, style = MaterialTheme.typography.labelLarge)
        Text(stringResource(row.item.why))
        row.oem.forEach { Text(stringResource(it.title) + ": " + stringResource(it.instructions)) }
        if (row.item != ChecklistItem.FOREGROUND_SERVICES && row.status != RowStatus.PASS) {
            OutlinedButton(onClick = onOpen) { Text(stringResource(R.string.open_settings)) }
        }
    }
}

private fun open(context: Context, item: ChecklistItem) {
    try {
        context.startActivity(ChecklistLinks.intentFor(item, context.packageName))
    } catch (_: ActivityNotFoundException) {
        // Some phones lack the specific page: the app's own settings page always exists.
        try {
            context.startActivity(ChecklistLinks.appDetails(context.packageName))
        } catch (_: ActivityNotFoundException) {
            // Nothing more to try.
        }
    }
}
