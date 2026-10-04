package com.example.hyperreader

import com.example.hyperreader.ui.HeatLevel
import com.example.hyperreader.ui.HeatThresholds
import com.example.hyperreader.ui.buildHeatGrid
import com.example.hyperreader.ui.heatLevelFor
import com.example.hyperreader.ui.heatThresholds
import com.example.hyperreader.ui.hitTestHeatGrid
import com.example.hyperreader.ui.quickSelectMinutes
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 阅读热力图的纯逻辑（阈值分档、网格切分、命中测试）。 */
class ReadingHeatmapTest {

    // —— 分位数 ——

    @Test
    fun quickSelectUsesLnrIndexFormula() {
        val sorted = listOf(1, 2, 3, 4, 5, 6, 7, 8)
        // (8 * 0.25).toInt() = 2 -> 第 3 个
        assertEquals(3, quickSelectMinutes(sorted, 0.25))
        assertEquals(5, quickSelectMinutes(sorted, 0.50))
        // (8 * 0.75).toInt() = 6 -> 第 7 个
        assertEquals(7, quickSelectMinutes(sorted, 0.75))
    }

    @Test
    fun quickSelectOnEmptyListIsZero() {
        assertEquals(0, quickSelectMinutes(emptyList(), 0.5))
    }

    @Test
    fun quickSelectClampsIndexIntoRange() {
        // 分位接近 1 时下标可能越过末元素，必须夹住而不是崩。
        val sorted = listOf(4, 9)
        assertEquals(9, quickSelectMinutes(sorted, 0.99))
    }

    // —— 阈值 ——

    @Test
    fun thresholdsIgnoreZeroDaysSoSparseHistoryStillRenders() {
        val daily = mapOf(
            "2026-10-01" to 600L, // 10 分
            "2026-10-02" to 1200L, // 20 分
            "2026-10-03" to 1800L, // 30 分
        )
        val t = heatThresholds(daily)
        assertEquals(10, t.low)
        assertEquals(20, t.mid)
        assertEquals(30, t.top)
    }

    @Test
    fun thresholdsAreEmptyWhenNothingRecorded() {
        val t = heatThresholds(emptyMap())
        assertTrue("无记录时应为空阈值", t.isEmpty)
        assertEquals(0, t.top)
    }

    @Test
    fun zeroEntriesDoNotDragThresholdsDown() {
        // 大量没读的日子（0 秒）若参与分位，阈值会塌到 0，整张图退化成空档。
        val daily = buildMap {
            put("2026-10-01", 300L)
            put("2026-10-02", 900L)
            repeat(40) { put("2026-09-${(it + 1).toString().padStart(2, '0')}", 0L) }
        }
        // 分钟值是 [5, 15]（size=2，0.5*2=1 偏向下标 1），分位全落在 5 或 15。
        assertEquals(HeatThresholds(5, 15, 15), heatThresholds(daily))
    }

    @Test
    fun subMinuteEntriesDoNotDragLowThresholdToZero() {
        // 30 秒与 1 分钟混在一起时，若先按秒剔除 0 再整除，30 秒会变成 0 分钟参与分位，
        // 把 low 压到 0，图例显示「0 分钟」。换算顺序必须是「先整除、再剔除」。
        val thresholds = heatThresholds(mapOf("2026-10-01" to 30L, "2026-10-02" to 600L))
        assertEquals("sub-minute reading must not zero the low threshold", 10, thresholds.low)
        assertEquals(HeatThresholds(10, 10, 10), thresholds)
    }

    // —— 分档 ——

    @Test
    fun levelsFollowThresholdsInAscendingOrder() {
        val t = HeatThresholds(10, 20, 30)
        assertEquals(HeatLevel.Zero, heatLevelFor(0L, t))
        assertEquals(HeatLevel.One, heatLevelFor(5 * 60L, t))
        assertEquals(HeatLevel.Two, heatLevelFor(10 * 60L, t))
        assertEquals(HeatLevel.Three, heatLevelFor(20 * 60L, t))
        assertEquals(HeatLevel.Four, heatLevelFor(30 * 60L, t))
    }

    @Test
    fun everythingIsZeroLevelWhenNoHistory() {
        val empty = HeatThresholds(0, 0, 0)
        assertEquals(HeatLevel.Zero, heatLevelFor(3600L, empty))
    }

    @Test
    fun subMinuteReadingLandsInLowestNonZeroBucket() {
        // 59 秒换算成 0 分钟；但它本身是「读过」，不该与「完全没读」的 0 秒同色。
        // 阈值全为 0 时（无任何记录）才是 Zero。
        assertEquals(HeatLevel.Zero, heatLevelFor(59L, HeatThresholds(0, 0, 0)))
        assertEquals(HeatLevel.One, heatLevelFor(59L, HeatThresholds(5, 10, 20)))
        assertEquals(HeatLevel.Zero, heatLevelFor(0L, HeatThresholds(5, 10, 20)))
    }

    // —— 网格 ——

    @Test
    fun gridHasSevenRowsAndMondayFirstColumn() {
        // 2026-10-05 是周一。
        val start = LocalDate.of(2026, 10, 5)
        val grid = buildHeatGrid(start, start.plusDays(20), emptyMap())
        assertTrue("每列必须 7 行", grid.weeks.all { it.size == 7 })
        assertEquals(DayOfWeek.MONDAY, grid.weeks.first().first()!!.date.dayOfWeek)
        // 第一列第二行是周二。
        assertEquals(DayOfWeek.TUESDAY, grid.weeks.first()[1]!!.date.dayOfWeek)
    }

