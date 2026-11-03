package com.example.ptt.update

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.ptt.data.SettingsRepository
import com.example.ptt.net.ApiResult
import com.example.ptt.net.AppLatestResponse
import com.example.ptt.net.PttApi
import com.example.ptt.outbox.FakePttApi
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val apk = ByteArray(2048) { (it % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(apk).joinToString("") { "%02x".format(it) }
    private val installed = ApkFacts("com.example.ptt", 4000, listOf("c1"))
    private val archive = ApkFacts("com.example.ptt", 5000, listOf("c1"))
    private var latest: ApiResult<AppLatestResponse> = ApiResult.Success(release())
    private var archiveFacts: ApkFacts? = archive
    private var busy = false
    private var now = 1_000_000_000L
    private var downloads = 0
    private var installs = 0
    private var installedFileExistedDuringInstall = false
    private lateinit var workDir: File
    private lateinit var settings: SettingsRepository

    private fun release(code: Long = 5000, min: Long = 0, size: Long = apk.size.toLong(), sha: String = hash) =
        AppLatestResponse("0.5.0", code, sha, size, min, "/v1/app/apk/0.5.0")

    private val api = object : PttApi by FakePttApi() {
        override suspend fun getAppLatest() = latest

        override suspend fun downloadApk(downloadPath: String, sink: OutputStream, maxBytes: Long): ApiResult<Long> {
            downloads++
            sink.write(apk)
            return ApiResult.Success(apk.size.toLong())
        }
    }

    private val packages = object : PackageFacts {
        override fun installed() = installed

        override fun archive(file: File) = archiveFacts
    }

    private fun checker(): UpdateChecker {
        settings =
            SettingsRepository(
                PreferenceDataStoreFactory.create {
                    tmp.newFile("s${System.nanoTime()}.preferences_pb")
                }
            )
        workDir = File(tmp.root, "update")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = Instant.ofEpochMilli(now)
        }
        val installer = ApkInstaller {
            installs++
            installedFileExistedDuringInstall = it.exists() && it.length() == apk.size.toLong()
        }
        return UpdateChecker(api, settings, packages, installer, { busy }, clock, workDir, Dispatchers.Unconfined)
    }

    private fun leftovers() = workDir.listFiles().orEmpty().toList()

    @Test(timeout = 60_000)
    fun installsAGoodHigherVersionAndDeletesTheFile() = runBlocking {
        val c = checker()
        assertEquals(UpdateOutcome.Installing, c.check())
        assertEquals(1, installs)
        assertTrue(installedFileExistedDuringInstall)
        assertTrue(leftovers().isEmpty())
    }

    @Test(timeout = 60_000)
    fun aNotFoundMeansNothingPublishedAndNothingRequired() = runBlocking {
        latest = ApiResult.Final(404, "not_found")
        val c = checker()
        settings.setMinSupportedCode(4500)
        assertEquals(UpdateOutcome.NothingPublished, c.check())
        assertEquals(0, downloads)
        assertEquals(0L, settings.minSupportedCode.first())
    }

    @Test(timeout = 60_000)
    fun aVersionThatIsNotHigherIsNotDownloaded() = runBlocking {
        latest = ApiResult.Success(release(code = 4000))
        assertEquals(UpdateOutcome.UpToDate, checker().check())
        assertEquals(0, downloads)
    }

    @Test(timeout = 60_000)
    fun aLowerVersionIsNotDownloaded() = runBlocking {
        latest = ApiResult.Success(release(code = 3000))
        assertEquals(UpdateOutcome.UpToDate, checker().check())
        assertEquals(0, downloads)
    }

    @Test(timeout = 60_000)
    fun defersWhileAlertingOrTalkingAndDownloadsNothing() = runBlocking {
        busy = true
        val c = checker()
        assertEquals(UpdateOutcome.Deferred, c.check())
        assertEquals(0, downloads)
        assertEquals(0, installs)
        busy = false
        assertEquals(UpdateOutcome.Installing, c.check())
    }

    @Test(timeout = 60_000)
    fun aSessionStartingDuringTheDownloadStillDefersTheInstall() = runBlocking {
        val c = checker()
        val flip = object : PttApi by api {
            override suspend fun downloadApk(
                downloadPath: String,
                sink: OutputStream,
                maxBytes: Long
            ): ApiResult<Long> {
                busy = true
                return api.downloadApk(downloadPath, sink, maxBytes)
            }
        }
        val deferring = UpdateChecker(
            flip,
            settings,
            packages,
            ApkInstaller {
                installs++
            },
            { busy },
            Clock.systemUTC(),
            workDir,
            Dispatchers.Unconfined
        )
        assertEquals(UpdateOutcome.Deferred, deferring.check())
        assertEquals(0, installs)
        assertTrue(leftovers().isEmpty())
        assertNull(c.status.value)
    }

    @Test(timeout = 60_000)
    fun aRejectedFileIsDeletedAndNeverInstalled() = runBlocking {
        archiveFacts = archive.copy(signers = listOf("c2"))
        val c = checker()
        assertEquals(UpdateOutcome.Rejected(Rejection.SIGNER), c.check())
        assertEquals(0, installs)
        assertTrue(leftovers().isEmpty())
    }

    @Test(timeout = 60_000)
    fun aWrongHashIsRejected() = runBlocking {
        latest = ApiResult.Success(release(sha = "0".repeat(64)))
        assertEquals(UpdateOutcome.Rejected(Rejection.HASH), checker().check())
        assertEquals(0, installs)
        assertTrue(leftovers().isEmpty())
    }

    @Test(timeout = 60_000)
    fun aNetworkFailureIsRetriedLaterAndNothingIsInstalled() = runBlocking {
        latest = ApiResult.Retryable(null)
        assertEquals(UpdateOutcome.Failed("network"), checker().check())
        assertEquals(0, downloads)
    }

    @Test(timeout = 60_000)
    fun theAppStartRunIsThrottledToSixHours() = runBlocking {
        latest = ApiResult.Success(release(code = 4000))
        val c = checker()
        assertEquals(UpdateOutcome.UpToDate, c.checkOnAppStart())
        now += UpdateChecker.START_INTERVAL_MS - 1
        assertEquals(UpdateOutcome.Throttled, c.checkOnAppStart())
        now += 1
        assertEquals(UpdateOutcome.UpToDate, c.checkOnAppStart())
    }

    @Test(timeout = 60_000)
    fun theButtonAndTheDailyRunIgnoreTheThrottle() = runBlocking {
        latest = ApiResult.Success(release(code = 4000))
        val c = checker()
        c.checkOnAppStart()
        assertEquals(UpdateOutcome.UpToDate, c.check())
    }

    @Test(timeout = 60_000)
    fun theMinimumIsRememberedEvenWhenNoDownloadHappens() = runBlocking {
        latest = ApiResult.Success(release(code = 4000, min = 4000))
        val c = checker()
        c.check()
        assertEquals(4000L, settings.minSupportedCode.first())
    }

    @Test(timeout = 60_000)
    fun aMinimumAboveTheOfferedVersionIsIgnored() = runBlocking {
        latest = ApiResult.Success(release(code = 5000, min = 6000))
        val c = checker()
        c.check()
        assertEquals(0L, settings.minSupportedCode.first())
        assertFalse(MinSupported(settings, 4000).isRequired())
    }
}
