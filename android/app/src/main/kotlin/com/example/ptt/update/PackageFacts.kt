package com.example.ptt.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/** What the update check compares about an APK: its package, version code and signing certificates. */
data class ApkFacts(val packageName: String, val versionCode: Long, val signers: List<String>)

/** Where the facts come from: the installed app and an APK file. A seam so the checker is tested without APKs. */
interface PackageFacts {
    fun installed(): ApkFacts

    /** Null when [file] is not a parseable APK. */
    fun archive(file: File): ApkFacts?
}

/** SHA-256 digests of encoded signing certificates, as lowercase hex. */
object CertDigests {
    fun of(signatures: Array<Signature>?): List<String> =
        signatures.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.sorted()

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}

class AndroidPackageFacts(private val context: Context) : PackageFacts {
    private val packages get() = context.packageManager

    @Suppress("DEPRECATION")
    override fun installed(): ApkFacts {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
        } else {
            packages.getPackageInfo(context.packageName, FLAGS)
        }
        return facts(info)
    }

    @Suppress("DEPRECATION")
    override fun archive(file: File): ApkFacts? {
        val path = file.path
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packages.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(FLAGS.toLong()))
        } else {
            packages.getPackageArchiveInfo(path, FLAGS)
        }
        return info?.let(::facts)
    }

    private fun facts(info: PackageInfo) = ApkFacts(
        info.packageName,
        info.longVersionCode,
        CertDigests.of(info.signingInfo?.apkContentsSigners)
    )

    private companion object {
        const val FLAGS = PackageManager.GET_SIGNING_CERTIFICATES
    }
}
