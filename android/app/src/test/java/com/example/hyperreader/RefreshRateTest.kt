package com.example.hyperreader

import com.example.hyperreader.settings.RefreshRate
import com.xyreader.core.ReaderPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefreshRateTest {

    @Test
    fun invalidOrMissingNamesFallBackToAdaptive() {
        assertEquals(RefreshRate.ADAPTIVE, RefreshRate.fromName(null))
        assertEquals(RefreshRate.ADAPTIVE, RefreshRate.fromName(""))
        assertEquals(RefreshRate.ADAPTIVE, RefreshRate.fromName("HZ_144"))
        assertEquals(RefreshRate.HZ_120, RefreshRate.fromName("HZ_120"))
    }

    @Test
    fun onlyFixedModesCarryATargetFrameRate() {
        assertNull(RefreshRate.ADAPTIVE.hz)
        assertEquals(60f, RefreshRate.HZ_60.hz)
        assertEquals(90f, RefreshRate.HZ_90.hz)
        assertEquals(120f, RefreshRate.HZ_120.hz)
    }

    @Test
    fun autoPageTurnIsOffByDefault() {
        assertEquals(0, ReaderPrefs().autoTurnSeconds)
    }
}
