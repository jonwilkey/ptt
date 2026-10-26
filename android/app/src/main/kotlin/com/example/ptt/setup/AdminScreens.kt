@file:Suppress("TooManyFunctions")

package com.example.ptt.setup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.ptt.R
import com.example.ptt.crypto.EnrollmentRejection
import com.example.ptt.crypto.RosterDevice

private const val QR_SIZE_DP = 288

/** Cells overlap by half a pixel so anti-aliasing leaves no light seams between dark modules. */
private const val CELL_OVERLAP_PX = 0.5f

/**
 * Tells the host window to set `FLAG_SECURE` while this is in the composition (no screenshots, no
 * recents thumbnail). Used by every screen that can show an enrollment QR.
 */
@Composable
internal fun SecureScreenEffect(onSecureScreen: (Boolean) -> Unit) {
    DisposableEffect(Unit) {
        onSecureScreen(true)
        onDispose { onSecureScreen(false) }
    }
}

@Composable
fun AddDeviceScreen(onSecureScreen: (Boolean) -> Unit, onBack: () -> Unit) {
    val viewModel: AddDeviceViewModel = hiltViewModel()
    SecureScreenEffect(onSecureScreen)
    val ui by viewModel.ui.collectAsState()
    AddDeviceEffects(viewModel, keepScreenOn = ui.needsScreenOn())
    val people by viewModel.people.collectAsState(initial = emptyList())
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.add_device_title), style = MaterialTheme.typography.headlineMedium)
        when (val state = ui) {
            AddDeviceUi.Form -> AddDeviceForm(people, onStart = viewModel::start)

            AddDeviceUi.Starting -> Text(stringResource(R.string.loading))

            is AddDeviceUi.ShowingQr -> {
                Text(stringResource(R.string.add_device_scan_hint))
                QrImage(state.qrText)
                Text(stringResource(R.string.add_device_waiting))
            }

            AddDeviceUi.Signing -> Text(stringResource(R.string.add_device_signing))

            is AddDeviceUi.Done -> Text(stringResource(R.string.add_device_done, state.displayName))

            AddDeviceUi.Expired -> Text(
                stringResource(R.string.add_device_expired),
                color = MaterialTheme.colorScheme.error
            )

            is AddDeviceUi.Rejected ->
                Text(stringResource(rejectionMessage(state.reason)), color = MaterialTheme.colorScheme.error)

            is AddDeviceUi.Failed -> {
                Text(stringResource(errorMessage(state.error)), color = MaterialTheme.colorScheme.error)
                if (state.retry) {
                    Button(onClick = viewModel::retrySigning) {
                        Text(stringResource(R.string.add_device_retry))
                    }
                }
            }
        }
        if (ui !is AddDeviceUi.Form && ui !is AddDeviceUi.Starting && ui !is AddDeviceUi.Signing) {
            TextButton(onClick = viewModel::abandon) {
                Text(
                    stringResource(
                        if (ui is AddDeviceUi.ShowingQr) R.string.devices_cancel else R.string.add_device_start_again
                    )
                )
            }
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
}

/** The display must not time out while the code is on screen, being signed, or waiting for a retry. */
private fun AddDeviceUi.needsScreenOn() =
    this is AddDeviceUi.ShowingQr || this is AddDeviceUi.Signing || (this as? AddDeviceUi.Failed)?.retry == true

@Composable
private fun AddDeviceEffects(viewModel: AddDeviceViewModel, keepScreenOn: Boolean) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Poll only while the screen is started; a stop (screen timeout, app switch) just pauses it.
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.pollLoop() }
    }
    // Leaving the page (Back, a route change, losing admin) abandons the enrollment. A rotation also disposes
    // the composition but is not leaving: the enrollment stays, as the single-use code could not be replaced.
    val activity = LocalContext.current.findActivity()
    DisposableEffect(viewModel) {
        onDispose { if (activity?.isChangingConfigurations != true) viewModel.abandon() }
    }
    val view = LocalView.current
    DisposableEffect(view, keepScreenOn) {
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun AddDeviceForm(people: List<RosterDevice>, onStart: (AddTarget) -> Unit) {
    // Plain remember: nothing here is worth keeping across a recreation, and nothing may be saved.
    var existing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var makeAdmin by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth().selectable(selected = !existing, onClick = { existing = false }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = !existing, onClick = null)
        Text(stringResource(R.string.add_device_new_person), modifier = Modifier.padding(start = 8.dp))
    }
    Row(
        Modifier.fillMaxWidth().selectable(selected = existing, onClick = { existing = true }),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = existing, onClick = null)
        Text(stringResource(R.string.add_device_existing), modifier = Modifier.padding(start = 8.dp))
    }
    if (!existing) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.add_device_name_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            Modifier.fillMaxWidth().toggleable(value = makeAdmin, onValueChange = { makeAdmin = it }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(checked = makeAdmin, onCheckedChange = null)
            Text(stringResource(R.string.add_device_make_admin), modifier = Modifier.padding(start = 8.dp))
        }
        Button(enabled = name.isNotBlank(), onClick = { onStart(AddTarget.NewPerson(name, makeAdmin)) }) {
            Text(stringResource(R.string.add_device_show_qr))
        }
    } else {
        Text(stringResource(R.string.add_device_pick_person))
        people.forEach { person ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = picked == person.userId, onClick = {
                    picked =
                        person.userId
                }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = picked == person.userId, onClick = null)
                Text(person.displayName, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Button(enabled = picked != null, onClick = { picked?.let { onStart(AddTarget.ExistingUser(it)) } }) {
            Text(stringResource(R.string.add_device_show_qr))
        }
    }
}

