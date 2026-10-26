package com.example.ptt.setup

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
fun SetupApp(viewModel: SetupViewModel, onSecureScreen: (Boolean) -> Unit = {}) {
    val route by viewModel.route.collectAsState()
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (val r = route) {
                null -> Centered { Text(stringResource(R.string.loading)) }
                is Route.Enroll -> EnrollmentScreen(viewModel, r.explain)
                Route.Waiting -> WaitingScreen(viewModel)
                Route.Home -> HomeHost(viewModel, onSecureScreen)
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
    var confirmStartOver by remember { mutableStateOf(false) }
    Centered {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.waiting_title), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = { viewModel.syncNow() }) { Text(stringResource(R.string.waiting_retry)) }
            TextButton(onClick = { confirmStartOver = true }) { Text(stringResource(R.string.waiting_start_over)) }
        }
    }
    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = { Text(stringResource(R.string.waiting_start_over_title)) },
            text = { Text(stringResource(R.string.waiting_start_over_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStartOver = false
                    viewModel.startOver()
                }) { Text(stringResource(R.string.waiting_start_over_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartOver = false }) { Text(stringResource(R.string.devices_cancel)) }
            }
        )
    }
}

private enum class HomePage { HOME, SETUP, ADD_DEVICE, DEVICES }

/**
 * Home and the screens reachable from it. The current page is saved across recreation (it is only an
 * enum, nothing secret), so a rotation keeps the Add-device screen and its still-pending enrollment. The admin
 * pages need this phone to be an admin in the verified roster, and they are never shown over the lock
 * screen (the activity does not opt in to that).
 */
@Composable
private fun HomeHost(viewModel: SetupViewModel, onSecureScreen: (Boolean) -> Unit) {
    val info by remember { viewModel.home }.collectAsState(initial = null)
    var page by rememberSaveable { mutableStateOf(HomePage.HOME) }
    val isAdmin = info?.isAdmin == true
    val adminPage = page == HomePage.ADD_DEVICE || page == HomePage.DEVICES
    // Admin status can be lost while an admin page is open (a synced roster); drop back to Home. Until the
    // home info has loaded (right after a recreation) the saved page is left alone.
    LaunchedEffect(isAdmin, adminPage, info) {
        if (info != null && !isAdmin && adminPage) page = HomePage.HOME
    }
    BackHandler(enabled = page != HomePage.HOME) { page = HomePage.HOME }
    when (page) {
        HomePage.HOME -> HomeScreen(viewModel, info) { page = it }

        HomePage.SETUP -> ChecklistScreen(viewModel) { page = HomePage.HOME }

        HomePage.ADD_DEVICE ->
            if (isAdmin) AddDeviceScreen(onSecureScreen) { page = HomePage.HOME }

        HomePage.DEVICES ->
            if (isAdmin) DevicesScreen { page = HomePage.HOME }
    }
}

@Composable
private fun HomeScreen(viewModel: SetupViewModel, info: HomeInfo?, open: (HomePage) -> Unit) {
    val checklist by viewModel.checklistState.collectAsState()
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        info?.let {
            Text(it.profile.displayName, style = MaterialTheme.typography.titleLarge)
            Text(it.profile.deviceName)
            if (it.isAdmin) Text(stringResource(R.string.home_admin_badge))
            it.serverUrl?.let { url -> Text(stringResource(R.string.home_server, url)) }
            it.rosterVersion?.let { v -> Text(stringResource(R.string.home_roster_version, v)) }
        }
        // Material's Badge is the error colour (red); the button text also says it, so colour is not the only signal.
        BadgedBox(badge = { if (checklist.hasFailures) Badge { Text("!") } }) {
            Button(onClick = { open(HomePage.SETUP) }) {
                Text(stringResource(if (checklist.hasFailures) R.string.home_setup_attention else R.string.home_setup))
            }
        }
        if (info?.isAdmin == true) {
            Button(onClick = { open(HomePage.ADD_DEVICE) }) { Text(stringResource(R.string.home_add_device)) }
            Button(onClick = { open(HomePage.DEVICES) }) { Text(stringResource(R.string.home_devices)) }
        }
    }
}
