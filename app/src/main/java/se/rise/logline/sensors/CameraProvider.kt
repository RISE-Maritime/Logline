package se.rise.logline.sensors

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Display
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.coroutines.resume

private const val TAG = "CameraProvider"

/**
 * How many captures in a row may fail before the subject is declared broken.
 *
 * A single failure is ordinary — another app grabbing the camera for a moment, a focus timeout — and
 * retrying at the next tick is the right response. A run of them is not, and a collector that logs
 * forever while publishing nothing is exactly the silent failure `SubjectSink` exists to prevent.
 */
private const val MAX_CONSECUTIVE_FAILURES = 5

/** One captured frame: JPEG bytes, and the boot-clock instant the sensor exposed it. */
data class CameraFrame(
    val jpeg: ByteArray,
    val elapsedNanos: Long,
    val width: Int,
    val height: Int,
) {
    // Data classes compare arrays by reference; content equality is what a test would want to assert.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CameraFrame) return false
        return elapsedNanos == other.elapsedNanos &&
            width == other.width &&
            height == other.height &&
            jpeg.contentEquals(other.jpeg)
    }

    override fun hashCode(): Int =
        ((jpeg.contentHashCode() * 31 + elapsedNanos.hashCode()) * 31 + width) * 31 + height
}

/**
 * The camera, as a flow of JPEG frames at a fixed interval — a time-lapse.
 *
 * Same contract as the other providers: the hardware is acquired on collection and released in
 * `awaitClose`, which is what stops the camera (and its privacy indicator) staying live after a run.
 *
 * The camera stays **bound for the whole run** rather than being opened per frame. Opening a camera
 * costs a few hundred milliseconds and blinks the indicator each time; at the default two-second
 * interval that would be most of the duty cycle spent opening. The cost is that the camera is powered
 * throughout, which is the honest trade for a subject someone deliberately switched on.
 */
class CameraProvider(private val context: Context) {

