package com.lhj.btmonitor

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.provider.Settings
import java.util.Locale

/**
 * 状态采集与文本行构造。所有字段都做成 key=value，方便外部脚本直接解析。
 */
object Snap {

    const val PREF = "btmon"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 枚举名 ----------------

    fun btName(s: Int): String = when (s) {
        BluetoothAdapter.STATE_OFF -> "OFF"
        BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
        BluetoothAdapter.STATE_ON -> "ON"
        BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
        else -> "?" + s
    }

    fun wifiName(s: Int): String = when (s) {
        WifiManager.WIFI_STATE_DISABLED -> "DISABLED"
        WifiManager.WIFI_STATE_DISABLING -> "DISABLING"
        WifiManager.WIFI_STATE_ENABLED -> "ENABLED"
        WifiManager.WIFI_STATE_ENABLING -> "ENABLING"
        else -> "UNKNOWN"
    }

    // ---------------- 单个读取（全部 try 包裹，任何一项失败都不影响整体） ----------------

    fun adapterState(ctx: Context): Int = try {
        BluetoothAdapter.getDefaultAdapter()?.state ?: -1
    } catch (t: Throwable) {
        -1
    }

    fun adapterName(ctx: Context): String = try {
        val n = BluetoothAdapter.getDefaultAdapter()?.name
        if (n.isNullOrEmpty()) "-" else n
    } catch (t: Throwable) {
        "-"
    }

    fun wifiState(ctx: Context): Int = try {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wm.wifiState
    } catch (t: Throwable) {
        -1
    }

    fun settingInt(ctx: Context, key: String): Int = try {
        Settings.Global.getInt(ctx.contentResolver, key, -1)
    } catch (t: Throwable) {
        -1
    }

    fun airplane(ctx: Context): Int = settingInt(ctx, Settings.Global.AIRPLANE_MODE_ON)

    fun btSet(ctx: Context): Int = settingInt(ctx, "bluetooth_on")

    fun wifiSet(ctx: Context): Int = settingInt(ctx, Settings.Global.WIFI_ON)

    // 注意：Settings.Global.MOBILE_DATA 是 @hide 常量，公开 SDK 里不存在（编不过），
    // 所以这里直接用它的实际键名字符串。
    fun mobileDataSet(ctx: Context): Int = settingInt(ctx, "mobile_data")

