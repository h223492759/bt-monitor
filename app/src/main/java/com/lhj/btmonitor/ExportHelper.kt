package com.lhj.btmonitor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/** 导出：① 写进公共「下载」目录（用户可直接翻到）② 走系统分享临时文件 */
object ExportHelper {

    /** 返回人类可读的落点描述 */
    fun writeToDownloads(ctx: Context, content: String, name: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/bt-monitor")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法在下载目录创建文件")
            cr.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            cr.update(uri, done, null, null)
            return "内部存储/Download/bt-monitor/" + name
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "bt-monitor"
            )
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, name)
            f.writeText(content, Charsets.UTF_8)
            return f.absolutePath
        }
    }

    fun shareFileUri(ctx: Context, content: String, name: String): Uri {
        val dir = File(ctx.getExternalFilesDir(null), "export")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, name)
        f.writeText(content, Charsets.UTF_8)
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
    }

    fun share(ctx: Context, uri: Uri) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(i, "导出监控日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
