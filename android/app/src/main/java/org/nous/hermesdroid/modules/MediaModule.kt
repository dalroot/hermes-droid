package org.nous.hermesdroid.modules

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class MediaModule(private val context: Context) {

    @SuppressLint("MissingPermission")
    suspend fun captureLivePhoto(useFrontCamera: Boolean = false): Result<ByteArray> {
        val perm = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
        if (perm != PackageManager.PERMISSION_GRANTED) {
            return Result.failure(SecurityException("Camera permission is not granted. Open Hermes Droid -> Access to enable."))
        }

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val targetFacing = if (useFrontCamera) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }

        var selectedCameraId: String? = null
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            if (facing == targetFacing) {
                selectedCameraId = id
                break
            }
        }

        if (selectedCameraId == null) {
            // fallback to first camera
            selectedCameraId = cameraManager.cameraIdList.firstOrNull()
        }

        if (selectedCameraId == null) {
            return Result.failure(IllegalStateException("No camera hardware found on this device."))
        }

        val thread = HandlerThread("HermesCameraThread").apply { start() }
        val handler = Handler(thread.looper)

        val imageReader = ImageReader.newInstance(1280, 720, ImageFormat.JPEG, 6)

        return try {
            val result = withTimeoutOrNull(9000L) {
                suspendCancellableCoroutine<Result<ByteArray>> { continuation ->
                    var cameraDevice: CameraDevice? = null
                    var captureSession: CameraCaptureSession? = null
                    var frameCount = 0

                    fun cleanup() {
                        try { captureSession?.stopRepeating() } catch (e: Exception) {}
                        try { captureSession?.close() } catch (e: Exception) {}
                        try { cameraDevice?.close() } catch (e: Exception) {}
                        try { imageReader.close() } catch (e: Exception) {}
                        try { thread.quitSafely() } catch (e: Exception) {}
                    }

                    imageReader.setOnImageAvailableListener({ reader ->
                        try {
                            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                            frameCount++
                            // Discard first 4 dark frames while sensor meters light
                            if (frameCount < 5) {
                                image.close()
                                return@setOnImageAvailableListener
                            }
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            image.close()
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(Result.success(bytes))
                            }
                        } catch (e: Exception) {
                            cleanup()
                            if (continuation.isActive) continuation.resume(Result.failure(e))
                        }
                    }, handler)

                    cameraManager.openCamera(selectedCameraId, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            cameraDevice = camera
                            try {
                                @Suppress("DEPRECATION")
                                camera.createCaptureSession(
                                    listOf(imageReader.surface),
                                    object : CameraCaptureSession.StateCallback() {
                                        override fun onConfigured(session: CameraCaptureSession) {
                                            captureSession = session
                                            try {
                                                // Start repeating preview to warm up sensor and calibrate auto-exposure
                                                val previewReq = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                    addTarget(imageReader.surface)
                                                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                                                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                                    set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                                                }
                                                session.setRepeatingRequest(previewReq.build(), null, handler)
                                            } catch (e: Exception) {
                                                cleanup()
                                                if (continuation.isActive) continuation.resume(Result.failure(e))
                                            }
                                        }

                                        override fun onConfigureFailed(session: CameraCaptureSession) {
                                            cleanup()
                                            if (continuation.isActive) {
                                                continuation.resume(Result.failure(IllegalStateException("Failed to configure camera capture session.")))
                                            }
                                        }
                                    },
                                    handler
                                )
                            } catch (e: Exception) {
                                cleanup()
                                if (continuation.isActive) continuation.resume(Result.failure(e))
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            cleanup()
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(IllegalStateException("Camera disconnected.")))
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            cleanup()
                            val msg = when (error) {
                                ERROR_CAMERA_IN_USE -> "Camera is currently in use by another app."
                                ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use."
                                ERROR_CAMERA_DISABLED -> "Camera is disabled by device policy."
                                ERROR_CAMERA_DEVICE -> "Fatal camera device error."
                                ERROR_CAMERA_SERVICE -> "Fatal camera service error."
                                else -> "Camera open error code: $error"
                            }
                            if (continuation.isActive) {
                                continuation.resume(Result.failure(IllegalStateException(msg)))
                            }
                        }
                    }, handler)

                    continuation.invokeOnCancellation {
                        cleanup()
                    }
                }
            }
            result ?: Result.failure(IllegalStateException("Camera capture timed out (7s). Camera sensor might be busy."))
        } catch (e: Exception) {
            try { thread.quitSafely() } catch (ex: Exception) {}
            Result.failure(e)
        }
    }

    fun getRecentPhotos(limit: Int = 5, includeBase64: Boolean = false): JSONArray {
        val result = JSONArray()
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            cursor?.use {
                val idIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val dateIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val sizeIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)

                var count = 0
                while (it.moveToNext() && count < limit) {
                    val id = it.getLong(idIdx)
                    val name = it.getString(nameIdx) ?: "photo"
                    val date = it.getLong(dateIdx)
                    val size = it.getLong(sizeIdx)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    val obj = JSONObject().apply {
                        put("id", id)
                        put("name", name)
                        put("date_added", date)
                        put("size_bytes", size)
                        put("uri", contentUri.toString())
                    }

                    if (count < 2 && includeBase64) {
                        try {
                            context.contentResolver.openInputStream(contentUri)?.use { stream ->
                                val bitmap = BitmapFactory.decodeStream(stream)
                                if (bitmap != null) {
                                    val scaled = Bitmap.createScaledBitmap(bitmap, 800, (800.0 * bitmap.height / bitmap.width).toInt(), true)
                                    val baos = ByteArrayOutputStream()
                                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                                    obj.put("base64_jpeg", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP))
                                }
                            }
                        } catch (e: Exception) {
                            // ignore thumbnail fail
                        }
                    }
                    count++
                    result.put(obj)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }
}
