package com.midu.tts.legado

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.midu.tts.ui.MainActivity
import com.midu.tts.util.Prefs

/** 前台服务：让本地 HTTP 朗读引擎在 App 退到后台后仍可被 Legado 调用。 */
class LegadoHttpService : Service() {

    companion object {
        const val ACTION_START = "com.midu.tts.legado.START"
        const val ACTION_STOP = "com.midu.tts.legado.STOP"
        private const val CHANNEL = "midu_legado_http"
        private const val NOTIF_ID = 1001

        fun start(c: Context) {
            val i = Intent(c, LegadoHttpService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i)
            else c.startService(i)
        }

        fun stop(c: Context) {
            c.startService(Intent(c, LegadoHttpService::class.java).setAction(ACTION_STOP))
        }
    }

    private var server: LegadoHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            @Suppress("DEPRECATION") stopForeground(true)
            LegadoState.markStopped()
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        val p = Prefs.get(this)
        createChannel()
        val bindAll = p.httpBindAll()
        val port = p.httpPort()
        val addr = if (bindAll) "0.0.0.0" else "127.0.0.1"
        try {
            stopServer()
            server = LegadoHttpServer(this, port, bindAll) { LegadoState.log(it) }.also { it.start() }
            LegadoState.markStarted("$addr:$port")
            startForeground(NOTIF_ID, buildNotification(addr, port))
        } catch (e: Exception) {
            LegadoState.markError("启动失败：${e.message}")
            @Suppress("DEPRECATION") stopForeground(true)
            LegadoState.markStopped()
            stopSelf()
        }
    }

    private fun stopServer() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
    }

    override fun onDestroy() {
        stopServer()
        LegadoState.markStopped()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL, "本地朗读服务", NotificationManager.IMPORTANCE_LOW)
            ch.description = "Legado 等阅读应用通过本机 HTTP 调用离线 TTS"
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(addr: String, port: Int): Notification {
        val flag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE else 0
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), flag)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, LegadoHttpService::class.java).setAction(ACTION_STOP), flag)
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Midu 离线朗读服务")
            .setContentText("http://$addr:$port · Legado 可连接")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stop)
            .setOngoing(true)
            .build()
    }
}
