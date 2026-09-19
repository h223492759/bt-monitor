package com.lhj.btmonitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * 常驻前台服务：周期采样 + 承接状态事件。
 *
 * 设计要点：
 *  - 采样行里带 `up`（服务自身已运行秒数）→ 若日志出现 unexpectedly 的大跳跃，说明服务被杀/被冻结，
 *    这是「监控本身是否可信」的判据，不要忽略。
 *  - 采样行里带 `btUp`（本次蓝牙 ON 已持续秒数）→ 直接对应"能撑多久"的分析。
 *  - `SUSPECT_CRASH` 行 = 蓝牙从 ON 掉下来、但系统设置里仍然"想开"，即非用户操作的异常关闭。
 */
class MonitorService : Service() {

    companion object {
        const val CH_ID = "btmonitor_run"
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.lhj.btmonitor.action.START"
        const val ACTION_STOP = "com.lhj.btmonitor.action.STOP"
        const val ACTION_SAMPLE = "com.lhj.btmonitor.action.SAMPLE"
        const val ACTION_INTERVAL = "com.lhj.btmonitor.action.INTERVAL"
        const val EXTRA_INTERVAL = "interval_sec"

        @Volatile var running = false
        @Volatile var startedAt = 0L
        @Volatile var lastHbLine = ""

        fun start(ctx: Context, intervalSec: Int) {
            val i = Intent(ctx, MonitorService::class.java)
            i.action = ACTION_START
            i.putExtra(EXTRA_INTERVAL, intervalSec)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (t: Throwable) {
                // Android 12+ 后台启动前台服务会被拒（ForegroundServiceStartNotAllowedException）。
                // 这里静默失败是刻意的：不能因为拉不起服务就把调用方（广播接收器）带崩。
            }
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java)
            i.action = ACTION_STOP
            try {
                ctx.startService(i)
            } catch (t: Throwable) {
            }
        }

        fun sampleNow(ctx: Context) {
            val i = Intent(ctx, MonitorService::class.java)
            i.action = ACTION_SAMPLE
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (t: Throwable) {
            }
        }

        fun setInterval(ctx: Context, sec: Int) {
            val i = Intent(ctx, MonitorService::class.java)
            i.action = ACTION_INTERVAL
            i.putExtra(EXTRA_INTERVAL, sec)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (t: Throwable) {
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var intervalMs = 60_000L
    private var foregrounded = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var cmRef: ConnectivityManager? = null

    private val ticker = object : Runnable {
        override fun run() {
            doSample("tick")
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                doSample("before_stop")
                // 打上「用户主动停止」标记：避免状态事件广播把服务又自动拉起来
                Snap.prefs(this).edit().putBoolean("user_stopped", true).apply()
                teardown()
                return START_NOT_STICKY
            }
            ACTION_SAMPLE -> {
                ensureForeground()
                doSample("manual")
                return START_STICKY
            }
            ACTION_INTERVAL -> {
                val v = intent.getIntExtra(EXTRA_INTERVAL, 60).coerceIn(10, 3600)
                intervalMs = v * 1000L
                Snap.prefs(this).edit().putInt("interval", v).apply()
                ensureForeground()
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, intervalMs)
                LogStore.append(this, Snap.event("service", "interval=" + v + "s"))
                updateNotification()
                return START_STICKY
            }
            else -> {
                val iv = intent?.getIntExtra(EXTRA_INTERVAL, -1) ?: -1
                val v = if (iv > 0) iv else Snap.prefs(this).getInt("interval", 60)
                intervalMs = v.coerceIn(10, 3600) * 1000L
                ensureForeground()
                if (!running) {
                    running = true
                    startedAt = System.currentTimeMillis()
                    Snap.prefs(this).edit().putBoolean("user_stopped", false).apply()
                    LogStore.append(
                        this,
                        Snap.event(
                            "service",
                            "START interval=" + (intervalMs / 1000) + "s android=" +
                                Build.VERSION.RELEASE + "(API " + Build.VERSION.SDK_INT + ")"
                        )
                    )
                    doSample("startup")
                    registerNetCallback()
                    Snap.prefs(this).edit().putBoolean("svc_running", true).apply()
                }
                handler.removeCallbacks(ticker)
                handler.postDelayed(ticker, intervalMs)
                return START_STICKY
            }
        }
    }

    private fun doSample(tag: String) {
        try {
            val line = Snap.buildHb(this, tag)
            LogStore.append(this, line)
            lastHbLine = line
            // 「想开却没开」的遗留异常态没有状态跃迁可捕获，只能靠采样发现
            for (l in Snap.checkStuckOff(this)) LogStore.append(this, l)
        } catch (t: Throwable) {
        }
        Snap.prefs(this).edit().putLong("last_tick", System.currentTimeMillis()).apply()
        updateNotification()
    }

    private fun ensureForeground() {
        if (foregrounded) return
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
            foregrounded = true
        } catch (t: Throwable) {
            // 极端情况下（例如无通知权限的旧系统）退化为普通服务，功能不受影响
            try {
                startForeground(NOTIF_ID, n)
                foregrounded = true
            } catch (t2: Throwable) {
            }
        }
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (t: Throwable) {
        }
    }

    private fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val st = Snap.btName(Snap.adapterState(this))
        val up = if (startedAt > 0) (System.currentTimeMillis() - startedAt) / 1000L else 0L
        val crash = Snap.crashCount(this)
        val fe = Snap.failEnableCount(this)
        val tryN = Snap.tryCount(this)
        val okN = Snap.onCount(this)
        val text = "蓝牙=" + st + " · 尝试" + tryN + "成功" + okN +
            " · 崩溃" + crash + "(启用" + fe + ") · " + fmtDur(up)
        return NotificationCompat.Builder(this, CH_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("蓝牙监控运行中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .build()
    }

    private fun fmtDur(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        return if (h > 0) (h.toString() + "h" + m + "m") else (m.toString() + "m")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH_ID) == null) {
                val ch = NotificationChannel(
                    CH_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.description = getString(R.string.notif_channel_desc)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
        } catch (t: Throwable) {
        }
    }

    /** 蜂窝/网络承载变化：静态广播在 Android 7+ 收不到，必须在服务里回调 */
    private fun registerNetCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cmRef = cm
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    LogStore.append(this@MonitorService, Snap.event("net", "onAvailable"))
                    doSample("net_up")
                }

                override fun onLost(network: Network) {
                    LogStore.append(this@MonitorService, Snap.event("net", "onLost"))
                    doSample("net_down")
                }

                override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) {
                    val t = when {
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                        caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                        else -> "OTHER"
                    }
                    LogStore.append(this@MonitorService, Snap.event("net", "caps=" + t))
                }
            }
            netCallback = cb
            cm.registerDefaultNetworkCallback(cb)
        } catch (t: Throwable) {
        }
    }

    private fun unregisterNetCallback() {
        try {
            val cb = netCallback
            val cm = cmRef
            if (cb != null && cm != null) cm.unregisterNetworkCallback(cb)
        } catch (t: Throwable) {
        }
        netCallback = null
        cmRef = null
    }

    private fun teardown() {
        try {
            LogStore.append(this, Snap.event("service", "STOP by user"))
        } catch (t: Throwable) {
        }
        running = false
        Snap.prefs(this).edit().putBoolean("svc_running", false).apply()
        handler.removeCallbacks(ticker)
        unregisterNetCallback()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (t: Throwable) {
        }
        foregrounded = false
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        unregisterNetCallback()
        running = false
        super.onDestroy()
    }
}
