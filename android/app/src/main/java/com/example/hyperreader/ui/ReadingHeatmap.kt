package com.example.hyperreader.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 阅读热力图（参考 LNR `HeatMapCalendar` + `Levels.kt`）。
 *
 * 与上游的差异，以及原因：
 * - 上游是一套完整可滚动月历（`ScrollableState` + `LazyListState` + 月数据 store），面向跨多年份
 *   的滚动窗口设计。本页是设置里的二级页、容器是普通 `Column`，搬 `ScrollableState` 只会带来
 *   用不上的复杂度，所以改为**一次性绘制整张网格 + 点击命中测试**；网格形状与色阶口径与上游一致。
 * - 上游 [HeatLevel] 的颜色是写死的 `Color(0xFF329c32)` 一类常量。本项目界面统一 MiuiX 且必须
 *   跟随亮/暗主题（AGENTS §4.4），所以色阶由 `MiuixTheme.colorScheme` 现场派生，见 [heatColorsFor]。
 * - 上游给工作日绿、周末蓝两套色。本项目里周末与工作日的阅读量没有值得区分的语义，两套色在
 *   暗色主题下还要多调一遍，故只用一套跟随主题的色阶。
 *
 * 色阶阈值沿用上游口径：不写死分钟数，而是对区间内**非零**阅读时长取 25%/50%/75% 分位
 * （上游 `quickSelect`），轻度与重度读者都能得到分布均匀的五档。
 */

/** 单日着色档位（与 LNR `Levels.kt` 的 `Zero..Four` 一一对应）。 */
enum class HeatLevel { Zero, One, Two, Three, Four }

/**
 * 色阶阈值，单位为分钟。[top] 是最高档门槛，供图例标注「多」那一端。
 * 三者全为 0 表示区间内没有任何阅读记录（上游同款处理）。
 */
data class HeatThresholds(val low: Int, val mid: Int, val top: Int) {
    val isEmpty: Boolean get() = low == 0 && mid == 0 && top == 0
}

/** 一格：某一天及其当日阅读秒数。 */
data class HeatCell(val date: LocalDate, val seconds: Long)

/**
 * 热力图绘制数据：按周分列的格子 + 月份标签的列位置。
 *
 * 网格形状与上游一致：**列 = 周，行 = 周内七天**（周一行首）。区间两端用 null 补空格，
 * 保证格子在月与月之间不错位。
 */
data class HeatGrid(
    val weeks: List<List<HeatCell?>>,
    /** 月份 -> 首次出现的列号，画月份标签用。 */
    val monthColumns: Map<Int, YearMonth>,
    /** 网格第一列周一对应的日期，可能早于统计起点（补位空格之前）。 */
    val gridStart: LocalDate,
)

/**
 * 取分位数。下标公式 `(size * fraction).toInt()` 与上游 `quickSelect` 完全一致
 * （上游用 quickselect 求同一位置的值，结果等于排序后取该下标）。
 *
 * 入参须**已排序**且已剔除 0；空集合返回 0。
 */
fun quickSelectMinutes(sortedNonZeroMinutes: List<Int>, fraction: Double): Int {
    if (sortedNonZeroMinutes.isEmpty()) return 0
    val index = (sortedNonZeroMinutes.size * fraction).toInt()
        .coerceIn(sortedNonZeroMinutes.indices)
    return sortedNonZeroMinutes[index]
}

/**
 * 按 25%/50%/75% 分位算出色阶阈值（上游 `generateLevelMap` 的口径）。
 *
 * 只有非零日期参与分位：大量「没读的日子」会把阈值压到 0，整张图退化成全空档。
 */
fun heatThresholds(dailySeconds: Map<String, Long>): HeatThresholds {
    val nonZero = dailySeconds.values.filter { it > 0 }.map { (it / 60).toInt() }.sorted()
    if (nonZero.isEmpty()) return HeatThresholds(0, 0, 0)
    return HeatThresholds(
        low = quickSelectMinutes(nonZero, 0.25),
        mid = quickSelectMinutes(nonZero, 0.50),
        top = quickSelectMinutes(nonZero, 0.75),
    )
}

