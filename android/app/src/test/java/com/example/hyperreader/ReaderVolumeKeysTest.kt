package com.example.hyperreader

import com.xyreader.core.ReaderVolumeKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音量键翻页桥：关闭时交回系统，开启时消费并给出方向。 */
class ReaderVolumeKeysTest {

    private companion object {
        // android.view.KeyEvent.KEYCODE_VOLUME_UP / VOLUME_DOWN / BACK
        const val VOLUME_UP = 24
        const val VOLUME_DOWN = 25
        const val BACK = 4
    }

    @Test
    fun disabledLeavesVolumeKeysToSystem() {
        ReaderVolumeKeys.clear()
        assertFalse(ReaderVolumeKeys.dispatch(VOLUME_UP))
        assertFalse(ReaderVolumeKeys.dispatch(VOLUME_DOWN))
    }

    @Test
    fun enabledConsumesVolumeKeysAndMapsDirections() {
        var delta = 0
        ReaderVolumeKeys.setHandler(true) { delta = it }
        try {
            assertTrue(ReaderVolumeKeys.dispatch(VOLUME_UP))
            assertEquals(-1, delta)
            assertTrue(ReaderVolumeKeys.dispatch(VOLUME_DOWN))
            assertEquals(1, delta)
            assertFalse(ReaderVolumeKeys.dispatch(BACK))
        } finally {
            ReaderVolumeKeys.clear()
        }
    }
}
