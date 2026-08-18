package me.rerere.rikkahub.utils

import android.content.res.Configuration
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceScreenMetricsTest {

    private fun baseConfiguration(
        densityDpi: Int = 420,
        screenWidthDp: Int = 411,
        screenHeightDp: Int = 914,
    ) = Configuration().apply {
        this.densityDpi = densityDpi
        this.screenWidthDp = screenWidthDp
        this.screenHeightDp = screenHeightDp
        this.smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
    }

    @Test
    fun `restore mode returns no override`() {
        val config = baseConfiguration()
        assertNull(
            DeviceScreenMetrics.buildOverrideConfiguration(config, DeviceScreenMetrics.MODE_NONE, 160)
        )
    }

    @Test
    fun `tablet mode scales logical screen to tablet size`() {
        val config = baseConfiguration()
        val override = DeviceScreenMetrics.buildOverrideConfiguration(
            config, DeviceScreenMetrics.MODE_TABLET, 160
        )!!
        assertEquals(160, override.densityDpi)
        assertTrue("tablet mode must produce a wider logical screen", override.screenWidthDp > config.screenWidthDp)
        assertTrue("tablet mode must produce a taller logical screen", override.screenHeightDp > config.screenHeightDp)
        assertEquals(
            minOf(override.screenWidthDp, override.screenHeightDp),
            override.smallestScreenWidthDp,
        )
    }

    @Test
    fun `custom mode uses provided density and scales accordingly`() {
        val config = baseConfiguration()
        val override = DeviceScreenMetrics.buildOverrideConfiguration(
            config, DeviceScreenMetrics.MODE_CUSTOM, 240
        )!!
        assertEquals(240, override.densityDpi)
        val scale = 420 / 240f
        assertEquals((411 * scale).roundToInt(), override.screenWidthDp)
        assertEquals((914 * scale).roundToInt(), override.screenHeightDp)
    }

    @Test
    fun `custom mode with invalid density returns null`() {
        val config = baseConfiguration()
        assertNull(
            DeviceScreenMetrics.buildOverrideConfiguration(config, DeviceScreenMetrics.MODE_CUSTOM, 80)
        )
        assertNull(
            DeviceScreenMetrics.buildOverrideConfiguration(config, DeviceScreenMetrics.MODE_CUSTOM, 900)
        )
    }

    @Test
    fun `modeDensityDpi maps modes correctly`() {
        assertEquals(DeviceScreenMetrics.DEFAULT_TABLET_DENSITY_DPI,
            DeviceScreenMetrics.modeDensityDpi(DeviceScreenMetrics.MODE_TABLET, 0))
        assertEquals(240, DeviceScreenMetrics.modeDensityDpi(DeviceScreenMetrics.MODE_CUSTOM, 240))
        assertNull(DeviceScreenMetrics.modeDensityDpi(DeviceScreenMetrics.MODE_NONE, 160))
    }
}