/** 把某一天的秒数映射到色阶档位。 */
fun heatLevelFor(seconds: Long, thresholds: HeatThresholds): HeatLevel {
    if (thresholds.isEmpty || seconds <= 0) return HeatLevel.Zero
    val minutes = seconds / 60
    return when {
        minutes >= thresholds.top -> HeatLevel.Four
        minutes >= thresholds.mid -> HeatLevel.Three
        minutes >= thresholds.low -> HeatLevel.Two
        else -> HeatLevel.One
    }
}

/**
 * 把日期区间切成「列 = 周、行 = 周内七天」的网格。
 *
 * 周一为一周之首（上游 `firstDayOfWeek = DayOfWeek.MONDAY`）。区间起点之前与终点之后补空格，
 * 使每列的星期行对齐。
 */
fun buildHeatGrid(startDate: LocalDate, endDate: LocalDate, dailySeconds: Map<String, Long>): HeatGrid {
    if (endDate < startDate) return HeatGrid(emptyList(), emptyMap(), startDate)

    // 周一 = 一周之首：起点距当周周一的天数。
    val leadingBlanks = (startDate.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val gridStart = startDate.minusDays(leadingBlanks.toLong())
    val spanDays = ChronoUnit.DAYS.between(gridStart, endDate).toInt() + 1
    val totalCells = ((spanDays + 6) / 7) * 7

    val weeks = ArrayList<List<HeatCell?>>()
    val monthColumns = LinkedHashMap<Int, YearMonth>()
    var lastMonth: YearMonth? = null
    for (offset in 0 until totalCells) {
        if (offset % 7 == 0) weeks.add(arrayOfNulls<HeatCell>(7).toList())
        val date = gridStart.plusDays(offset.toLong())
        if (date < startDate || date > endDate) continue
        val yearMonth = YearMonth.from(date)
        // 月份标签只画在该月**首次出现**的列；跨列时月份推进一次记一次。
        if (yearMonth != lastMonth) {
            monthColumns[offset / 7] = yearMonth
            lastMonth = yearMonth
        }
        val row = offset % 7
        weeks[weeks.lastIndex] = weeks.last().toMutableList().also { it[row] = HeatCell(date, dailySeconds[date.toString()] ?: 0L) }
    }
    return HeatGrid(weeks, monthColumns, gridStart)
}

/**
 * 点击坐标命中的格子。落在补位空格、网格左侧标签区或右侧留白时返回 null。
 *
 * [offsetX]/[offsetY] 是网格内容区左上角在画布内的位置。
 */
fun hitTestHeatGrid(
    grid: HeatGrid,
    cellSize: Float,
    gap: Float,
    offsetX: Float,
    offsetY: Float,
    x: Float,
    y: Float,
): HeatCell? {
    if (x < offsetX || y < offsetY) return null
    val pitch = cellSize + gap
    val column = ((x - offsetX) / pitch).toInt()
    val row = ((y - offsetY) / pitch).toInt()
    // 命中格需要完全落在格内，避免点击相邻格的间隙时选中错日。
    if ((x - offsetX) % pitch > cellSize || (y - offsetY) % pitch > cellSize) return null
    if (row !in 0..6 || column !in grid.weeks.indices) return null
    return grid.weeks[column][row]
}

/**
 * 派生五档颜色：从主题强调色逐级加深；Zero 用 `onSurface` 的极低透明度表示「无记录」。
 *
 * 不使用上游的十六进制常量，跟随亮/暗主题（AGENTS §4.4）。
 */
@Composable
fun heatColorsFor(): Map<HeatLevel, Color> {
    val primary = MiuixTheme.colorScheme.primary
    return mapOf(
        HeatLevel.Zero to MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        HeatLevel.One to primary.copy(alpha = 0.35f),
        HeatLevel.Two to primary.copy(alpha = 0.55f),
        HeatLevel.Three to primary.copy(alpha = 0.78f),
        HeatLevel.Four to primary,
    )
}

// —— 布局尺寸 ——
// 格子边长、圆角、网格内间距是图形本身的装饰参数，与控件尺寸无关，故不走 UiDimens 令牌
// （AGENTS §4.4 明确允许装饰性小值直接写）。
private val CellSize: Dp = 13.dp
private val CellGap: Dp = 3.dp
private val CellCorner: Dp = 2.dp
/** 月份标签行高，与上游 `MonthHeader` 的字号量级一致。 */
private val MonthLabelHeight: Dp = 14.dp
/** 左侧周内标签宽度，够放「一/三/五/日」单字。 */
private val SideLabelWidth: Dp = 12.dp
/** 轴标签字号。 */
private val AxisLabelSize = 10.sp

/** 默认展示的周数（约半年）：再多列在窄屏上格子会小到难以点中。 */
private const val DEFAULT_WEEKS = 26L

/** 网格区整体高度：月份标签行 + 7 行格子。 */
private val GridHeight: Dp = MonthLabelHeight + CellSize * 7 + CellGap * 6

/** 网格内容宽度：[weeks] 列按固定格子宽排布。 */
private fun gridContentWidth(weeks: Long): Dp = CellSize * weeks + CellGap * (weeks - 1)

/**
 * 阅读热力图。
 *
 * @param dailySeconds `日期字符串 -> 当日秒数`，直接传 `ReadingStats.dailySeconds`。
 * @param onSelectDate 用户点选某一天时回调；命中测试保证只对真实日期触发。
 */
@Composable
fun ReadingHeatmap(
    dailySeconds: Map<String, Long>,
    modifier: Modifier = Modifier,
    weeks: Long = DEFAULT_WEEKS,
    onSelectDate: ((LocalDate) -> Unit)? = null,
) {
    val today = remember { LocalDate.now() }
    val startDate = remember(today, weeks) { today.minusWeeks(weeks).plusDays(1) }
    val grid = remember(startDate, today, dailySeconds) { buildHeatGrid(startDate, today, dailySeconds) }
    val thresholds = remember(dailySeconds) { heatThresholds(dailySeconds) }
    val colors = heatColorsFor()
    val measurer = rememberTextMeasurer()
    // 轴标签颜色在组合期取一次：DrawScope 内不是 @Composable，读不到 MiuixTheme。
    val labelColor = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    val scrollState = rememberScrollState()

    // 网格一进来就定位到最新一列：统计是回看「最近」的习惯，默认停在半年前没有意义。
    LaunchedEffect(grid) { scrollState.scrollTo(((grid.weeks.size - 1) * (CellSize + CellGap)).toInt()) }

    // 入场淡入；系统「移除动画」时 duration 为 0，直接显示（AGENTS §4.4）。
    val appear by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(Motion.duration(UiDimens.MOTION_SLOW), easing = FastOutSlowInEasing),
        label = "heatmapAppear",
    )

    Column(modifier.fillMaxWidth()) {
        // 列数固定（约半年），格子宽度不压缩——压缩到窄屏上会小到点不中。
        // 因此左侧周内标签固定不动，网格横向滚动（同 GitHub 贡献图的做法）。
        Row(Modifier.fillMaxWidth().height(GridHeight)) {
            Canvas(Modifier.size(SideLabelWidth, GridHeight)) {
                val labelStyle = TextStyle(fontSize = AxisLabelSize, color = labelColor)
                val gapPx = CellGap.toPx()
                val pitch = CellSize.toPx() + gapPx
                listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY).forEach { day ->
                    val measured = measurer.measure(day.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault()), labelStyle)
                    drawText(
                        measured,
                        topLeft = Offset(
                            x = (size.width - gapPx - measured.size.width).coerceAtLeast(0f),
                            y = MonthLabelHeight.toPx() + (day.value - 1) * pitch,
                        ),
                    )
                }
            }
            // 绘制与点击共用一个 Canvas：命中测试的尺寸换算与绘制用的是同一组 dp，保证所见即所点。
            Canvas(
                Modifier
                    .width(gridContentWidth(weeks))
                    .height(GridHeight)
                    .horizontalScroll(scrollState)
                    .pointerInput(grid) {
                        val cellPx = CellSize.toPx()
                        val gapPx = CellGap.toPx()
                        detectTapGestures { tap ->
                            hitTestHeatGrid(grid, cellPx, gapPx, 0f, MonthLabelHeight.toPx(), tap.x, tap.y)?.let { cell ->
                                selected = cell.date
                                onSelectDate?.invoke(cell.date)
                            }
                        }
                    },
            ) {
                drawHeatmap(
                    grid = grid,
                    cellSize = CellSize.toPx(),
                    gap = CellGap.toPx(),
                    offsetY = MonthLabelHeight.toPx(),
                    thresholds = thresholds,
                    colors = colors,
                    selected = selected,
                    appear = appear,
                    measurer = measurer,
                    labelColor = labelColor,
                )
            }
        }
        Spacer(Modifier.height(UiDimens.spaceS))
        HeatLegend(thresholds, colors)
    }
}

