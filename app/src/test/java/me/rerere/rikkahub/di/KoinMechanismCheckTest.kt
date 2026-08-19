package me.rerere.rikkahub.di

import androidx.lifecycle.ViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.parameter.parametersOf
import org.koin.dsl.module

private class FakeRepo

private class FakeSettings

private class FakeVM(
    val id: String?,
    val repo: FakeRepo,
    val settings: FakeSettings,
) : ViewModel()

class KoinMechanismCheckTest {

    @Before
    fun setUp() {
        stopKoin()
    }

    @After
    fun tearDown() {
        stopKoin()
    }

    @Test
    fun `empty string parameter resolves and dependencies come from scope`() {
        startKoin {
            modules(
                module {
                    viewModel<FakeVM> {
                        FakeVM(
                            id = it.get(),
                            repo = get(),
                            settings = get(),
                        )
                    }
                    single { FakeRepo() }
                    single { FakeSettings() }
                }
            )
        }
        val vm = GlobalContext.get().get<FakeVM>(
            parameters = { parametersOf("") }
        )
        assertEquals("id 参数应为空串", "", vm.id)
        assertEquals(FakeRepo::class, vm.repo::class)
        assertEquals(FakeSettings::class, vm.settings::class)
    }

    @Test
    fun `null parameter throws InstanceCreationException`() {
        startKoin {
            modules(
                module {
                    viewModel<FakeVM> {
                        FakeVM(
                            id = it.get(),
                            repo = get(),
                            settings = get(),
                        )
                    }
                    single { FakeRepo() }
                    single { FakeSettings() }
                }
            )
        }
        try {
            GlobalContext.get().get<FakeVM>(
                parameters = { parametersOf(null as String?) }
            )
            fail("Koin 无法解析 null 参数，应抛出 InstanceCreationException")
        } catch (e: Throwable) {
            val isInstanceCreation = e.javaClass.name.contains("InstanceCreationException")
                || (e.cause?.javaClass?.name?.contains("InstanceCreationException") == true)
                || (e.cause?.cause?.javaClass?.name?.contains("InstanceCreationException") == true)
            println(">>> null 参数异常类型: ${e.javaClass.name}, cause=${e.cause?.javaClass?.name}")
            if (!isInstanceCreation) {
                throw AssertionError("期望 InstanceCreationException，实际 ${e.javaClass.name} cause=${e.cause}", e)
            }
        }
    }

    @Test
    fun `non-null parameter resolves`() {
        startKoin {
            modules(
                module {
                    viewModel<FakeVM> {
                        FakeVM(
                            id = it.get(),
                            repo = get(),
                            settings = get(),
                        )
                    }
                    single { FakeRepo() }
                    single { FakeSettings() }
                }
            )
        }
        val vm = GlobalContext.get().get<FakeVM>(
            parameters = { parametersOf("abc") }
        )
        assertEquals("abc", vm.id)
        assertEquals(FakeRepo::class, vm.repo::class)
        assertEquals(FakeSettings::class, vm.settings::class)
    }

    @Test
    fun `missing parameter throws InstanceCreationException`() {
        startKoin {
            modules(
                module {
                    viewModel<FakeVM> {
                        FakeVM(
                            id = it.get(),
                            repo = get(),
                            settings = get(),
                        )
                    }
                    single { FakeRepo() }
                    single { FakeSettings() }
                }
            )
        }
        try {
            GlobalContext.get().get<FakeVM>()
            fail("缺少参数应抛出 InstanceCreationException")
        } catch (e: Throwable) {
            val isInstanceCreation = e.javaClass.name.contains("InstanceCreationException")
                || (e.cause?.javaClass?.name?.contains("InstanceCreationException") == true)
                || (e.cause?.cause?.javaClass?.name?.contains("InstanceCreationException") == true)
            println(">>> 缺少参数时异常类型: ${e.javaClass.name}, cause=${e.cause?.javaClass?.name}")
            if (!isInstanceCreation) {
                throw AssertionError("期望 InstanceCreationException，实际 ${e.javaClass.name} cause=${e.cause}", e)
            }
        }
    }
}