    /** Whether this device has a camera at all, for the "unavailable" row rather than a silent one. */
    fun available(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    /**
     * @param intervalMillis time-lapse period. It is the publish period: 2000 ms publishes at 0.5 Hz.
     *   Capture latency is absorbed into the period rather than added to it, so the frame rate is the
     *   one asked for as long as the hardware can keep up.
     *
     * Requires `CAMERA`; the caller checks that, as `runLocation` does for its own permission.
     */
    fun frames(
        intervalMillis: Long,
        front: Boolean,
        size: Size,
        quality: Int,
    ): Flow<CameraFrame> = callbackFlow {
        jpegQuality = quality
        loggedScaling = false
        val lifecycle = CameraLifecycle()
        // One thread for the capture callbacks: they arrive off the camera's own threads otherwise, and
        // a single executor keeps the JPEG copy off both the main thread and the collector's.
        val executor = Executors.newSingleThreadExecutor()
        val provider = ProcessCameraProvider.awaitInstance(context)

        val capture = ImageCapture.Builder()
            // Latency over quality: the frame wanted is the one at the tick, not a slightly better one
            // assembled from several exposures a moment later.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setJpegQuality(quality)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    // The aspect ratio has to be stated, and stating it is not optional. A selector's
                    // default strategy prefers 4:3 and filters the candidate list *before* the
                    // resolution strategy sees it, so asking for 1280x720 on this phone's front camera
                    // produced 1920x1440 — a 4:3 frame at 2.7x the pixels and 2.7x the data rate,
                    // silently. Measured, not theorised.
                    .setAspectRatioStrategy(aspectRatioStrategyFor(size))
                    .setResolutionStrategy(
                        ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            // Without a preview there is nothing to infer orientation from, so it is taken from the
            // display: a phone mounted in landscape then produces upright frames. Consumers that ignore
            // EXIF are the norm, which is why this is set rather than left to the default.
            .setTargetRotation(displayRotation())
            .build()

        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        // Binding is main-thread only, and so is LifecycleRegistry.
        withContext(Dispatchers.Main) {
            lifecycle.resume()
            provider.unbindAll()
            provider.bindToLifecycle(lifecycle, selector, capture)
        }
        Log.i(
            TAG,
            "camera bound (${if (front) "front" else "rear"}, requested ${size.width}x${size.height}, " +
                "q$quality); one frame every ${intervalMillis}ms",
        )

        val ticker = launch {
            var consecutiveFailures = 0
            while (isActive) {
                val startedAt = SystemClock.elapsedRealtime()
                val frame = try {
                    takeOne(capture, executor)
                } catch (e: ImageCaptureException) {
                    consecutiveFailures++
                    Log.w(TAG, "capture failed ($consecutiveFailures in a row)", e)
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) throw e
                    null
                }
                if (frame != null) {
                    consecutiveFailures = 0
                    send(scaleIfOversized(frame, size.width))
                }
                // Measured from the start of the capture, so the period is the interval rather than the
                // interval plus however long the camera took.
                val remaining = intervalMillis - (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0) delay(remaining)
            }
        }

        awaitClose {
            ticker.cancel()
            // awaitClose cannot suspend, and both of these must happen on the main thread. Posting is
            // what releases the camera; without it the indicator stays lit until the process dies.
            Handler(Looper.getMainLooper()).post {
                runCatching { provider.unbindAll() }
                lifecycle.destroy()
            }
            executor.shutdown()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Bring a frame down to the requested width when the camera could not capture it that small.
     *
     * **Most phones have no small JPEG stream.** Measured on a Pixel 6: the smallest JPEG output either
     * camera offers is 1920x1080, so a run configured for 1280x720 was publishing 2.25x the pixels — and
     * roughly 2.25x the data rate — while the settings screen quoted the smaller figure. A setting that
     * is silently ignored is worse than one that is not offered, and the whole point of this control is
     * the number of bytes per hour, so the frame is re-encoded here instead.
     *
     * A second JPEG generation is the cost, and it is small: the downscale hides the first encode's
     * artefacts, and at one frame every two seconds the CPU is not the constraint. Frames the camera
     * already delivered at or below the requested width are passed through untouched, which is the case
     * on any device with a smaller stream.
     */
    private fun scaleIfOversized(frame: CameraFrame, targetWidth: Int): CameraFrame {
        if (frame.width <= targetWidth) return frame
        val scaled = scaleJpeg(frame.jpeg, targetWidth, jpegQuality) ?: return frame
        if (!loggedScaling) {
            loggedScaling = true
            Log.i(
                TAG,
                "camera delivered ${frame.width}x${frame.height} for a ${targetWidth}px request — no " +
                    "smaller JPEG stream on this device; scaling each frame to " +
                    "${scaled.width}x${scaled.height}",
            )
        }
        return CameraFrame(scaled.jpeg, frame.elapsedNanos, scaled.width, scaled.height)
    }

    /** Said once per run, not once per frame — at 0.5 Hz a line per frame is still a slow flood. */
    private var loggedScaling = false

    /** The configured quality, kept for the re-encode above. Set when a flow starts. */
    private var jpegQuality = 80

    /**
     * The aspect-ratio strategy matching a requested frame size.
     *
     * Only 4:3 and 16:9 exist in the API, and every offered size is one of them; `FALLBACK_RULE_AUTO`
     * means a camera that cannot do the preferred ratio still produces frames rather than none.
     */
    private fun aspectRatioStrategyFor(size: Size): AspectRatioStrategy {
        // Nearest of the two, by ratio distance. A strict `w * 9 > h * 16` test looks equivalent and is
        // not: 1280x720 satisfies it with equality, so the exact 16:9 case fell through to 4:3 and the
        // camera handed back 1920x1440.
        val ratio = size.width.toDouble() / size.height
        val widescreen = kotlin.math.abs(ratio - 16.0 / 9.0) <= kotlin.math.abs(ratio - 4.0 / 3.0)
        return if (widescreen) {
            AspectRatioStrategy(AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        } else {
            AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO)
        }
    }

    /**
     * The default display's rotation, or portrait when there is nothing to ask.
     *
     * A service has no window, so this is the only orientation reference available — and it is the one
     * that matters: it is what the phone's own screen would be doing in the mount it is sitting in.
     */
    private fun displayRotation(): Int {
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        return displays?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }

    /** One capture, awaited. The `ImageProxy` is closed on every path — a leaked one stalls the pipeline. */
    private suspend fun takeOne(capture: ImageCapture, executor: java.util.concurrent.Executor): CameraFrame =
        suspendCancellableCoroutine { cont: CancellableContinuation<CameraFrame> ->
            capture.takePicture(
                executor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        try {
                            val frame = CameraFrame(
                                jpeg = image.jpegBytes(),
                                // The camera's own exposure timestamp, on a boot clock — the same shape
                                // as SensorEvent.timestamp, and converted the same way by the caller.
                                elapsedNanos = image.imageInfo.timestamp,
                                width = image.width,
                                height = image.height,
                            )
                            if (cont.isActive) cont.resume(frame)
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resumeWith(Result.failure(t))
                        } finally {
                            image.close()
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resumeWith(Result.failure(exception))
                    }
                },
            )
        }

    /**
     * The frame as JPEG bytes.
     *
     * An in-memory `ImageCapture` normally hands back `ImageFormat.JPEG`, in which case plane 0 already
     * holds the encoded file and nothing needs doing. Devices that default to Ultra HDR return `JPEG_R`
     * (or, on a few, `YUV_420_888`), which no consumer of this subject is expecting — so those are
     * re-encoded as plain JPEG rather than published as something the `format` field would lie about.
     */
    private fun ImageProxy.jpegBytes(): ByteArray = when (format) {
        ImageFormat.JPEG -> {
            val buffer = planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        }
        else -> ByteArrayOutputStream().use { out ->
            toBitmap().compress(android.graphics.Bitmap.CompressFormat.JPEG, JPEG_REENCODE_QUALITY, out)
            out.toByteArray()
        }
    }

    /**
     * A `LifecycleOwner` for a camera with no UI.
     *
     * `bindToLifecycle` is the only public way to bind a use case, and the publisher is not a lifecycle
     * owner — so this one exists purely to be driven to RESUMED at bind and DESTROYED at close. Both
     * transitions happen on the main thread, which `LifecycleRegistry` requires.
     */
    private class CameraLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    private companion object {
        /** Only for the odd device that hands back something other than JPEG; see [jpegBytes]. */
        const val JPEG_REENCODE_QUALITY = 85
    }
}