private fun DrawScope.drawHeatmap(
    grid: HeatGrid,
    cellSize: Float,
    gap: Float,
    offsetY: Float,
    thresholds: HeatThresholds,
    colors: Map<HeatLevel, Color>,
    selected: LocalDate?,
    appear: Float,
    measurer: TextMeasurer,
    labelColor: Color,
) {
    val pitch = cellSize + gap
    val labelStyle = TextStyle(fontSize = AxisLabelSize, color = labelColor)
    val corner = CornerRadius(CellCorner.toPx())

    // 月份标签（上游 `MonthHeader`：1 月带年份，其余只显示月份）。
    grid.monthColumns.forEach { (column, yearMonth) ->
        if (column >= grid.weeks.size) return@forEach
        val text = if (yearMonth.month == Month.JANUARY) {
            "${yearMonth.year}年"
        } else {
            yearMonth.month.getDisplayName(JavaTextStyle.SHORT, Locale.getDefault())
        }
        val measured = measurer.measure(text, labelStyle)
        val x = column * pitch
        // 标签超出画布右缘就跳过，末列最容易发生。
        if (x + measured.size.width > size.width) return@forEach
        drawText(measured, topLeft = Offset(x, 0f))
    }

    grid.weeks.forEachIndexed { column, week ->
        week.forEachIndexed { row, cell ->
            val topLeft = Offset(column * pitch, offsetY + row * pitch)
            val color = colors.getValue(heatLevelFor(cell?.seconds ?: 0L, thresholds))
            drawRoundRect(color = color.copy(alpha = color.alpha * appear), topLeft = topLeft, size = Size(cellSize, cellSize), cornerRadius = corner)
            // 选中态描边而非换填充色，这样深浅两档都看得清（上游用 border，这里同思路）。
            if (cell != null && cell.date == selected) {
                drawRoundRect(
                    color = colors.getValue(HeatLevel.Four).copy(alpha = appear),
                    topLeft = topLeft,
                    size = Size(cellSize, cellSize),
                    cornerRadius = corner,
                    style = Stroke(width = 1.5f),
                )
            }
        }
    }
}

/** 图例：「少 → 多」，右端标注最高档阈值（上游 `heatmap_indicator_more` 的口径）。 */
@Composable
private fun HeatLegend(thresholds: HeatThresholds, colors: Map<HeatLevel, Color>) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("少", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f))
        Spacer(Modifier.size(UiDimens.spaceXS))
        HeatLevel.entries.forEach { level ->
            Canvas(Modifier.size(CellSize).padding(end = UiDimens.spaceXXS)) {
                drawRoundRect(
                    color = colors.getValue(level),
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(CellCorner.toPx()),
                )
            }
        }
        Spacer(Modifier.size(UiDimens.spaceXS))
        Text(
            text = if (thresholds.isEmpty) "暂无记录" else "多（${thresholds.top} 分钟）",
            fontSize = UiDimens.captionSmall,
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
