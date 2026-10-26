package com.example.ptt.setup

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.ptt.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val POLL_INTERVAL_MS = 5_000L

/** Top-level screen for the current [route]. */
@Composable
fun SetupApp(viewModel: SetupViewModel) {
    val route by viewModel.route.collectAsState()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val r = route) {
                null -> Centered { Text(stringResource(R.string.loading)) }
                is Route.Enroll -> EnrollmentScreen(viewModel, r.explain)
                Route.Waiting -> WaitingScreen(viewModel)
                Route.Home -> HomeScreen(viewModel)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun EnrollmentScreen(viewModel: SetupViewModel, explain: Boolean) {
    val state by viewModel.enrollState.collectAsState()
    var deviceName by rememberSaveable { mutableStateOf(Build.MODEL.orEmpty().take(MAX_DEFAULT_NAME)) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.enroll_title), style = MaterialTheme.typography.headlineMedium)
        if (explain) Text(stringResource(R.string.enroll_explain_removed))
        Text(stringResource(R.string.enroll_intro))
        OutlinedTextField(
            value = deviceName,
            onValueChange = { deviceName = it },
            label = { Text(stringResource(R.string.device_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = !state.busy,
            onClick = {
                scope.launch {
                    when (val scan = QrScanner.scan(context)) {
                        is ScanResult.Text -> viewModel.enroll(scan.value, deviceName)
                        ScanResult.Failed -> viewModel.scanFailed()
                        ScanResult.Cancelled -> Unit
                    }
                }
            }
        ) { Text(stringResource(R.string.scan_qr_button)) }
        PasteQrField(enabled = !state.busy) { viewModel.enroll(it, deviceName) }
        if (state.busy) Text(stringResource(R.string.enrolling))
        state.error?.let { Text(stringResource(messageFor(it)), color = MaterialTheme.colorScheme.error) }
        if (state.scanFailed) Text(stringResource(R.string.error_scan), color = MaterialTheme.colorScheme.error)
    }
}

private const val MAX_DEFAULT_NAME = 64

private fun messageFor(error: EnrollError): Int = when (error) {
    EnrollError.INVALID_QR -> R.string.error_invalid_qr
    EnrollError.INVALID_NAME -> R.string.error_invalid_name
    EnrollError.CODE_INVALID -> R.string.error_code_invalid
    EnrollError.RATE_LIMITED -> R.string.error_rate_limited
    EnrollError.NETWORK -> R.string.error_network
    EnrollError.NOT_ADMIN -> R.string.error_not_admin
    EnrollError.KEYS_UNAVAILABLE -> R.string.error_keys
    EnrollError.OTHER -> R.string.error_other
}

@Composable
private fun WaitingScreen(viewModel: SetupViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Poll every 5 s, but only while the screen is visible.
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(POLL_INTERVAL_MS)
                viewModel.syncNow()
            }
        }
    }
    Centered {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.waiting_title), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { viewModel.syncNow() }) { Text(stringResource(R.string.waiting_retry)) }
        }
    }
}

@Composable
private fun HomeScreen(viewModel: SetupViewModel) {
    val info by remember { viewModel.home }.collectAsState(initial = null)
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        info?.let {
            Text(it.profile.displayName, style = MaterialTheme.typography.titleLarge)
            Text(it.profile.deviceName)
            if (it.profile.admin) Text(stringResource(R.string.home_admin_badge))
            it.serverUrl?.let { url -> Text(stringResource(R.string.home_server, url)) }
            it.rosterVersion?.let { v -> Text(stringResource(R.string.home_roster_version, v)) }
        }
    }
}
