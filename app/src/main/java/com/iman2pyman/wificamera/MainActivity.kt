package com.iman2pyman.wificamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
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

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSenderCamera()
        else toast("برای ارسال تصویر، اجازه دسترسی به دوربین لازم است.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "WiFi Camera"
        showHome()
    }

    private fun baseLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.rgb(246, 248, 251))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    private fun text(value: String, size: Float = 16f): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(Color.rgb(30, 41, 59))
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }

    private fun button(value: String, action: () -> Unit): Button =
        Button(this).apply {
            text = value
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun showHome() {
        root = baseLayout()
        root.addView(text("WiFi Camera", 28f))
        root.addView(text("نمایش زنده دوربین یک گوشی روی گوشی دیگر از طریق یک شبکه Wi-Fi مشترک"))
        root.addView(button("ارسال تصویر (گوشی دوربین)") {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startSenderCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        })
        root.addView(button("دریافت تصویر (گوشی بیننده)") { showViewer() })
        root.addView(text("هر دو گوشی باید به یک شبکه Wi-Fi متصل باشند. برای امنیت، از شبکه عمومی استفاده نکنید.", 13f))
        setContentView(root)
    }

    private fun startSenderCamera() {
        root = baseLayout()
        root.addView(text("ارسال زنده دوربین", 23f))
        val preview = PreviewView(this)
        root.addView(preview, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val address = text("در حال آماده‌سازی دوربین و سرور…")
        root.addView(address)
        root.addView(button("توقف ارسال") { stopSender(); showHome() })
        setContentView(root)

        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val previewUseCase = androidx.camera.core.Preview.Builder().build()
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                previewUseCase.setSurfaceProvider(preview.surfaceProvider)
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, imageCapture
                )
                startCaptureLoop()
                val ip = localIpAddress()
                val server = CameraHttpServer(8080) { latestJpeg }
                server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                cameraServer = server
                address.text = if (ip != null) {
                    "آدرس گوشی بیننده را وارد کنید:\nhttp://$ip:8080"
                } else {
                    "سرور روشن شد، اما IP پیدا نشد. اتصال Wi-Fi را بررسی کنید."
                }
            } catch (e: Exception) {
                toast("راه‌اندازی دوربین ناموفق بود: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
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
                    val output = ImageCapture.OutputFileOptions.Builder(
                        java.io.File(cacheDir, "latest-camera-frame.jpg")
                    ).build()
                    capture.takePicture(output, executor, object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                            try {
                                latestJpeg = java.io.File(cacheDir, "latest-camera-frame.jpg").readBytes()
                            } catch (_: Exception) { }
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
        root.addView(text("دریافت تصویر", 23f))
        val input = EditText(this).apply {
            hint = "http://192.168.1.10:8080"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        root.addView(input)
        root.addView(button("اتصال") {
            var url = input.text.toString().trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            if (url.isBlank()) {
                toast("آدرس گوشی فرستنده را وارد کنید.")
            } else {
                showWebViewer(url)
            }
        })
        root.addView(button("بازگشت") { showHome() })
        setContentView(root)
    }

    private fun showWebViewer(url: String) {
        root = baseLayout()
        root.addView(text("تصویر زنده", 22f))
        val web = WebView(this).apply {
            settings.javaScriptEnabled = false
            webViewClient = WebViewClient()
            loadUrl("$url/viewer")
        }
        root.addView(web, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(button("قطع اتصال") { web.stopLoading(); showViewer() })
        setContentView(root)
    }

    private fun localIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                for (addr in Collections.list(intf.inetAddresses)) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) return addr.hostAddress
                }
            }
        } catch (_: Exception) { }
        return null
    }

    private fun stopSender() {
        streaming.set(false)
        try { cameraServer?.stop() } catch (_: Exception) { }
        cameraServer = null
        try {
            ProcessCameraProvider.getInstance(this).get().unbindAll()
        } catch (_: Exception) { }
        imageCapture = null
        latestJpeg = null
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        stopSender()
        executor.shutdown()
        super.onDestroy()
    }
}

private class CameraHttpServer(
    port: Int,
    private val frameProvider: () -> ByteArray?
) : NanoHTTPD(port) {
    override fun serve(session: IHTTPSession): Response {
        return when (session.uri) {
            "/" -> newFixedLengthResponse(
                Response.Status.OK, "text/plain; charset=utf-8",
                "WiFi Camera server is running. Open /viewer on the receiving phone."
            )
            "/viewer" -> newFixedLengthResponse(
                Response.Status.OK, "text/html; charset=utf-8",
                """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;background:#111;display:flex;align-items:center;justify-content:center;height:100vh}img{width:100%;height:auto;max-height:100vh;object-fit:contain}</style></head><body><img src="/stream" /></body></html>"""
            )
            "/stream" -> streamResponse()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }

    private fun streamResponse(): Response {
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
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
            } finally {
                try { output.close() } catch (_: Exception) { }
            }
        }
        worker.isDaemon = true
        worker.start()
        return newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=frame", input)
    }
}
