package com.example.ptt.update

import android.util.Log
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.ApiResult
import com.example.ptt.net.AppLatestResponse
import com.example.ptt.net.ErrorCode
import com.example.ptt.net.PttApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Clock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** How one run of the update check ended. */
sealed interface UpdateOutcome {
    /** The server has no APK (or no such endpoint): nothing is offered and nothing is required. */
    data object NothingPublished : UpdateOutcome

    data object UpToDate : UpdateOutcome

    /** A page is alerting or a talk session is active: the install waits for the next run. */
    data object Deferred : UpdateOutcome

    /** The install was handed to the system (it may still need the user's confirmation). */
    data object Installing : UpdateOutcome

    data class Rejected(val reason: Rejection) : UpdateOutcome

    /** Network or install failure; retried on the next run. */
    data class Failed(val kind: String) : UpdateOutcome

    /** The app-start check ran less than [UpdateChecker.START_INTERVAL_MS] ago. */
    data object Throttled : UpdateOutcome

    /** Another run is under way. */
    data object Running : UpdateOutcome
}

/** True while installing now would cut off a page or a conversation. */
fun interface InstallGate {
    suspend fun busy(): Boolean
}

/** Hands a verified APK to the system installer. */
fun interface ApkInstaller {
    /**
     * Returns true once the session is committed (the system's answer arrives at [UpdateInstallReceiver]). The
     * session is abandoned and false returned when [stillClear] says, right before the commit, that it is no
     * longer safe to install.
     */
    fun install(file: File, stillClear: () -> Boolean): Boolean
}

/**
 * The update check of docs/protocol.md section 14.4. Every outside effect is behind a parameter: the server
 * ([api]), the package manager ([packages]), the installer, the busy test and the clock.
 */
@Suppress("LongParameterList")
class UpdateChecker(
    private val api: PttApi,
    private val settings: SettingsRepository,
    private val packages: PackageFacts,
    private val installer: ApkInstaller,
    private val gate: InstallGate,
    private val clock: Clock,
    private val workDir: File,
    private val io: CoroutineContext
) {
    private val running = Mutex()
    private val mutableStatus = MutableStateFlow<UpdateOutcome?>(null)

    /** The outcome of the latest run, for the "Update required" screen; null before the first one. */
    val status: StateFlow<UpdateOutcome?> = mutableStatus.asStateFlow()

    /** The app-start run: skipped when the server was reached less than six hours ago. */
    suspend fun checkOnAppStart(): UpdateOutcome {
        val last = settings.lastUpdateCheck()
        if (last != null && clock.millis() - last in 0 until START_INTERVAL_MS) return UpdateOutcome.Throttled
        return check()
    }

    /** The daily job's run and the Update button's run: no throttle. */
    suspend fun check(): UpdateOutcome {
        if (!running.tryLock()) return UpdateOutcome.Running
        try {
            val outcome = runCheck()
            mutableStatus.value = outcome
            if (outcome is UpdateOutcome.Rejected || outcome is UpdateOutcome.Failed) {
                Log.w(TAG, "update check: ${outcome.javaClass.simpleName} ${detail(outcome)}")
            }
            return outcome
        } finally {
            running.unlock()
        }
    }

    private fun detail(outcome: UpdateOutcome) = when (outcome) {
        is UpdateOutcome.Rejected -> outcome.reason.name
        is UpdateOutcome.Failed -> outcome.kind
        else -> ""
    }

    @Suppress("ReturnCount")
    private suspend fun runCheck(): UpdateOutcome {
        withContext(io) { workDir.listFiles()?.forEach { it.delete() } }
        val release = when (val result = api.getAppLatest()) {
            is ApiResult.Success -> result.body
            is ApiResult.Final -> return if (result.status == HTTP_NOT_FOUND) nothingPublished() else failed("http")
            is ApiResult.Retryable -> return failed("network")
            is ApiResult.NotEnrolled -> return failed("not_enrolled")
        }
        settings.setLastUpdateCheck(clock.millis())
        val installed = packages.installed()
        // A minimum above the offered version cannot be met by updating, so it is malformed, not a requirement.
        val min = if (release.minSupportedVersionCode in 0..release.versionCode) release.minSupportedVersionCode else 0L
        settings.setMinSupportedCode(min)
        return when {
            release.versionCode <= installed.versionCode -> UpdateOutcome.UpToDate
            !ApkVerifier.metadataOk(release) -> UpdateOutcome.Rejected(Rejection.METADATA)
            gate.busy() -> UpdateOutcome.Deferred
            else -> downloadAndInstall(release, installed)
        }
    }

    private suspend fun nothingPublished(): UpdateOutcome {
        settings.setLastUpdateCheck(clock.millis())
        settings.setMinSupportedCode(0L)
        return UpdateOutcome.NothingPublished
    }

    private fun failed(kind: String) = UpdateOutcome.Failed(kind)

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun downloadAndInstall(release: AppLatestResponse, installed: ApkFacts): UpdateOutcome {
        val file = File(workDir, APK_NAME)
        try {
            workDir.mkdirs()
            file.delete()
            val download = when (val result = download(release, file)) {
                is Downloaded.Done -> result.download
                is Downloaded.Refused -> return result.outcome
            }
            val archive = packages.archive(file)
            val rejection = ApkVerifier.verify(release, installed, download, archive)
            if (rejection != null) return UpdateOutcome.Rejected(rejection)
            if (gate.busy()) return UpdateOutcome.Deferred
            val committed = withContext(io) { installer.install(file) { runBlocking { !gate.busy() } } }
            return if (committed) UpdateOutcome.Installing else UpdateOutcome.Deferred
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return failed(e.javaClass.simpleName)
        } catch (e: RuntimeException) {
            return failed(e.javaClass.simpleName)
        } finally {
            file.delete()
        }
    }

    private sealed interface Downloaded {
        data class Done(val download: Download) : Downloaded

        data class Refused(val outcome: UpdateOutcome) : Downloaded
    }

    private suspend fun download(release: AppLatestResponse, file: File): Downloaded {
        val digest = MessageDigest.getInstance("SHA-256")
        val result = withContext(io) {
            DigestOutputStream(FileOutputStream(file), digest).use {
                api.downloadApk(release.downloadPath, it, release.size)
            }
        }
        return when (result) {
            is ApiResult.Success -> Downloaded.Done(Download(file.length(), CertDigests.hex(digest.digest())))

            is ApiResult.Final ->
                Downloaded.Refused(
                    if (result.code ==
                        ErrorCode.TOO_LARGE
                    ) {
                        UpdateOutcome.Rejected(Rejection.SIZE)
                    } else {
                        failed("http")
                    }
                )

            is ApiResult.Retryable -> Downloaded.Refused(failed("network"))

            is ApiResult.NotEnrolled -> Downloaded.Refused(failed("not_enrolled"))
        }
    }

    companion object {
        const val START_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val TAG = "UpdateChecker"
        private const val APK_NAME = "update.apk"
        private const val HTTP_NOT_FOUND = 404
    }
}
