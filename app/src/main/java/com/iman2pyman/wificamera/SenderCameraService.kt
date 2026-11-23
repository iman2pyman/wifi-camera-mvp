package com.iman2pyman.wificamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

object SenderCameraRuntime {
    @Volatile var latestJpeg: ByteArray? = null
    @Volatile var latestFrameAt: Long = 0L
    @Volatile var lastError: String? = null
    @Volatile var previewSurfaceProvider: Preview.SurfaceProvider? = null
}

class SenderCameraService : LifecycleService() {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            cpuWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BabyMonitor:Camera").apply {
                setReferenceCounted(false)
                acquire()
            }
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BabyMonitor:WiFi").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            SenderCameraRuntime.lastError = "فعال‌سازی قفل پردازنده/وای‌فای ناموفق بود: ${e.message}"
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baby_monitor)
            .setContentTitle("دوربین کنترل کودک فعال است")
            .setContentText("ارسال تصویر در پس‌زمینه ادامه دارد")
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (Build.VERSION.SDK_INT >= 30 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(NOTIFICATION_ID, notification, types)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            SenderCameraRuntime.lastError = "اجرای سرویس پس‌زمینه ناموفق بود: ${e.message}"
            stopSelf()
            return
        }
        startCamera()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                SenderCameraRuntime.previewSurfaceProvider?.let { preview.setSurfaceProvider(it) }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraExecutor) { frame ->
                    try {
                        val jpeg = imageProxyToJpeg(frame)
                        if (jpeg.isNotEmpty()) {
                            SenderCameraRuntime.latestJpeg = jpeg
                            SenderCameraRuntime.latestFrameAt = android.os.SystemClock.elapsedRealtime()
                            SenderCameraRuntime.lastError = null
                        }
                    } catch (e: Exception) {
                        SenderCameraRuntime.lastError = e.message ?: "خطای پردازش تصویر"
                    } finally { frame.close() }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                SenderCameraRuntime.lastError = e.message ?: "راه‌اندازی دوربین ناموفق بود"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun imageProxyToJpeg(image: ImageProxy): ByteArray {
        val width = image.width; val height = image.height
        val planes = image.planes
        val yPlane = planes[0]; val uPlane = planes[1]; val vPlane = planes[2]
        val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
        val nv21 = ByteArray(width * height * 3 / 2)
        var out = 0
        for (row in 0 until height) {
            val rowStart = row * yPlane.rowStride
            for (col in 0 until width) nv21[out++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
        }
        for (row in 0 until height / 2) {
            val uRow = row * uPlane.rowStride; val vRow = row * vPlane.rowStride
            for (col in 0 until width / 2) {
                nv21[out++] = vBuffer.get(vRow + col * vPlane.pixelStride)
                nv21[out++] = uBuffer.get(uRow + col * uPlane.pixelStride)
            }
        }
        val encoded = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, width, height, null).compressToJpeg(Rect(0, 0, width, height), 68, encoded)
        val jpeg = encoded.toByteArray()
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0 || jpeg.isEmpty()) return jpeg
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return jpeg
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val result = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 68, result)
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        return result.toByteArray()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.getBooleanExtra("enable_audio", false) == true &&
            Build.VERSION.SDK_INT >= 30 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            try {
                val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_baby_monitor)
                    .setContentTitle("دوربین کنترل کودک فعال است")
                    .setContentText("تصویر و صدای محیط در پس‌زمینه ارسال می‌شود")
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } catch (e: Exception) {
                SenderCameraRuntime.lastError = "فعال‌سازی صدای پس‌زمینه ناموفق بود: ${e.message}"
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        cameraExecutor.shutdown()
        try { if (cpuWakeLock?.isHeld == true) cpuWakeLock?.release() } catch (_: Exception) { }
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) { }
        cpuWakeLock = null
        wifiLock = null
        SenderCameraRuntime.latestJpeg = null
        SenderCameraRuntime.latestFrameAt = 0L
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "فعالیت دوربین کودک", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "baby_camera_background"
        private const val NOTIFICATION_ID = 2107
    }
}
