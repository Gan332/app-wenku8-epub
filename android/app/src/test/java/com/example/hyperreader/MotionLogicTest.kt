package com.example.hyperreader

import com.example.hyperreader.ui.Motion
import com.example.hyperreader.ui.UiDimens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 动效纯逻辑（0.18.0）的夹具。
 *
 * 关键约束（AGENTS §4.4 + 性能）：
 * - 系统「移除动画」开启时延迟必须为 0；
 * - 长列表只对首屏附近的项累加延迟，否则靠后的项要等数秒才出现。
 */
class MotionLogicTest {

    @Test
    fun staggerAccumulatesOnlyForFirstScreen() {
        assertEquals(0, Motion.staggeredDelayFor(index = 0, scaleFactor = 1f))
        assertEquals(UiDimens.STAGGER_STEP, Motion.staggeredDelayFor(index = 1, scaleFactor = 1f))
        assertEquals(3 * UiDimens.STAGGER_STEP, Motion.staggeredDelayFor(index = 3, scaleFactor = 1f))
        // 超过首屏上限后不再累加
        val beyond = Motion.STAGGER_VISIBLE_LIMIT + 5
        assertEquals(UiDimens.STAGGER_STEP, Motion.staggeredDelayFor(index = beyond, scaleFactor = 1f))
    }

    @Test
    fun staggerIsDisabledWhenSystemAnimationsAreOff() {
        assertEquals(0, Motion.staggeredDelayFor(index = 5, scaleFactor = 0f))
        assertEquals(0, Motion.staggeredDelayFor(index = 5, scaleFactor = 0f, step = 50))
    }

    @Test
    fun customStepIsHonored() {
        assertEquals(40, Motion.staggeredDelayFor(index = 2, scaleFactor = 1f, step = 20))
    }
}