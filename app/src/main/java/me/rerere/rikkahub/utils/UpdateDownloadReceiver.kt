package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import me.rerere.rikkahub.BuildConfig
import java.io.File

/**
 * 监听应用内更新 APK 下载完成事件, 完成后自动弹出系统安装界面。
 */
class UpdateDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId < 0 || downloadId != UpdateChecker.pendingInstallDownloadId) return

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(downloadId) ?: return

        // 尝试转成 File 供 FileProvider 共享; 部分系统返回 file:// 或 content://
        val file = uri.toFile()
        val installUri = if (file != null) {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.fileprovider", file)
        } else {
            uri
        }

        if (!context.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(context, "请先允许安装未知来源应用", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(installUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onFailure {
            Toast.makeText(context, "打开安装界面失败", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun Uri.toFile(): File? = runCatching {
    if (scheme == "file") File(path!!) else null
}.getOrNull()
