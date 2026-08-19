package se.rise.logline.ui

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.MultiFormatReader
import se.rise.logline.ui.components.ScreenScaffold
import java.util.concurrent.Executors

private const val TAG = "QrScannerScreen"

/**
 * Point the camera at another phone's connection QR.
 *
 * `ImageAnalysis` rather than taking a picture: the frames never leave the process, nothing is
 * written, and the Y plane CameraX delivers is already the greyscale image zxing wants. One
 * `MultiFormatReader` is reused across frames — it carries decoding state and building one per frame
 * is most of the cost of scanning.
 *
 * The camera is bound to this composable's lifecycle, so leaving the screen releases it and the
 * privacy indicator goes out. That is not the same arrangement the time-lapse uses — it binds against
 * a private `LifecycleOwner` because it has to outlive the UI — and the difference is deliberate.
 */
@Composable
fun QrScannerScreen(
    onScanned: (String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    // Read the latest callback without re-binding the camera when recomposition hands us a new one.
    val deliver by rememberUpdatedState(onScanned)

    LaunchedEffect(Unit) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val executor = Executors.newSingleThreadExecutor()
        val reader = MultiFormatReader()
        // Only the newest frame matters: a queue of stale ones would scan the past.
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        var delivered = false
        analysis.setAnalyzer(executor) { image ->
            try {
                if (!delivered) {
                    val plane = image.planes.firstOrNull()
                    val buffer = plane?.buffer
                    if (buffer != null) {
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        // rowStride, not width: CameraX pads rows to an alignment, and treating the
                        // padding as pixels shears the image into something no decoder can read.
                        val stride = plane.rowStride
                        decodeQr(bytes, stride, image.height, reader)?.let { text ->
                            delivered = true
                            deliver(text)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "frame could not be scanned", t)
            } finally {
                // Every path, or the pipeline stalls after a handful of frames.
                image.close()
            }
        }
        val preview = Preview.Builder().build().apply { surfaceProvider = previewView.surfaceProvider }
        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }.onFailure { Log.e(TAG, "could not open the camera", it) }
    }

    ScreenScaffold(title = "Scan a connection QR", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Point at the QR on the other phone's settings screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "It carries the realm, router endpoints and source ids — not switches, rates or " +
                        "QoS overrides, which need the exported file.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
