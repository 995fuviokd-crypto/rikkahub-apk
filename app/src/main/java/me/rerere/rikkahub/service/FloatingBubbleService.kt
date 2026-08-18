package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.petterp.floatingx.FloatingX
import com.petterp.floatingx.assist.FxAdsorbDirection
import com.petterp.floatingx.assist.FxScopeType
import com.petterp.floatingx.listener.IFxTouchListener
import com.petterp.floatingx.listener.control.IFxAppControl
import com.petterp.floatingx.view.IFxInternalHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.FLOATING_BUBBLE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.floating.FloatingExpandWindow
import org.koin.android.ext.android.inject

private const val TAG = "FloatingBubbleService"

/**
 * 悬浮球前台服务: 在系统层显示一个可拖动、可半隐藏的悬浮小球，
 * 点击小球可回到 RikkaHub。颜色/大小/开关由偏好设置实时驱动。
 *
 * 注意: 悬浮球运行在 Service 进程中, 没有 Activity 的 LifecycleOwner,
 * 因此不能使用 ComposeView (setContent 会因缺少 ViewTreeLifecycleOwner 崩溃),
 * 改用普通 View + GradientDrawable 绘制纯色圆球。
 */
class FloatingBubbleService : Service() {

    companion object {
        const val ACTION_START = "me.rerere.rikkahub.action.FLOATING_BUBBLE_START"
        const val ACTION_STOP = "me.rerere.rikkahub.action.FLOATING_BUBBLE_STOP"
        const val FLOATING_X_TAG = "floating_bubble"
        const val NOTIFICATION_ID = 3002
        private const val SIZE_MIN_DP = 32
        private const val SIZE_MAX_DP = 80
        private const val HALF_HIDE_ALPHA = 0.5f
        private const val DOUBLE_CLICK_INTERVAL_MS = 350L
    }

    private val settingsStore: SettingsStore by inject()
    private val floatingActivityHub: FloatingActivityHub by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settingsJob: Job? = null

    private var control: IFxAppControl? = null
    private var bubbleView: BubbleView? = null
    private var expandWindow: FloatingExpandWindow? = null

    // 悬浮球外观状态
    private var bubbleColor = 0xFF4F8EF7.toInt()
    private var bubbleSizeDp = 48
    private var bubbleAlpha = 1f

    // 交互状态
    private var isHalfHidden = false
    private var hasDragged = false
    private var lastClickTime = 0L

