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
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
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
