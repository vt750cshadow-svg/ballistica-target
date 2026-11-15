package ru.ballistica.target

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.camera2.CameraCharacteristics
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
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
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private var auto = false
    private var camera: Camera? = null
    private var lensInfos: List<CameraInfo> = emptyList()
    private var currentLens = 0
    private var baseGray: Detector.Gray? = null

    private val bg = Color.parseColor("#0D1117")
    private val fg = Color.parseColor("#E6EDF3")
    private val dim = Color.parseColor("#93A1AE")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (getSharedPreferences("morda", MODE_PRIVATE).getString("role", null)) {
            "cam" -> showCamera()
            "ctl" -> showControl()
            "one" -> showSingle()
            else -> showChooser()
        }
    }

    private fun setRole(r: String) {
        auto = false
        getSharedPreferences("morda", MODE_PRIVATE).edit().putString("role", r).apply()
        when (r) {
            "cam" -> showCamera()
            "ctl" -> showControl()
            "one" -> showSingle()
            else -> showChooser()
        }
    }

    // ---------------------------------------------------------------- мелочи UI

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun base(): LinearLayout {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(bg)
        root.setPadding(dp(16), dp(18), dp(16), dp(24))
        return root
    }

    private fun text(s: CharSequence, size: Float = 15f, color: Int = fg): TextView {
        val t = TextView(this)
        t.text = s
        t.setTextColor(color)
        t.textSize = size
        return t
    }

    private fun button(label: String, wide: Boolean = true, action: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 14f
        b.isAllCaps = false
        b.setOnClickListener { action() }
        if (wide) b.layoutParams = LinearLayout.LayoutParams(-1, dp(52))
        return b
    }

    // ---------------------------------------------------------------- выбор роли

    private fun showChooser() {
        val root = base()
        root.gravity = Gravity.CENTER_HORIZONTAL
        root.addView(text("Мишень", 28f))
        root.addView(text("Чем будет этот телефон", 14f, dim))
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(28)))
        root.addView(button("Камера у мишени") { setRole("cam") })
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        root.addView(button("Пульт: смотрю и считаю") { setRole("ctl") })
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(10)))
        root.addView(button("Всё на одном телефоне") { setRole("one") })
        root.addView(View(this), LinearLayout.LayoutParams(1, dp(16)))
        root.addView(
            text(
                "«Всё на одном» — для проверки без второго телефона: камера, разбор и результат на этом же экране.",
                12.5f, dim
            )
        )
        setContentView(root)
    }

    // ---------------------------------------------------------------- камера

    @OptIn(ExperimentalCamera2Interop::class)
    private fun focalOf(info: CameraInfo): Float {
        return try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?.firstOrNull() ?: 0f
        } catch (e: Exception) {
            0f
        }
    }

    private fun selectorFor(info: CameraInfo): CameraSelector =
        CameraSelector.Builder().addCameraFilter { list -> list.filter { it == info } }.build()

    private fun startCamera(
        pv: PreviewView,
        info: TextView? = null,
        onReady: (Camera, ImageCapture) -> Unit = { _, _ -> }
    ) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 7)
            info?.text = "разреши доступ к камере"
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val back = provider.availableCameraInfos.filter {
                    try {
                        CameraSelector.DEFAULT_BACK_CAMERA.filter(listOf(it)).isNotEmpty()
                    } catch (e: Exception) {
                        false
                    }
                }.sortedBy { focalOf(it) }
                if (back.isEmpty()) {
                    info?.text = "задней камеры нет"
                    return@addListener
                }
                lensInfos = back
                currentLens = currentLens.coerceIn(0, back.size - 1)

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(pv.surfaceProvider)
                val cap = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, selectorFor(back[currentLens]), preview, cap)
                CameraNode.attach(this, cap)
                onReady(camera!!, cap)
            } catch (e: Exception) {
                info?.text = "камера не поднялась: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun switchLens(index: Int, pv: PreviewView, info: TextView, onReady: (Camera, ImageCapture) -> Unit) {
        if (index !in lensInfos.indices) return
        currentLens = index
        startCamera(pv, info, onReady)
    }

    private fun lensRow(pv: PreviewView, info: TextView, onReady: (Camera, ImageCapture) -> Unit): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val buttons = ArrayList<Button>()
        val baseF = if (lensInfos.isNotEmpty()) focalOf(lensInfos[0]) else 0f
        lensInfos.forEachIndexed { i, ci ->
            val f = focalOf(ci)
            val label = if (f > 0 && baseF > 0) {
                val r = f / baseF
                if (r >= 1.95) "${r.roundToInt()}×" else "0,5×"
            } else "модуль ${i + 1}"
            val b = Button(this)
            b.text = label
            b.textSize = 13f
            b.isAllCaps = false
            b.setOnClickListener {
                switchLens(i, pv, info, onReady)
                buttons.forEachIndexed { j, bb -> bb.alpha = if (j == i) 1f else 0.55f }
            }
            buttons.add(b)
            row.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        buttons.forEachIndexed { j, bb -> bb.alpha = if (j == currentLens) 1f else 0.55f }
        return row
    }

    private fun zoomRow(): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val label = text("зум 1,0×", 12.5f, dim)
        val bar = SeekBar(this)
        bar.max = 100
        bar.progress = 0
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val cam = camera ?: return
                val maxZ = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                val ratio = 1f + (maxZ - 1f) * (progress / 100f)
                cam.cameraControl.setZoomRatio(ratio)
                label.text = "зум ${"%.1f".format(ratio)}× (макс ${"%.1f".format(maxZ)}×)"
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        box.addView(label)
        box.addView(bar)
        return box
    }

    private fun showCamera() {
        auto = false
        val root = base()
        val pv = PreviewView(this)
        root.addView(pv, LinearLayout.LayoutParams(-1, dp(330)))
        val info = text("запускаю камеру…", 13f, dim)
        val ready: (Camera, ImageCapture) -> Unit = { _, _ ->
            CameraNode.startServer(8080)
            info.text = "адрес: http://${localIp()}:8080\nснимков отдано: ${CameraNode.shots}"
            lifecycleScope.launch {
                while (true) {
                    delay(1000)
                    info.text = "адрес: http://${localIp()}:8080\nснимков отдано: ${CameraNode.shots}"
                }
            }
        }
        root.addView(info)
        root.addView(lensRow(pv, info, ready))
        root.addView(zoomRow())
        root.addView(button("Сменить роль") { setRole("") })
        setContentView(root)
        startCamera(pv, info, ready)
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
        return "нет сети"
    }

    // ---------------------------------------------------------------- всё на одном

    private fun showSingle() {
        auto = false
        val root = base()
        val pv = PreviewView(this)
        root.addView(pv, LinearLayout.LayoutParams(-1, dp(280)))

        val result = ImageView(this)
        result.adjustViewBounds = true
        result.setBackgroundColor(Color.BLACK)
        result.visibility = View.GONE
        root.addView(result, LinearLayout.LayoutParams(-1, dp(250)))

        val status = text("наведи на мишень и нажми «Запомнить мишень»", 12.5f, dim)
        root.addView(status)

        val ready: (Camera, ImageCapture) -> Unit = { _, _ -> status.text = "камера готова" }
        root.addView(lensRow(pv, status, ready))
        root.addView(zoomRow())

        val row = LinearLayout(this)
        row.addView(
            button("Запомнить мишень", false) { rememberLocal(status) },
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        row.addView(
            button("Снять", false) { shotLocal(status, result) },
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        root.addView(row)
        root.addView(button("Сменить роль") { setRole("") })

        setContentView(root)
        startCamera(pv, status, ready)
    }

    private fun rememberLocal(status: TextView) {
        lifecycleScope.launch {
            status.text = "снимаю 5 кадров…"
            try {
                val frames = ArrayList<Detector.Gray>()
                repeat(5) {
                    frames.add(Detector.gray(decode(CameraNode.takeJpeg())))
                    delay(250)
                }
                baseGray = Detector.medianOf(frames)
                status.text = "база запомнена: ${baseGray!!.w}×${baseGray!!.h}"
            } catch (e: Exception) {
                status.text = "не вышло: ${e.message}"
            }
        }
    }

    private fun shotLocal(status: TextView, view: ImageView) {
        val base = baseGray
        if (base == null) {
            status.text = "сначала запомни мишень"
            return
        }
        lifecycleScope.launch {
            try {
                val bmp = decode(CameraNode.takeJpeg())
                val scaled = Bitmap.createScaledBitmap(bmp, base.w, base.h, true)
                val res = Detector.analyze(base, Detector.gray(scaled))
                status.text = describe(res)
                view.visibility = View.VISIBLE
                view.setImageBitmap(drawOverlay(scaled, res))
            } catch (e: Exception) {
                status.text = "ошибка: ${e.message}"
            }
        }
    }

    // ---------------------------------------------------------------- пульт

    private fun showControl() {
        auto = false
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

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        inner.addView(picture, LinearLayout.LayoutParams(-1, dp(300)))
        inner.addView(status)

        val row1 = LinearLayout(this)
        row1.addView(
            button("Запомнить мишень", false) { rememberBase(url.text.toString(), status) },
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        row1.addView(
            button("Снять и разобрать", false) { doShot(url.text.toString(), status, picture) },
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        inner.addView(row1)

        val row2 = LinearLayout(this)
        val autoBtn = button("Авто: выкл", false) { }
        autoBtn.setOnClickListener {
            auto = !auto
            autoBtn.text = "Авто: " + if (auto) "вкл" else "выкл"
            if (auto) startAuto(url.text.toString(), status, picture)
        }
        row2.addView(autoBtn, LinearLayout.LayoutParams(0, dp(52), 1f))
        row2.addView(
            button("Сменить роль", false) { setRole("") },
            LinearLayout.LayoutParams(0, dp(52), 1f)
        )
        inner.addView(row2)

        val scroll = ScrollView(this)
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
                    frames.add(Detector.gray(fetchBitmap(url)))
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
                val res = Detector.analyze(base, Detector.gray(scaled))
                status.text = describe(res)
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

    // ---------------------------------------------------------------- общее

    private fun describe(res: Detector.Result): String = buildString {
        append("новых: ${res.count}")
        if (res.count > 0) {
            append(" · группа ${"%.1f".format(res.groupMm)} мм")
            res.moa?.let { append(" · ${"%.2f".format(it)} MOA") }
            res.offX?.let { append("\nСТП ${"%+.1f".format(it)} / ${"%+.1f".format(res.offY ?: 0.0)} мм") }
        }
        append("\nсдвиг ${res.shiftX}, ${res.shiftY} px")
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
