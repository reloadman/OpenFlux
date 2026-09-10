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

        private const val SOCKS_ADDR = "127.0.0.1:1080"
        private const val DNS_SERVER = "1.1.1.1"
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
                startTunnel(url)
            }
        }
        return START_STICKY
    }

    private fun startTunnel(url: String) {
        try {
            startForeground(1, buildNotification())

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
                .addDnsServer(DNS_SERVER)

            // SOCKS5 в OpenFlux принимает только CONNECT, то есть UDP через
            // туннель не ходит, а DNS работает именно по UDP. Поэтому в туннель
            // отправляем весь интернет, КРОМЕ адреса DNS-сервера: иначе запросы
            // уходят в никуда и домены перестают резолвиться.
            // excludeRoute() умеет только Android 13+, поэтому строим маршруты
            // вручную — так работает на любой версии.
            for (route in routesExcluding(DNS_SERVER)) {
                builder.addRoute(route.first, route.second)
            }
            builder.addDisallowedApplication(packageName)

            val descriptor = builder.establish()
            if (descriptor == null) {
                report(false, "Не удалось поднять VPN-интерфейс")
                shutdown()
                return
            }
            tun = descriptor

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
        try { Openfluxmobile.stop() } catch (_: Throwable) {}
        try { tunnelProcess?.destroy() } catch (_: Throwable) {}
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

    // Разбивает 0.0.0.0/0 на префиксы так, чтобы указанный адрес в них не попал.
    private fun routesExcluding(excluded: String): List<Pair<String, Int>> {
        val target = excluded.split(".").map { it.toInt() }
        var addr = (target[0].toLong() shl 24) or (target[1].toLong() shl 16) or
                   (target[2].toLong() shl 8) or target[3].toLong()
        val routes = ArrayList<Pair<String, Int>>(32)
        for (prefix in 32 downTo 1) {
            // на каждом шаге берём соседний блок того же размера — вместе они
            // покрывают всё пространство, кроме исключаемого адреса
            val sibling = addr xor (1L shl (32 - prefix))
            val masked = sibling and (-1L shl (32 - prefix)) and 0xFFFFFFFFL
            routes.add(Pair(longToIp(masked), prefix))
            addr = addr and (-1L shl (32 - prefix)) and 0xFFFFFFFFL
        }
        return routes
    }

    private fun longToIp(value: Long): String =
        "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}"

    private fun pumpLogs(process: Process) {
        Thread {
            try {
                var lastSent = 0L
                process.inputStream.bufferedReader().forEachLine { line ->
                    // Подробный режим сыплет построчным дампом пакетов: если слать
                    // это в интерфейс, он захлёбывается и приложение падает.
                    // Пропускаем дамп и ограничиваем частоту обновлений.
                    if (line.contains(" bytes - TCP") || line.contains("[STATS]")) return@forEachLine
                    val now = System.currentTimeMillis()
                    if (now - lastSent < 400) return@forEachLine
                    lastSent = now
                    report(true, line.take(160))
                }
            } catch (_: Throwable) {}
        }.apply { isDaemon = true; start() }
    }

    private fun report(running: Boolean, message: String) {
        try {
            sendBroadcast(Intent(BROADCAST_STATUS).apply {
                setPackage(packageName)
                putExtra(EXTRA_RUNNING, running)
                putExtra(EXTRA_MESSAGE, message)
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
