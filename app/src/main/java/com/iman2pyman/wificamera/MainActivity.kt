package com.iman2pyman.wificamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var cameraServer: CameraHttpServer? = null
    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val streaming = AtomicBoolean(false)
    @Volatile private var latestJpeg: ByteArray? = null
    private var captureBusy = false
    private var hotspotReservation: Any? = null
    private var fallbackHotspotMode = false

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestWifiPermissionAndStart() else toast("برای ارسال تصویر، اجازه دوربین لازم است.")
    }
    private val wifiPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startSenderCamera() else toast("برای ایجاد شبکه محلی، اجازه دستگاه‌های نزدیک لازم است.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "دوربین کنترل کودک"
        showHome()
    }

    private fun baseLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(22, 20, 22, 20)
        setBackgroundColor(Color.rgb(239, 248, 255))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun text(value: String, size: Float = 16f) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.rgb(48, 72, 99))
        gravity = Gravity.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.NORMAL)
        setPadding(8, 9, 8, 9)
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value
        isAllCaps = false
        textSize = 16f
        typeface = android.graphics.Typeface.create("sans-serif-rounded", android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (value.contains("دریافت") || value.contains("بازگشت") || value.contains("قطع")) Color.rgb(238, 151, 177)
            else Color.rgb(66, 157, 218)
        )
        elevation = 7f
        stateListAnimator = null
        setPadding(12, 9, 12, 9)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.setMargins(0, 8, 0, 8)
        layoutParams = lp
        setOnClickListener { action() }
    }

    private fun addBrandHeader(target: LinearLayout) {
        val icon = ImageView(this).apply {
            setImageResource(R.drawable.ic_baby_monitor)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        target.addView(icon, LinearLayout.LayoutParams(116, 116).apply { gravity = Gravity.CENTER })
        target.addView(text("دوربین کنترل کودک", 27f))
        target.addView(text("آرامش خاطر، با یک نگاه به کوچولوی شما", 14f))
    }

    private fun beginSender() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        } else requestWifiPermissionAndStart()
    }

    private fun requestWifiPermissionAndStart() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) startSenderCamera()
        else wifiPermission.launch(permission)
    }

    private fun showHome() {
        root = baseLayout()
        root.gravity = Gravity.CENTER_HORIZONTAL
        addBrandHeader(root)
        root.addView(text("برای شروع، نقش هر گوشی را انتخاب کنید.", 15f))
        root.addView(button("📷  گوشی کنار کودک — ارسال تصویر") { beginSender() })
        root.addView(button("👀  گوشی والدین — مشاهده تصویر") { showViewer() })
        root.addView(text("اتصال مستقیم دو گوشی، بدون مودم و بدون اینترنت", 13f))
        root.addView(text("برای حفظ حریم خصوصی، فقط با افراد مورد اعتماد اتصال برقرار کنید.", 12f))
        setContentView(root)
    }

    private fun startSenderCamera() {
        stopSender()
        root = baseLayout()
        root.addView(text("📷 ارسال زنده تصویر کوچولو", 22f))
        val preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val status = text("در حال آماده‌سازی دوربین…")
        root.addView(status)
        root.addView(button("توقف ارسال") { stopSender(); showHome() })
        setContentView(root)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val previewUseCase = androidx.camera.core.Preview.Builder().build()
                val captureUseCase = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                imageCapture = captureUseCase
                previewUseCase.setSurfaceProvider(preview.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, captureUseCase)
                startCaptureLoop()
                if (Build.VERSION.SDK_INT >= 26) startLocalOnlyHotspot(status)
                else showManualHotspotFallback(status)
            } catch (e: Exception) {
                toast("راه‌اندازی دوربین ناموفق بود: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @RequiresApi(26)
    private fun startLocalOnlyHotspot(status: TextView) {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                hotspotReservation = reservation
                try {
                    val ssid: String
                    val password: String
                    if (Build.VERSION.SDK_INT >= 30) {
                        val config = reservation.softApConfiguration
                        ssid = config.ssid ?: ""
                        password = config.passphrase ?: ""
                    } else {
                        @Suppress("DEPRECATION")
                        val config = reservation.wifiConfiguration
                        ssid = config?.SSID?.trim('"') ?: ""
                        password = config?.preSharedKey?.trim('"') ?: ""
                    }
                    if (ssid.isBlank()) {
                        status.text = "شبکه ساخته شد اما خواندن نام شبکه ممکن نشد. از روش جایگزین استفاده کنید."
                        return
                    }
                    startHttpServer()
                    val ip = localIpAddress()
                    runOnUiThread { showHotspotDetails(ssid, password, ip) }
                } catch (e: Exception) {
                    runOnUiThread { status.text = "خواندن مشخصات شبکه ناموفق بود: ${e.message}" }
                }
            }

            override fun onStopped() {
                runOnUiThread { toast("شبکه محلی متوقف شد.") }
            }

            override fun onFailed(reason: Int) {
                runOnUiThread {
                    status.text = "ایجاد خودکار شبکه ممکن نشد (کد $reason). از روش جایگزین استفاده کنید."
                    showManualHotspotFallback(status)
                }
            }
        }
        try {
            wifi.startLocalOnlyHotspot(callback, android.os.Handler(mainLooper))
            status.text = "درخواست ایجاد شبکه اختصاصی از اندروید ارسال شد…"
        } catch (e: SecurityException) {
            status.text = "مجوز Wi-Fi داده نشده است: ${e.message}"
        } catch (e: Exception) {
            status.text = "این گوشی ایجاد خودکار شبکه را نپذیرفت. روش جایگزین را امتحان کنید."
            showManualHotspotFallback(status)
        }
    }

    private fun showHotspotDetails(ssid: String, password: String, ip: String?) {
        root = baseLayout()
        root.addView(text("شبکه اختصاصی آماده است", 22f))
        root.addView(text("در گوشی دوم، QR را با دوربین یا تنظیمات Wi-Fi اسکن کن."))
        val qr = ImageView(this)
        val qrData = if (password.isBlank()) "WIFI:T:nopass;S:$ssid;;"
            else "WIFI:T:WPA;S:$ssid;P:$password;;"
        try {
            qr.setImageBitmap(makeQr(qrData))
            root.addView(qr, LinearLayout.LayoutParams(260, 260).apply { gravity = Gravity.CENTER })
        } catch (_: Exception) { }
        root.addView(text("نام شبکه: $ssid"))
        root.addView(text(if (password.isBlank()) "رمز: بدون رمز" else "رمز شبکه: $password"))
        root.addView(text(if (ip != null) "پس از اتصال گوشی دوم، در برنامه گیرنده این نشانی را وارد کن:\nhttp://$ip:8080" else "شبکه فعال است؛ در حال شناسایی نشانی محلی…"))
        root.addView(button("به‌روزرسانی نشانی") {
            val current = localIpAddress()
            toast(if (current == null) "نشانی شبکه هنوز پیدا نشد." else "نشانی سرور: http://$current:8080")
        })
        root.addView(button("توقف ارسال") { stopSender(); showHome() })
        setContentView(root)
    }

    private fun showManualHotspotFallback(status: TextView) {
        fallbackHotspotMode = true
        status.text = "این گوشی از هات‌اسپات محلی برنامه‌ای پشتیبانی نکرد. هات‌اسپات گوشی را دستی روشن کن."
        val settingsButton = button("بازکردن تنظیمات هات‌اسپات") {
            try {
                startActivity(android.content.Intent("android.settings.TETHER_SETTINGS"))
            } catch (_: Exception) {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            }
        }
        root.addView(settingsButton)
        root.addView(button("هات‌اسپات روشن شد؛ راه‌اندازی سرور") {
            try {
                startHttpServer()
                val ip = localIpAddress()
                status.text = if (ip == null) "سرور روشن شد اما IP پیدا نشد. مطمئن شو هات‌اسپات فعال است."
                    else "گوشی دوم را به هات‌اسپات وصل کن و این نشانی را وارد کن:\nhttp://$ip:8080"
            } catch (e: Exception) {
                toast("راه‌اندازی سرور ناموفق بود: ${e.message}")
            }
        })
    }

    private fun makeQr(value: String): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 420, 420, hints)
        val pixels = IntArray(420 * 420)
        for (y in 0 until 420) for (x in 0 until 420) {
            pixels[y * 420 + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, 420, 420, Bitmap.Config.RGB_565)
    }

    private fun startHttpServer() {
        if (cameraServer != null) return
        val server = CameraHttpServer(8080) { latestJpeg }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        cameraServer = server
    }

    private fun startCaptureLoop() {
        streaming.set(true)
        val handler = android.os.Handler(mainLooper)
        val task = object : Runnable {
            override fun run() {
                if (!streaming.get()) return
                val capture = imageCapture
                if (capture != null && !captureBusy) {
                    captureBusy = true
                    val output = ImageCapture.OutputFileOptions.Builder(java.io.File(cacheDir, "latest-camera-frame.jpg")).build()
                    capture.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            try { latestJpeg = java.io.File(cacheDir, "latest-camera-frame.jpg").readBytes() } catch (_: Exception) { }
                            runOnUiThread { captureBusy = false }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            runOnUiThread { captureBusy = false }
                        }
                    })
                }
                handler.postDelayed(this, 650)
            }
        }
        handler.post(task)
    }

    private fun showViewer() {
        root = baseLayout()
        root.addView(text("👶 مشاهده تصویر کودک", 23f))
        root.addView(text("ابتدا گوشی را از طریق QR یا تنظیمات Wi-Fi به شبکه گوشی فرستنده وصل کن."))
        val input = EditText(this).apply {
            hint = "http://192.168.43.1:8080"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        root.addView(input)
        root.addView(button("اتصال") {
            val entered = input.text.toString().trim().trimEnd('/')
            if (entered.isBlank()) toast("آدرس گوشی فرستنده را وارد کن.")
            else {
                val url = if (entered.startsWith("http://") || entered.startsWith("https://")) entered else "http://$entered"
                showWebViewer(url)
            }
        })
        root.addView(button("بازگشت") { showHome() })
        setContentView(root)
    }

    private fun showWebViewer(url: String) {
        root = baseLayout()
        root.addView(text("تصویر زنده کودک", 22f))
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            webViewClient = WebViewClient()
            loadUrl("$url/viewer")
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(button("قطع اتصال") { web.stopLoading(); showViewer() })
        setContentView(root)
    }

    private fun localIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                .sortedBy { if (it.name.contains("wlan", true) || it.name.contains("ap", true)) 0 else 1 }
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address && !addr.isLinkLocalAddress) return addr.hostAddress
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun stopSender() {
        streaming.set(false)
        try { cameraServer?.stop() } catch (_: Exception) { }
        cameraServer = null
        if (Build.VERSION.SDK_INT >= 26) {
            try { (hotspotReservation as? WifiManager.LocalOnlyHotspotReservation)?.close() } catch (_: Exception) { }
        }
        hotspotReservation = null
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        imageCapture = null
        latestJpeg = null
        fallbackHotspotMode = false
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        stopSender()
        executor.shutdown()
        super.onDestroy()
    }
}

private class CameraHttpServer(port: Int, private val frameProvider: () -> ByteArray?) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response = when (session.uri) {
        "/" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "Baby Monitor Camera server is running. Open /viewer on the parent phone.")
        "/viewer" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
            """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;background:#111;display:flex;align-items:center;justify-content:center;height:100vh}img{width:100%;height:auto;max-height:100vh;object-fit:contain}</style></head><body><img src="/stream" /></body></html>""")
        "/stream" -> streamResponse()
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
    }

    private fun streamResponse(): Response {
        val input = java.io.PipedInputStream(64 * 1024)
        val output = java.io.PipedOutputStream(input)
        val worker = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val frame = frameProvider()
                    if (frame != null) {
                        output.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray())
                        output.write(frame)
                        output.write("\r\n".toByteArray())
                        output.flush()
                    }
                    Thread.sleep(120)
                }
            } catch (_: Exception) {
            } finally { try { output.close() } catch (_: Exception) { } }
        }
        worker.isDaemon = true
        worker.start()
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", input)
    }
}
