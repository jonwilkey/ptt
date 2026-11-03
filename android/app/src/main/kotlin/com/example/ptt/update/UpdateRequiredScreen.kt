package com.example.ptt.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.ptt.R

/** The blocking screen shown in place of the home screen when the app is older than the server's minimum. */
@Composable
fun UpdateRequiredScreen(status: UpdateOutcome?, onUpdate: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, androidx.compose.ui.Alignment.CenterVertically)
    ) {
        Text(stringResource(R.string.update_required_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.update_required_text))
        statusText(status)?.let { Text(stringResource(it)) }
        Button(onClick = onUpdate, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.update_button)) }
    }
}

private fun statusText(status: UpdateOutcome?): Int? = when (status) {
    UpdateOutcome.Installing -> R.string.update_status_installing
    UpdateOutcome.Deferred -> R.string.update_status_deferred
    UpdateOutcome.UpToDate, UpdateOutcome.NothingPublished -> R.string.update_status_latest
    is UpdateOutcome.Failed -> R.string.update_status_failed
    is UpdateOutcome.Rejected -> R.string.update_status_rejected
    UpdateOutcome.Running -> R.string.update_checking
    UpdateOutcome.Throttled, null -> null
}
