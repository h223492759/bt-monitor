package com.lhj.btmonitor

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.provider.Settings

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

    fun bumpCrash(ctx: Context) {
        val p = prefs(ctx)
        p.edit().putInt("crash_count", p.getInt("crash_count", 0) + 1).apply()
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

    /** 蓝牙状态变化：返回要写的事件行列表（含疑似崩溃判定） */
    fun onBluetoothChanged(ctx: Context, prev: Int, cur: Int): List<String> {
        val lines = ArrayList<String>()
        val wanted = btSet(ctx)
        lines.add(event("bt_adapter", btName(prev) + "->" + btName(cur) + "|btSet=" + wanted))
        if (cur == BluetoothAdapter.STATE_ON) {
            prefs(ctx).edit().putLong("bt_on_since", System.currentTimeMillis()).apply()
            lines.add(event("bt_ready", "reached_ON|btName=" + clean(adapterName(ctx))))
        }
        val fellDown = (cur == BluetoothAdapter.STATE_OFF || cur == BluetoothAdapter.STATE_TURNING_OFF)
        if (fellDown && prev == BluetoothAdapter.STATE_ON && wanted == 1) {
            bumpCrash(ctx)
            lines.add(
                event(
                    "SUSPECT_CRASH",
                    "ON->" + btName(cur) + " 但系统期望仍为开(btSet=1)|crash=" + crashCount(ctx)
                )
            )
            prefs(ctx).edit().putLong("bt_on_since", 0L).apply()
        }
        return lines
    }
}
