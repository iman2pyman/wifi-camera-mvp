package com.iman2pyman.wificamera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.wifi.WifiManager
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
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
import java.util.UUID
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioAttributes
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var root: LinearLayout
    private var cameraServer: CameraHttpServer? = null
    private var imageCapture: ImageCapture? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val streaming = AtomicBoolean(false)
    @Volatile private var latestJpeg: ByteArray? = null
    @Volatile private var latestFrameAt: Long = 0L
    private var senderNetworkStatus: TextView? = null
    @Volatile private var captureBusy = false
    private var hotspotReservation: Any? = null
    private var fallbackHotspotMode = false
    @Volatile private var sessionToken: String = UUID.randomUUID().toString().replace("-", "")
    private var ambientRecorder: AudioRecord? = null
    private var ambientThread: Thread? = null
    private var talkRecorder: AudioRecord? = null
    private var talkThread: Thread? = null
    private var talkTrack: AudioTrack? = null
    @Volatile private var latestAudioPcm: ByteArray? = null
    @Volatile private var soundLevel: Double = 0.0
    @Volatile private var pendingParentTalk: Pair<String, String>? = null

    private var scannedSsid: String? = null
    private var scannedPassword: String? = null
    private var scannedUrl: String? = null
    private val qrScanner = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            toast("اسکن QR لغو شد.")
        } else {
            handleScannedQr(contents)
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestWifiPermissionAndStart() else toast("برای ارسال تصویر، اجازه دوربین لازم است.")
    }
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val pending = pendingParentTalk
            if (pending != null) { pendingParentTalk = null; startParentTalk(pending.first, pending.second) }
            else startSoundMonitor()
        } else toast("اجازه میکروفون داده نشد؛ قابلیت‌های صوتی غیرفعال هستند.")
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
        val preview = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 230))
        val status = text("در حال آماده‌سازی دوربین…")
        senderNetworkStatus = status
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
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startSoundMonitor() else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
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
                runOnUiThread {
                    senderNetworkStatus?.text = "ارتباط Wi-Fi قطع شد؛ شبکه اختصاصی متوقف شده است."
                    toast("شبکه محلی متوقف شد. برای ادامه، ارسال را دوباره آغاز کن.")
                }
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
        // Keep the camera preview attached; only append connection details.
        root.addView(text("شبکه اختصاصی آماده است", 20f))
        root.addView(text("در گوشی والدین، برنامه را باز کن و گزینه «اسکن QR اتصال» را بزن.", 14f))
        val qr = ImageView(this)
        val endpoint = if (ip != null) "http://$ip:8080?key=$sessionToken" else ""
        val qrData = "babymonitor://connect?ssid=${Uri.encode(ssid)}&password=${Uri.encode(password)}&url=${Uri.encode(endpoint)}"
        try { qr.setImageBitmap(makeQr(qrData)); root.addView(qr, LinearLayout.LayoutParams(190, 190).apply { gravity = Gravity.CENTER }) } catch (_: Exception) { }
        root.addView(text("نام شبکه: $ssid", 14f))
        root.addView(text(if (password.isBlank()) "رمز: بدون رمز" else "رمز شبکه: $password", 14f))
        root.addView(text(if (ip != null) "نشانی تصویر: http://$ip:8080" else "شبکه فعال است؛ در حال شناسایی نشانی محلی…", 13f))
        senderNetworkStatus?.text = "شبکه آماده است؛ منتظر اتصال گوشی والدین…"
        root.addView(button("توقف ارسال") { stopSender(); showHome() })
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
                    else "گوشی دوم را به هات‌اسپات وصل کن و این نشانی امن را وارد کن:\nhttp://$ip:8080?key=$sessionToken"
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

    private fun startSoundMonitor() {
        if (ambientRecorder != null) return
        try {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            recorder.startRecording(); ambientRecorder = recorder
            ambientThread = Thread {
                val buffer = ByteArray(3200)
                while (ambientRecorder === recorder && !Thread.currentThread().isInterrupted) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        val chunk = buffer.copyOf(n); latestAudioPcm = chunk
                        var sum = 0.0; var count = 0
                        var i = 0; while (i + 1 < n) { val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 255)).toShort().toInt(); sum += sample.toDouble() * sample.toDouble(); count++; i += 2 }
                        soundLevel = if (count > 0) (kotlin.math.sqrt(sum / count) / 32767.0).coerceIn(0.0, 1.0) else 0.0
                    }
                }
            }.apply { isDaemon = true; start() }
        } catch (_: Exception) { soundLevel = 0.0; toast("پایش صدای محیط فعال نشد.") }
    }

    private fun startParentTalk(base: String, key: String) {
        if (talkRecorder != null) return
        try {
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recorder = AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
            recorder.startRecording(); talkRecorder = recorder
            talkThread = Thread {
                val buffer = ByteArray(3200)
                while (talkRecorder === recorder && !Thread.currentThread().isInterrupted) {
                    val n = recorder.read(buffer, 0, buffer.size)
                    if (n > 0) try {
                        val connection = java.net.URL("$base/talk?key=${Uri.encode(key)}").openConnection() as java.net.HttpURLConnection
                        connection.requestMethod = "POST"; connection.doOutput = true; connection.connectTimeout = 2500; connection.readTimeout = 2500
                        connection.setRequestProperty("Content-Type", "application/octet-stream")
                        connection.outputStream.use { it.write(buffer, 0, n) }; connection.inputStream.close(); connection.disconnect()
                    } catch (_: Exception) { }
                }
            }.apply { isDaemon = true; start() }
        } catch (_: Exception) { toast("میکروفون گوشی والدین فعال نشد.") }
    }

    private fun stopParentTalk() {
        val recorder = talkRecorder; talkRecorder = null
        try { recorder?.stop() } catch (_: Exception) { }; try { recorder?.release() } catch (_: Exception) { }
        talkThread?.interrupt(); talkThread = null
    }

    private fun playTalkAudio(bytes: ByteArray) {
        try {
            if (talkTrack == null) {
                val min = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                talkTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(maxOf(min, 6400)).setTransferMode(AudioTrack.MODE_STREAM).build().apply { play() }
            }
            talkTrack?.write(bytes, 0, bytes.size)
        } catch (_: Exception) { }
    }

    private fun currentBatteryPercent(): Int {
        return try {
            val manager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val level = manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (level in 0..100) level else {
                val battery = registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val current = battery?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (current >= 0 && scale > 0) (current * 100) / scale else -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun startHttpServer() {
        if (cameraServer != null) return
        val server = CameraHttpServer(8080, { latestJpeg }, { latestFrameAt }, { currentBatteryPercent() }, { sessionToken }, { soundLevel }, { latestAudioPcm }, { bytes -> playTalkAudio(bytes) })
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
                    // Save through CameraX's JPEG encoder. Copying plane[0] from
                    // an ImageProxy is not a valid JPEG on devices that return YUV.
                    val frameFile = java.io.File(cacheDir, "baby-monitor-frame.jpg")
                    val outputOptions = ImageCapture.OutputFileOptions.Builder(frameFile).build()
                    capture.takePicture(outputOptions, executor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                            try {
                                val bytes = frameFile.readBytes()
                                if (bytes.isNotEmpty()) {
                                    latestJpeg = bytes
                                    latestFrameAt = android.os.SystemClock.elapsedRealtime()
                                    runOnUiThread { senderNetworkStatus?.text = "دوربین آماده است؛ منتظر اتصال گوشی والدین…" }
                                }
                            } catch (_: Exception) {
                            } finally {
                                captureBusy = false
                            }
                        }
                        override fun onError(exception: ImageCaptureException) {
                            captureBusy = false
                            runOnUiThread {
                                senderNetworkStatus?.text = "خطای دریافت تصویر دوربین: ${exception.message}"
                                toast("خطای دریافت تصویر دوربین: ${exception.message}")
                            }
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
        root.addView(text("با دکمه زیر QR نمایش‌داده‌شده روی گوشی کنار کودک را اسکن کن."))
        val input = EditText(this).apply {
            hint = "http://192.168.43.1:8080"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            scannedUrl?.let { setText(it) }
        }
        root.addView(button("اسکن QR اتصال") {
            val options = ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("کد QR گوشی کنار کودک را اسکن کنید")
                .setBeepEnabled(false).setOrientationLocked(false)
            qrScanner.launch(options)
        })
        root.addView(input)
        if (!scannedSsid.isNullOrBlank()) {
            root.addView(text("شبکه اسکن‌شده: $scannedSsid", 15f))
            if (!scannedPassword.isNullOrBlank()) root.addView(text("رمز شبکه: $scannedPassword", 14f))
            root.addView(text("ابتدا در تنظیمات Wi-Fi به شبکه بالا متصل شو و به همین برنامه برگرد.", 13f))
        }
        root.addView(button("بازکردن تنظیمات Wi-Fi") {
            startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
        })
        root.addView(button("نمایش تصویر زنده") {
            val entered = input.text.toString().trim().trimEnd('/')
            if (entered.isBlank()) toast("ابتدا QR را اسکن یا آدرس فرستنده را وارد کن.")
            else {
                val url = if (entered.startsWith("http://") || entered.startsWith("https://")) entered else "http://$entered"
                showWebViewer(url)
            }
        })
        root.addView(button("بازگشت") { showHome() })
        setContentView(root)
    }

    private fun handleScannedQr(contents: String) {
        try {
            val uri = Uri.parse(contents)
            if (uri.scheme != "babymonitor" || uri.host != "connect") {
                toast("این QR مربوط به اتصال دوربین کودک نیست.")
                return
            }
            scannedSsid = uri.getQueryParameter("ssid")
            scannedPassword = uri.getQueryParameter("password")
            scannedUrl = uri.getQueryParameter("url")?.takeIf { it.isNotBlank() }
            showViewer()
            val message = buildString {
                append("شبکه پیدا شد: ${scannedSsid ?: "نامشخص"}")
                if (!scannedPassword.isNullOrBlank()) append("\nرمز Wi-Fi: $scannedPassword")
                append("\nابتدا به همین شبکه وصل شو؛ سپس نمایش تصویر زنده را بزن.")
            }
            toast(message)
        } catch (_: Exception) {
            toast("خواندن QR ناموفق بود.")
        }
    }

    private fun bindProcessToWifiNetwork(): Boolean {
        return try {
            val manager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifi = manager.allNetworks.firstOrNull { network ->
                manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifi == null) false else manager.bindProcessToNetwork(wifi)
        } catch (_: Exception) {
            false
        }
    }

    private inner class TalkBridge {
        @JavascriptInterface fun start(base: String, key: String) {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startParentTalk(base, key)
                else { pendingParentTalk = Pair(base, key); audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
            }
        }
        @JavascriptInterface fun stop() { runOnUiThread { stopParentTalk() } }
    }

    private fun showWebViewer(url: String) {
        val bound = bindProcessToWifiNetwork()
        root = baseLayout()
        root.addView(text("تصویر زنده کودک", 22f))
        root.addView(text(if (bound) "اتصال از مسیر Wi-Fi برقرار می‌شود." else "شبکه Wi-Fi پیدا نشد؛ مطمئن شو گوشی گیرنده به شبکه فرستنده وصل است.", 13f))
        val web = WebView(this).apply {
            addJavascriptInterface(TalkBridge(), "AndroidTalk")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) runOnUiThread {
                        toast("ارتباط با دوربین برقرار نشد. اتصال Wi-Fi و نشانی فرستنده را بررسی کن.")
                    }
                }
            }
            val parsed = Uri.parse(url)
            val base = "${parsed.scheme}://${parsed.authority}"
            val key = parsed.getQueryParameter("key") ?: ""
            loadUrl("$base/viewer?key=${Uri.encode(key)}")
        }
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(button("قطع اتصال") {
            web.stopLoading()
            try { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).bindProcessToNetwork(null) } catch (_: Exception) { }
            showViewer()
        })
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
        sessionToken = UUID.randomUUID().toString().replace("-", "")
        val ambient = ambientRecorder; ambientRecorder = null
        try { ambient?.stop() } catch (_: Exception) { }; try { ambient?.release() } catch (_: Exception) { }
        ambientThread?.interrupt(); ambientThread = null
        stopParentTalk()
        try { talkTrack?.stop() } catch (_: Exception) { }; try { talkTrack?.release() } catch (_: Exception) { }; talkTrack = null
        latestAudioPcm = null; soundLevel = 0.0
        try { cameraServer?.stop() } catch (_: Exception) { }
        cameraServer = null
        if (Build.VERSION.SDK_INT >= 26) {
            try { (hotspotReservation as? WifiManager.LocalOnlyHotspotReservation)?.close() } catch (_: Exception) { }
        }
        hotspotReservation = null
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        imageCapture = null
        latestJpeg = null
        latestFrameAt = 0L
        senderNetworkStatus = null
        fallbackHotspotMode = false
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        stopSender()
        executor.shutdown()
        super.onDestroy()
    }
}

