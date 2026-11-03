package com.example.ptt.update

import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowSigningInfo

@RunWith(RobolectricTestRunner::class)
class AndroidPackageFactsTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test(timeout = 60_000)
    fun digestsTheInstalledAppsOwnCertificate() {
        val certBytes = ByteArray(40) { (it * 7).toByte() }
        val signing = SigningInfo::class.java.getDeclaredConstructor().newInstance()
        Shadow.extract<ShadowSigningInfo>(signing).setSignatures(arrayOf(Signature(certBytes)))
        val info = PackageInfo().apply {
            packageName = context.packageName
            longVersionCode = 4000
            signingInfo = signing
        }
        shadowOf(context.packageManager).installPackage(info)

        val facts = AndroidPackageFacts(context).installed()

        val expected = MessageDigest.getInstance("SHA-256").digest(certBytes).joinToString("") { "%02x".format(it) }
        assertEquals(listOf(expected), facts.signers)
        assertEquals(context.packageName, facts.packageName)
        assertEquals(4000L, facts.versionCode)
    }

    @Test(timeout = 60_000)
    fun theDigestIsLowercaseHexOfSha256() {
        val digest = CertDigests.of(arrayOf(Signature(byteArrayOf(1, 2, 3))))
        assertEquals(
            listOf("039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81"),
            digest
        )
    }
}
