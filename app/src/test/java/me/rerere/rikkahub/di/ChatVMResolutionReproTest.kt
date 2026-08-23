package me.rerere.rikkahub.di

import androidx.lifecycle.ViewModel
import me.rerere.rikkahub.data.ai.agent.AcpRuntime
import me.rerere.rikkahub.service.ChatService
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.parameter.parametersOf
import org.koin.core.scope.get
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatVMResolutionReproTest {

    @Before
    fun setUp() {
        stopKoin()
        val app = RuntimeEnvironment.getApplication()
        app.applicationInfo.nativeLibraryDir = "/tmp/native"
        if (com.google.firebase.FirebaseApp.getApps(app).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(app)
        }
        stopKoin()
        startKoin {
            androidLogger()
            androidContext(app)
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    private fun causeChainOf(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    @Test
    fun chatService_resolves() {
        val ctx = GlobalContext.get()
        try {
            val chatService: ChatService = ctx.get()
            println(">>> ChatService resolved OK: $chatService")
        } catch (e: Throwable) {
            println(">>> ChatService FAILED\n${causeChainOf(e)}")
            throw AssertionError("ChatService resolution failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    @Test
    fun acpRuntime_resolves() {
        val ctx = GlobalContext.get()
        try {
            val acp: AcpRuntime = ctx.get()
            println(">>> AcpRuntime resolved OK: $acp")
        } catch (e: Throwable) {
            println(">>> AcpRuntime FAILED\n${causeChainOf(e)}")
            throw AssertionError("AcpRuntime resolution failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }

    @Test
    fun chatVM_resolves_with_param() {
        val ctx = GlobalContext.get()
        try {
            val vm: me.rerere.rikkahub.ui.pages.chat.ChatVM = ctx.get(
                parameters = { parametersOf("00000000-0000-0000-0000-000000000001") }
            )
            println(">>> ChatVM resolved OK: $vm")
        } catch (e: Throwable) {
            println(">>> ChatVM FAILED\n${causeChainOf(e)}")
            throw AssertionError("ChatVM resolution failed: ${e.javaClass.name}: ${e.message}", e)
        }
    }
}
