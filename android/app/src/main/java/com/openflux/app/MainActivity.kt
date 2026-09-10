package com.openflux.app

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var toggleButton: Button
    private lateinit var logText: TextView

    private var running = false
    private val logLines = ArrayDeque<String>()

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val isRunning = intent?.getBooleanExtra(OpenFluxVpnService.EXTRA_RUNNING, false) ?: false
            val message = intent?.getStringExtra(OpenFluxVpnService.EXTRA_MESSAGE).orEmpty()
            if (message.isNotBlank()) {
                logLines.addLast(message)
                while (logLines.size > 40) logLines.removeFirst()
                logText.text = logLines.joinToString("\n")
                detailText.text = message
            }
            render(isRunning)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        detailText = findViewById(R.id.detailText)
        toggleButton = findViewById(R.id.toggleButton)
        logText = findViewById(R.id.logText)

        urlInput.setText(prefs().getString("url", "").orEmpty())
        toggleButton.setOnClickListener { onToggle() }
        render(false)
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
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
    }

    private fun onToggle() {
        if (running) {
            startService(Intent(this, OpenFluxVpnService::class.java).apply {
                action = OpenFluxVpnService.ACTION_STOP
            })
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
        statusText.text = if (isRunning) "Работает" else "Остановлен"
        toggleButton.text = if (isRunning) "ВЫКЛЮЧИТЬ" else "ВКЛЮЧИТЬ"
        urlInput.isEnabled = !isRunning
    }

    private fun prefs() = getSharedPreferences("openflux", Context.MODE_PRIVATE)
}
