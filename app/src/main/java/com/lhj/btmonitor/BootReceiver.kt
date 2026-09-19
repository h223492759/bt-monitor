package com.lhj.btmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机 / 升级后自动恢复监控（需要用户允许自启动，荣耀等机型需在「应用启动管理」中放行） */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action ?: return
        try {
            val ctx = context.applicationContext
            if (!Snap.prefs(ctx).getBoolean("autostart", true)) return
            // 重启视为新会话：清掉「用户主动停止」标记，让监控接着跑
            Snap.prefs(ctx).edit().putBoolean("user_stopped", false).apply()
            LogStore.append(ctx, Snap.event("boot", a))
            MonitorService.start(ctx, Snap.prefs(ctx).getInt("interval", 60))
        } catch (t: Throwable) {
        }
    }
}
