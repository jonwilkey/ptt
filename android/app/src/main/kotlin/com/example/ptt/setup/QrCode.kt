package com.example.ptt.setup

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders text as a QR module matrix with zxing core. Nothing here stores or logs the text. */
object QrCode {
    private const val QUIET_ZONE_MODULES = 4

    /**
     * The QR for [text] at its natural size: one matrix cell per module, `true` = dark, including the
     * quiet zone. Draw dark cells on a light background.
     */
    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to QUIET_ZONE_MODULES,
            EncodeHintType.CHARACTER_SET to "ISO-8859-1"
        )
    )
}
