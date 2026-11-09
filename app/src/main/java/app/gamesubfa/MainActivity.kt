package app.gamesubfa

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : Activity() {

    private val langs = linkedMapOf(
        "English" to TranslateLanguage.ENGLISH,
        "日本語 (Japanese)" to TranslateLanguage.JAPANESE,
        "한국어 (Korean)" to TranslateLanguage.KOREAN,
        "中文 (Chinese)" to TranslateLanguage.CHINESE,
        "Español" to TranslateLanguage.SPANISH,
        "Français" to TranslateLanguage.FRENCH,
        "Deutsch" to TranslateLanguage.GERMAN,
        "Italiano" to TranslateLanguage.ITALIAN,
        "Português" to TranslateLanguage.PORTUGUESE,
        "Türkçe" to TranslateLanguage.TURKISH,
    )

    private lateinit var spinner: Spinner
    private lateinit var status: TextView
    private val reqCapture = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(TextView(this).apply { text = "مترجم زیرنویس بازی"; textSize = 24f })
        root.addView(TextView(this).apply { text = "\nزبان زیرنویس بازی:" })

        spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                langs.keys.toList()
            )
        }
        root.addView(spinner)

        root.addView(Button(this).apply {
            text = "شروع"
            setOnClickListener { start() }
        })
        status = TextView(this).apply { text = "" }
        root.addView(status)

        setContentView(root)
    }

    private fun selectedLang(): String = langs.values.toList()[spinner.selectedItemPosition]

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "اجازه «نمایش روی برنامه‌های دیگر» را بده و برگرد", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }

        status.text = "\nدر حال آماده‌سازی مدل ترجمه (بار اول دانلود می‌شود)..."
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(selectedLang())
                .setTargetLanguage(TranslateLanguage.PERSIAN)
                .build()
        )
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                translator.close()
                status.text = ""
                val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
            }
            .addOnFailureListener {
                translator.close()
                status.text = "\nدانلود مدل ناموفق بود. اینترنت (یا VPN) را بررسی کن."
            }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, SubtitleService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
                .putExtra("src", selectedLang())
            startForegroundService(i)
            moveTaskToBack(true)
        }
    }
}