    @Test
    fun gridPadsBeforeStartWhenStartIsMidWeek() {
        // 2026-10-07 是周三，其所在周的周一为 10-05，应补两格空格。
        val start = LocalDate.of(2026, 10, 7)
        val grid = buildHeatGrid(start, LocalDate.of(2026, 10, 20), emptyMap())
        assertEquals(LocalDate.of(2026, 10, 5), grid.gridStart)
        assertNull("补位空格不应有日期", grid.weeks.first()[0])
        assertNull("补位空格不应有日期", grid.weeks.first()[1])
        assertEquals(start, grid.weeks.first()[2]!!.date)
    }

    @Test
    fun gridPadsAfterEndSoLastColumnHasSevenRows() {
        val start = LocalDate.of(2026, 10, 5)
        val grid = buildHeatGrid(start, LocalDate.of(2026, 10, 20), emptyMap())
        assertEquals("每列都必须是 7 行", 7, grid.weeks.last().size)
        assertNull("末列补位格不应有日期", grid.weeks.last()[5])
    }

    @Test
    fun gridIsEmptyWhenEndPrecedesStart() {
        val grid = buildHeatGrid(LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 5), emptyMap())
        assertTrue(grid.weeks.isEmpty())
    }

    @Test
    fun gridCarriesRecordedSecondsPerDay() {
        val start = LocalDate.of(2026, 10, 5)
        val grid = buildHeatGrid(start, LocalDate.of(2026, 10, 11), mapOf("2026-10-06" to 900L))
        val cell = grid.weeks.first()[1]
        assertEquals(900L, cell?.seconds)
    }

    @Test
    fun monthColumnsRecordEachMonthOnce() {
        val grid = buildHeatGrid(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), emptyMap())
        val months = grid.monthColumns.values.map { it.monthValue }.sorted()
        assertEquals(listOf(9, 10, 11, 12), months)
        // 每个月只应登记一次，避免同月多列都画标签。
        assertEquals(months.size, grid.monthColumns.size)
    }

    @Test
    fun gridCoversEveryDayExactlyOnce() {
        val start = LocalDate.of(2026, 9, 3)
        val end = LocalDate.of(2026, 11, 20)
        val grid = buildHeatGrid(start, end, emptyMap())
        val dates = grid.weeks.flatten().filterNotNull().map { it.date }.sorted()
        // datesUntil 是序列，用 count() 之外再转 Int 与 Int 比较，避免 Long/Integer 混比。
        assertEquals("区间内每一天都应出现且只出现一次", start.until(end.plusDays(1), ChronoUnit.DAYS).toInt(), dates.size)
        assertEquals(start, dates.first())
        assertEquals(end, dates.last())
    }

    // —— 命中测试 ——

    @Test
    fun hitTestFindsCellUnderTap() {
        val start = LocalDate.of(2026, 10, 5)
        val grid = buildHeatGrid(start, start.plusDays(20), emptyMap())
        val cellSize = 10f
        val gap = 2f
        val offsetX = 12f
        val offsetY = 14f
        val cell = hitTestHeatGrid(grid, cellSize, gap, offsetX, offsetY, offsetX + 3f, offsetY + 3f)
        assertEquals(start, cell?.date)
    }

    @Test
    fun hitTestMissesPaddingCell() {
        // 起点为周三，前两格是补位空格：点它们不应命中日期。
        val start = LocalDate.of(2026, 10, 7)
        val grid = buildHeatGrid(start, start.plusDays(20), emptyMap())
        val cell = hitTestHeatGrid(grid, 10f, 2f, 12f, 14f, 12f + 3f, 14f + 3f)
        assertNull("补位空格不应被点中", cell)
    }

    @Test
    fun hitTestIgnoresMonthLabelStripAboveGrid() {
        val start = LocalDate.of(2026, 10, 5)
        val grid = buildHeatGrid(start, start.plusDays(20), emptyMap())
        // offsetY 之上是月份标签行，点它不应命中任何日期。
        assertNull(hitTestHeatGrid(grid, 10f, 2f, 0f, 14f, 15f, 6f))
    }

    @Test
    fun hitTestMissesLabelAreaAndGaps() {
        val start = LocalDate.of(2026, 10, 5)
        val grid = buildHeatGrid(start, start.plusDays(20), emptyMap())
        // 左侧周内标签区
        assertNull(hitTestHeatGrid(grid, 10f, 2f, 12f, 14f, 2f, 20f))
        // 顶部月份标签区
        assertNull(hitTestHeatGrid(grid, 10f, 2f, 12f, 14f, 20f, 4f))
        // 两列之间的缝隙
        assertNull(hitTestHeatGrid(grid, 10f, 2f, 12f, 14f, 12f + 11f, 14f + 3f))
    }

    @Test
    fun hitTestMissesBeyondLastColumn() {
        val start = LocalDate.of(2026, 10, 5)
        val grid = buildHeatGrid(start, start.plusDays(6), emptyMap())
        val far = 12f + grid.weeks.size * 12f + 40f
        assertNull(hitTestHeatGrid(grid, 10f, 2f, 12f, 14f, far, 14f + 3f))
    }

    // —— 尺寸 ——

    @Test
    fun everyCellInGridIsHitTestable() {
        // 命中测试与绘制共用同一套偏移，逐格回扫一遍，确保没有算错的格子点不到。
        val start = LocalDate.of(2026, 10, 5)
        val grid = buildHeatGrid(start, start.plusDays(48), emptyMap())
        val cellSize = 10f
        val gap = 2f
        val offsetX = 12f
        val offsetY = 14f
        grid.weeks.forEachIndexed { column, week ->
            week.forEachIndexed { row, expected ->
                val x = offsetX + column * (cellSize + gap) + cellSize / 2f
                val y = offsetY + row * (cellSize + gap) + cellSize / 2f
                assertEquals(expected, hitTestHeatGrid(grid, cellSize, gap, offsetX, offsetY, x, y))
            }
        }
    }
}
