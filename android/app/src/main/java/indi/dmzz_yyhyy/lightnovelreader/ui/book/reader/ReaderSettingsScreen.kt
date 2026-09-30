package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
// LNR 引入适配：material3 ModalBottomSheet/SecondaryTabRow → MiuiX OverlayBottomSheet/TabRow（4.4 守卫）
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hyperreader.R
import io.nightfish.lightnovelreader.api.ui.components.SettingsMenuEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsSliderEntry
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions
import io.nightfish.lightnovelreader.api.ui.components.SettingsClickableEntry
import io.nightfish.lightnovelreader.api.ui.components.SettingsSwitchEntry

@Composable
fun SettingsBottomSheet(
    onDismissRequest: () -> Unit,
    settingState: SettingState,
    onClickReaderStyleSettings: () -> Unit
) {
    // LNR 引入适配：material3 ModalBottomSheet → MiuiX OverlayBottomSheet
    // （shape/containerColor/tonalElevation 由 MiuiX 主题接管；show 由调用方条件渲染控制）
    OverlayBottomSheet(
        show = true,
        onDismissRequest = onDismissRequest,
    ) {
        var selectedTabIndex by remember { mutableIntStateOf(0) }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MiuixTheme.textStyles.title2,
                text = stringResource(R.string.reader_settings),
                fontWeight = FontWeight.W600
            )
            ContentSettings(
                settingState = settingState,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { index -> selectedTabIndex = index },
                onClickReaderStyleSettings = onClickReaderStyleSettings
            )
        }
    }
}

data class TabItem(val title: String, val iconRes: Int)

@Composable
fun ContentSettings(
    settingState: SettingState,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onClickReaderStyleSettings: () -> Unit
) {
    val tabs = listOf(
        TabItem(stringResource(R.string.appearance_settings), R.drawable.filled_menu_book_24px),
        TabItem(stringResource(R.string.control_settings), R.drawable.settings_applications_24px),
        TabItem(stringResource(R.string.margin_settings), R.drawable.aspect_ratio_24px),
    )

    val pagerState = rememberPagerState(initialPage = selectedTabIndex, pageCount = { tabs.size })

    LaunchedEffect(selectedTabIndex) {
        pagerState.scrollToPage(selectedTabIndex)
    }
    LaunchedEffect(pagerState.currentPage) {
        onTabSelected(pagerState.currentPage)
    }

    Column {
        TabsRow(
            tabs = tabs,
            selectedTabIndex = selectedTabIndex,
            onTabSelected = onTabSelected
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            userScrollEnabled = false
        ) { pageIndex ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                when (pageIndex) {
                    0 -> AppearancePage(settingState, onClickReaderStyleSettings)
                    1 -> ActionPage(settingState)
                    2 -> PaddingPage(settingState)
                }
            }
        }
    }
}

@Composable
fun TabsRow(
    tabs: List<TabItem>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    // LNR 引入适配：material3 SecondaryTabRow/Tab/SecondaryIndicator（三槽结构）→
    // MiuiX TabRow（文字标签 + 内置指示器）。TabItem.iconRes 不再渲染，调用方签名不变。
    TabRow(
        tabs = tabs.map { it.title },
        selectedTabIndex = selectedTabIndex,
        onTabSelected = onTabSelected,
        modifier = Modifier.fillMaxWidth(),
    )
}

fun LazyListScope.AppearancePage(
    settingState: SettingState,
    onClickReaderStyleSettings: () -> Unit
) {
    item {
        SettingsClickableEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.imagesearch_roller_24px),
            title = stringResource(R.string.settings_reader_style),
            description = stringResource(R.string.settings_reader_style_desc),
            onClick = onClickReaderStyleSettings
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.lightbulb_24px),
            title = stringResource(R.string.settings_reader_keep_screen_on),
            description = stringResource(R.string.settings_reader_keep_screen_on_desc),
            checked = settingState.keepScreenOn,
            booleanUserData = settingState.keepScreenOnUserData,
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.toolbar_24px),
            title = stringResource(R.string.settings_hide_status_bar),
            description = stringResource(R.string.settings_hide_status_bar_desc),
            checked = settingState.enableHideStatusBar,
            booleanUserData = settingState.enableHideStatusBarUserData,
        )
    }
    item {
        SettingsMenuEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.battery_android_full_24px),
            title = stringResource(R.string.settings_reader_battery_indicator),
            description = stringResource(R.string.settings_reader_battery_indicator_desc),
            options = MenuOptions.ReaderIndicatorBatteryDisplayMode,
            selectedOptionKey = settingState.batteryIndicatorDisplayMode,
            stringUserData = settingState.batteryIndicatorDisplayModeUserData
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.outline_schedule_24px),
            title = stringResource(R.string.settings_reader_time_indicator),
            description = stringResource(R.string.settings_reader_time_indicator_desc),
            checked = settingState.enableTimeIndicator,
            booleanUserData = settingState.enableTimeIndicatorUserData,
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.contract_24px),
            title = stringResource(R.string.settings_reader_chapter_indicator),
            description = stringResource(R.string.settings_reader_chapter_indicator_desc),
            checked = settingState.enableChapterTitleIndicator,
            booleanUserData = settingState.enableChapterTitleIndicatorUserData,
        )
    }
    item {
        SettingsSwitchEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.clock_loader_40_24px),
            title = stringResource(R.string.settings_reader_progress_indicator),
            description = stringResource(R.string.settings_reader_progress_indicator_desc),
            checked = settingState.enableReadingChapterProgressIndicator,
            booleanUserData = settingState.enableReadingChapterProgressIndicatorUserData,
        )
    }
}

