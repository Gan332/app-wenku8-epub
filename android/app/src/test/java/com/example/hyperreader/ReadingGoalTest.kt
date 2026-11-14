package com.example.hyperreader

import com.example.hyperreader.ui.readingGoalProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/** 每日阅读目标的完成度换算。 */
class ReadingGoalTest {

    @Test
    fun zeroOrNegativeGoalMeansDisabled() {
        assertEquals(0f, readingGoalProgress(3600, 0), 0.0001f)
        assertEquals(0f, readingGoalProgress(3600, -5), 0.0001f)
    }

    @Test
    fun progressIsHalfwayAtHalfTheGoal() {
        assertEquals(0.5f, readingGoalProgress(15 * 60, 30), 0.0001f)
    }

    @Test
    fun progressIsClampedToFull() {
        assertEquals(1f, readingGoalProgress(90 * 60, 30), 0.0001f)
        assertEquals(0f, readingGoalProgress(-10, 30), 0.0001f)
    }
}
