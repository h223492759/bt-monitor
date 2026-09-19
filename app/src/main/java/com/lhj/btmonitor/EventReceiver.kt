package com.lhj.btmonitor

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager

/**
 * 状态变化捕获（静态注册，进程不在也能记）。
 * 这里出现的 action 都属于 Android 隐式广播豁免清单，静态注册长期有效；
 * 唯一例外是 CONNECTIVITY_CHANGE（7.0 起静态收不到），已由服务内的
 * registerDefaultNetworkCallback + 周期采样覆盖。
 */
class EventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val ctx = context.applicationContext
            handle(ctx, intent)
            tryHeal(ctx)
        } catch (t: Throwable) {
            // 记录失败不影响系统
        }
    }

    companion object {

        /**
         * 自愈：服务被厂商省电策略杀掉后，借一次状态变化广播把它拉回来。
         * 只在「用户没有主动停止」时才做；后台启动前台服务在 Android 12+ 可能被拒，故整体 try 包裹。
         */
        private fun tryHeal(ctx: Context) {
            try {
                val p = Snap.prefs(ctx)
                if (p.getBoolean("svc_running", false)) return
                if (p.getBoolean("user_stopped", false)) return
                if (!p.getBoolean("autostart", true)) return
                MonitorService.start(ctx, p.getInt("interval", 60))
            } catch (t: Throwable) {
            }
        }

        fun handle(ctx: Context, intent: Intent) {
            val a = intent.action ?: return
            when (a) {

                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val cur = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                    val prev = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1)
                    for (l in Snap.onBluetoothChanged(ctx, prev, cur)) LogStore.append(ctx, l)
                }

                Intent.ACTION_AIRPLANE_MODE_CHANGED -> {
                    val on = if (intent.hasExtra("state"))
                        (if (intent.getBooleanExtra("state", false)) 1 else 0)
                    else Snap.airplane(ctx)
                    LogStore.append(
                        ctx,
                        Snap.event("airplane", "state=" + on + "|bt=" + Snap.btName(Snap.adapterState(ctx)))
                    )
                }

                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val cur = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1)
                    val prev = intent.getIntExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, -1)
                    LogStore.append(ctx, Snap.event("wifi", Snap.wifiName(prev) + "->" + Snap.wifiName(cur)))
                }

                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val sr = Snap.ssidRssi(ctx)
                    LogStore.append(ctx, Snap.event("wifi_net", "ssid=" + sr[0] + "|rssi=" + sr[1]))
                }

                WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION -> {
                    val c = intent.getBooleanExtra(WifiManager.EXTRA_SUPPLICANT_CONNECTED, false)
                    LogStore.append(ctx, Snap.event("wifi_supp", "connected=" + (if (c) 1 else 0)))
                }

                Intent.ACTION_SCREEN_ON -> LogStore.append(ctx, Snap.event("screen", "ON"))
                Intent.ACTION_SCREEN_OFF -> LogStore.append(ctx, Snap.event("screen", "OFF"))
                Intent.ACTION_USER_PRESENT -> LogStore.append(ctx, Snap.event("screen", "UNLOCK"))

                Intent.ACTION_POWER_CONNECTED -> LogStore.append(ctx, Snap.event("power", "PLUGGED"))
                Intent.ACTION_POWER_DISCONNECTED -> LogStore.append(ctx, Snap.event("power", "UNPLUGGED"))

                Intent.ACTION_SHUTDOWN -> LogStore.append(ctx, Snap.event("system", "SHUTDOWN"))

                "android.net.conn.CONNECTIVITY_CHANGE" -> {
                    val n = Snap.netInfo(ctx)
                    LogStore.append(ctx, Snap.event("conn", "net=" + n[0] + "|cell=" + n[1]))
                }
            }
        }
    }
}
