package com.clawdbot.ar.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Camera capture utility using CameraX.
 * Captures photos and converts them to base64 for sending to AI.
 */
class CameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "CameraCapture"
        private const val MAX_IMAGE_SIZE = 1024 // Max dimension for resizing
        private const val JPEG_QUALITY = 85
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isCameraReady = false

    /**
     * Initialize camera. Call this before capturing.
     */
    suspend fun initialize(lifecycleOwner: LifecycleOwner): Boolean = suspendCancellableCoroutine { cont ->
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                // Set up image capture use case
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(android.view.Surface.ROTATION_0)
                    .build()

                // Bind to lifecycle
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageCapture
                )

                isCameraReady = true
                Log.d(TAG, "Camera initialized successfully")
                cont.resume(true)

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera: ${e.message}", e)
                cont.resume(false)
            }
        }, ContextCompat.getMainExecutor(context))

        cont.invokeOnCancellation {
            cameraProvider?.unbindAll()
        }
    }

    /**
     * Capture a photo and return it as base64.
     * Returns null on failure.
     */
    suspend fun capturePhoto(): CaptureResult? = suspendCancellableCoroutine { cont ->
        val capture = imageCapture
        if (capture == null || !isCameraReady) {
            Log.e(TAG, "Camera not ready for capture")
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        Log.d(TAG, "Taking photo...")

        capture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        Log.d(TAG, "Photo captured: ${imageProxy.width}x${imageProxy.height}")

                        // Convert to bitmap
                        val bitmap = imageProxyToBitmap(imageProxy)
                        imageProxy.close()

                        if (bitmap == null) {
                            Log.e(TAG, "Failed to convert image to bitmap")
                            cont.resume(null)
                            return
                        }

                        // Resize if needed
                        val resizedBitmap = resizeBitmap(bitmap, MAX_IMAGE_SIZE)
                        if (bitmap != resizedBitmap) {
                            bitmap.recycle()
                        }

                        // Get dimensions before recycling
                        val resultWidth = resizedBitmap.width
                        val resultHeight = resizedBitmap.height

                        // Convert to base64
                        val base64 = bitmapToBase64(resizedBitmap, JPEG_QUALITY)
                        resizedBitmap.recycle()

                        Log.d(TAG, "Photo converted to base64: ${base64.length} chars")

                        cont.resume(CaptureResult(
                            base64 = base64,
                            mimeType = "image/jpeg",
                            width = resultWidth,
                            height = resultHeight
                        ))

                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing captured image: ${e.message}", e)
                        cont.resume(null)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                    cont.resume(null)
                }
            }
        )
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

        // Apply rotation if needed
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it != bitmap) bitmap.recycle()
            }
        } else {
            bitmap
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height

        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }

        val scale = maxSize.toFloat() / maxOf(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()

        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Release camera resources.
     */
    fun release() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        isCameraReady = false
        cameraExecutor.shutdown()
    }
}

/**
 * Result of a photo capture.
 */
data class CaptureResult(
    val base64: String,
    val mimeType: String,
    val width: Int,
    val height: Int
)
