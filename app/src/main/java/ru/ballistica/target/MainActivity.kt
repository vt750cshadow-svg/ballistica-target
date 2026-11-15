package ru.ballistica.target

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

class MainActivity : AppCompatActivity() {

    private var auto = false

    private val bg = Color.parseColor("#0D1117")
    private val fg = Color.parseColor("#E6EDF3")
    private val dim = Color.parseColor("#93A1AE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val role = getSharedPreferences("morda", MODE_PRIVATE).getString("role", null)
        when (role) {
            "cam" -> showCamera()
            "ctl" -> showControl()
            else -> showChooser()
        }
    }

    private fun setRole(r: String) {
        getSharedPreferences("morda", MODE_PRIVATE).edit().putString("role", r).apply()
        when (r) {
            "cam" -> showCamera()
            "ctl" -> showControl()
            else -> showChooser()
        }
    }

    private fun base(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.setPadding(dp(16), dp(20), dp(16), dp(20))
        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: CharSequence, size: Float = 15f, color: Int = fg): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextColor(color)
        t.textSize = size
        return t
    }

    private fun button(label: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 15f
        b.setOnClickListener { action() }
        return b
    }

    // ------------------------------------------------------------ выбор роли

    private fun showChooser() {
        val root = base()
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(text("Мишень", 26f))
        root.addView(text("Выбери, чем будет этот телефон", 14f, dim))
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(24)))
        root.addView(button("Я камера у мишени") { setRole("cam") }, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(12)))
        root.addView(button("Я пульт (смотрю и считаю)") { setRole("ctl") }, LinearLayout.LayoutParams(-1, dp(64)))
        setContentView(root)
    }

    // ------------------------------------------------------------ роль: камера

    private fun showCamera() {
        val root = base()
        val pv = PreviewView(this)
        root.addView(pv, LinearLayout.LayoutParams(-1, dp(360)))
        val info = text("запускаю камеру…", 13f, dim)
        root.addView(info)
        root.addView(button("Сменить роль") { setRole("") })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7)
            info.text = "разреши доступ к камере и вернись на этот экран"
            setContentView(root)
            return
        }

        setContentView(root)

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(pv.surfaceProvider)
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cap)
                CameraNode.attach(this, cap)
                CameraNode.startServer(8080)
                info.text = "адрес: http://${localIp()}:8080\nснимков отдано: 0"
                lifecycleScope.launch {
                    while (true) {
                        delay(1000)
                        info.text = "адрес: http://${localIp()}:8080\nснимков отдано: ${CameraNode.shots}"
                    }
                }
            } catch (e: Exception) {
                info.text = "камера не поднялась: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun localIp(): String {
        try {
            val ifs = NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val nif = ifs.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) return a.hostAddress ?: "?"
                }
            }
        } catch (_: Exception) {
        }
        return "адрес не найден"
    }

    // ------------------------------------------------------------ роль: пульт

    private var baseGray: Detector.Gray? = null
    private var lastBitmap: Bitmap? = null

    private fun showControl() {
        val root = base()
        val url = EditText(this)
        url.setText("http://192.168.1.50:8080")
        url.textSize = 14f
        root.addView(text("Адрес камеры", 12f, dim))
        root.addView(url)

        val status = text("база не запомнена", 13f, dim)
        val picture = ImageView(this)
        picture.adjustViewBounds = true
        picture.setBackgroundColor(Color.BLACK)

        val scroll = ScrollView(this)
        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.addView(picture, LinearLayout.LayoutParams(-1, dp(300)))
        inner.addView(status)

        val row1 = LinearLayout(this)
        row1.addView(button("Запомнить мишень") { rememberBase(url.text.toString(), status) }, LinearLayout.LayoutParams(0, -2, 1f))
        row1.addView(button("Снять и разобрать") { doShot(url.text.toString(), status, picture) }, LinearLayout.LayoutParams(0, -2, 1f))
        inner.addView(row1)

        val row2 = LinearLayout(this)
        val autoBtn = button("Авто: выкл") { }
        autoBtn.setOnClickListener {
            auto = !auto
            autoBtn.text = "Авто: " + if (auto) "вкл" else "выкл"
            if (auto) startAuto(url.text.toString(), status, picture)
        }
        row2.addView(autoBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(button("Сменить роль") { auto = false; setRole("") }, LinearLayout.LayoutParams(0, -2, 1f))
        inner.addView(row2)

        scroll.addView(inner)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun rememberBase(url: String, status: TextView) {
        lifecycleScope.launch {
            status.text = "снимаю 5 кадров для базы…"
            try {
                val frames = ArrayList<Detector.Gray>()
                repeat(5) {
                    val bmp = fetchBitmap(url)
                    frames.add(Detector.gray(bmp))
                    delay(250)
                }
                baseGray = Detector.medianOf(frames)
                status.text = "база запомнена: ${baseGray!!.w}×${baseGray!!.h}"
            } catch (e: Exception) {
                status.text = "не вышло: ${e.message}"
            }
        }
    }

    private fun doShot(url: String, status: TextView, picture: ImageView, silent: Boolean = false) {
        val base = baseGray
        if (base == null) {
            status.text = "сначала запомни мишень"
            return
        }
        lifecycleScope.launch {
            try {
                val bmp = fetchBitmap(url)
                val scaled = Bitmap.createScaledBitmap(bmp, base.w, base.h, true)
                lastBitmap = scaled
                val g = Detector.gray(scaled)
                val res = Detector.analyze(base, g)
                status.text = buildString {
                    append("новых: ${res.count}")
                    if (res.count > 0) {
                        append(" · группа ${"%.1f".format(res.groupMm)} мм")
                        res.moa?.let { append(" · ${"%.2f".format(it)} MOA") }
                        res.offX?.let { append("\nСТП ${"%+.1f".format(it)} / ${"%+.1f".format(res.offY ?: 0.0)} мм") }
                    }
                    append("\nсдвиг ${res.shiftX}, ${res.shiftY} px")
                }
                picture.setImageBitmap(drawOverlay(scaled, res))
            } catch (e: Exception) {
                if (!silent) status.text = "ошибка: ${e.message}"
            }
        }
    }

    private fun startAuto(url: String, status: TextView, picture: ImageView) {
        lifecycleScope.launch {
            while (auto) {
                doShot(url, status, picture, silent = true)
                delay(2000)
            }
        }
    }

    private fun drawOverlay(bmp: Bitmap, res: Detector.Result): Bitmap {
        val out = bmp.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val p = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#C2410C")
        }
        val g = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            color = Color.parseColor("#12703C")
        }
        res.holes.forEach { h -> c.drawCircle(h.x.toFloat(), h.y.toFloat(), 22f, p) }
        if (res.holes.isNotEmpty()) {
            val cx = res.holes.map { it.x }.average().toFloat()
            val cy = res.holes.map { it.y }.average().toFloat()
            c.drawLine(cx - 30, cy, cx + 30, cy, g)
            c.drawLine(cx, cy - 30, cx, cy + 30, g)
        }
        return out
    }

    private suspend fun fetchBitmap(url: String): Bitmap = withContext(Dispatchers.IO) {
        val conn = URL(url.trim().trimEnd('/') + "/shot.jpg").openConnection() as HttpURLConnection
        conn.connectTimeout = 8000
        conn.readTimeout = 25000
        val bytes = conn.inputStream.use { it.readBytes() }
        conn.disconnect()
        decode(bytes)
    }

    private fun decode(bytes: ByteArray, maxW: Int = 1600): Bitmap {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (o.outWidth / (s * 2) >= maxW) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
            ?: throw IllegalStateException("кадр не декодировался")
        return if (bmp.width > maxW) {
            val k = maxW.toFloat() / bmp.width
            Bitmap.createScaledBitmap(bmp, maxW, (bmp.height * k).toInt(), true)
        } else bmp
    }
}
