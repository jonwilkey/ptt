package com.example.ptt.talk

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.ptt.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * The intent contract of [SendActivity], which the family widget fires: "send this person a page at this level
 * and open the talk screen". It is a separate, non-exported activity (not a mode of [TalkActivity]) because
 * TalkActivity shows over the lock screen and a send must never start there ("Lock screen": no way to start a new
 * page without unlocking); this one does not, and it leaves once the page is sent.
 */
object SendIntents {
    const val EXTRA_USER_ID = "com.example.ptt.extra.SEND_USER_ID"
    const val EXTRA_LEVEL = "com.example.ptt.extra.SEND_LEVEL"

    /** Display only (the confirmation and the talk screen); absent if the widget could not name the person. */
    const val EXTRA_NAME = "com.example.ptt.extra.SEND_NAME"

    /** `ptt://send/<user>/<level>`: keeps the PendingIntents Glance builds for the widget buttons distinct. */
    fun identity(userId: String, level: String): Uri =
        Uri.Builder().scheme("ptt").authority("send").appendPath(userId).appendPath(level).build()

    fun intent(context: Context, userId: String, level: String, name: String? = null): Intent =
        Intent(context, SendActivity::class.java)
            .setData(identity(userId, level))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(EXTRA_USER_ID, userId)
            .putExtra(EXTRA_LEVEL, level)
            .apply { if (name != null) putExtra(EXTRA_NAME, name) }

    @Suppress("ReturnCount")
    internal fun request(intent: Intent): SendRequest? {
        val userId = intent.getStringExtra(EXTRA_USER_ID)
        val level = intent.getStringExtra(EXTRA_LEVEL)
        return if (userId != null &&
            level != null
        ) {
            SendRequest(userId, level, intent.getStringExtra(EXTRA_NAME))
        } else {
            null
        }
    }
}

/** The keyguard, behind an interface so tests can lock it. */
@VisibleForTesting
interface KeyguardGate {
    fun isLocked(activity: Activity): Boolean

    /** Asks the user to unlock; [onResult] gets whether they did. */
    fun requestDismiss(activity: Activity, onResult: (Boolean) -> Unit)
}

private object SystemKeyguard : KeyguardGate {
    override fun isLocked(activity: Activity) =
        activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: false

    override fun requestDismiss(activity: Activity, onResult: (Boolean) -> Unit) {
        val manager = activity.getSystemService(KeyguardManager::class.java)
        if (manager == null) {
            onResult(false)
            return
        }
        manager.requestDismissKeyguard(
            activity,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() = onResult(true)

                override fun onDismissCancelled() = onResult(false)

                override fun onDismissError() = onResult(false)
            }
        )
    }
}

/**
 * Sends the page an intent from [SendIntents] asks for, then opens the talk screen for it and finishes. It is
 * not exported, not in Recents, and does not show over the lock screen: with the keyguard locked it asks to
 * unlock first and sends nothing unless that succeeds. What it asks or says (End the current page? / why
 * nothing was sent) is in [SendScreen]; the logic is in [SendViewModel].
 */
@AndroidEntryPoint
class SendActivity : ComponentActivity() {
    private val viewModel: SendViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // (Process death while the End question is showing closes the screen here without sending: intended, the
        // user can tap again.)
        // Restored after process death: the request was never sent from here (or was, and is no longer ours to
        // repeat). Either way, sending again on a restore would be a second page.
        if (savedInstanceState != null && !viewModel.started) {
            finishAndRemoveTask()
            return
        }
        // Recreated (rotation): the intent was handled by the first instance.
        if (savedInstanceState == null) handle(intent)
        lifecycleScope.launch { viewModel.events.collect(::onEvent) }
        setContent {
            MaterialTheme {
                val ui by viewModel.ui.collectAsState()
                SendScreen(ui, viewModel::confirmEnd, viewModel::cancel)
            }
        }
    }

    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent) {
        val request = SendIntents.request(intent)
        if (request == null) {
            viewModel.cancel()
            finishAndRemoveTask()
            return
        }
        if (!viewModel.accept(request)) return
        val gate = keyguard
        if (!gate.isLocked(this)) {
            viewModel.run()
        } else {
            val token = viewModel.newUnlock()
            gate.requestDismiss(this) { unlocked -> viewModel.unlocked(token, unlocked) }
        }
    }

    private fun onEvent(event: SendEvent) {
        when (event) {
            // The view model has started the talk screen already.
            is SendEvent.Open, SendEvent.Close -> finishAndRemoveTask()
        }
    }

    companion object {
        @VisibleForTesting
        @Volatile
        var keyguard: KeyguardGate = SystemKeyguard

        @VisibleForTesting
        fun resetKeyguard() {
            keyguard = SystemKeyguard
        }
    }
}

/** What the send entry point shows: nothing but a spinner, the End question, or the reason nothing was sent. */
@Composable
fun SendScreen(ui: SendUi, onConfirm: () -> Unit, onClose: () -> Unit) {
    // The window is translucent (the home screen shows through): the content gets a mostly opaque backdrop.
    // The End question is a dialog with its own surface and needs none.
    Surface(
        Modifier.fillMaxSize(),
        color = if (ui is SendUi.Confirm) Color.Transparent else MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
    ) {
        SendBody(ui, onClose)
    }
    if (ui is SendUi.Confirm) {
        AlertDialog(
            onDismissRequest = onClose,
            title = { Text(stringResource(R.string.family_confirm_title)) },
            text = {
                Text(
                    if (ui.peerName != null) {
                        stringResource(R.string.family_confirm_text, ui.peerName)
                    } else {
                        stringResource(R.string.family_confirm_text_unknown)
                    }
                )
            },
            confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.family_confirm_yes)) } },
            dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.devices_cancel)) } }
        )
    }
}

@Composable
private fun SendBody(ui: SendUi, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (ui) {
            SendUi.Working -> CircularProgressIndicator()

            SendUi.Unlocking -> {
                CircularProgressIndicator()
                TextButton(onClick = onClose) { Text(stringResource(R.string.talk_close)) }
            }

            is SendUi.Confirm -> Unit

            is SendUi.Failed -> {
                Text(
                    if (ui.name != null) stringResource(ui.res, ui.name) else stringResource(ui.res),
                    style = MaterialTheme.typography.titleLarge
                )
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.talk_close))
                }
            }
        }
    }
}
