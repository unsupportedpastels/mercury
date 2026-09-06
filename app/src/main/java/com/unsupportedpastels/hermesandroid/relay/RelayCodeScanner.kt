package com.unsupportedpastels.hermesandroid.relay

import android.app.Activity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

class RelayCodeScanner(
    private val activity: Activity,
) {
    private val scanner = GmsBarcodeScanning.getClient(
        activity,
        // No auto-zoom: the pairing QR is dense (version ~18, ~100 modules a
        // side), and ML Kit's auto-zoom overshoots on it, pushing the finder
        // patterns out of frame so the scan never completes. Without it the
        // scanner reads at whatever distance the user holds the phone, which
        // is how the iOS scanner already behaves.
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    fun scan(
        onResult: (String) -> Unit,
        onUnavailable: () -> Unit,
    ) {
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                barcode.rawValue?.takeIf(String::isNotBlank)?.let(onResult) ?: onUnavailable()
            }
            .addOnFailureListener { onUnavailable() }
    }
}
