package com.github.jvsena42.loopky.ui.discover

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

private const val TAG = "Loopky/PubkyScanner"

/**
 * Launch Google's ML Kit code scanner to read a pubky from a QR code. Uses the bundled scanner UI,
 * which needs **no `CAMERA` permission** — the scan runs in a Google-provided activity. [onResult]
 * is invoked with the decoded string on success; cancellations and failures are no-ops (logged).
 */
fun scanPubky(context: Context, onResult: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        .build()
    GmsBarcodeScanning.getClient(context, options)
        .startScan()
        .addOnSuccessListener { barcode -> barcode.rawValue?.let(onResult) }
        .addOnCanceledListener { Log.d(TAG, "scanPubky: cancelled") }
        .addOnFailureListener { Log.e(TAG, "scanPubky: failed — ${it.message}", it) }
}
