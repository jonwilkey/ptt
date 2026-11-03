package com.example.ptt.update

import com.example.ptt.net.AppLatestResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkVerifierTest {
    private val hash = "a".repeat(64)
    private val release = AppLatestResponse("0.5.0", 5000, hash, 1000, 4000, "/v1/app/apk/0.5.0")
    private val installed = ApkFacts("com.example.ptt", 4000, listOf("c1"))
    private val download = Download(1000, hash)
    private val good = ApkFacts("com.example.ptt", 5000, listOf("c1"))

    private fun verify(
        download: Download = this.download,
        archive: ApkFacts? = good,
        installed: ApkFacts = this.installed
    ) = ApkVerifier.verify(release, installed, download, archive)

    @Test(timeout = 60_000)
    fun acceptsTheGoodCase() = assertNull(verify())

    @Test(timeout = 60_000)
    fun rejectsAWrongHash() = assertEquals(Rejection.HASH, verify(download = Download(1000, "b".repeat(64))))

    @Test(timeout = 60_000)
    fun rejectsAWrongSize() = assertEquals(Rejection.SIZE, verify(download = Download(999, hash)))

    @Test(timeout = 60_000)
    fun rejectsAnUnreadableFile() = assertEquals(Rejection.UNREADABLE, verify(archive = null))

    @Test(timeout = 60_000)
    fun rejectsAWrongPackage() =
        assertEquals(Rejection.PACKAGE, verify(archive = good.copy(packageName = "com.example.other")))

    @Test(timeout = 60_000)
    fun rejectsAVersionCodeThatIsNotTheOffered() =
        assertEquals(Rejection.VERSION, verify(archive = good.copy(versionCode = 5001)))

    @Test(timeout = 60_000)
    fun rejectsAVersionCodeThatIsNotHigherThanInstalled() {
        assertEquals(Rejection.VERSION, verify(installed = installed.copy(versionCode = 5000)))
        assertEquals(Rejection.VERSION, verify(installed = installed.copy(versionCode = 6000)))
    }

    @Test(timeout = 60_000)
    fun rejectsADifferentSigner() = assertEquals(Rejection.SIGNER, verify(archive = good.copy(signers = listOf("c2"))))

    @Test(timeout = 60_000)
    fun rejectsAnExtraSigner() =
        assertEquals(Rejection.SIGNER, verify(archive = good.copy(signers = listOf("c1", "c2"))))

    @Test(timeout = 60_000)
    fun rejectsAMissingSigner() {
        assertEquals(Rejection.SIGNER, verify(archive = good.copy(signers = emptyList())))
        assertEquals(
            Rejection.SIGNER,
            verify(archive = good.copy(signers = emptyList()), installed = installed.copy(signers = emptyList()))
        )
    }

    @Test(timeout = 60_000)
    fun signerOrderDoesNotMatter() {
        val two = listOf("c1", "c2")
        assertNull(verify(archive = good.copy(signers = two.reversed()), installed = installed.copy(signers = two)))
    }

    @Test(timeout = 60_000)
    fun checksTheOfferBeforeDownloading() {
        assertTrue(ApkVerifier.metadataOk(release))
        assertFalse(ApkVerifier.metadataOk(release.copy(sha256 = "A".repeat(64))))
        assertFalse(ApkVerifier.metadataOk(release.copy(sha256 = "a".repeat(63))))
        assertFalse(ApkVerifier.metadataOk(release.copy(size = 0)))
        assertFalse(ApkVerifier.metadataOk(release.copy(downloadPath = "/v1/app/apk/../x")))
        assertFalse(ApkVerifier.metadataOk(release.copy(downloadPath = "https://evil.example.com/a.apk")))
    }
}
