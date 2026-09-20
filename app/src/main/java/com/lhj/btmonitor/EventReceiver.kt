package com.lhj.btmonitor

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 静态注册的状态变化捕获 —— 进程不在也能记。
 *
 * ⚠️ 这里**只处理蓝牙状态变化**，因为它是实测唯一真正能到达的静态广播
 * （2026-09-19~20 两天 86 条）。历史上这里还写过飞行模式 / 屏幕 / 电源 / 关机 /
 * Wi-Fi 的分支，跑两天后逐条清点发现**一条都没到达**：荣耀 Android 17 把这些
 * 隐式状态广播基本全拦了（SCREEN_ON/OFF 自 Android 8 起更是只允许动态注册）。
 * 那些通道已迁移到 `MonitorService.registerSysEvents()` 里动态注册，
 * 对应的 manifest action 也已移除，避免两个通道同时写入造成重复行。
 */
class EventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val ctx = context.applicationContext
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val cur = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)
                val prev = intent.getIntExtra(BluetoothAdapter.EXTRA_PREVIOUS_STATE, -1)
                for (l in Snap.onBluetoothChanged(ctx, prev, cur)) LogStore.append(ctx, l)
            }
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
                if (p.getBoolean("user_stopped", false)) return
                if (!p.getBoolean("autostart", true)) return
                val iv = p.getInt("interval", 60).coerceIn(10, 3600)
                // ❗判据必须是「现在是否真的在产数据」，**不能看 svc_running 这个持久化标记** ——
                //   进程被杀后它仍然是 true，会让自愈永远不触发（实测：静默停摆 39 分钟没被救回来）。
                //   改成看 last_tick 的新鲜度：超过 3 个采样周期没有任何数据 = 确实死了，才动手。
                val last = p.getLong("last_tick", 0L)
                val stale = System.currentTimeMillis() - last > iv * 3L * 1000L
                if (MonitorService.running && !stale) return
                MonitorService.start(ctx, iv)
            } catch (t: Throwable) {
            }
        }
    }
}