private class CameraHttpServer(
    port: Int,
    private val frameProvider: () -> ByteArray?,
    private val frameTimeProvider: () -> Long,
    private val batteryProvider: () -> Int,
    private val tokenProvider: () -> String,
    private val soundLevelProvider: () -> Double,
    private val audioProvider: () -> ByteArray?,
    private val talkSink: (ByteArray) -> Unit
) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        val supplied = session.parameters["key"]?.firstOrNull()
        if (supplied != tokenProvider()) return newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain; charset=utf-8", "Unauthorized device")
        return when (session.uri) {
        "/" -> newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "Baby Monitor Camera server is running. Open /viewer on the parent phone.")
        "/viewer" -> {
            val pageToken = tokenProvider()
            val html = """<!doctype html><html lang="fa" dir="rtl"><head><meta name="viewport" content="width=device-width,initial-scale=1"><meta charset="utf-8"><style>
            *{box-sizing:border-box}body{margin:0;background:#eff8ff;color:#304863;font-family:sans-serif;padding:12px}
            header{text-align:center;font-size:23px;font-weight:bold;padding:8px}
            #connection{background:#fff;border-radius:16px;padding:12px;margin:8px 0;box-shadow:0 3px 12px #0001}
            .grid{display:grid;grid-template-columns:1fr 1fr;gap:8px}.item{background:#f5f9fc;border-radius:12px;padding:11px;text-align:center}
            .value{display:block;font-size:17px;font-weight:bold;margin-top:5px}.ok{color:#16865b}.warn{color:#bd7b16}.bad{color:#c43d58}
            #state{padding:9px;text-align:center;font-size:14px}#stage{background:#111;border-radius:14px;min-height:48vh;display:flex;align-items:center;justify-content:center;overflow:hidden}
            #live{width:100%;max-height:66vh;object-fit:contain;transition:filter .2s}#hint{text-align:center;font-size:12px;color:#60758b;padding:8px}
            .controls{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}.controls button{flex:1;min-width:42%;border:0;border-radius:12px;padding:12px;background:#ed9ab5;color:#fff;font-weight:bold;font-size:14px}
            #alert{display:none;background:#fff0f2;color:#bd3155;border-radius:12px;padding:10px;text-align:center;font-weight:bold;margin:8px 0}
            </style></head><body><header>👶 دوربین کنترل کودک</header>
            <section id="connection"><div class="grid">
            <div class="item">اتصال گوشی<span id="net" class="value warn">در حال بررسی…</span></div>
            <div class="item">باتری گوشی کودک<span id="battery" class="value">--</span></div>
            <div class="item">وضعیت دوربین<span id="camera" class="value warn">در انتظار</span></div>
            <div class="item">تازگی تصویر<span id="fresh" class="value">--</span></div>
            </div><div id="state">در حال بررسی ارتباط با گوشی فرستنده…</div></section>
            <div id="alert">⚠️ حرکت قابل توجهی در تصویر تشخیص داده شد</div>
            <div id="stage"><img id="live" style="display:none" alt="تصویر زنده کودک"></div>
            <div class="controls"><button id="night">🌙 حالت دید در شب: خاموش</button><button id="motion">🔔 هشدار حرکت: روشن</button></div>
            <div class="controls"><button id="sound">🔊 هشدار صدای بلند / احتمال گریه: روشن</button><button id="listen">👂 شنیدن صدای محیط: خاموش</button></div>
            <div class="controls"><button id="talk">🎙️ صحبت با کودک</button></div>
            <div id="hint">تشخیص حرکت تصویری و صدای بلند به‌صورت محلی انجام می‌شود؛ این هشدار تشخیص پزشکی یا قطعی گریه نیست.<br>اتصال محلی امن · بدون نیاز به اینترنت</div>
            <script>
            const key='__KEY__',img=document.getElementById('live'),state=document.getElementById('state');
            const net=document.getElementById('net'),battery=document.getElementById('battery'),camera=document.getElementById('camera'),fresh=document.getElementById('fresh'),alertBox=document.getElementById('alert');
            let ready=false,night=false,motionOn=true,soundOn=true,previous=null,lastAlert=0;
            function label(el,value,kind){el.textContent=value;el.className='value '+kind}
            function url(path){return path+'?key='+encodeURIComponent(key)+'&t='+Date.now()}
            document.getElementById('night').onclick=()=>{night=!night;img.style.filter=night?'grayscale(1) brightness(1.65) contrast(1.15)':'none';document.getElementById('night').textContent='🌙 حالت دید در شب: '+(night?'روشن':'خاموش')};
            document.getElementById('motion').onclick=()=>{motionOn=!motionOn;document.getElementById('motion').textContent='🔔 هشدار حرکت: '+(motionOn?'روشن':'خاموش')};
            document.getElementById('sound').onclick=()=>{soundOn=!soundOn;document.getElementById('sound').textContent='🔊 هشدار صدای بلند / احتمال گریه: '+(soundOn?'روشن':'خاموش')};
            async function check(){try{const r=await fetch(url('/status'),{cache:'no-store'});if(!r.ok)throw Error('server');const s=await r.json();label(net,'متصل','ok');label(battery,s.batteryPercent>=0?s.batteryPercent+'٪':'نامشخص',s.batteryPercent>=20?'ok':'warn');
            if(s.cameraReady){label(camera,'فعال','ok');const age=s.frameAgeMs||0;label(fresh,age<1500?'روان':age<3000?'قابل قبول':'با تأخیر',age<3000?'ok':'warn');state.textContent='تصویر زنده در حال دریافت است';state.className='ok';img.style.display='block';if(!ready){img.src=url('/snapshot.jpg');ready=true;}}
            else{label(camera,'آماده نیست','warn');label(fresh,'بدون تصویر','warn');state.textContent=s.message||'منتظر آماده‌شدن دوربین…';state.className='warn';img.style.display='none';ready=false;}
            if(soundOn&&s.soundLevel>0.72&&Date.now()-lastAlert>12000){lastAlert=Date.now();alertBox.textContent='🔊 صدای بلند در محیط کودک تشخیص داده شد';alertBox.style.display='block';}
            }catch(e){label(net,'قطع ارتباط','bad');label(camera,'نامشخص','bad');label(fresh,'دریافت نمی‌شود','bad');state.textContent='ارتباط با گوشی فرستنده قطع است؛ Wi-Fi و برنامه را بررسی کنید.';state.className='bad';img.style.display='none';ready=false;label(battery,'در دسترس نیست','bad');}}
            async function detectMotion(){if(!ready||!motionOn||!img.complete||!img.naturalWidth)return;try{const c=document.createElement('canvas'),w=48,h=36;c.width=w;c.height=h;const x=c.getContext('2d',{willReadFrequently:true});x.drawImage(img,0,0,w,h);const data=x.getImageData(0,0,w,h).data;let diff=0;if(previous){for(let i=0;i<data.length;i+=4)diff+=Math.abs(data[i]-previous[i])+Math.abs(data[i+1]-previous[i+1])+Math.abs(data[i+2]-previous[i+2]);const ratio=diff/(w*h*3*255);if(ratio>0.13&&Date.now()-lastAlert>12000){lastAlert=Date.now();alertBox.textContent='⚠️ حرکت قابل توجهی در تصویر تشخیص داده شد';alertBox.style.display='block';if(navigator.vibrate)navigator.vibrate([180,80,180]);}}previous=data;}catch(e){}}
            let listening=false,talking=false,audioContext=null;
            document.getElementById('listen').onclick=async()=>{listening=!listening;document.getElementById('listen').textContent='👂 شنیدن صدای محیط: '+(listening?'روشن':'خاموش');if(listening){try{audioContext=new (window.AudioContext||window.webkitAudioContext)({sampleRate:16000});await audioContext.resume();}catch(e){listening=false;alert('پخش صدا در این گوشی پشتیبانی نشد.');}}else if(audioContext){await audioContext.close();audioContext=null;}};
            async function playAmbient(){if(!listening||!audioContext)return;try{const r=await fetch(url('/ambient'),{cache:'no-store'});if(!r.ok)return;const b=await r.arrayBuffer();if(!b.byteLength)return;const pcm=new Int16Array(b),buf=audioContext.createBuffer(1,pcm.length,16000),ch=buf.getChannelData(0);for(let i=0;i<pcm.length;i++)ch[i]=pcm[i]/32768;const src=audioContext.createBufferSource();src.buffer=buf;src.connect(audioContext.destination);src.start();}catch(e){}}
            document.getElementById('talk').onclick=()=>{talking=!talking;document.getElementById('talk').textContent=talking?'⏹ توقف مکالمه':'🎙️ صحبت با کودک';if(talking){try{window.AndroidTalk.start(window.location.protocol+'//'+window.location.host,key);}catch(e){talking=false;alert('فعال‌سازی میکروفون ممکن نشد.');}}else{try{window.AndroidTalk.stop();}catch(e){}}};
            check();setInterval(check,1500);setInterval(()=>{if(ready)img.src=url('/snapshot.jpg')},900);setInterval(detectMotion,1200);setInterval(playAmbient,120);
            </script></body></html>""".replace("__KEY__", pageToken)
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }
        "/status" -> {
            val frameAt = frameTimeProvider()
            val age = if (frameAt == 0L) Long.MAX_VALUE else android.os.SystemClock.elapsedRealtime() - frameAt
            val ready = frameProvider() != null && age < 5000L
            val message = if (ready) "تصویر زنده برقرار است" else if (frameAt == 0L) "شبکه و سرور وصل‌اند؛ هنوز اولین تصویر دوربین دریافت نشده است." else "گوشی وصل است، اما ارسال تصویر متوقف یا کند شده است."
            val battery = batteryProvider()
            val sound = soundLevelProvider()
            val json = """{"server":"online","cameraReady":$ready,"frameAgeMs":${if (age == Long.MAX_VALUE) -1 else age},"batteryPercent":$battery,"soundLevel":$sound,"message":"$message"}"""
            newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", json)
        }
        "/ambient" -> {
            val audio = audioProvider()
            if (audio == null) newFixedLengthResponse(Response.Status.NO_CONTENT, "application/octet-stream", "")
            else newFixedLengthResponse(Response.Status.OK, "application/octet-stream", java.io.ByteArrayInputStream(audio), audio.size.toLong())
        }
        "/talk" -> {
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                val path = files["postData"]
                if (path != null) talkSink(java.io.File(path).readBytes())
                newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
            } catch (_: Exception) {
                newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "invalid audio")
            }
        }
        "/snapshot.jpg" -> {
            val frame = frameProvider()
            if (frame == null) newFixedLengthResponse(Response.Status.SERVICE_UNAVAILABLE, "text/plain", "Camera is starting")
            else newFixedLengthResponse(Response.Status.OK, "image/jpeg", java.io.ByteArrayInputStream(frame), frame.size.toLong())
        }
        "/stream" -> streamResponse()
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
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