    private val singleClickRunnable = Runnable {
        toggleExpandWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                if (!startForegroundCompat()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (!Settings.canDrawOverlays(this)) {
                    Log.w(TAG, "No overlay permission, stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                setupBubble()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        settingsJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        expandWindow?.hide()
        expandWindow = null
        control?.cancel()
        control = null
        bubbleView = null
        serviceScope.cancel()
    }

    private fun startForegroundCompat(): Boolean {
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            false
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, FLOATING_BUBBLE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.small_icon)
            .setContentTitle(getString(R.string.notification_floating_bubble_running))
            .setContentText(getString(R.string.notification_floating_bubble_desc))
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun setupBubble() {
        val existing = control
        if (existing != null) {
            existing.show()
            observeSettings()
            return
        }

        val view = BubbleView(this).apply {
            setBubbleColor(bubbleColor)
            setBubbleAlpha(bubbleAlpha)
            setBubbleSize(dp2px(bubbleSizeDp).toInt())
        }
        bubbleView = view

        control = FloatingX.install {
            setTag(FLOATING_X_TAG)
            setContext(this@FloatingBubbleService)
            setScopeType(FxScopeType.SYSTEM_AUTO)
            setEnableSafeArea(true)
            setLayoutView(view)
        }
        control?.configControl?.apply {
            setEnableEdgeAdsorption(true)
            setEdgeAdsorbDirection(FxAdsorbDirection.LEFT_OR_RIGHT)
            setEdgeOffset(0f)
            setEnableClick(true)
            setEnableAnimation(true)
            setTouchListener(object : IFxTouchListener {
                override fun onDown() {
                    hasDragged = false
                }

                override fun onUp() {
                    if (hasDragged) {
                        mainHandler.postDelayed({ updateHalfHideState() }, 150)
                    }
                }

                override fun onDragIng(event: MotionEvent, x: Float, y: Float) {
                    hasDragged = true
                }

                override fun onTouch(event: MotionEvent, control: IFxInternalHelper?): Boolean = false

                override fun onInterceptTouchEvent(event: MotionEvent, control: IFxInternalHelper?): Boolean = false
            })
        }
        control?.apply {
            setClickListener { handleClick() }
            show()
        }

        observeSettings()
    }

    private fun observeSettings() {
        if (settingsJob != null) return
        settingsJob = serviceScope.launch {
            settingsStore.settingsFlow.collect { settings ->
                if (!settings.floatingBubbleEnabled) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                bubbleColor = settings.floatingBubbleColor.toInt()
                bubbleSizeDp = settings.floatingBubbleSize.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)
                bubbleView?.setBubbleColor(bubbleColor)
                bubbleView?.setBubbleSize(dp2px(bubbleSizeDp).toInt())
            }
        }
    }

    private fun handleClick() {
        if (isHalfHidden) {
            restoreFromHalfHide()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTime < DOUBLE_CLICK_INTERVAL_MS) {
            // 双击：收起展开窗口并回到软件
            mainHandler.removeCallbacks(singleClickRunnable)
            lastClickTime = 0L
            expandWindow?.hide()
            launchApp()
        } else {
            // 单击：延迟到双击判定窗口之后执行展开/收起
            lastClickTime = now
            mainHandler.removeCallbacks(singleClickRunnable)
            mainHandler.postDelayed(singleClickRunnable, DOUBLE_CLICK_INTERVAL_MS)
        }
    }

    private fun toggleExpandWindow() {
        val window = expandWindow ?: FloatingExpandWindow(this, floatingActivityHub, settingsStore).also {
            expandWindow = it
        }
        if (window.isShowing) {
            window.hide()
        } else {
            window.show()
        }
    }

    private fun launchApp() {
        val intent = Intent(this, RouteActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }.onFailure {
            Log.e(TAG, "launchApp failed", it)
        }
    }

    private fun updateHalfHideState() {
        val c = control ?: return
        val sizePx = dp2px(bubbleSizeDp)
        val screenWidthPx = resources.displayMetrics.widthPixels
        val x = c.getX()
        val nearLeft = x <= sizePx * 0.5f
        val nearRight = x >= screenWidthPx - sizePx * 1.5f
        if (nearLeft || nearRight) {
            if (isHalfHidden) return
            isHalfHidden = true
            bubbleAlpha = HALF_HIDE_ALPHA
            bubbleView?.setBubbleAlpha(bubbleAlpha)
            val targetX = if (nearLeft) -sizePx / 2f else screenWidthPx - sizePx / 2f
            c.move(targetX, c.getY(), true)
        } else if (isHalfHidden) {
            isHalfHidden = false
            bubbleAlpha = 1f
            bubbleView?.setBubbleAlpha(bubbleAlpha)
        }
    }

    private fun restoreFromHalfHide() {
        isHalfHidden = false
        bubbleAlpha = 1f
        bubbleView?.setBubbleAlpha(bubbleAlpha)
        val c = control ?: return
        val sizePx = dp2px(bubbleSizeDp)
        val screenWidthPx = resources.displayMetrics.widthPixels
        val targetX = if (c.getX() < screenWidthPx / 2f) 0f else screenWidthPx - sizePx
        c.move(targetX, c.getY(), true)
    }

    private fun dp2px(dp: Int): Float = dp * resources.displayMetrics.density

    /**
     * 悬浮球视图: 普通 View + GradientDrawable 绘制纯色圆球。
     * 支持运行时更新颜色/透明度/大小, 不依赖 Compose 与 LifecycleOwner。
     */
    private class BubbleView(context: Context) : View(context) {

        private val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }

        private var sizePx = 0

        init {
            background = drawable
        }

        fun setBubbleColor(color: Int) {
            drawable.setColor(color)
        }

        fun setBubbleAlpha(alpha: Float) {
            drawable.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        }

        fun setBubbleSize(px: Int) {
            if (sizePx == px) return
            sizePx = px
            requestLayout()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val size = if (sizePx > 0) sizePx else suggestedMinimumWidth
            setMeasuredDimension(size, size)
        }
    }
}
