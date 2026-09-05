package me.rerere.androidvm.engine

import android.util.Log

/** androidvm 引擎侧统一日志出口 */
internal object EngineLog {
    private const val TAG = "AndroidVmEngine"

    fun warn(message: String) = Log.w(TAG, message)

    fun error(message: String, throwable: Throwable? = null) = Log.e(TAG, message, throwable)
}
