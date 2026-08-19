package me.rerere.rikkahub.ui.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
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
import me.rerere.rikkahub.utils.STREAM_UI_THROTTLE_MS
import me.rerere.rikkahub.utils.throttleLatest

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

    private var composeView: ComposeView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    // 每次展开创建新的 owner（SavedStateRegistry.performRestore 只能调用一次），
    // 关闭后再次展开必须重建，否则会抛 "performRestore cannot be called more than once"
    private var lifecycleOwner: WindowLifecycleOwner? = null

    // 记忆窗口最后位置（内存态，服务存活期间有效），重新展开时恢复到上次拖动后的位置
    private var lastX = -1
    private var lastY = -1

    val isShowing: Boolean get() = composeView != null

    fun show() {
        if (composeView != null) return
        val settings = settingsStore.settingsFlow.value
        val density = context.resources.displayMetrics.density
        val widthPx = (settings.floatingBubbleExpandWidth * density).toInt()
        val heightPx = (settings.floatingBubbleExpandHeight * density).toInt()
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        // 首次展开居中偏上，之后恢复上次拖动的位置（并夹在屏幕范围内）
        val initialX = if (lastX >= 0) lastX.coerceIn(0, (screenWidth - widthPx).coerceAtLeast(0)) else (screenWidth - widthPx) / 2
        val initialY = if (lastY >= 0) lastY.coerceIn(0, (screenHeight - heightPx).coerceAtLeast(0)) else (40 * density).toInt()

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

        val owner = WindowLifecycleOwner()
        lifecycleOwner = owner

        val view = ComposeView(context).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            setViewTreeViewModelStoreOwner(owner)
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
                            this@FloatingExpandWindow.lastX = lp.x
                            this@FloatingExpandWindow.lastY = lp.y
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

        owner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        owner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        runCatching { windowManager.addView(view, params) }
            .onFailure {
                // 窗口挂载失败（如权限被回收）：回滚状态并销毁 owner，
                // 保证下次点击仍可重新尝试展开，而不是卡在"isShowing 为真但窗口不存在"
                composeView = null
                layoutParams = null
                lifecycleOwner = null
                runCatching { owner.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
            }
            .onSuccess {
                composeView = view
            }
    }

    fun hide() {
        val view = composeView ?: return
        composeView = null
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleOwner?.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        lifecycleOwner = null
        runCatching { windowManager.removeView(view) }
        layoutParams = null
    }
}

/**
 * 悬浮窗专用的最小 LifecycleOwner：驱动 ComposeView 内部的 recomposer 与副作用。
 *
 * Compose 1.6+ 的 ComposeView 在组合时同时要求 ViewTreeLifecycleOwner、
 * ViewTreeSavedStateRegistryOwner 与 ViewTreeViewModelStoreOwner 齐备，
 * 缺任一都会在窗口挂载时抛异常导致崩溃，因此一并实现三个 owner 接口。
 */
private class WindowLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val viewModelStoreImpl = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = viewModelStoreImpl

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> savedStateRegistryController.performRestore(Bundle())
            Lifecycle.Event.ON_DESTROY -> viewModelStoreImpl.clear()
            else -> {}
        }
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
    val state by hub.state
        .throttleLatest(STREAM_UI_THROTTLE_MS)
        .collectAsState(initial = hub.state.value)

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
        // AI 活动指示点：生成中为高亮色，空闲为弱化色
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (state.isGenerating) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
        )
        Spacer(modifier = Modifier.width(8.dp))
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

    val scrollState = rememberScrollState()
    val contentModifier = Modifier
        .fillMaxSize()
        .padding(12.dp)

    if (selectedTab == 0) {
        TodoContent(state.todos, state.terminalCommands, scrollState, contentModifier)
    } else {
        LiveOutputContent(state, scrollState, contentModifier)
    }
}

@Composable
private fun TodoContent(
    todos: List<TodoItem>,
    terminalCommands: List<TerminalCommand>,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "暂无待办",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "生成时让 AI 输出任务清单，如「- [ ] 第一步」，会自动显示在这里",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    // 新内容到达时自动滚动到底部，持续跟随实时输出
    LaunchedEffect(state.liveText, state.reasoning, state.terminalCommands.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
    }
    Column(
        modifier = modifier.verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
                fontFamily = FontFamily.Monospace,
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
