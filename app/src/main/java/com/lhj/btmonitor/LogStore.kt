package com.lhj.btmonitor

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 日志落盘：按天一个文件，纯文本、可读、便于外部工具（Python/awk）分析。
 * 目录：/sdcard/Android/data/com.lhj.btmonitor/files/btlog/
 */
object LogStore {

    private const val DIR_NAME = "btlog"
    private val lock = Any()

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun timeStr(ts: Long = System.currentTimeMillis()): String =
        synchronized(this) { timeFmt.format(Date(ts)) }

    fun dayStr(ts: Long = System.currentTimeMillis()): String =
        synchronized(this) { dayFmt.format(Date(ts)) }

    fun fullStamp(ts: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ts))

    fun logDir(ctx: Context): File {
        val base = ctx.getExternalFilesDir(null) ?: File(ctx.filesDir, "ext")
        val d = File(base, DIR_NAME)
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun dayFile(ctx: Context, ts: Long = System.currentTimeMillis()): File =
        File(logDir(ctx), "btmon-" + dayStr(ts) + ".txt")

    fun append(ctx: Context, body: String) {
        val line = timeStr() + " " + body
        synchronized(lock) {
            try {
                val f = dayFile(ctx)
                if (!f.exists()) f.writeText(header(), Charsets.UTF_8)
                f.appendText(line + "\n", Charsets.UTF_8)
            } catch (t: Throwable) {
                // 日志失败绝不影响监控主流程
            }
        }
    }

    private fun header(): String = buildString {
        append("# bt-monitor ").append(BuildConfig.VERSION_NAME)
        append("  device=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append("  android=").append(Build.VERSION.RELEASE)
        append("(API ").append(Build.VERSION.SDK_INT).append(')')
        append("  tz=").append(TimeZone.getDefault().id)
        append("  created=").append(fullStamp()).append('\n')
        append("# ------------------------------------------------------------------\n")
        append("# 字段: bt=蓝牙适配器态(OFF/TURNING_ON/ON/TURNING_OFF) btSet=系统期望(1开/0关)\n")
        append("#       btUp=本次ON已持续秒(-1=当前不在ON) btName=本机蓝牙名\n")
        append("#       ap=飞行模式(1/0) wifi=Wi-Fi态 wifiSet=期望 ssid=(无定位权限时为?) rssi=\n")
        append("#       cell=蜂窝可用(1/0) net=当前承载(WIFI/CELLULAR/NONE/...) mdata=移动数据期望\n")
        append("#       scr=屏幕(ON/OFF) batt=电量% chg=充电(Y/N)\n")
        append("#       up=监控服务已运行秒(-1=未知) crash=非人为关闭累计\n")
        append("# 类型: HB=周期采样快照 | EV=状态变化事件 | MARK=手动标记\n")
        append("# ------------------------------------------------------------------\n")
        append("# ⚠️ 两类行的时间精度完全不同，看日志时别混淆：\n")
        append("#   EV 行 = **事件驱动**，发生即写，毫秒级精确、不会漏；\n")
        append("#   HB 行 = **周期快照**，屏幕活跃时 60 秒一格；\n")
        append("#           夜间灭屏进 Doze 后 Handler 会被系统停掉，退化到**约 300 秒一格**\n")
        append("#           （由 AlarmManager 看门狗兜底唤醒）。这是系统限制，不是 App 故障。\n")
        append("#   判断服务是否断档，看 HB 的 up 字段连续性；判断事件是否发生，只看 EV 行。\n")
        append("# ------------------------------------------------------------------\n")
        append("# 关键事件（分析只看这几类就够）：\n")
        append("#   EV|bt_try          一次开启尝试（进入 TURNING_ON）\n")
        append("#   EV|bt_ready        成功到达 ON（带累计统计）\n")
        append("#   EV|SUSPECT_CRASH   非人为关闭（系统期望开、却掉下来了）。\n")
        append("#                      mode=WHILE_ON(开着时掉线) / ON_ENABLE(启用阶段就失败)\n")
        append("#                      ⚠️ 一次适配器重启只记 1 次：ON->TURNING_OFF->OFF 属同一事件。\n")
        append("#   EV|SUSPECT_CRASH_MORE  同类崩溃持续中，burst=重复次数（60s 合并刷屏）\n")
        append("#   EV|STUCK_OFF       采样时发现「系统期望开、实际没开」；STUCK_OFF_END 为其结束\n")
        append("#   EV|bt_off_by_user  人为关闭（与崩溃区分用）\n")
        append("#   EV|REBOOT_DETECTED 设备曾关机/重启（elapsedRealtime 被复位即可自证，不依赖任何广播）\n")
        append("#   EV|CLOCK_JUMP      墙钟被调整（NTP 校时/手动改时间）——**不是**重启\n")
        append("#   EV|airplane        飞行模式开关（实时；服务内动态注册）\n")
        append("#   EV|screen          屏幕亮灭/解锁（实时；服务内动态注册）\n")
        append("#   EV|power           充电插拔（实时；服务内动态注册）\n")
        append("#   EV|wifi            Wi-Fi 开关（实时；服务内动态注册）\n")
        append("#   EV|net            网络承载变化。❗只在承载**真的变化**时写一行，\n")
        append("#                      格式 caps=旧->新|held=旧值持续秒数（同值重复回调已去重）\n")
        append("# ------------------------------------------------------------------\n")
        append("# 关于「崩溃」这个词：指**手机系统的蓝牙服务把适配器重启了一遍**，\n")
        append("# 不是本 App 崩溃，也不是蓝牙被关掉不回来。完整形态是\n")
        append("#   ON -> TURNING_OFF -> OFF -> TURNING_ON -> ON，全程通常 1~3 秒，\n")
        append("# 状态栏图标闪一下即恢复，所以用户往往毫无察觉。\n")
        append("# 与系统侧交叉验证：adb shell dumpsys bluetooth_manager | grep -i crashed\n")
        append("# ------------------------------------------------------------------\n")
    }

    fun listDayFiles(ctx: Context): List<File> {
        val arr = logDir(ctx).listFiles { f ->
            f.isFile && f.name.startsWith("btmon-") && f.name.endsWith(".txt")
        }
        return (arr ?: emptyArray()).sortedBy { it.name }
    }

    /** 计数汇总，导出文件头部用 */
    fun statsSummary(ctx: Context): String = Snap.statsLine(ctx)

    fun stats(ctx: Context): LongArray {
        var bytes = 0L
        var lines = 0L
        try {
            for (f in listDayFiles(ctx)) {
                bytes += f.length()
                lines += f.readLines().size.toLong()
            }
        } catch (t: Throwable) {
        }
        return longArrayOf(bytes, lines)
    }

    fun tail(ctx: Context, n: Int): String {
        val fs = listDayFiles(ctx)
        if (fs.isEmpty()) return "(尚无日志)"
        return try {
            fs.last().readLines().takeLast(n).joinToString("\n")
        } catch (t: Throwable) {
            "(读取失败: " + t.message + ")"
        }
    }

    fun merged(ctx: Context): String {
        val fs = listDayFiles(ctx)
        if (fs.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("# ==================================================\n")
        sb.append("# bt-monitor 全量导出  生成于 ").append(fullStamp()).append('\n')
        sb.append("# 文件数 ").append(fs.size).append('\n')
        sb.append("# 手机侧统计: ").append(statsSummary(ctx)).append('\n')
        sb.append("# 交叉验证（在电脑上跑，拿系统侧权威计数）:\n")
        sb.append("#   adb shell dumpsys bluetooth_manager | grep -A5 \"Enable log\"\n")
        sb.append("#   adb shell dumpsys bluetooth_manager | grep \"crashed\"\n")
        sb.append("# ==================================================\n")
        for (f in fs) {
            sb.append("\n##### FILE ").append(f.name)
                .append("  size=").append(f.length()).append(" #####\n")
            try {
                sb.append(f.readText(Charsets.UTF_8))
            } catch (t: Throwable) {
                sb.append("(读取失败)\n")
            }
            if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
        }
        return sb.toString()
    }

    fun clearAll(ctx: Context): Int {
        var n = 0
        for (f in listDayFiles(ctx)) if (f.delete()) n++
        return n
    }

    fun exportFileName(): String =
        "btmon-export-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".txt"
}