fun LazyListScope.ActionPage(settingState: SettingState) {
    item {
        SettingsSwitchEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.menu_book_24px),
            title = stringResource(R.string.settings_reader_page_mode),
            description = stringResource(R.string.settings_reader_page_mode_desc),
            checked = settingState.isUsingFlipPage,
            booleanUserData = settingState.isUsingFlipPageUserData,
        )
    }
    item {
        SettingsMenuEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            painter = painterResource(R.drawable.block_24px),
            title = stringResource(R.string.settings_reader_back_block_mode),
            description = stringResource(R.string.settings_reader_back_block_mode_desc),
            options = MenuOptions.ReaderBackBlockMode,
            selectedOptionKey = settingState.backBlockMode,
            stringUserData = settingState.backBlockModeUserData
        )
    }
    if (settingState.isUsingFlipPage) {
        item {
            SettingsSwitchEntry(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .animateItem(),
                painter = painterResource(R.drawable.auto_stories_24px),
                title = stringResource(R.string.settings_reader_volume_key_control),
                description = stringResource(R.string.settings_reader_volume_key_control_desc),
                checked = settingState.isUsingVolumeKeyFlip,
                booleanUserData = settingState.isUsingVolumeKeyFlipUserData,
            )
        }
        if (settingState.isUsingVolumeKeyFlip) {
            item {
                val steps = listOf(-1f, 0.1f, 0.2f, 0.3f, 0.5f, 0.8f, 1.0f, 2.0f, 4.0f)
                SettingsSliderEntry(
                    modifier = Modifier
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                        .animateItem(),
                    painter = painterResource(R.drawable.timer_24px),
                    title = stringResource(R.string.settings_reader_volume_key_interval),
                    unit = "s",
                    value = settingState.volumeKeyContinuousFlipInterval,
                    valueRange = steps.first()..steps.last(),
                    steps = steps,
                    floatUserData = settingState.volumeKeyContinuousFlipIntervalUserData,
                )
            }
        }
    }
    if (settingState.isUsingFlipPage) {
        item {
            SettingsSwitchEntry(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .animateItem(),
                painter = painterResource(R.drawable.touch_app_24px),
                title = stringResource(R.string.settings_reader_t2tp),
                description = stringResource(R.string.settings_reader_t2tp_desc),
                checked = settingState.isUsingClickFlipPage,
                booleanUserData = settingState.isUsingClickFlipPageUserData,
            )
        }
    }
    if (settingState.isUsingFlipPage) {
        item {
            SettingsMenuEntry(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .animateItem(),
                painter = painterResource(R.drawable.transition_chop_24px),
                title = stringResource(R.string.settings_reader_page_turn_anim),
                description = stringResource(R.string.settings_reader_page_turn_anim_desc),
                options = MenuOptions.FlipAnimationOptions,
                selectedOptionKey = settingState.flipAnime,
                stringUserData = settingState.flipAnimeUserData
            )
        }
    }
}

fun LazyListScope.PaddingPage(settingState: SettingState) {
    item {
        SettingsSwitchEntry(
            modifier = Modifier
                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                .animateItem(),
            title = stringResource(R.string.settings_reader_auto_margin),
            description = stringResource(R.string.settings_reader_auto_margin_desc),
            checked = settingState.autoPadding,
            booleanUserData = settingState.autoPaddingUserData,
        )
    }
    if (!settingState.autoPadding) {
        item {
            SettingsSliderEntry(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .animateItem(),
                title = stringResource(R.string.settings_reader_top_margin),
                unit = "dp",
                valueRange = 0f..128f,
                value = settingState.topPadding,
                floatUserData = settingState.topPaddingUserData
            )
        }
    }
    if (!settingState.autoPadding) {
        item {
            SettingsSliderEntry(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .animateItem(),
                title = stringResource(R.string.settings_reader_bottom_margin),
                unit = "dp",
                valueRange = 0f..128f,
                value = settingState.bottomPadding,
                floatUserData = settingState.bottomPaddingUserData
            )
        }
    }
    if (!settingState.autoPadding) {
        item {
            SettingsSliderEntry(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .animateItem(),
                title = stringResource(R.string.settings_reader_left_margin),
                unit = "dp",
                valueRange = 0f..128f,
                value = settingState.leftPadding,
                floatUserData = settingState.leftPaddingUserData
            )
        }
    }
    if (!settingState.autoPadding) {
        item {
            SettingsSliderEntry(
                modifier = Modifier
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .animateItem(),
                title = stringResource(R.string.settings_reader_right_margin),
                unit = "dp",
                valueRange = 0f..128f,
                value = settingState.rightPadding,
                floatUserData = settingState.rightPaddingUserData
            )
        }
    }
}