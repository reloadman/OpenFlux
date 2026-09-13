package com.openflux.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import openfluxmobile.Openfluxmobile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class OpenFluxVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var tunnelProcess: Process? = null
    private val stopping = AtomicBoolean(false)

    companion object {
        const val ACTION_START = "com.openflux.app.START"
        const val ACTION_STOP = "com.openflux.app.STOP"
        const val EXTRA_URL = "url"

        const val BROADCAST_STATUS = "com.openflux.app.STATUS"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_IS_LOG = "isLog"

        private const val SOCKS_ADDR = "127.0.0.1:1080"
        private const val CHANNEL_ID = "openflux"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Остановка ждёт завершения процесса и tun2socks: в главном
                // потоке это подвешивает интерфейс до падения.
                Thread { shutdown() }.apply { isDaemon = true; start() }
                return START_NOT_STICKY
            }
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL).orEmpty()
                if (url.isBlank()) {
                    report(false, "Не задан URL документа")
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Уведомление обязано появиться сразу, иначе система убьёт сервис
                // за то, что он не стал foreground вовремя.
                startForeground(1, buildNotification())
                // Запуск ждёт полторы секунды и читает вывод процесса — в главном
                // потоке это давало «приложение не отвечает».
                Thread { startTunnel(url) }.apply { isDaemon = true; start() }
            }
        }
        return START_STICKY
    }

    private fun startTunnel(url: String) {
        try {
            // Android 10+ запрещает исполнять файлы из каталога данных, поэтому
            // бинарь поставляется как нативная библиотека.
            val binary = File(applicationInfo.nativeLibraryDir, "libopenflux.so")
            if (!binary.canExecute()) {
                report(false, "Бинарь недоступен: ${binary.absolutePath}")
                shutdown()
                return
            }

            tunnelProcess = ProcessBuilder(
                binary.absolutePath, "--client",
                "--url", url,
                "--socks5", SOCKS_ADDR
            ).redirectErrorStream(true).start()

            pumpLogs(tunnelProcess!!)

            Thread.sleep(1500)
            if (tunnelProcess?.isAlive != true) {
                report(false, "Клиент не запустился")
                shutdown()
                return
            }

            val builder = Builder()
                .setSession("OpenFlux")
                .setMtu(1500)
                .addAddress("10.255.0.1", 30)
                .addRoute("0.0.0.0", 0)

            // DNS отдаём внутрь туннеля, а не оператору. При ограничениях его
            // резолвер отвечает только по белому списку, поэтому обычный UDP-запрос
            // вернул бы подделку или молчание. Перехватчик в Go ловит порт 53 и
            // повторяет запрос по TCP (RFC 7766) внутри туннеля, так что «Частный
            // DNS» включать больше не нужно.
            builder.addDnsServer("1.1.1.1")
            builder.addDnsServer("8.8.8.8")

            builder.addDisallowedApplication(packageName)

            val descriptor = builder.establish()
            if (descriptor == null) {
                report(false, "Не удалось поднять VPN-интерфейс")
                shutdown()
                return
            }
            tun = descriptor

            // Go дублирует этот дескриптор и закрывает уже собственную копию,
            // поэтому закрыть свой мы можем безопасно.
            Openfluxmobile.start(descriptor.fd.toLong(), SOCKS_ADDR, "warning")
            report(true, "Туннель активен")
        } catch (e: Throwable) {
            report(false, "Ошибка: ${e.message}")
            shutdown()
        }
    }

    // Останавливаем ровно один раз: раньше выключение звало эту логику дважды
    // (из кнопки и следом из onDestroy), что роняло приложение.
    private fun shutdown() {
        if (!stopping.compareAndSet(false, true)) return

        // Порядок важен: сначала tun2socks отпускает свою копию дескриптора,
        // потом гасим клиента, и только затем закрываем дескриптор сами.
        try { Openfluxmobile.stop() } catch (_: Throwable) {}

        try {
            tunnelProcess?.destroy()
            tunnelProcess?.waitFor()
        } catch (_: Throwable) {}

        try { tun?.close() } catch (_: Throwable) {}

        tunnelProcess = null
        tun = null
        report(false, "Остановлен")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (_: Throwable) {}
        stopSelf()
    }

    private fun pumpLogs(process: Process) {
        Thread {
            try {
                var lastSent = 0L
                process.inputStream.bufferedReader().forEachLine { line ->
                    // Подробный режим сыплет построчным дампом пакетов: если слать
                    // это в интерфейс, он захлёбывается и приложение падает.
                    if (line.contains(" bytes - TCP") || line.contains("[STATS]")) return@forEachLine

                    // Ограничение частоты душило и диагностику: клиент печатает всё
                    // важное одной пачкой за первые миллисекунды, и на экране
                    // оставалась ровно одна строка. Сообщения об ошибках пропускаем
                    // всегда, ограничиваем только рутину.
                    val important = line.contains("failed", ignoreCase = true) ||
                        line.contains("error", ignoreCase = true) ||
                        line.contains("refused", ignoreCase = true) ||
                        line.contains("timeout", ignoreCase = true) ||
                        line.contains("connect", ignoreCase = true)

                    val now = System.currentTimeMillis()
                    if (!important && now - lastSent < 400) return@forEachLine
                    lastSent = now
                    reportLog(line.take(160))
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
    }

    // Строка вывода клиента: попадает только в журнал и не трогает статус.
    private fun reportLog(message: String) {
        broadcast(running = true, message = message, isLog = true)
    }

    private fun report(running: Boolean, message: String) {
        broadcast(running = running, message = message, isLog = false)
    }

    private fun broadcast(running: Boolean, message: String, isLog: Boolean) {
        try {
            sendBroadcast(Intent(BROADCAST_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_RUNNING, running)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_IS_LOG, isLog)
            })
        } catch (_: Throwable) {}
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "OpenFlux", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenFlux")
            .setContentText("Туннель активен")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    override fun onRevoke() {
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }
}
