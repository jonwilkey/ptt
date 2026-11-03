package com.example.ptt.update

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Drives the "Update required" screen. The check runs on the application scope, so it survives leaving it. */
@HiltViewModel
class UpdateViewModel @Inject constructor(
    minSupported: MinSupported,
    private val checker: UpdateChecker,
    private val scope: CoroutineScope
) : ViewModel() {
    /** Whether this app is older than the server's minimum. */
    val required: Flow<Boolean> = minSupported.required

    val status: StateFlow<UpdateOutcome?> = checker.status

    /** The Update button: the check at once, and the install confirmation opened directly when one is needed. */
    fun updateNow() {
        InstallPrompt.expectDirect()
        scope.launch { checker.check() }
    }
}
