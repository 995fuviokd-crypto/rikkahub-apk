package me.rerere.rikkahub.ui.floating

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.CommandLine
import me.rerere.hugeicons.stroke.Task01
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.FloatingActivityHub
import me.rerere.rikkahub.service.FloatingActivityState
import me.rerere.rikkahub.service.TerminalCommand
import me.rerere.rikkahub.service.TodoItem

/**
 * 悬浮球展开窗口：一个可通过 WindowManager 显示在任意应用之上的 Compose 悬浮窗。
 *
 * 运行在悬浮球前台服务进程中，没有 Activity 的 LifecycleOwner，因此手动维护
 * [WindowLifecycleOwner] 并注册到 ComposeView。窗口可拖动、可关闭，展示 AI 的
 * 待办与实时输出两个标签页，标签开关与窗口大小由偏好设置驱动。
 */
class FloatingExpandWindow(
    private val context: Context,
    private val hub: FloatingActivityHub,
    private val settingsStore: SettingsStore,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val lifecycleOwner = WindowLifecycleOwner()

    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = composeView != null

    fun show() {
        if (composeView != null) return
        val settings = settingsStore.settingsFlow.value
        val density = context.resources.displayMetrics.density
        val widthPx = (settings.floatingBubbleExpandWidth * density).toInt()
        val heightPx = (settings.floatingBubbleExpandHeight * density).toInt()
        val initialX = (context.resources.displayMetrics.widthPixels - widthPx) / 2
        val initialY = (40 * density).toInt()

        val params = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }
        layoutParams = params

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setContent {
                ExpandWindowContent(
                    hub = hub,
                    settingsStore = settingsStore,
                    onClose = { hide() },
                    onDrag = { dx, dy ->
                        val lp = this@FloatingExpandWindow.layoutParams
                        if (lp != null) {
                            lp.x += dx
                            lp.y += dy
                            runCatching { windowManager.updateViewLayout(this@apply, lp) }
                        }
                    },
                    onResize = { wDp, hDp ->
                        val lp = this@FloatingExpandWindow.layoutParams
                        if (lp != null) {
                            lp.width = (wDp * density).toInt()
                            lp.height = (hDp * density).toInt()
                            runCatching { windowManager.updateViewLayout(this@apply, lp) }
                        }
                    },
                )
            }
        }
        composeView = view

        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        runCatching { windowManager.addView(view, params) }
    }

    fun hide() {
        val view = composeView ?: return
        composeView = null
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        runCatching { windowManager.removeView(view) }
        layoutParams = null
    }
}

/**
 * 悬浮窗专用的最小 LifecycleOwner：驱动 ComposeView 内部的 recomposer 与副作用。
 */
private class WindowLifecycleOwner : LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}

@Composable
private fun ExpandWindowContent(
    hub: FloatingActivityHub,
    settingsStore: SettingsStore,
    onClose: () -> Unit,
    onDrag: (Int, Int) -> Unit,
    onResize: (Int, Int) -> Unit,
) {
    val settings by settingsStore.settingsFlow.collectAsState()
    val state by hub.state.collectAsState()

    LaunchedEffect(settings.floatingBubbleExpandWidth, settings.floatingBubbleExpandHeight) {
        onResize(settings.floatingBubbleExpandWidth, settings.floatingBubbleExpandHeight)
    }

    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = colorScheme.surface,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ExpandWindowHeader(
                    state = state,
                    onClose = onClose,
                    onDrag = onDrag,
                )
                HorizontalDivider(color = colorScheme.outlineVariant)
                ExpandWindowBody(
                    state = state,
                    showTodoTab = settings.floatingBubbleShowTodoTab,
                    showLiveTab = settings.floatingBubbleShowLiveTab,
                )
            }
        }
    }
}

@Composable
private fun ExpandWindowHeader(
    state: FloatingActivityState,
    onClose: () -> Unit,
    onDrag: (Int, Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x.toInt(), dragAmount.y.toInt())
                }
            }
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val title = if (state.isGenerating) state.senderName else "RikkaHub"
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
            )
            if (state.isGenerating && state.status.isNotBlank()) {
                Text(
                    text = state.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Icon(
            imageVector = HugeIcons.Cancel01,
            contentDescription = "关闭",
            modifier = Modifier
                .size(28.dp)
                .clickable { onClose() },
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExpandWindowBody(
    state: FloatingActivityState,
    showTodoTab: Boolean,
    showLiveTab: Boolean,
) {
    var selectedTab by remember { mutableIntStateOf(if (showTodoTab) 0 else 1) }

    if (!showTodoTab && !showLiveTab) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "标签已全部关闭",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    if (showTodoTab && showLiveTab) {
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("待办") },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("实时输出") },
            )
        }
    } else if (showTodoTab) {
        selectedTab = 0
    } else {
        selectedTab = 1
    }

    val contentModifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(12.dp)

    if (selectedTab == 0) {
        TodoContent(state.todos, state.terminalCommands, contentModifier)
    } else {
        LiveOutputContent(state, contentModifier)
    }
}

@Composable
private fun TodoContent(
    todos: List<TodoItem>,
    terminalCommands: List<TerminalCommand>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (todos.isNotEmpty()) {
            todos.forEach { todo ->
                TodoRow(todo)
            }
        } else {
            val running = terminalCommands.filter { it.isRunning }
            if (running.isNotEmpty()) {
                Text(
                    text = "进行中",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                running.forEach { cmd ->
                    CommandLine(command = cmd.command, output = "")
                }
            } else {
                Text(
                    text = "暂无待办",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TodoRow(todo: TodoItem) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (todo.done) HugeIcons.CheckmarkCircle02 else HugeIcons.Task01,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (todo.done) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            text = todo.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (todo.done) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

@Composable
private fun LiveOutputContent(
    state: FloatingActivityState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.reasoning.isNotBlank()) {
            Text(
                text = state.reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.liveText.isNotBlank()) {
            Text(
                text = state.liveText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.terminalCommands.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            state.terminalCommands.forEach { cmd ->
                CommandLine(command = cmd.command, output = cmd.output)
            }
        }
        if (state.reasoning.isBlank() && state.liveText.isBlank() && state.terminalCommands.isEmpty()) {
            Text(
                text = if (state.isGenerating) "等待输出..." else "暂无输出",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommandLine(command: String, output: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = HugeIcons.CommandLine,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (output.isNotBlank()) {
            Text(
                text = output,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp),
            )
        }
    }
}
