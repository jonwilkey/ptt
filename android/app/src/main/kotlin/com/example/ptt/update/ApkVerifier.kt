package com.example.ptt.update

import com.example.ptt.net.AppLatestResponse

/** Why a downloaded APK was refused; logged by name only. */
enum class Rejection { METADATA, SIZE, HASH, UNREADABLE, PACKAGE, VERSION, SIGNER }

/** What was measured while the APK was downloaded. */
data class Download(val size: Long, val sha256: String)

/** The checks of docs/protocol.md section 14.4 steps 4 and 5, with no I/O. */
object ApkVerifier {
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val DOWNLOAD_PATH = Regex("^/v1/app/apk/(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})$")

    /** Whether the offer is well formed enough to download. */
    fun metadataOk(release: AppLatestResponse): Boolean = release.size > 0 &&
        release.versionCode > 0 &&
        HEX64.matches(release.sha256) &&
        DOWNLOAD_PATH.matches(release.downloadPath)

    /** The first check [download] and [archive] fail, or null when the APK may be installed. */
    @Suppress("ReturnCount")
    fun verify(release: AppLatestResponse, installed: ApkFacts, download: Download, archive: ApkFacts?): Rejection? {
        if (download.size != release.size) return Rejection.SIZE
        if (download.sha256 != release.sha256) return Rejection.HASH
        if (archive == null) return Rejection.UNREADABLE
        if (archive.packageName != installed.packageName) return Rejection.PACKAGE
        if (archive.versionCode != release.versionCode || archive.versionCode <= installed.versionCode) {
            return Rejection.VERSION
        }
        if (installed.signers.isEmpty() ||
            archive.signers.sorted() != installed.signers.sorted()
        ) {
            return Rejection.SIGNER
        }
        return null
    }
}