    /** 返回 [net, cell] —— net 为当前承载，cell 为蜂窝是否可用 */
    fun netInfo(ctx: Context): Array<String> {
        var net = "NONE"
        var cell = "0"
        try {
            val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val active = cm.activeNetwork
            val caps = if (active != null) cm.getNetworkCapabilities(active) else null
            if (caps != null) {
                net = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BT_PAN"
                    else -> "OTHER"
                }
            }
            @Suppress("DEPRECATION")
            val all = cm.allNetworks
            for (n in all) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    cell = "1"
                    break
                }
            }
        } catch (t: Throwable) {
        }
        return arrayOf(net, cell)
    }

    /** 返回 [ssid, rssi] —— 无定位权限时通常为 "?" */
    fun ssidRssi(ctx: Context): Array<String> {
        var ssid = "?"
        var rssi = ""
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val info = wm.connectionInfo
            if (info != null) {
                var s = info.ssid ?: "?"
                if (s == "<unknown ssid>" || s == "0x") s = "?"
                ssid = s.replace("\"", "")
                @Suppress("DEPRECATION")
                val r = info.rssi
                if (r != 0) rssi = r.toString()
            }
        } catch (t: Throwable) {
        }
        return arrayOf(ssid, rssi)
    }

    /** 返回 [电量%, 是否充电 Y/N] */
    fun battery(ctx: Context): Array<String> {
        var lvl = "-1"
        var chg = "N"
        try {
            val i = ctx.applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) lvl = (level * 100 / scale).toString()
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                if (plugged != 0 || st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL) {
                    chg = "Y"
                }
            }
        } catch (t: Throwable) {
        }
        return arrayOf(lvl, chg)
    }

    fun screenState(ctx: Context): String = try {
        val pm = ctx.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isInteractive) "ON" else "OFF"
    } catch (t: Throwable) {
        "?"
    }

    // ---------------- 计数与持续时长（持久化，进程被杀也不丢） ----------------

    fun crashCount(ctx: Context): Int = prefs(ctx).getInt("crash_count", 0)

    /** 其中「启用阶段就失败」的次数（本机最主要的故障形态） */
    fun failEnableCount(ctx: Context): Int = prefs(ctx).getInt("fail_enable_count", 0)

    /** 用户/系统发起开启的次数（进入 TURNING_ON 即计一次） */
    fun tryCount(ctx: Context): Int = prefs(ctx).getInt("try_count", 0)

    /** 成功到达 ON 的次数 */
    fun onCount(ctx: Context): Int = prefs(ctx).getInt("reached_on_count", 0)

    // 注：这里曾有一个 bumpCrash(ctx)，现已删除 —— 计数统一在 onBluetoothChanged 里
    //     与 last_crash_wall / crash_burst 一并写入（同一个 edit 保证原子），
    //     单独再暴露一个"只加计数"的入口只会让人误用、并绕过去重逻辑。

    /**
     * 一次性修正历史计数。
     *
     * 旧版把**同一次**适配器重启记了两遍：`ON->TURNING_OFF` 记一次（mode=WHILE_ON），
     * 紧接着 `TURNING_OFF->OFF` 又记一次（mode=FROM_UNKNOWN，因 mode 不同不受 60s 合并约束）。
     * 实测 2026-09-20 导出日志里 30 条 `SUSPECT_CRASH` 实际只对应 15 次事件，即**崩溃数虚高一倍**。
     *
     * ⚠️ 只修正 `crash_count`，**不要动 `fail_enable_count`**：旧版对它是
     *   `if (mode == "ON_ENABLE") fe += 1` —— 只有 `TURNING_ON->TURNING_OFF/OFF` 那一跃会 +1，
     *   随后的 `TURNING_OFF->OFF`（mode=FROM_UNKNOWN）**不加**。也就是说它从来没有虚高，
     *   对它做整除会把真实值腰斩。
     *
     * （曾经有一版把两者都除了 2，属错误实现 —— 若检测到那个标记就先把 fe 还原回 2 倍。）
     */
    fun migrateCounts(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("count_fixed_v6", false)) return
        val c = p.getInt("crash_count", 0)
        val fe = p.getInt("fail_enable_count", 0)
        val badV5Applied = p.getBoolean("count_fixed_v5", false)
        p.edit()
            .putInt("crash_count", if (badV5Applied) c else c / 2)
            .putInt("fail_enable_count", if (badV5Applied) fe * 2 else fe)
            .putBoolean("count_fixed_v6", true)
            .apply()
    }

    /**
     * 重启自证 —— **完全不依赖任何广播**。
     *
     * 原理：**主判据是 `elapsedRealtime` 是否被复位**。它是单调钟，只计设备真正在运行的时间，
     * **只有重启会把它清零**，所以 `elNow < elPrev` 就是「两次采样之间设备重启过」的充分证据。
     *
     * ⚠️ 不能只看「墙钟流逝 − 系统运行时长流逝」（下称 gap）：NTP 校时、手动改时间、切时区都会
     *    让它 > 10s，于是把**校时误判成重启**，进而错误地撤销一次**真实的**蓝牙崩溃。
     *    所以这里改成：只有 elapsedRealtime 复位才认定重启；墙钟被调整时另记 `CLOCK_JUMP`
     *    并且**不撤销任何计数**。
     *
     * 为什么需要它：荣耀不投递 `ACTION_SHUTDOWN`（实测静态注册 0 条），关机在日志里毫无痕迹；
     * 而关机时系统会关掉蓝牙适配器、`bluetooth_on` 设置值却仍是 1，于是**关机本身会被误记成一次
     * 「非人为关闭」**（实测 2026-09-20 12:21 那次重启平白多了 1 笔，且带出一个假的 170s 中断）。
     * 这里在重启后的第一拍把它自证出来，并把那次误报撤销掉。
     *
     * 注：重启时 gap 的数值不能当成「关机时长」看 —— elapsedRealtime 归零会让它混入
     * 「上次开机后已运行的时长」。所以日志里如实报「距上次采样」与「本次开机已运行」两个量，
     * 不用一个含义模糊的合并值。
     */
    fun detectReboot(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val p = prefs(ctx)
        val wallNow = System.currentTimeMillis()
        val elNow = SystemClock.elapsedRealtime()
        val wallPrev = p.getLong("wall_ref", 0L)
        val elPrev = p.getLong("el_ref", 0L)
        p.edit().putLong("wall_ref", wallNow).putLong("el_ref", elNow).apply()
        if (wallPrev <= 0L || elPrev <= 0L) return out   // 首次建立基线，不算重启

        if (elNow >= elPrev) {
            // 运行时长连续增长 ⇒ 设备没重启。墙钟若被明显调整，记一行说明（不撤销任何计数）。
            val jump = (wallNow - wallPrev) - (elNow - elPrev)
            if (jump >= 10_000L || jump <= -10_000L) {
                out.add(event("CLOCK_JUMP", "墙钟被调整 约=" + (jump / 1000L) + "s（非重启，不撤销计数）"))
            }
            return out
        }

        // —— 到这里 = elapsedRealtime 被复位，确实重启过 ——
        // 撤销重启造成的误报（判据见 revokeShutdownArtifact 的注释）。
        val revoked = revokeShutdownArtifact(ctx, wallPrev, "关机/重启")
        p.edit().putLong("stuck_since", 0L).putBoolean("stuck_reported", false).apply()
        out.add(
            event(
                "REBOOT_DETECTED",
                "设备曾关机/重启 距上次采样=" + ((wallNow - wallPrev) / 1000L) +
                    "s 本次开机已运行=" + (elNow / 1000L) + "s" +
                    (if (revoked > 0) "|已撤销" + revoked + "次由重启造成的误报崩溃" else "")
            )
        )
        return out
    }

    /**
     * 关机/重启广播与随之而来的「掉线」之间允许的最大间隔。
     * 实测 2026-09-20 22:41 那次只差 **4ms**（广播 31.183，掉线 31.187），10 秒绰绰有余。
     */
    private const val SHUTDOWN_GRACE_MS = 10_000L

    /**
     * 标一个「刚刚收到关机/重启广播」的时间窗。
     *
     * 为什么必须有它：**系统关蓝牙** 和 **发 shutdown 广播** 是两条**互相独立**的通道，
     * 谁先谁后是随机的 —— 实测 2026-09-20 22:41:31.183 广播先到，31.187 掉线后到（差 4ms）。
     * 只在「广播到达时撤销**已有的**那笔」是**单向**的：广播先到时它必然扑空（那一刻还没有崩溃可撤），
     * 于是那笔误报要一直等到**下次开机**靠单调钟自证才被反推回来 —— 计数在
     * 「关机 → 下次开机」这段窗口里一直是错的（实测 1.0.6 就是这样，差 3 分 20 秒）。
     *
     * 修法：广播一到就落这个标记，**崩溃记完立刻回查**（见 onBluetoothChanged）。谁先到都能覆盖。
     * 同一类错误在本项目出现过两次（上一次是「撤销窗口只判单侧」），教训一致：
     * **两个独立通道之间的时序假设，必须双向缝合，不能只写一个方向。**
     */
    fun markShutdownWindow(ctx: Context) {
        prefs(ctx).edit().putLong("shutdown_marker", System.currentTimeMillis()).apply()
    }

    /**
     * 撤销「关机造成的误报崩溃」。关机广播（ACTION_SHUTDOWN/ACTION_REBOOT）与重启自证**共用**此判据。
     *
     * 背景：关机/重启时系统会先关掉蓝牙适配器，而那一刻 `Settings.Global.bluetooth_on` **仍是 1**，
     * 于是这次掉线被 onBluetoothChanged 记成一笔「非人为关闭」，凭空多 1 笔崩溃。
     *
     * 判据（三者必须同时成立）：
     *  ① **之后再没恢复过**（`last_crash_recovered` = false）。真崩溃系统会自己把适配器重启回来
     *     （实测形态 ON→TURNING_OFF→OFF→TURNING_ON→ON，全程 1~3 秒）；关机则永远回不来。
     *  ② 它紧贴上一次采样：`lastCrash >= lastSampleWall - grace`，grace = 2 × 采样周期。
     *     ❗窗口必须**两侧都放宽**。实测 2026-09-20 22:10 那次：crash 22:10:02.309 →
     *     最后一拍 22:10:03.753 → 设备断电 —— **crash 落在最后一拍「之前」1.4 秒**。
     *     旧版只判 `lastCrash >= 上一拍`（单侧、只看"之后"），于是漏撤、计数永久多 1。
     *  ③ 与当前时刻相距 < 10 分钟（防墙钟被 NTP 调整造成的时间乱序）。
     *
     * ⚠️ 残留误差（刻意取舍）：若**真崩溃后适配器一直没起来**（卡死），且用户在 2 个采样周期内
     *    就重启了设备，这笔真崩溃会被一起撤掉（少记 1）。两害相权取其轻 ——
     *    宁可偶尔少记一笔"卡死不恢复"的极端情况，也不要**每次关机都平白多记一笔**。
     *    撤销动作一律落 `EV|COUNT_ADJUSTED` 到日志，可回查、可审计、不静默。
     *
     * @param lastSampleWall 上一次采样（tick/net/watchdog/startup 任一来源）的墙钟毫秒
     * @param why 写日志用的原因词（"关机/重启" / "系统关机" 等）
     * @param out 非空时，把 `COUNT_ADJUSTED` 行**交回调用方**而不是直接落盘。
     *            用于「崩溃刚记下、立刻回查撤销」的场合：撤销行必须排在 `SUSPECT_CRASH` **之后**，
     *            直接写会和还没落盘的崩溃行**顺序颠倒**，导出后读起来像"先撤了再记"。
     * @return 实际撤销的笔数（0 或 1）
     */
    fun revokeShutdownArtifact(
        ctx: Context,
        lastSampleWall: Long,
        why: String,
        out: MutableList<String>? = null
    ): Int {
        val p = prefs(ctx)
        val lastCrash = p.getLong("last_crash_wall", 0L)
        if (lastCrash <= 0L) return 0
        // 默认 true：老数据没有这个标记时**不撤销**（宁可漏撤，不可错撤）
        if (p.getBoolean("last_crash_recovered", true)) return 0
        val grace = p.getInt("interval", 60).coerceIn(10, 3600) * 1000L * 2
        if (lastCrash < lastSampleWall - grace) return 0
        val now = System.currentTimeMillis()
        if (now - lastCrash > 10L * 60_000L || lastCrash - now > 10L * 60_000L) return 0
        val c = p.getInt("crash_count", 0)
        if (c <= 0) return 0
        p.edit().putInt("crash_count", c - 1).putLong("last_crash_wall", 0L)
            // 这次关机的使命已完成，标记一并清掉（避免 10 秒窗口内误撤后续的真崩溃）
            .putLong("shutdown_marker", 0L)
            .apply()
        val line = event(
            "COUNT_ADJUSTED",
            "撤销1笔由" + why + "造成的误报崩溃 崩溃=" + c + "->" + (c - 1) +
                "（该笔之后再无适配器恢复）"
        )
        if (out != null) out.add(line) else LogStore.append(ctx, line)
        return 1
    }

    /**
     * 一次性历史回正：把**旧版漏撤**的那笔关机误报从计数里减掉。
     *
     * 为什么不能只靠 revokeShutdownArtifact：它是**当场**执行的；一旦当时没命中
     * （例如 1.0.5 的单侧窗口漏判），事后 prefs 里只剩「最后一笔崩溃」这点残迹，
     * 已经分不清它是关机误报还是真崩溃。所以这里**回到日志里找证据**：
     *   ① 找到最后一处 `EV|REBOOT_DETECTED`，用它的「距上次采样=Ns」反算出上一次采样时刻；
     *   ② 往前找最近一笔 `EV|SUSPECT_CRASH`，要求它与该时刻相差 ≤ 180 秒（= 就在断电前那一瞬）；
     *   ③ 两者之间**没有任何恢复**（无 `bt_ready`、无 `->ON` / `->TURNING_ON`）；
     *   ④ 它记录的 `崩溃=N` 恰好等于**当前** crash_count（说明此后没再产生新崩溃）。
     * 四条同时成立才判定为关机误报，减 1 并落一行 `EV|COUNT_ADJUSTED` 供审计。
     *
     * 只跑一次（`count_fixed_v7`）；找不到证据就**什么都不做** —— 不回正、不猜测。
     */
    fun repairShutdownArtifactFromLog(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("count_fixed_v7", false)) return
        p.edit().putBoolean("count_fixed_v7", true).apply()
        val cur = p.getInt("crash_count", 0)
        if (cur <= 0) return
        try {
            val files = LogStore.listDayFiles(ctx).takeLast(3)
            if (files.isEmpty()) return
            val rows = ArrayList<Pair<Long, String>>()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            for (f in files) {
                val day = f.name.removePrefix("btmon-").removeSuffix(".txt")
                val text = try {
                    f.readText(Charsets.UTF_8)
                } catch (t: Throwable) {
                    continue
                }
                for (ln in text.split('\n')) {
                    if (ln.length < 13 || ln[2] != ':' || ln[5] != ':') continue
                    val ms = try {
                        fmt.parse(day + " " + ln.substring(0, 12))?.time
                    } catch (t: Throwable) {
                        null
                    }
                    if (ms == null) continue
                    rows.add(Pair(ms, ln.substring(13)))
                }
            }
            if (rows.size < 3) return
            rows.sortBy { it.first }
            val rb = rows.indexOfLast { it.second.startsWith("EV|REBOOT_DETECTED|") }
            if (rb <= 0) return
            val gapM = Regex("距上次采样=(\\d+)s").find(rows[rb].second) ?: return
            val lastSample = rows[rb].first - gapM.groupValues[1].toLong() * 1000L
            var ci = -1
            for (k in rb - 1 downTo 0) {
                if (rows[k].second.startsWith("EV|SUSPECT_CRASH|")) {
                    ci = k
                    break
                }
            }
            if (ci < 0) return
            val crashMs = rows[ci].first
            val cntM = Regex("崩溃=(\\d+)").find(rows[ci].second) ?: return
            if (cntM.groupValues[1].toInt() != cur) return
            if (Math.abs(lastSample - crashMs) > 180_000L) return
            for (k in ci + 1 until rb) {
                val b = rows[k].second
                if (b.startsWith("EV|bt_ready|")) return
                if (b.startsWith("EV|bt_adapter|") &&
                    (b.contains("->ON/") || b.contains("->TURNING_ON/"))
                ) return
            }
            val c = cur - 1
            p.edit().putInt("crash_count", c).putLong("last_crash_wall", 0L).apply()
            LogStore.append(
                ctx,
                event(
                    "COUNT_ADJUSTED",
                    "历史回正：撤销1笔关机误报崩溃 崩溃=" + cur + "->" + c +
                        "（该笔之后无任何适配器恢复且紧接关机）"
                )
            )
        } catch (t: Throwable) {
            // 回正失败绝不影响监控主流程
        }
    }

    /** 一行汇总，方便导出时只看这一条就够 */
    fun statsLine(ctx: Context): String {
        val p = prefs(ctx)
        return "尝试=" + p.getInt("try_count", 0) +
            " 成功=" + p.getInt("reached_on_count", 0) +
            " 崩溃=" + p.getInt("crash_count", 0) +
            "(启用阶段=" + p.getInt("fail_enable_count", 0) + ")"
    }

    /** 依据当前蓝牙态维护「本次 ON 起始时刻」，返回已持续秒数（-1 = 当前不在 ON） */
    fun btUptime(ctx: Context, state: Int): Long {
        val p = prefs(ctx)
        val since = p.getLong("bt_on_since", 0L)
        val now = System.currentTimeMillis()
        if (state == BluetoothAdapter.STATE_ON) {
            if (since <= 0L) {
                p.edit().putLong("bt_on_since", now).apply()
                return 0L
            }
            return (now - since) / 1000L
        } else {
            if (since > 0L) p.edit().putLong("bt_on_since", 0L).apply()
            return -1L
        }
    }

    private fun clean(s: String): String =
        s.replace("|", "/").replace("\n", " ").replace("\r", " ").trim()

    // ---------------- 行构造 ----------------

    /** 周期采样行 */
    fun buildHb(ctx: Context, tag: String): String {
        val st = adapterState(ctx)
        val bt = btName(st)
        val btUp = btUptime(ctx, st)
        val net = netInfo(ctx)
        val sr = ssidRssi(ctx)
        val bat = battery(ctx)
        val svcUp = if (MonitorService.startedAt > 0) (System.currentTimeMillis() - MonitorService.startedAt) / 1000L else -1L
        return buildString {
            append("HB|src=").append(tag)
            append("|bt=").append(bt)
            append("|btSet=").append(btSet(ctx))
            append("|btUp=").append(btUp)
            append("|btName=").append(clean(adapterName(ctx)))
            append("|ap=").append(airplane(ctx))
            append("|wifi=").append(wifiName(wifiState(ctx)))
            append("|wifiSet=").append(wifiSet(ctx))
            append("|ssid=").append(clean(sr[0]))
            append("|rssi=").append(sr[1])
            append("|cell=").append(net[1])
            append("|net=").append(net[0])
            append("|mdata=").append(mobileDataSet(ctx))
            append("|scr=").append(screenState(ctx))
            append("|batt=").append(bat[0])
            append("|chg=").append(bat[1])
            append("|up=").append(svcUp)
            append("|crash=").append(crashCount(ctx))
        }
    }

    /** 通用事件行 */
    fun event(source: String, detail: String): String = "EV|" + clean(source) + "|" + clean(detail)

    /**
     * 蓝牙状态变化 → 事件行。
     *
     * ⚠️ 关键：判定「非人为关闭」只看一个条件 —— **掉下来了、但系统期望仍然是开（btSet=1）**。
     * 不要求「上一态必须是 ON」。因为本机最主要的故障形态是**根本开不起来**：
     *   OFF -> TURNING_ON -> OFF（初始化就崩，从不经过 ON）。
     * 旧版只认 ON->OFF，导致这类崩溃 100% 漏记 —— 这正是用户的痛点，必须覆盖。
     *
     * 形态区分：
     *   WHILE_ON   = ON -> OFF   开成功后运行中掉线
     *   ON_ENABLE  = TURNING_ON -> OFF  启用阶段失败（未到达 ON）
     *   FROM_UNKNOWN = 其他
     */
    fun onBluetoothChanged(ctx: Context, prev: Int, cur: Int): List<String> {
        val lines = ArrayList<String>()
        val p = prefs(ctx)
        val wanted = btSet(ctx)
        lines.add(event("bt_adapter", btName(prev) + "->" + btName(cur) + "|btSet=" + wanted))

        // 1) 到达 ON：记成功一次；顺带结算「卡在想开没开」的时长
        if (cur == BluetoothAdapter.STATE_ON) {
            val stuckSince = p.getLong("stuck_since", 0L)
            val wasReported = p.getBoolean("stuck_reported", false)
            p.edit()
                .putLong("bt_on_since", System.currentTimeMillis())
                .putInt("reached_on_count", p.getInt("reached_on_count", 0) + 1)
                .putLong("stuck_since", 0L)
                .putBoolean("stuck_reported", false)
                // 到达 ON = 上一笔崩溃**已经恢复**。这是区分「真崩溃」与「关机误报」的关键：
                // 真崩溃系统会自己把适配器重启回来（实测 1~2 秒），关机误报则永远回不来。
                .putBoolean("last_crash_recovered", true)
                .apply()
            lines.add(event("bt_ready", "reached_ON|btName=" + clean(adapterName(ctx)) + "|" + statsLine(ctx)))
            // 关键：脱离「想开没开」的结算必须在这里做。状态恢复总能被广播先捕获，
            // 采样侧（checkStuckOff）永远等不到，否则这个时长会静默丢失。
            // 但只有 STUCK_OFF **真的写出过日志**（过了宽限期）才写结束行，否则会配对不上。
            if (stuckSince > 0L && wasReported) {
                lines.add(
                    event(
                        "STUCK_OFF_END",
                        "已脱离「想开没开」状态 持续=" + ((System.currentTimeMillis() - stuckSince) / 1000L) + "s"
                    )
                )
            }
        }

        // 2) 开始启用：记一次「尝试」
        if (cur == BluetoothAdapter.STATE_TURNING_ON) {
            val n = p.getInt("try_count", 0) + 1
            p.edit().putInt("try_count", n).apply()
            lines.add(event("bt_try", "第" + n + "次尝试开启|prev=" + btName(prev)))
        }

        // 3) 掉下来了：只要系统期望还是「开」，就不是人为关闭 → 记崩溃
        //
        // ❗去重（旧版的计数 bug）：一次适配器重启的完整序列是
        //     ON → TURNING_OFF → OFF → TURNING_ON → ON
        //   只有「从 ON / TURNING_ON 离开」的那一跃才算这一次崩溃。
        //   旧版对随后的 TURNING_OFF→OFF 也记一次（mode=FROM_UNKNOWN），且因 mode 不同
        //   绕过了 60s 合并，导致**所有崩溃数虚高一倍** —— 实测 2026-09-20 导出日志里
        //   30 条 SUSPECT_CRASH 实际只对应 15 次事件（见 migrateCounts 的历史回正）。
        val fellDown = (cur == BluetoothAdapter.STATE_OFF || cur == BluetoothAdapter.STATE_TURNING_OFF)
        val mode = when (prev) {
            BluetoothAdapter.STATE_ON -> "WHILE_ON"
            BluetoothAdapter.STATE_TURNING_ON -> "ON_ENABLE"
            else -> ""          // 已是掉线过程中的后续跃迁，属同一次，不再计数
        }
        if (fellDown && wanted == 1 && mode.isNotEmpty()) {
            val crash = p.getInt("crash_count", 0) + 1
            var fe = p.getInt("fail_enable_count", 0)
            if (mode == "ON_ENABLE") fe += 1

            // 抑制刷屏：崩溃循环时每秒都在变，同一形态 60 秒内只写一行，其余只累加计数
            val now = System.currentTimeMillis()
            val lastWall = p.getLong("last_crash_wall", 0L)
            val lastMode = p.getString("last_crash_mode", "") ?: ""
            val burst = if (lastMode == mode && now - lastWall < 60_000L)
                p.getInt("crash_burst", 0) + 1 else 1

            p.edit()
                .putInt("crash_count", crash)
                .putInt("fail_enable_count", fe)
                .putLong("bt_on_since", 0L)
                .putLong("stuck_since", 0L)
                .putLong("last_crash_wall", now)
                .putString("last_crash_mode", mode)
                .putInt("crash_burst", burst)
                // 刚记下的这笔还「没恢复」；若之后收到 bt_ready/ON 会被翻成 true
                .putBoolean("last_crash_recovered", false)
                .apply()

            // 3b) **双向缝合的下半程**：刚才是否刚收到关机/重启广播？
            //     广播和掉线是两条独立通道、先后随机（实测差 4ms 且广播在前）。若广播先到，
            //     上面那次 revokeShutdownArtifact 会扑空，得靠这里补 —— 崩溃一记下就回查标记，
            //     命中就当场撤销。撤销行交给 lines 一起返回，保证它排在 SUSPECT_CRASH **之后**。
            val mark = p.getLong("shutdown_marker", 0L)
            if (mark > 0L) {
                if (now - mark <= SHUTDOWN_GRACE_MS) {
                    // 原因写"关机/重启"：标记不区分二者（ACTION_REBOOT 也走这条路）
                    revokeShutdownArtifact(ctx, now, "关机/重启", lines)
                } else {
                    p.edit().putLong("shutdown_marker", 0L).apply()   // 过期，清掉
                }
            }

            if (burst == 1) {
                lines.add(
                    event(
                        "SUSPECT_CRASH",
                        "mode=" + mode + " " + btName(prev) + "->" + btName(cur) +
                            " 系统期望仍为开(btSet=1) 非人为关闭|" + statsLine(ctx)
                    )
                )
            } else {
                lines.add(
                    event(
                        "SUSPECT_CRASH_MORE",
                        "mode=" + mode + " 同类崩溃仍在持续|burst=" + burst + "|" + statsLine(ctx)
                    )
                )
            }
        }

        // 4) 人为关闭：明确标注，方便与崩溃区分（分析时不用猜）
        if (fellDown && wanted == 0 && prev == BluetoothAdapter.STATE_ON) {
            lines.add(event("bt_off_by_user", "ON->OFF 且系统期望=关（人为关闭）"))
        }
        return lines
    }

    /**
     * 周期采样时的「卡在想开没开」检测。
     *
     * 覆盖一个旧版完全没管的场景：**服务启动时蓝牙就已经处于 OFF + btSet=1**（例如崩溃循环
     * 发生在监控启动之前，或手机重启后蓝牙没能自动起来）。这种「遗留异常态」没有任何状态
     * 跃迁可供捕获，只能靠采样发现。进入时写一行、脱离时写一行，中间不重复刷。
     */
    fun checkStuckOff(ctx: Context): List<String> {
        val out = ArrayList<String>()
        val p = prefs(ctx)
        val st = adapterState(ctx)
        val stuck = (st == BluetoothAdapter.STATE_OFF) && btSet(ctx) == 1
        val since = p.getLong("stuck_since", 0L)
        val now = System.currentTimeMillis()

        // ⚠️ 宽限期：从「系统写 bluetooth_on=1」到「适配器真的转起来」之间有天然延迟
        //    （实测点开开关后约 47 秒才进入 TURNING_ON）。没有宽限期时，这个正常等待窗口
        //    会被记成一行 STUCK_OFF 噪声。这里要求**持续 ≥2 个采样周期**才落盘，
        //    时长仍从**第一次发现**的时刻起算（不牺牲真实性），只有短于宽限期的才被丢掉。
        val ivMs = p.getInt("interval", 60).coerceIn(10, 3600) * 1000L
        if (stuck) {
            if (since <= 0L) {
                p.edit().putLong("stuck_since", now).apply()
            } else if (!p.getBoolean("stuck_reported", false) && now - since >= ivMs * 2) {
                p.edit().putBoolean("stuck_reported", true).apply()
                out.add(
                    event(
                        "STUCK_OFF",
                        "当前处于「系统期望开、实际却没开」的异常态 bt=OFF btSet=1|已持续=" +
                            ((now - since) / 1000L) + "s|" + statsLine(ctx)
                    )
                )
            }
        } else if (since > 0L) {
            val durS = (now - since) / 1000L
            val reported = p.getBoolean("stuck_reported", false)
            p.edit().putLong("stuck_since", 0L).putBoolean("stuck_reported", false).apply()
            if (reported) out.add(event("STUCK_OFF_END", "已脱离「想开没开」状态 持续=" + durS + "s"))
        }
        return out
    }
}
