package me.rerere.rikkahub.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
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
import org.koin.android.ext.android.inject

private const val TAG = "FloatingBubbleService"

/**
 * 悬浮球前台服务: 在系统层显示一个可拖动、可半隐藏的悬浮小球，
 * 点击小球可回到 RikkaHub。颜色/大小/开关由偏好设置实时驱动。
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
    }

    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var settingsJob: Job? = null

    private var control: IFxAppControl? = null

    // 悬浮球外观状态 (Compose 内容实时读取)
    private var bubbleColor by mutableStateOf(Color(0xFF4F8EF7))
    private var bubbleSizeDp by mutableIntStateOf(48)
    private var bubbleAlpha by mutableFloatStateOf(1f)

    // 交互状态
    private var isHalfHidden = false
    private var hasDragged = false

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
        control?.cancel()
        control = null
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

        // 注意: 悬浮球在系统级 Service 中渲染, 不能包裹 RikkahubTheme
        // (其内部会强转 Activity 上下文), 因此直接使用最简 Compose 内容。
        val composeView = ComposeView(this).apply {
            setContent {
                FloatingBubbleContent(
                    color = bubbleColor,
                    sizeDp = bubbleSizeDp,
                    alpha = bubbleAlpha,
                )
            }
        }

        control = FloatingX.install {
            setTag(FLOATING_X_TAG)
            setContext(this@FloatingBubbleService)
            setScopeType(FxScopeType.SYSTEM_AUTO)
            setEnableSafeArea(true)
            setLayoutView(composeView)
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
                bubbleColor = Color(settings.floatingBubbleColor.toInt())
                bubbleSizeDp = settings.floatingBubbleSize.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)
            }
        }
    }

    private fun handleClick() {
        if (isHalfHidden) {
            restoreFromHalfHide()
        } else {
            launchApp()
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
            val targetX = if (nearLeft) -sizePx / 2f else screenWidthPx - sizePx / 2f
            c.move(targetX, c.getY(), true)
        } else if (isHalfHidden) {
            isHalfHidden = false
            bubbleAlpha = 1f
        }
    }

    private fun restoreFromHalfHide() {
        isHalfHidden = false
        bubbleAlpha = 1f
        val c = control ?: return
        val sizePx = dp2px(bubbleSizeDp)
        val screenWidthPx = resources.displayMetrics.widthPixels
        val targetX = if (c.getX() < screenWidthPx / 2f) 0f else screenWidthPx - sizePx
        c.move(targetX, c.getY(), true)
    }

    private fun dp2px(dp: Int): Float = dp * resources.displayMetrics.density
}

@Composable
private fun FloatingBubbleContent(
    color: Color,
    sizeDp: Int,
    alpha: Float,
) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}
