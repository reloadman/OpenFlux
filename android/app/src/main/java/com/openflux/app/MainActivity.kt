package com.openflux.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import openfluxmobile.Openfluxmobile
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var activityText: TextView
    private lateinit var upText: TextView
    private lateinit var downText: TextView
    private lateinit var upRateText: TextView
    private lateinit var downRateText: TextView
    private lateinit var detailText: TextView
    private lateinit var toggleButton: Button
    private lateinit var logText: TextView

    private var running = false
    private val logLines = ArrayDeque<String>()

    private val handler = Handler(Looper.getMainLooper())
    private var lastUp = 0L
    private var lastDown = 0L
    private var idleSeconds = 0

    // Счётчик трафика — единственный честный признак того, что туннель живой:
    // статус «работает» говорит лишь о том, что процесс поднялся.
    private val ticker = object : Runnable {
        override fun run() {
            refreshTraffic()
            handler.postDelayed(this, 1000)
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(OpenFluxVpnService.EXTRA_MESSAGE).orEmpty()
            val isLog = intent?.getBooleanExtra(OpenFluxVpnService.EXTRA_IS_LOG, false) ?: false

            if (message.isNotBlank()) {
                logLines.addLast(message)
                while (logLines.size > 40) logLines.removeFirst()
                logText.text = logLines.joinToString("\n")
            }

            // Строки лога больше не трогают состояние: раньше каждая из них
            // переключала статус, и он мигал.
            if (!isLog) {
                detailText.text = message
                render(intent?.getBooleanExtra(OpenFluxVpnService.EXTRA_RUNNING, false) ?: false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        activityText = findViewById(R.id.activityText)
        upText = findViewById(R.id.upText)
        downText = findViewById(R.id.downText)
        upRateText = findViewById(R.id.upRateText)
        downRateText = findViewById(R.id.downRateText)
        detailText = findViewById(R.id.detailText)
        toggleButton = findViewById(R.id.toggleButton)
        logText = findViewById(R.id.logText)

        urlInput.setText(prefs().getString("url", "").orEmpty())
        toggleButton.setOnClickListener { onToggle() }
        render(isTunnelUp())
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(OpenFluxVpnService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        // Состояние могло измениться, пока экран был свёрнут.
        render(isTunnelUp())
        handler.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        handler.removeCallbacks(ticker)
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun isTunnelUp(): Boolean = try {
        Openfluxmobile.isRunning()
    } catch (_: Throwable) {
        false
    }

    private fun refreshTraffic() {
        if (!running) {
            activityText.text = ""
            return
        }

        val up = try { Openfluxmobile.uploaded() } catch (_: Throwable) { 0L }
        val down = try { Openfluxmobile.downloaded() } catch (_: Throwable) { 0L }

        // Счётчики обнуляются при запуске, поэтому после перезапуска туннеля
        // разница может уйти в минус — тогда просто начинаем отсчёт заново.
        val deltaUp = (up - lastUp).coerceAtLeast(0)
        val deltaDown = (down - lastDown).coerceAtLeast(0)
        lastUp = up
        lastDown = down

        upText.text = formatBytes(up)
        downText.text = formatBytes(down)
        upRateText.text = formatBytes(deltaUp) + "/с"
        downRateText.text = formatBytes(deltaDown) + "/с"

        if (deltaUp + deltaDown > 0) {
            idleSeconds = 0
            activityText.text = "идёт обмен данными"
            activityText.setTextColor(Color.parseColor("#2E7D32"))
        } else {
            idleSeconds++
            if (idleSeconds >= 5) {
                activityText.text = "тишина $idleSeconds с — трафика нет"
                activityText.setTextColor(Color.parseColor("#757575"))
            }
        }
    }

    private fun formatBytes(value: Long): String {
        if (value < 1024) return "$value Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var size = value.toDouble() / 1024
        var index = 0
        while (size >= 1024 && index < units.size - 1) {
            size /= 1024
            index++
        }
        val pattern = if (size >= 100) "%.0f %s" else "%.1f %s"
        return String.format(Locale.getDefault(), pattern, size, units[index])
    }

    private fun onToggle() {
        if (running) {
            toggleButton.isEnabled = false
            detailText.text = "Останавливаю..."
            startService(Intent(this, OpenFluxVpnService::class.java).apply {
                action = OpenFluxVpnService.ACTION_STOP
            })
            // Кнопку возвращаем сами: если сервис умрёт молча, она не должна
            // остаться заблокированной навсегда.
            handler.postDelayed({ toggleButton.isEnabled = true }, 3000)
            return
        }

        val url = urlInput.text.toString().trim()
        if (url.isBlank()) {
            detailText.text = "Укажите URL документа"
            return
        }
        prefs().edit().putString("url", url).apply()

        // Система сама спрашивает разрешение на VPN при первом запуске.
        val intent = VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, 1)
        } else {
            launchTunnel()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            launchTunnel()
        } else if (requestCode == 1) {
            detailText.text = "Разрешение на VPN не выдано"
        }
    }

    private fun launchTunnel() {
        lastUp = 0
        lastDown = 0
        idleSeconds = 0
        val intent = Intent(this, OpenFluxVpnService::class.java).apply {
            action = OpenFluxVpnService.ACTION_START
            putExtra(OpenFluxVpnService.EXTRA_URL, urlInput.text.toString().trim())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        detailText.text = "Запускаю..."
    }

    private fun render(isRunning: Boolean) {
        running = isRunning
        statusText.text = if (isRunning) "Туннель работает" else "Остановлен"
        statusText.setTextColor(
            if (isRunning) Color.parseColor("#2E7D32") else Color.parseColor("#757575")
        )
        toggleButton.text = if (isRunning) "ВЫКЛЮЧИТЬ" else "ВКЛЮЧИТЬ"
        toggleButton.isEnabled = true
        urlInput.isEnabled = !isRunning

        if (!isRunning) {
            upText.text = "0 Б"
            downText.text = "0 Б"
            upRateText.text = "0 Б/с"
            downRateText.text = "0 Б/с"
            activityText.text = ""
            lastUp = 0
            lastDown = 0
            idleSeconds = 0
        }
    }

    private fun prefs() = getSharedPreferences("openflux", Context.MODE_PRIVATE)
}
