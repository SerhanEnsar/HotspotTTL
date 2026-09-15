package com.serhanensar.hotspotttl

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private class Palette(dark: Boolean) {
        val background = if (dark) 0xFF1C1C1E.toInt() else 0xFFF2F2F7.toInt()
        val card = if (dark) 0xFF2C2C2E.toInt() else 0xFFFFFFFF.toInt()
        val text = if (dark) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        val secondary = if (dark) 0xFF98989D.toInt() else 0xFF6E6E73.toInt()
        val green = if (dark) 0xFF30D158.toInt() else 0xFF28A745.toInt()
        val red = if (dark) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
        val neutral = if (dark) 0xFF3A3A3C.toInt() else 0xFFE5E5EA.toInt()
    }

    private lateinit var c: Palette
    private lateinit var icon: ImageView
    private lateinit var subtitle: TextView
    private lateinit var toggle: TextView
    private lateinit var testButton: TextView
    private lateinit var testResult: TextView
    private val values = mutableMapOf<String, TextView>()

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        c = Palette(dark)
        window.decorView.setBackgroundColor(c.background)
        if (!dark && Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
        setContentView(buildUi())
        if (intent.getBooleanExtra(EXTRA_START, false)) requestStart()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false) && !TtlVpnService.isRunning) requestStart()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    @Deprecated("Activity sonucu için platform API'si")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) {
            TtlVpnService.start(this)
            handler.postDelayed({ render() }, 300)
        }
    }

    private fun requestStart() {
        val prepare = VpnService.prepare(this)
        @Suppress("DEPRECATION")
        if (prepare != null) startActivityForResult(prepare, REQUEST_VPN)
        else TtlVpnService.start(this)
        handler.postDelayed({ render() }, 300)
    }

    private fun onToggle() {
        if (TtlVpnService.isRunning) TtlVpnService.stop(this) else requestStart()
        handler.postDelayed({ render() }, 300)
    }

    private fun onTest() {
        testButton.isEnabled = false
        testButton.text = "Test ediliyor…"
        testResult.visibility = View.VISIBLE
        testResult.text = "Bağlanılıyor…"
        thread {
            val result = SelfTest.run()
            runOnUiThread {
                testResult.text = result
                testButton.text = "Test Et"
                testButton.isEnabled = true
            }
        }
    }

    private fun render() {
        val vpn = TtlVpnService.instance
        val on = vpn != null
        icon.imageTintList = ColorStateList.valueOf(if (on) c.green else c.secondary)
        subtitle.text = when {
            on -> "Aktif: TTL ${TtlSocket.TTL}, IPv4 + IPv6"
            TtlVpnService.lastError != null -> "Kapalı: ${TtlVpnService.lastError}"
            else -> "Kapalı"
        }
        toggle.text = if (on) "Kapat" else "Aç"
        toggle.background = rounded(if (on) c.red else c.green, 14f)

        fun set(key: String, text: String, highlight: Boolean = false) {
            values[key]?.apply {
                this.text = text
                setTextColor(if (highlight) c.green else c.text)
                typeface = if (highlight) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        if (vpn != null) {
            set("ttl", "${TtlSocket.TTL}", highlight = true)
            set("tcp", "${vpn.tcpCount}")
            set("udp", "${vpn.udpCount}")
            set("up", formatBytes(vpn.stats.bytesUp.get()))
            set("down", formatBytes(vpn.stats.bytesDown.get()))
            set("dns", vpn.currentDns?.hostAddress ?: "—")
        } else {
            set("ttl", "64 (varsayılan)")
            listOf("tcp", "udp", "up", "down", "dns").forEach { set(it, "—") }
        }
    }

    // ---- arayüz ----

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        icon = ImageView(this).apply { setImageResource(R.drawable.ic_antenna) }
        header.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Hotspot TTL", 24f, c.text, bold = true))
        subtitle = label("Kapalı", 14f, c.secondary)
        titles.addView(subtitle)
        header.addView(titles)
        root.addView(header, matchWidth(bottom = 22))

        toggle = label("Aç", 19f, 0xFFFFFFFF.toInt(), bold = true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { onToggle() }
        }
        root.addView(toggle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(18) })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(c.card, 14f)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        listOf(
            "ttl" to "Giden TTL",
            "tcp" to "TCP bağlantı",
            "udp" to "UDP oturum",
            "up" to "Gönderilen",
            "down" to "Alınan",
            "dns" to "DNS",
        ).forEach { (key, title) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(7), 0, dp(7))
            }
            row.addView(label(title, 16f, c.secondary), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val value = label("—", 16f, c.text).apply { gravity = Gravity.END }
            values[key] = value
            row.addView(value)
            card.addView(row)
        }
        root.addView(card, matchWidth(bottom = 18))

        testButton = label("Test Et", 17f, c.text).apply {
            gravity = Gravity.CENTER
            background = rounded(c.neutral, 12f)
            setOnClickListener { onTest() }
        }
        root.addView(testButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50)).apply { bottomMargin = dp(12) })

        testResult = label("", 14f, c.text).apply {
            typeface = Typeface.MONOSPACE
            background = rounded(c.card, 12f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        root.addView(testResult, matchWidth(bottom = 18))

        root.addView(
            label(
                "1. Diğer telefonun hotspot'una bağlan.\n" +
                    "2. Aç'a bas. İlk seferde Android VPN izni ister.\n" +
                    "3. Hızlı ayarlara \"Hotspot TTL\" kutucuğunu ekleyebilirsin.\n\n" +
                    "Trafik hiçbir sunucuya gitmez. Bağlantılar telefonun içinde TTL 65 ile yeniden açılır, " +
                    "hotspot'tan çıkarken 64 olur. Ping (ICMP) bu yoldan geçmez.",
                13f, c.secondary,
            ),
            matchWidth(),
        )

        val scroll = ScrollView(this).apply {
            addView(root)
            clipToPadding = false
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        return scroll
    }

    private fun label(text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun matchWidth(bottom: Int = 0) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(bottom)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / (1L shl 10).toDouble())
        else -> "$b B"
    }

    companion object {
        const val EXTRA_START = "start"
        private const val REQUEST_VPN = 1
    }
}
