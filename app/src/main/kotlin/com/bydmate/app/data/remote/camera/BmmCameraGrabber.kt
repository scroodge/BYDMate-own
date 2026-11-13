package com.bydmate.app.data.remote.camera

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import dalvik.system.PathClassLoader
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * One surround-view JPEG straight from BYD's camera stack, without di+.
 *
 * `/system/framework/bmmcamera.jar` (`android.hardware.AVMCamera`) talks to the
 * `bmmcameraserver` Binder service. It is not on the boot classpath, so it is loaded here
 * with its own class loader — the same trick di+ uses. Proven on `way` (DiLink 3.0)
 * 2026-11-06 from shell uid and from the app uid under `run-as`: camera 1 gave a
 * 5120×960 NV21 strip of the four surround cameras within a second, with di+'s own
 * capture process running at the same time.
 *
 * `AVMCamera.open(id)` returns null on this car because `BmmCameraInfo` reads the camera
 * map from `vehicle.config.cam_sort`, which SELinux hides from both shell and apps. di+
 * therefore opens by id through the private constructor; so do we.
 *
 * Every failure returns null and logs one line: the callers fall back to the di+ path.
 * Only a 4-tile surround strip is accepted, so an unknown camera at the fallback id (an
 * interior DMS camera on another model, say) is never uploaded.
 */
object BmmCameraGrabber {
    private const val JAR = "/system/framework/bmmcamera.jar"
    private const val NATIVE_LIB_PATH = "/system/lib64:/system/lib"
    private const val CAMERA_CLASS = "android.hardware.AVMCamera"
    private const val INFO_CLASS = "android.hardware.BmmCameraInfo"
    private const val CALLBACK_CLASS = "android.hardware.AVMCamera\$IPreviewCallback"

    /** Surround-view camera id on `way`; used when the camera map is unreadable. */
    const val FALLBACK_SURROUND_CAMERA_ID = 1
    private const val PREVIEW_INDEX = 0
    const val DEFAULT_TIMEOUT_MS = 6_000L
    private const val JPEG_QUALITY = 85

    @Volatile private var loader: ClassLoader? = null

    private class Frame(val nv21: ByteArray, val width: Int, val height: Int)

    /** @return a 2×2 grid JPEG (2560×1920 on `way`), or null — see [log] for why. */
    fun captureSurroundJpeg(timeoutMs: Long = DEFAULT_TIMEOUT_MS, log: (String) -> Unit): ByteArray? {
        if (!File(JAR).exists()) {
            log("camera direct: $JAR missing")
            return null
        }
        return try {
            synchronized(this) { captureLocked(timeoutMs, log) }
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            log("camera direct: ${cause.javaClass.simpleName}: ${cause.message}")
            null
        }
    }

    private fun captureLocked(timeoutMs: Long, log: (String) -> Unit): ByteArray? {
        val classes = classLoader()
        val cameraClass = Class.forName(CAMERA_CLASS, true, classes)
        val cameraId = surroundCameraId(classes)
        val camera = open(cameraClass, cameraId) ?: run {
            log("camera direct: open($cameraId) failed")
            return null
        }
        var started = false
        try {
            val latch = CountDownLatch(1)
            var frame: Frame? = null
            val callbackClass = Class.forName(CALLBACK_CLASS, true, classes)
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onPreview" -> {
                        if (latch.count > 0L) {
                            val data = args[1] as? ByteArray
                            val width = args[2] as Int
                            val height = args[3] as Int
                            val size = NvFrame.nv21Size(width, height)
                            if (data != null && data.size >= size) {
                                // The library reuses its buffer for the next frame: copy now.
                                frame = Frame(data.copyOf(size), width, height)
                                latch.countDown()
                            }
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "VoltFlowPreviewCallback"
                    else -> null
                }
            }
            val callback = Proxy.newProxyInstance(classes, arrayOf(callbackClass), handler)
            cameraClass.getMethod("setPreviewCallback", callbackClass).invoke(camera, callback)
            if (cameraClass.getMethod("enablePreviewCallback", Int::class.javaPrimitiveType)
                    .invoke(camera, PREVIEW_INDEX) != true
            ) {
                log("camera direct: enablePreviewCallback rejected (camera $cameraId)")
                return null
            }
            if (cameraClass.getMethod("isPreview").invoke(camera) != true) {
                started = cameraClass.getMethod("startPreview").invoke(camera) == true
                if (!started) {
                    log("camera direct: startPreview failed (camera $cameraId)")
                    return null
                }
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                log("camera direct: no frame in ${timeoutMs}ms (camera $cameraId)")
                return null
            }
            val captured = frame ?: return null
            if (!NvFrame.isSurroundStrip(captured.width, captured.height)) {
                log("camera direct: camera $cameraId is ${captured.width}x${captured.height}, not a surround strip")
                return null
            }
            val grid = NvFrame.stripToGrid(captured.nv21, captured.width, captured.height)
            val width = captured.width / 2
            val height = captured.height * 2
            val jpeg = ByteArrayOutputStream().use { out ->
                YuvImage(grid, ImageFormat.NV21, width, height, null)
                    .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, out)
                out.toByteArray()
            }
            log("camera direct: camera $cameraId ${captured.width}x${captured.height} → ${width}x$height jpeg ${jpeg.size} B")
            return jpeg
        } finally {
            runCatching {
                cameraClass.getMethod("disablePreviewCallback", Int::class.javaPrimitiveType)
                    .invoke(camera, PREVIEW_INDEX)
            }
            if (started) runCatching { cameraClass.getMethod("stopPreview").invoke(camera) }
            runCatching { cameraClass.getMethod("close").invoke(camera) }
        }
    }

    private fun classLoader(): ClassLoader =
        loader ?: PathClassLoader(JAR, NATIVE_LIB_PATH, BmmCameraGrabber::class.java.classLoader)
            .also { loader = it }

    private fun surroundCameraId(classes: ClassLoader): Int {
        val ids = runCatching {
            val info = Class.forName(INFO_CLASS, true, classes)
            val getId = info.getMethod("getCameraId", String::class.java)
            listOf("pano_h", "pano_l").map { getId.invoke(null, it) as Int }
        }.getOrDefault(emptyList())
        return ids.firstOrNull { it >= 0 } ?: FALLBACK_SURROUND_CAMERA_ID
    }

    /** The public `open(id)` first; when the camera map is unreadable, the private constructor. */
    private fun open(cameraClass: Class<*>, cameraId: Int): Any? {
        cameraClass.getMethod("open", Int::class.javaPrimitiveType).invoke(null, cameraId)?.let { return it }
        val camera = cameraClass.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .newInstance(cameraId)
        val opened = cameraClass.getDeclaredMethod("open")
            .apply { isAccessible = true }
            .invoke(camera) == true
        if (opened) return camera
        runCatching { cameraClass.getMethod("close").invoke(camera) }
        return null
    }
}
