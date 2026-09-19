package com.lhj.btmonitor

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var previewView: TextView
    private lateinit var toggleBtn: Button
    private lateinit var intervalBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val INTERVALS = intArrayOf(15, 30, 60, 120, 300)

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        requestNeededPermissions()

        // 首次打开即自动开始（默认间隔 60s）
        val p0 = Snap.prefs(this)
        if (p0.getBoolean("autostart", true) && !p0.getBoolean("user_stopped", false) && !isServiceRunning()) {
            MonitorService.start(this, Snap.prefs(this).getInt("interval", 60))
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun mkBtn(text: String, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        b.setOnClickListener { action() }
        return b
    }

    private fun buildUi(): View {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(20), dp(16), dp(28))

        val title = TextView(this)
        title.text = "蓝牙监控"
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        title.setTypeface(null, Typeface.BOLD)
        root.addView(title)

        val sub = TextView(this)
        sub.text = "记录 蓝牙 / 飞行模式 / Wi-Fi / 移动网络 / 屏幕 / 充电 的状态变化 + 周期采样"
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        sub.setTextColor(Color.parseColor("#666666"))
        sub.setPadding(0, dp(4), 0, dp(10))
        root.addView(sub)

        statusView = TextView(this)
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        statusView.setTypeface(Typeface.MONOSPACE)
        root.addView(statusView)

        toggleBtn = mkBtn("") { toggleService() }
        root.addView(toggleBtn)

        intervalBtn = mkBtn("") { cycleInterval() }
        root.addView(intervalBtn)

        root.addView(mkBtn("立即采样一次") { MonitorService.sampleNow(this); toast("已采样") })
        root.addView(mkBtn("导出 TXT 到「下载」目录") { exportToDownloads() })
        root.addView(mkBtn("分享 TXT（微信 / 邮件 / 文件管理器）") { shareTxt() })
        root.addView(mkBtn("申请「忽略电池优化」") { askIgnoreBattery() })
        root.addView(mkBtn("保活设置说明（荣耀 / 华为必看）") { showKeepAliveHelp() })
        root.addView(mkBtn("清空全部日志") { confirmClear() })

        val lbl = TextView(this)
        lbl.text = "最新日志（最后 25 行）"
        lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        lbl.setTextColor(Color.parseColor("#666666"))
        lbl.setPadding(0, dp(16), 0, dp(4))
        root.addView(lbl)

        previewView = TextView(this)
        previewView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        previewView.setTypeface(Typeface.MONOSPACE)
        previewView.setTextColor(Color.parseColor("#333333"))
        root.addView(previewView)

        val sv = ScrollView(this)
        sv.addView(root)
        return sv
    }

    // ------------------------------------------------------------- 状态刷新

    private fun isServiceRunning(): Boolean =
        Snap.prefs(this).getBoolean("svc_running", false)

    private fun fmt(sec: Long): String {
        if (sec < 0) return "-"
        val d = sec / 86400
        val h = (sec % 86400) / 3600
        val m = (sec % 3600) / 60
        return when {
            d > 0 -> d.toString() + "d" + h + "h"
            h > 0 -> h.toString() + "h" + m + "m"
            else -> m.toString() + "m"
        }
    }

    private fun fmtSize(b: Long): String = when {
        b > 1024L * 1024L -> String.format("%.2f MB", b / 1048576.0)
        b > 1024L -> String.format("%.1f KB", b / 1024.0)
        else -> b.toString() + " B"
    }

    private fun refresh() {
        val running = isServiceRunning()
        val st = Snap.adapterState(this)
        val bt = Snap.btName(st)
        val btSet = Snap.btSet(this)
        val since = Snap.prefs(this).getLong("bt_on_since", 0L)
        val btUp = if (since > 0L) (System.currentTimeMillis() - since) / 1000L else -1L
        val ap = Snap.airplane(this)
        val wifi = Snap.wifiName(Snap.wifiState(this))
        val sr = Snap.ssidRssi(this)
        val net = Snap.netInfo(this)
        val bat = Snap.battery(this)
        val scr = Snap.screenState(this)
        val crash = Snap.crashCount(this)
        val fe = Snap.failEnableCount(this)
        val tryN = Snap.tryCount(this)
        val okN = Snap.onCount(this)
        val stuckSince = Snap.prefs(this).getLong("stuck_since", 0L)
        val stuckUp = if (stuckSince > 0L) (System.currentTimeMillis() - stuckSince) / 1000L else -1L
        val stats = LogStore.stats(this)
        val up = if (MonitorService.startedAt > 0L)
            (System.currentTimeMillis() - MonitorService.startedAt) / 1000L else -1L

        statusView.text = buildString {
            append("服务      : ").append(if (running) "运行中" else "已停止")
            append("   本次运行 ").append(fmt(up)).append('\n')
            append("蓝牙      : ").append(bt).append("   期望=")
            append(if (btSet == 1) "开" else "关").append('\n')
            append("本次ON持续: ").append(fmt(btUp)).append('\n')
            append("开启尝试  : ").append(tryN).append(" 次，成功 ")
            append(okN).append(" 次\n")
            append("非人为关闭: ").append(crash).append(" 次（其中启用阶段失败 ")
            append(fe).append(" 次）\n")
            if (stuckUp >= 0) {
                append("⚠ 想开没开 : 已持续 ").append(fmt(stuckUp)).append('\n')
            }
            append("飞行模式  : ").append(if (ap == 1) "开" else "关").append('\n')
            append("Wi-Fi     : ").append(wifi)
            append("   SSID=").append(sr[0])
            if (sr[1].isNotEmpty()) append("  ").append(sr[1]).append("dBm")
            append('\n')
            append("网络      : ").append(net[0]).append("   蜂窝=").append(net[1]).append('\n')
            append("屏幕/电量 : ").append(scr).append("   ").append(bat[0]).append("%   充电=").append(bat[1]).append('\n')
            append("日志      : ").append(stats[1]).append(" 行 / ").append(fmtSize(stats[0])).append('\n')
            append("目录      : ").append(LogStore.logDir(this@MainActivity).absolutePath)
        }

        toggleBtn.text = if (running) "停止监控" else "开始监控"
        intervalBtn.text = "采样间隔：" + Snap.prefs(this).getInt("interval", 60) + " 秒（点击切换）"
        previewView.text = LogStore.tail(this, 25)
    }

    // ------------------------------------------------------------- 交互动作

    private fun toggleService() {
        if (isServiceRunning()) {
            MonitorService.stop(this)
            toast("已停止监控")
        } else {
            MonitorService.start(this, Snap.prefs(this).getInt("interval", 60))
            toast("已开始监控")
        }
        handler.postDelayed({ refresh() }, 400)
    }

    private fun cycleInterval() {
        val cur = Snap.prefs(this).getInt("interval", 60)
        var idx = INTERVALS.indexOf(cur)
        if (idx < 0) idx = 2
        val next = INTERVALS[(idx + 1) % INTERVALS.size]
        Snap.prefs(this).edit().putInt("interval", next).apply()
        if (isServiceRunning()) MonitorService.setInterval(this, next)
        toast("采样间隔改为 " + next + " 秒")
        handler.postDelayed({ refresh() }, 300)
    }

    private fun exportToDownloads() {
        try {
            val content = LogStore.merged(this)
            if (content.isEmpty()) {
                toast("还没有日志可导出")
                return
            }
            val name = LogStore.exportFileName()
            val dest = ExportHelper.writeToDownloads(this, content, name)
            AlertDialog.Builder(this)
                .setTitle("导出完成")
                .setMessage("已写入：\n" + dest + "\n\n内容大小 " + fmtSize(content.toByteArray(Charsets.UTF_8).size.toLong()))
                .setPositiveButton("知道了", null)
                .show()
        } catch (t: Throwable) {
            toast("导出失败：" + t.message)
        }
    }

    private fun shareTxt() {
        try {
            val content = LogStore.merged(this)
            if (content.isEmpty()) {
                toast("还没有日志可导出")
                return
            }
            val uri: Uri = ExportHelper.shareFileUri(this, content, LogStore.exportFileName())
            ExportHelper.share(this, uri)
        } catch (t: Throwable) {
            toast("分享失败：" + t.message)
        }
    }

    private fun askIgnoreBattery() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                toast("已在电池优化白名单中")
                return
            }
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + packageName))
            )
        } catch (t: Throwable) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t2: Throwable) {
                toast("请手动到 设置 → 电池 中允许后台运行")
            }
        }
    }

    private fun showKeepAliveHelp() {
        val msg = """
            要让监控稳定跑满一周，请做这 4 步（荣耀 MagicOS / 华为 EMUI 通用）：

            1) 设置 → 应用 → 应用启动管理 → 找到「蓝牙监控」→ 关闭「自动管理」，
               手动勾选：允许自启动、允许关联启动、允许后台活动。

            2) 设置 → 电池 → 更多电池设置 → 关闭「休眠时始终保持网络连接」的省电限制，
               并把「蓝牙监控」加入「应用省电策略 → 不受限制 / 允许后台高耗电」。

            3) 最近任务列表里下拉本应用的卡片（或点小锁），锁定后台不被清理。

            4) 本页「申请忽略电池优化」点一次并同意。

            完成这 4 步后，服务被系统杀掉的概率会大幅下降。
            如果日志里 up 字段出现大跳跃，说明监控曾中断过——那种时段的蓝牙数据不可信。
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle("保活设置")
            .setMessage(msg)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("确认清空？")
            .setMessage("将删除全部已记录的日志文件，不可恢复。建议先导出备份。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                val n = LogStore.clearAll(this)
                toast("已删除 " + n + " 个文件")
                refresh()
            }
            .show()
    }

    private fun requestNeededPermissions() {
        val need = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (need.isNotEmpty()) {
            try {
                requestPermissions(need.toTypedArray(), 100)
            } catch (t: Throwable) {
            }
        }
    }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    }
}
