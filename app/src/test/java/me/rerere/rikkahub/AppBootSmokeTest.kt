package me.rerere.rikkahub

import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppBootSmokeTest {
    @Test
    fun application_initializes_without_crashing() {
        val app = RuntimeEnvironment.getApplication()
        assertNotNull("RikkaHubApp must initialize (Koin/DB) without crashing", app)
    }
}
