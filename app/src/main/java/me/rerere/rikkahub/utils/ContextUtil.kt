package me.rerere.rikkahub.utils

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

private const val TAG = "ContextUtil"

/**
 * Read clipboard data as text
 */
fun Context.readClipboardText(): String {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = clipboardManager.primaryClip ?: return ""
    val item = clip.getItemAt(0) ?: return ""
    return item.text.toString()
}

/**
 * 发起添加群流程
 *
 * @param key 由官网生成的key
 * @return 返回true表示呼起手Q成功，返回false表示呼起失败
 */
fun Context.joinQQGroup(key: String?): Boolean {
    val intent = Intent(Intent.ACTION_VIEW)
    intent.setData(("mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key").toUri())
    // 此Flag可根据具体产品需要自定义，如设置，则在加群界面按返回，返回手Q主界面，不设置，按返回会返回到呼起产品界面    //intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
        return true
    } catch (e: java.lang.Exception) {
        // 未安装手Q或安装的版本不支持
        return false
    }
}

/**
 * Write text into clipboard
 */
fun Context.writeClipboardText(text: String) {
    val clipboardManager =
        getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    runCatching {
        clipboardManager.setPrimaryClip(android.content.ClipData.newPlainText("text", text))
        Log.i(TAG, "writeClipboardText: $text")
    }.onFailure {
        Log.e(TAG, "writeClipboardText: $text", it)
        Toast.makeText(this, "Failed to write text into clipboard", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Whether the app has been granted the "Usage access" special permission
 * (android.permission.PACKAGE_USAGE_STATS), required to query screen usage time.
 */
fun Context.hasUsageStatsPermission(): Boolean {
    val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )
    }
    return mode == AppOpsManager.MODE_ALLOWED
}

/**
 * Open the system "Usage access" settings page so the user can grant the
 * PACKAGE_USAGE_STATS permission manually.
 */
fun Context.openUsageAccessSettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.onFailure {
        Log.e(TAG, "openUsageAccessSettings failed", it)
    }
}

/**
 * Open a url
 */
fun Context.openUrl(url: String) {
    Log.i(TAG, "openUrl: $url")
    runCatching {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .build()
        intent.launchUrl(this, url.toUri())
    }.onFailure {
        it.printStackTrace()
        Toast.makeText(this, "Failed to open URL: $url", Toast.LENGTH_SHORT).show()
    }
}

fun Context.getActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

/**
 * Whether the app has been granted "All files access"
 * (android.permission.MANAGE_EXTERNAL_STORAGE), which lets the workspace
 * Linux environment read and write all files on the phone via /sdcard.
 */
fun Context.hasAllFilesAccessPermission(): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }
}

/**
 * Whether the RikkaHub accessibility service is enabled in system settings.
 * The service powers the accessibility tools (click / tap / navigate on screen).
 */
fun Context.hasAccessibilityServiceEnabled(): Boolean {
    val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    if (!am.isEnabled) return false
    val enabledServices = android.provider.Settings.Secure.getString(
        contentResolver,
        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabledServices.split(':').any { it.contains(accessibilityServiceName) }
}

/** Open the system accessibility settings page so the user can enable the service. */
fun Context.openAccessibilitySettings() {
    runCatching {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.onFailure {
        Log.e(TAG, "openAccessibilitySettings failed", it)
    }
}

/** Whether the app is exempted from battery optimizations (doze). */
fun Context.hasIgnoreBatteryOptimizationsPermission(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

/** Open the system battery optimization settings for this app. */
fun Context.openBatteryOptimizationSettings() {
    runCatching {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }.onFailure {
        Log.e(TAG, "openBatteryOptimizationSettings failed", it)
        runCatching {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Log.e(TAG, "openBatteryOptimizationSettings(global) failed", it)
        }
    }
}

private val Context.accessibilityServiceName: String
    get() = "$packageName/me.rerere.rikkahub.service.accessibility.RikkaAccessibilityService"

/**
 * Open the system "All files access" settings page (Android 11+), or request
 * the legacy external storage runtime permission on older versions.
 */
fun Context.openAllFilesAccessSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Log.e(TAG, "openAllFilesAccessSettings failed", it)
            runCatching {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }.onFailure {
                Log.e(TAG, "openAllFilesAccessSettings(global) failed", it)
            }
        }
    } else {
        getActivity()?.let { activity ->
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ),
                2
            )
        }
    }
}

fun Context.getComponentActivity(): ComponentActivity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is ComponentActivity) {
            return context
        }
        context = context.baseContext
    }
    return null
}

fun Context.exportImage(
    activity: Activity,
    bitmap: Bitmap,
    fileName: String = "RikkaHub_${System.currentTimeMillis()}.png"
) {
    // 检查存储权限（Android 9及以下需要）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
            return
        }
    }

    // 保存到相册
    var outputStream: OutputStream? = null
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上使用MediaStore API
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                outputStream = contentResolver.openOutputStream(it)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream!!)
            }
        } else {
            // Android 9及以下直接写入文件
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, fileName)
            outputStream = FileOutputStream(image)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)

            // 通知图库更新
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(image)
            sendBroadcast(mediaScanIntent)
        }
        Log.i(TAG, "Image saved successfully: $fileName")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save image", e)
    } finally {
        outputStream?.close()
    }
}

fun Context.exportImageFile(
    activity: Activity,
    file: File,
    fileName: String = "RikkaHub_${System.currentTimeMillis()}.png"
) {
    // 检查存储权限（Android 9及以下需要）
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                1
            )
            return
        }
    }

    // 保存到相册
    var outputStream: OutputStream? = null
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10及以上使用MediaStore API
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                outputStream = contentResolver.openOutputStream(it)
                file.inputStream().copyTo(outputStream!!)
            }
        } else {
            // Android 9及以下直接写入文件
            val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val image = File(imagesDir, fileName)
            file.copyTo(image, overwrite = true)

            // 通知图库更新
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = Uri.fromFile(image)
            sendBroadcast(mediaScanIntent)
        }
        Log.i(TAG, "Image file saved successfully: $fileName")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to save image file", e)
    } finally {
        outputStream?.close()
    }
}
