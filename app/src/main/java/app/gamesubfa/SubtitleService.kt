package app.gamesubfa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.DisplayMetrics
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Cover(val rect: RectF, val bg: Int, val layout: StaticLayout, val padX: Float)

class CoverView(ctx: Context) : View(ctx) {
    @Volatile var items: List<Cover> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        for (i in items) {
            paint.color = i.bg
            c.drawRoundRect(i.rect, 10f, 10f, paint)
            c.save()
            c.translate(i.rect.left + i.padX, i.rect.top + (i.rect.height() - i.layout.height) / 2f)
            i.layout.draw(c)
            c.restore()
        }
    }
}

class SubtitleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var recognizer: TextRecognizer
    private lateinit var translator: Translator
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private lateinit var cover: CoverView
    private lateinit var params: WindowManager.LayoutParams

    private val worker = HandlerThread("capture").apply { start() }
    private val bg = Handler(worker.looper)
    private val ui = Handler(Looper.getMainLooper())

    private var curW = 0
    private var curH = 0
    private var dpi = 320
    private var lastThumb: IntArray? = null
    private val cache = LruCache<String, String>(800)
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startAsForeground()

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        val src = intent.getStringExtra("src") ?: TranslateLanguage.ENGLISH
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        recognizer = when (src) {
            TranslateLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TranslateLanguage.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            TranslateLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
        translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(TranslateLanguage.PERSIAN)
                .build()
        )

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, ui)
        }

        val (w, h) = screenSize()
        addOverlay(w, h)
        running = true
        bg.post(loop)
        return START_NOT_STICKY
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            try { cycle() } catch (_: Exception) { setCoverVisible(true) }
            bg.postDelayed(this, if (cover.items.isNotEmpty()) COVERED_DELAY else IDLE_DELAY)
        }
    }

    private fun cycle() {
        val (w, h) = screenSize()
        if (w != curW || h != curH) {
            rebuildDisplay(w, h)
            lastThumb = null
            setItems(emptyList())
            return
        }

        val covered = cover.items.isNotEmpty()
        if (covered) {
            setCoverVisible(false)
            Thread.sleep(HIDE_MS)
        }
        val img = reader?.acquireLatestImage()
        val frame: Bitmap? = img?.let { try { toBitmap(it, w) } finally { it.close() } }

        if (frame == null || !changed(frame)) {
            frame?.recycle()
            if (covered) setCoverVisible(true)
            return
        }

        val s = min(1f, OCR_MAX / max(w, h).toFloat())
        val ocrBmp = if (s < 1f) Bitmap.createScaledBitmap(frame, (w * s).toInt(), (h * s).toInt(), true) else frame
        val text = Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBmp, 0)))
        if (ocrBmp !== frame) ocrBmp.recycle()

        class Cand(val rect: Rect, val text: String)
        val cands = ArrayList<Cand>()
        for (b in text.textBlocks) {
            val box = b.boundingBox ?: continue
            val t = b.text.replace("\n", " ").trim()
            if (t.count { it.isLetter() } < 3 || t.any { it.code in 0x0600..0x06FF }) continue
            val r = Rect((box.left / s).toInt(), (box.top / s).toInt(), (box.right / s).toInt(), (box.bottom / s).toInt())
            r.inset(-6, -4)
            r.intersect(0, 0, w, h)
            if (r.width() > 8 && r.height() > 8) cands.add(Cand(r, t))
        }

        val pending = HashMap<String, Task<String>>()
        for (c in cands) if (cache.get(c.text) == null && !pending.containsKey(c.text))
            pending[c.text] = translator.translate(c.text)
        for ((k, task) in pending) runCatching { cache.put(k, Tasks.await(task)) }

        val items = ArrayList<Cover>()
        for (c in cands) {
            val fa = cache.get(c.text) ?: continue
            val bgColor = sampleBg(frame, c.rect)
            val lum = (Color.red(bgColor) * 299 + Color.green(bgColor) * 587 + Color.blue(bgColor) * 114) / 1000
            val fg = if (lum > 140) Color.BLACK else Color.WHITE
            val padX = 8f
            val layout = fit(fa, (c.rect.width() - 2 * padX).toInt(), c.rect.height(), fg)
            items.add(Cover(RectF(c.rect), bgColor, layout, padX))
        }
        frame.recycle()
        setItems(items)
    }

    private fun changed(b: Bitmap): Boolean {
        val s = Bitmap.createScaledBitmap(b, TW, TH, true)
        val px = IntArray(TW * TH)
        s.getPixels(px, 0, TW, 0, 0, TW, TH)
        s.recycle()
        for (i in px.indices) {
            val c = px[i]
            px[i] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
        }
        val old = lastThumb
        lastThumb = px
        if (old == null) return true
        var n = 0
        for (i in px.indices) if (abs(px[i] - old[i]) > 24) n++
        return n >= 6
    }

    private fun sampleBg(bmp: Bitmap, r: Rect): Int {
        var rs = 0L; var gs = 0L; var bs = 0L; var n = 0
        fun px(x: Int, y: Int) {
            if (x in 0 until bmp.width && y in 0 until bmp.height) {
                val c = bmp.getPixel(x, y)
                rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c); n++
            }
        }
        val m = 2
        var x = r.left
        val sx = max(1, r.width() / 16)
        while (x <= r.right) { px(x, r.top - m); px(x, r.bottom + m); x += sx }
        var y = r.top
        val sy = max(1, r.height() / 8)
        while (y <= r.bottom) { px(r.left - m, y); px(r.right + m, y); y += sy }
        if (n == 0) return Color.BLACK
        return Color.rgb((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    private fun fit(text: String, w: Int, h: Int, fg: Int): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; typeface = Typeface.DEFAULT_BOLD }
        var size = min(h * 0.9f, 96f)
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, max(w, 1))
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .build()
            if (layout.height <= h || size <= 10f) break
            size -= 2f
        }
        return layout
    }

    private fun toBitmap(img: Image, w: Int): Bitmap {
        val p = img.planes[0]
        val rw = p.rowStride / p.pixelStride
        val bmp = Bitmap.createBitmap(rw, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(p.buffer)
        if (rw == w) return bmp
        return Bitmap.createBitmap(bmp, 0, 0, w, img.height).also { bmp.recycle() }
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        dpi = dm.densityDpi
        return dm.widthPixels to dm.heightPixels
    }

    private fun rebuildDisplay(w: Int, h: Int) {
        reader?.close()
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        reader = r
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay = projection!!.createVirtualDisplay(
                "subtitle-capture", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
            )
        } else {
            vd.resize(w, h, dpi)
            vd.surface = r.surface
        }
        curW = w; curH = h
        ui.post {
            params.width = w; params.height = h
            if (cover.isAttachedToWindow) wm.updateViewLayout(cover, params)
        }
    }

    private fun addOverlay(w: Int, h: Int) {
        cover = CoverView(this)
        params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(cover, params)
    }

    private fun setCoverVisible(v: Boolean) {
        val latch = CountDownLatch(1)
        ui.post {
            cover.visibility = if (v) View.VISIBLE else View.INVISIBLE
            latch.countDown()
        }
        latch.await(200, TimeUnit.MILLISECONDS)
    }

    private fun setItems(list: List<Cover>) {
        ui.post {
            cover.items = list
            cover.visibility = View.VISIBLE
            cover.invalidate()
        }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "مترجم زیرنویس", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0, Intent(this, SubtitleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("مترجم بازی فعال است")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(Notification.Action.Builder(null, "توقف", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        running = false
        bg.removeCallbacksAndMessages(null)
        if (::cover.isInitialized) runCatching { wm.removeView(cover) }
        virtualDisplay?.release()
        projection?.stop()
        reader?.close()
        if (::recognizer.isInitialized) recognizer.close()
        if (::translator.isInitialized) translator.close()
        worker.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "subtitle"
        const val ACTION_STOP = "app.gamesubfa.STOP"
        const val IDLE_DELAY = 120L
        const val COVERED_DELAY = 500L
        const val HIDE_MS = 70L
        const val OCR_MAX = 1600f
        const val TW = 240
        const val TH = 135
    }
}
