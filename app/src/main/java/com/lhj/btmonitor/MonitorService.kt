package com.lhj.btmonitor

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
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
        /** 看门狗专用：与 ACTION_SAMPLE 分开，避免"健康时也被强行采样"污染 60s 节奏 */
        const val ACTION_WATCHDOG = "com.lhj.btmonitor.action.WATCHDOG"
        const val EXTRA_INTERVAL = "interval_sec"

        /** 看门狗闹钟的 PendingIntent 请求码（与通知 id 复用值无所谓，语义不同） */
        private const val WATCHDOG_REQ = 2001

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

    /** 服务内动态注册的系统状态广播接收器（飞行模式/屏幕/电源/Wi-Fi） */
    private var sysReceiver: BroadcastReceiver? = null

    private val ticker = object : Runnable {
        override fun run() {
            doSample("tick")
            handler.postDelayed(this, intervalMs)
            scheduleWatchdog()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 进程重启后 intervalMs 会退回类默认值 → 每次进来先按 intent/pref 对齐，
        // 否则看门狗、START 日志、ticker 节奏都会用错间隔。
        val ivExtra = intent?.getIntExtra(EXTRA_INTERVAL, -1) ?: -1
        intervalMs = (if (ivExtra > 0) ivExtra else Snap.prefs(this).getInt("interval", 60))
            .coerceIn(10, 3600) * 1000L
        when (intent?.action) {
            ACTION_STOP -> {
                doSample("before_stop")
                // 打上「用户主动停止」标记：避免状态事件广播把服务又自动拉起来
                Snap.prefs(this).edit().putBoolean("user_stopped", true).apply()
                teardown()
                return START_NOT_STICKY
            }
            ACTION_SAMPLE -> {
                // ❗这里必须走 ensureRunning()。
                // 旧版这个分支只 ensureForeground + doSample，**不排 ticker**，
                // 于是「升级/进程被杀后由 App 的采样入口把服务拉起来」会得到一个
                // 「通知在、进程在、却一条数据都不写」的空壳服务 —— 2026-09-19 实测踩到：
                // 装完 v1.0.3 后日志凭空断了 39 分钟，而通知还写着"运行中"。
                ensureForeground()
                if (!ensureRunning()) doSample("manual")
                kickTicker()
                return START_STICKY
            }
            ACTION_WATCHDOG -> {
                // 兜底巡检。**健康时一行日志都不写**（否则每 3 分钟多一行，破坏 60s 采样节奏）；
                // 只有真死了（服务没在跑 / ticker 早就停了）才动手恢复并补一拍。
                ensureForeground()
                val last = Snap.prefs(this).getLong("last_tick", 0L)
                val stale = System.currentTimeMillis() - last > intervalMs * 2L + 20_000L
                val started = ensureRunning()
                if (started || stale) {
                    // ensureRunning() 成功时已经写过一条 startup 采样，不重复写
                    if (!started) doSample("watchdog")
                    kickTicker()
                } else {
                    scheduleWatchdog()
                }
                return START_STICKY
            }
            ACTION_INTERVAL -> {
                val v = intent.getIntExtra(EXTRA_INTERVAL, 60).coerceIn(10, 3600)
                intervalMs = v * 1000L
                Snap.prefs(this).edit().putInt("interval", v).apply()
                ensureForeground()
                ensureRunning()
                kickTicker()
                LogStore.append(this, Snap.event("service", "interval=" + v + "s"))
                updateNotification()
                return START_STICKY
            }
            else -> {
                val iv = intent?.getIntExtra(EXTRA_INTERVAL, -1) ?: -1
                val v = if (iv > 0) iv else Snap.prefs(this).getInt("interval", 60)
                intervalMs = v.coerceIn(10, 3600) * 1000L
                ensureForeground()
                ensureRunning()
                kickTicker()
                return START_STICKY
            }
        }
    }

    /**
     * 进入「运行态」。幂等：已在运行则直接返回 false。
     *
     * ⚠️ 所有非 STOP 分支都必须调用它。曾经只有 `else`（ACTION_START / null intent）
     * 分支做初始化，导致其它入口只能得到一个「半启动」的服务（见 ACTION_SAMPLE 注释）。
     *
     * @return true = 本次调用真正完成了启动初始化
     */
    private fun ensureRunning(): Boolean {
        if (running) return false
        running = true
        startedAt = System.currentTimeMillis()
        Snap.prefs(this).edit().putBoolean("user_stopped", false).apply()
        // 旧版把一次适配器重启记了两遍 → 历史计数虚高一倍，这里做一次性的整除回正
        Snap.migrateCounts(this)
        LogStore.append(
            this,
            Snap.event(
                "service",
                "START interval=" + (intervalMs / 1000) + "s android=" +
                    Build.VERSION.RELEASE + "(API " + Build.VERSION.SDK_INT + ") ver=" +
                    BuildConfig.VERSION_NAME
            )
        )
        // 一次性历史回正：减掉旧版漏撤的那笔关机误报（须在 detectReboot 之前跑，
        // 它会把 last_crash_wall 清零，避免随后再撤一次）
        Snap.repairShutdownArtifactFromLog(this)
        doSample("startup")
        registerNetCallback()
        registerSysEvents()
        Snap.prefs(this).edit().putBoolean("svc_running", true).apply()
        return true
    }

    /** 重置采样节奏：取消旧的 ticker 与看门狗，按当前 interval 重排。幂等，可随时调用 */
    private fun kickTicker() {
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, intervalMs)
        scheduleWatchdog()
    }

    /**
     * 看门狗：用 AlarmManager 给 ticker 兜底。
     *
     * Handler 的 ticker 会在两种情况下停：① 设备进 Doze（CPU 睡）② 进程被厂商省电策略杀掉。
     * 这两者都**不产生任何异常日志**，只表现为「日志凭空断掉」—— 对"跑一周"的监控是致命的。
     * 所以额外排一个 setAndAllowWhileIdle 闹钟：即使 ticker 已死，也会被唤醒一次
     * （走 ACTION_SAMPLE → ensureRunning/kickTicker → 自动恢复）。
     *
     * 用 `getForegroundService` 而不是 `getService`：Android 12+ 从后台启动前台服务会被拒，
     * 走 foreground 版本系统才会放行。setAndAllowWhileIdle 不需要 SCHEDULE_EXACT_ALARM 权限。
     */
    private fun scheduleWatchdog() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + intervalMs * 3L,
                watchdogIntent()
            )
        } catch (t: Throwable) {
        }
    }

    private fun cancelWatchdog() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(watchdogIntent())
        } catch (t: Throwable) {
        }
    }

    private fun watchdogIntent(): PendingIntent {
        val i = Intent(this, MonitorService::class.java).setAction(ACTION_WATCHDOG)
        return PendingIntent.getForegroundService(
            this, WATCHDOG_REQ, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun doSample(tag: String) {
        try {
            // 先做重启自证：关机广播拿不到，只能用「墙钟流逝 - 系统运行时长流逝」把重启算出来
            for (l in Snap.detectReboot(this)) LogStore.append(this, l)
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
            " · 自重启" + crash + "(启用" + fe + ") · " + fmtDur(up)
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
                    // ❗去重：同一次网络状态下系统会反复回调（实测两天刷了 3884 行，占全部日志 97%）。
                    // 改为**只在承载真的变化时写一行**，并带上「上一个承载持续了多久」，
                    // 频率信息不丢、噪声基本清零。
                    val p = Snap.prefs(this@MonitorService)
                    val prev = p.getString("last_caps", "") ?: ""
                    if (prev == t) return
                    val nowMs = System.currentTimeMillis()
                    val heldMs = nowMs - p.getLong("last_caps_wall", 0L)
                    p.edit().putString("last_caps", t).putLong("last_caps_wall", nowMs).apply()
                    val detail = if (prev.isEmpty()) "caps=" + t
                    else "caps=" + prev + "->" + t + "|held=" + (heldMs / 1000L) + "s"
                    LogStore.append(this@MonitorService, Snap.event("net", detail))
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

    /**
     * 在服务里**动态注册**状态广播 —— 这是本工程最容易被忽略的一条通道。
     *
     * 为什么必须动态注册：这些 action 在 Manifest 里静态注册是**收不到**的。
     * 实测（2026-09-19~20 两天日志）：静态注册了 11 个 action，只有「蓝牙 STATE_CHANGED」86 条
     * 和「BOOT_COMPLETED」2 条真正到达；`ACTION_AIRPLANE_MODE_CHANGED` / `ACTION_SHUTDOWN` /
     * `ACTION_POWER_*` / `ACTION_SCREEN_*` / `ACTION_USER_PRESENT` **全部 0 条**。
     * 结果飞行模式只能靠"断网顺带采样"蹭到、关机完全无记录 —— 接收器里写的分支是死代码。
     * （`ACTION_SCREEN_ON/OFF` 从 Android 8 起更是明确只允许动态注册。）
     *
     * 动态注册的接收器活在服务进程里，只要服务在跑就一定能收到。
     *
     * 注：`ACTION_SHUTDOWN` / `ACTION_REBOOT` 也在这里动态注册 —— 它们是**关机误报的第一现场**
     * （系统关机时会关掉蓝牙适配器，而 `bluetooth_on` 设置值仍是 1），拿到广播就能当场撤销那笔误报，
     * 不必等下次开机靠 `elapsedRealtime` 复位反推。
     */
    private fun registerSysEvents() {
        if (sysReceiver != null) return
        try {
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    if (i == null) return
                    try {
                        when (i.action) {
                            Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                                val on = if (i.hasExtra("state")) i.getBooleanExtra("state", false)
                                else Snap.airplane(this@MonitorService) == 1
                                LogStore.append(
                                    this@MonitorService,
                                    Snap.event(
                                        "airplane",
                                        "state=" + (if (on) 1 else 0) + "|bt=" +
                                            Snap.btName(Snap.adapterState(this@MonitorService))
                                    )
                                )
                            }
                            Intent.ACTION_SCREEN_ON -> LogStore.append(
                                this@MonitorService, Snap.event("screen", "ON")
                            )
                            Intent.ACTION_SCREEN_OFF -> LogStore.append(
                                this@MonitorService, Snap.event("screen", "OFF")
                            )
                            Intent.ACTION_USER_PRESENT -> LogStore.append(
                                this@MonitorService, Snap.event("screen", "UNLOCK")
                            )
                            Intent.ACTION_POWER_CONNECTED -> LogStore.append(
                                this@MonitorService, Snap.event("power", "PLUGGED")
                            )
                            Intent.ACTION_POWER_DISCONNECTED -> LogStore.append(
                                this@MonitorService, Snap.event("power", "UNPLUGGED")
                            )
                            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                                val cur = i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                                val prev = i.getIntExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, -1)
                                LogStore.append(
                                    this@MonitorService,
                                    Snap.event("wifi", Snap.wifiName(prev) + "->" + Snap.wifiName(cur))
                                )
                            }
                            // 关机/重启：这是**关机误报的第一现场** —— 系统此刻会关掉蓝牙适配器，
                            // 而 bluetooth_on 设置值仍是 1，等适配器掉下来就会被记成「非人为关闭」。
                            // 动态注册能收到（静态注册实测 0 条）；拿到它就能**当场**撤销、不用等下次开机反推。
                            // 另注：ACTION_SHUTDOWN 是**有序广播**，onReceive 里要尽快返回，别做重活。
                            Intent.ACTION_SHUTDOWN, Intent.ACTION_REBOOT -> {
                                val reb = i.action == Intent.ACTION_REBOOT
                                val n = Snap.revokeShutdownArtifact(
                                    this@MonitorService,
                                    System.currentTimeMillis(),
                                    if (reb) "系统重启" else "系统关机"
                                )
                                LogStore.append(
                                    this@MonitorService,
                                    Snap.event(
                                        "shutdown",
                                        (if (reb) "reboot" else "shutdown") + " 系统正在关闭设备|bt=" +
                                            Snap.btName(Snap.adapterState(this@MonitorService)) +
                                            "|" + Snap.statsLine(this@MonitorService)
                                    )
                                )
                            }
                        }
                    } catch (t: Throwable) {
                    }
                }
            }
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(Intent.ACTION_SHUTDOWN)
                addAction(Intent.ACTION_REBOOT)
            }
            // Android 13+ 动态注册要求显式声明是否对外导出；这些全是系统广播，用 NOT_EXPORTED。
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(r, f)
            }
            sysReceiver = r
        } catch (t: Throwable) {
        }
    }

    private fun unregisterSysEvents() {
        try {
            val r = sysReceiver
            if (r != null) unregisterReceiver(r)
        } catch (t: Throwable) {
        }
        sysReceiver = null
    }

    private fun teardown() {
        try {
            LogStore.append(this, Snap.event("service", "STOP by user"))
        } catch (t: Throwable) {
        }
        running = false
        Snap.prefs(this).edit().putBoolean("svc_running", false).apply()
        handler.removeCallbacks(ticker)
        cancelWatchdog()
        unregisterNetCallback()
        unregisterSysEvents()
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
        // ⚠️ 这里**故意不** cancelWatchdog()：进程被厂商杀掉时 onDestroy 可能都不走；
        // 而真走到了（例如 stopped by system），留着闹钟反而能把它叫回来。
        // 用户主动停止走 teardown()，那里会明确取消。
        unregisterNetCallback()
        unregisterSysEvents()
        running = false
        super.onDestroy()
    }
}