/** Dark modules on a white background, quiet zone included. The text is encoded here and kept nowhere. */
@Composable
private fun QrImage(text: String) {
    val matrix = remember(text) { QrCode.matrix(text) }
    val description = stringResource(R.string.add_device_qr_description)
    Canvas(Modifier.size(QR_SIZE_DP.dp).semantics { contentDescription = description }) {
        drawRect(Color.White, size = size)
        val cell = size.width / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) {
                    drawRect(
                        Color.Black,
                        topLeft = Offset(x * cell, y * cell),
                        size = Size(
                            cell + CELL_OVERLAP_PX,
                            cell + CELL_OVERLAP_PX
                        )
                    )
                }
            }
        }
    }
}

@StringRes
private fun rejectionMessage(reason: EnrollmentRejection): Int = when (reason) {
    EnrollmentRejection.BAD_MAC -> R.string.reject_bad_mac
    EnrollmentRejection.ADMIN_MISMATCH -> R.string.reject_admin
    EnrollmentRejection.NAME_MISMATCH -> R.string.reject_name
    EnrollmentRejection.USER_MISMATCH -> R.string.reject_user
    EnrollmentRejection.USER_ALREADY_EXISTS -> R.string.reject_exists
    EnrollmentRejection.MALFORMED -> R.string.reject_malformed
}

@StringRes
private fun errorMessage(error: AddDeviceError): Int = when (error) {
    AddDeviceError.LOCKED -> R.string.add_device_error_locked
    AddDeviceError.NOT_ADMIN -> R.string.add_device_error_not_admin
    AddDeviceError.INVALID_NAME -> R.string.add_device_error_name
    AddDeviceError.UNKNOWN_USER -> R.string.add_device_error_unknown_user
    AddDeviceError.NETWORK -> R.string.add_device_error_network
    AddDeviceError.REJECTED_BY_SERVER -> R.string.add_device_error_server
    AddDeviceError.NOT_ENROLLED -> R.string.add_device_error_not_enrolled
    AddDeviceError.INVALID_ROSTER -> R.string.add_device_error_roster
    AddDeviceError.STALE -> R.string.add_device_error_stale
    AddDeviceError.KEYS_UNAVAILABLE -> R.string.add_device_error_keys
}

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    val viewModel: DevicesViewModel = hiltViewModel()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Retry an outstanding revoke only while the screen is visible.
    LaunchedEffect(lifecycle, viewModel) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.retryLoop() }
    }
    val view by remember { viewModel.view }.collectAsState(initial = null)
    val pending by remember { viewModel.pending }.collectAsState(initial = emptySet())
    val ui by viewModel.ui.collectAsState()
    var confirm by remember { mutableStateOf<RosterDevice?>(null) }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.devices_title), style = MaterialTheme.typography.headlineMedium)
        if (pending.isNotEmpty()) {
            Text(stringResource(R.string.devices_pending), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = viewModel::retryNow) { Text(stringResource(R.string.devices_pending_retry)) }
        }
        ui.message?.let { message ->
            val error = message != DevicesMessage.REMOVED
            Text(
                stringResource(devicesMessage(message)),
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(view?.roster?.devices.orEmpty(), key = { it.id }) { device ->
                DeviceRow(device, isSelf = device.id == view?.selfId, busy = ui.busy) { confirm = device }
            }
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
    confirm?.let { device ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.devices_remove_title)) },
            text = { Text(stringResource(R.string.devices_remove_text, device.deviceName, device.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    viewModel.remove(device.id)
                }) { Text(stringResource(R.string.devices_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.devices_cancel)) }
            }
        )
    }
}

@Composable
private fun DeviceRow(device: RosterDevice, isSelf: Boolean, busy: Boolean, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device.displayName, style = MaterialTheme.typography.titleMedium)
            Text(device.deviceName)
            if (device.admin) Text(stringResource(R.string.devices_admin))
            if (isSelf) Text(stringResource(R.string.devices_this_phone))
        }
        if (!isSelf) {
            TextButton(enabled = !busy, onClick = onRemove) { Text(stringResource(R.string.devices_remove)) }
        }
    }
}

@StringRes
private fun devicesMessage(message: DevicesMessage): Int = when (message) {
    DevicesMessage.REMOVED -> R.string.devices_removed
    DevicesMessage.LOCKED -> R.string.devices_error_locked
    DevicesMessage.SELF -> R.string.devices_error_self
    DevicesMessage.LAST_ADMIN -> R.string.devices_error_last_admin
    DevicesMessage.UNKNOWN -> R.string.devices_error_unknown
    DevicesMessage.NOT_ADMIN -> R.string.devices_error_not_admin
    DevicesMessage.FAILED -> R.string.devices_error_failed
    DevicesMessage.SERVER_REFUSED -> R.string.devices_error_server_refused
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
