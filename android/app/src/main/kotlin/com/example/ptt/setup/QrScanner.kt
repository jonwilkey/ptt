package com.example.ptt.setup

import android.content.Context
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Result of one scan. [Text] is the raw QR text: never log or display it. */
sealed interface ScanResult {
    class Text(val value: String) : ScanResult {
        override fun toString() = "Text(<redacted>)"
    }

    /** The user closed the scanner. */
    data object Cancelled : ScanResult

    /** The scanner is unavailable (for example Play services is missing or still installing it). */
    data object Failed : ScanResult
}

/** The Google code scanner (QR only; it runs in Play services, so the app needs no camera permission). */
object QrScanner {
    suspend fun scan(context: Context): ScanResult = suspendCancellableCoroutine { cont ->
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue
                if (cont.isActive) cont.resume(if (raw == null) ScanResult.Failed else ScanResult.Text(raw))
            }
            .addOnCanceledListener { if (cont.isActive) cont.resume(ScanResult.Cancelled) }
            .addOnFailureListener { if (cont.isActive) cont.resume(ScanResult.Failed) }
    }
}
