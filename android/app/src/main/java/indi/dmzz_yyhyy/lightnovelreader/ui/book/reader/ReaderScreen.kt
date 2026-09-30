package indi.dmzz_yyhyy.lightnovelreader.ui.book.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context.BATTERY_SERVICE
import android.os.BatteryManager
import android.util.Log
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.basic.TopAppBarScrollBehavior
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOrElse
import com.github.michaelbull.result.map
import com.github.michaelbull.result.onErr
import com.github.michaelbull.result.onOk
import com.example.hyperreader.R
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.ContentComponent
import indi.dmzz_yyhyy.lightnovelreader.ui.book.reader.content.scroll.ScrollContentUiState
import indi.dmzz_yyhyy.lightnovelreader.ui.components.AnimatedText
import indi.dmzz_yyhyy.lightnovelreader.ui.components.AnimatedTextLine
import indi.dmzz_yyhyy.lightnovelreader.ui.components.LnrSnackbar
import indi.dmzz_yyhyy.lightnovelreader.ui.home.settings.data.MenuOptions
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalClaimSnackbarHost
import indi.dmzz_yyhyy.lightnovelreader.utils.LocalSnackbarHost
import indi.dmzz_yyhyy.lightnovelreader.utils.readerBackgroundColor
import indi.dmzz_yyhyy.lightnovelreader.utils.rememberReaderBackgroundPainter
import indi.dmzz_yyhyy.lightnovelreader.utils.showSnackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter", "LocalContextGetResourceValueCall")
@Composable
fun ReaderScreen(
    readingScreenUiState: ReaderScreenUiState,
    settingState: SettingState,
    onClickBackButton: () -> Unit,
    accumulateReadTime: (bookId: String, Int) -> Unit,
    updateTotalReadingTime: (bookId: String, Int) -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    onChangeChapter: (chapterId: String) -> Unit,
    onClickReaderStyleSettings: () -> Unit
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var isImmersive by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHost.current
    val backBlockMode = settingState.backBlockMode
    var lastBackPressTime: Long by remember { mutableLongStateOf(0) }
    var showSettingsBottomSheet by remember { mutableStateOf(false) }
    var showChapterSelectionBottomSheet by remember { mutableStateOf(false) }
    var selectedVolumeId by remember { mutableStateOf("") }

    val coroutineScope = rememberCoroutineScope()
    // LNR 引入适配：M3 的 rememberBottomSheetState/SheetState 关闭协程模式移除 ——
    // OverlayBottomSheet 挂载即展开、onDismissRequest 直接置 false，无需 hide+等待 isVisible

    val claim = LocalClaimSnackbarHost.current

    DisposableEffect(Unit) {
        claim(true)
        onDispose { claim(false) }
    }

    BackHandler {
        when (backBlockMode) {
            MenuOptions.ReaderBackBlockMode.None -> {
                isImmersive = false
                onClickBackButton()
            }

            MenuOptions.ReaderBackBlockMode.DoublePress -> {
                val now = System.currentTimeMillis()
                if (!isImmersive || now - lastBackPressTime < 1500) {
                    onClickBackButton()
                } else {
                    lastBackPressTime = now
                    showSnackbar(
                        coroutineScope = coroutineScope,
                        hostState = snackbarHostState,
                        message = context.getString(R.string.reader_back_press_again),
                        duration = SnackbarDuration.Short
                    )
                }
            }

            MenuOptions.ReaderBackBlockMode.FullyBlocked -> {}
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            isImmersive = false
        }
    }
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AnimatedVisibility(
                visible = !isImmersive,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                TopBar(
                    onClickBackButton = onClickBackButton,
                    title = readingScreenUiState.contentUiState?.readingChapterContent
                        ?.map { it.title }
                        ?.getOrElse { "Unknowing" }
                        ?: "Unknowing",
                    scrollBehavior
                )
            }
        },
        snackbarHost = {
            SnackbarHost(LocalSnackbarHost.current) { data ->
                LnrSnackbar(
                    data,
                    modifier = Modifier
                        .padding(bottom = animateDpAsState(if (isImmersive) 56.dp else 12.dp).value)
                )
            }
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !isImmersive,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                BottomBar(
                    hasNextChapter = readingScreenUiState.contentUiState?.readingChapterContent
                        ?.get()
                        ?.hasNextChapter() ?: false,
                    hasPrevChapter = readingScreenUiState.contentUiState?.readingChapterContent
                        ?.get()
                        ?.hasPrevChapter() ?: false,
                    onClickPrevChapter = onClickPrevChapter,
                    onClickNextChapter = onClickNextChapter,
                    onClickSettings = { showSettingsBottomSheet = true },
                    onClickChapterSelector = { showChapterSelectionBottomSheet = true },
                )
            }
        },
        containerColor = readerBackgroundColor(settingState),
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        val isScrollingLoopBackground =
            readingScreenUiState.contentUiState is ScrollContentUiState &&
                settingState.backgroundImageDisplayMode ==
                MenuOptions.ReaderBgImageDisplayModeOptions.Loop
        if (settingState.enableBackgroundImage && !isScrollingLoopBackground) {
            // LNR 引入适配：Coil 画笔的 state 机已随图片管线移除（Ready 才返回 bitmap 画笔，
            // 失败/加载中返回纯色 fallback），这里不再需要 key(bgState) 重绘
            val bgPainter = rememberReaderBackgroundPainter(settingState)
            Image(
                modifier = Modifier.fillMaxSize(),
                painter = bgPainter,
                contentDescription = null,
                contentScale = ContentScale.Crop
            )
        }

        Content(
            isImmersive = isImmersive,
            readingScreenUiState = readingScreenUiState,
            settingState = settingState,
            accumulateReadingTime = accumulateReadTime,
            updateTotalReadingTime = updateTotalReadingTime,
            onClickPrevChapter = onClickPrevChapter,
            onClickNextChapter = onClickNextChapter,
            onChangeIsImmersive = { isImmersive = !isImmersive }
        )
    }
    if (showSettingsBottomSheet) {
        SettingsBottomSheet(
            onDismissRequest = { showSettingsBottomSheet = false },
            settingState = settingState,
            onClickReaderStyleSettings = onClickReaderStyleSettings
        )
    }

    if (showChapterSelectionBottomSheet) {
        readingScreenUiState.contentUiState?.let { contentUiState ->
            contentUiState.readingChapterId?.let { readingChapterId ->
                ChapterSelectionBottomSheet(
                        selectedVolumeId = selectedVolumeId,
                        bookVolumes = readingScreenUiState.bookVolumes?.get(),
                        readingChapterId = readingChapterId,
                        onDismissRequest = {
                            showChapterSelectionBottomSheet = false
                            selectedVolumeId = readingScreenUiState.bookVolumes?.get()?.volumes
                                ?.firstOrNull { volume ->
                                    volume.chapters.any {
                                        it.id == readingChapterId
                                    }
                                }?.volumeId.orEmpty()
                        },
                        onClickChapter = { chapterId ->
                            onChangeChapter(chapterId)
                            showChapterSelectionBottomSheet = false
                        },
                        onChangeSelectedVolumeId = {
                            selectedVolumeId = it
                        }
                )
            }

            LaunchedEffect(readingScreenUiState.bookVolumes) {
                contentUiState.readingChapterId?.let { chapterId ->
                    readingScreenUiState.bookVolumes?.onOk { bookVolumes ->
                        selectedVolumeId = bookVolumes.volumes.firstOrNull { volume ->
                            volume.chapters.any {
                                it.id == chapterId
                            }
                        }?.volumeId ?: ""
                    }
                }
            }
        }
    }
}


@Composable
fun Content(
    isImmersive: Boolean,
    readingScreenUiState: ReaderScreenUiState,
    settingState: SettingState,
    updateTotalReadingTime: (bookId: String, Int) -> Unit,
    accumulateReadingTime: (bookId: String, Int) -> Unit,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    onChangeIsImmersive: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as Activity
    val window = activity.window
    val density = LocalDensity.current

    val stableSafeTopDp by remember {
        mutableStateOf(
            with(density) {
                WindowInsetsCompat
                    .toWindowInsetsCompat(activity.window.decorView.rootWindowInsets)
                    .getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
                    .top
                    .toDp()
            }
        )
    }

    val originalUiFlags = remember {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility
    }

    var isRunning by remember { mutableStateOf(false) }
    var totalReadingTime by remember { mutableIntStateOf(0) }

    LaunchedEffect(
        isImmersive,
        settingState.enableHideStatusBar,
        settingState.batteryIndicatorDisplayMode
    ) {
        updateReaderImmersiveMode(
            window = window,
            immersive = isImmersive,
            enableHideStatusBar = settingState.enableHideStatusBar,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        @Suppress("deprecation")
        onDispose {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.decorView.systemUiVisibility = originalUiFlags
        }
    }

    LifecycleResumeEffect(Unit) {
        isRunning = true
        onPauseOrDispose {
            isRunning = false
            if (totalReadingTime <= 60) {
                readingScreenUiState.bookId?.let {
                    updateTotalReadingTime(it, totalReadingTime)
                }
            } else {
                Log.e("ReaderScreen", "time counter error, time now is $totalReadingTime over 60s")
            }
            totalReadingTime = 0
        }
    }
    LaunchedEffect(settingState.keepScreenOn) {
        if (settingState.keepScreenOn)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    LaunchedEffect(isRunning) {
        while (isRunning) {
            totalReadingTime += 1
            if (totalReadingTime > 60) {
                readingScreenUiState.bookId?.let {
                    updateTotalReadingTime(it, totalReadingTime)
                }
                totalReadingTime = 0
            }
            delay(1.seconds)
        }
    }

    LaunchedEffect(isRunning) {
        while (isRunning) {
            readingScreenUiState.bookId?.let {
                accumulateReadingTime(it, 1)
            }
            delay(1.seconds)
        }
    }

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            readingScreenUiState.bookId?.let {
                accumulateReadingTime(it, -1)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (totalReadingTime <= 60) {
                readingScreenUiState.bookId?.let {
                    updateTotalReadingTime(it, totalReadingTime)
                }
            } else {
                Log.e("ReaderScreen", "time counter error, time now is $totalReadingTime over 60s")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val isEnableIndicator =
            settingState.enableTimeIndicator ||
                    settingState.enableReadingChapterProgressIndicator ||
                    settingState.enableChapterTitleIndicator

        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                readingScreenUiState.contentUiState,
                label = "ContentAnimate"
            ) { contentUiState ->
                ContentComponent(
                    uiState = contentUiState,
                    settingState = settingState,
                    paddingValues =
                        if (settingState.autoPadding)
                            PaddingValues(
                                top = stableSafeTopDp,
                                bottom = with(density) {
                                    WindowInsets.safeContent.getBottom(density).toDp()
                                } + if (isEnableIndicator) 40.dp else 0.dp,
                                start = 16.dp,
                                end = 16.dp
                            )
                        else PaddingValues(
                            top = settingState.topPadding.dp,
                            bottom = if (isEnableIndicator)
                                (settingState.bottomPadding + 40).dp
                            else settingState.bottomPadding.dp,
                            start = settingState.leftPadding.dp,
                            end = settingState.rightPadding.dp
                        ),
                    changeIsImmersive = onChangeIsImmersive,
                    onClickPrevChapter = onClickPrevChapter,
                    onClickNextChapter = onClickNextChapter
                )
            }

            AnimatedVisibility(
                modifier = Modifier.align(Alignment.BottomCenter),
                visible = isEnableIndicator,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Indicator(
                    Modifier
                        .padding(
                            if (settingState.autoPadding)
                                PaddingValues(
                                    bottom = 8.dp,
                                    start = 16.dp,
                                    end = 16.dp
                                )
                            else PaddingValues(
                                bottom = settingState.bottomPadding.dp,
                                start = settingState.leftPadding.dp,
                                end = settingState.rightPadding.dp
                            )
                        ),
                    enableBatteryIndicator = settingState.batteryIndicatorDisplayMode == "classic",
                    enableTimeIndicator = settingState.enableTimeIndicator,
                    enableChapterTitle = settingState.enableChapterTitleIndicator,
                    chapterTitle = readingScreenUiState.contentUiState?.readingChapterContent
                        ?.map { it.title }
                        ?.getOrElse { "Unknowing" }
                        ?: "Unknowing",
                    enableReadingChapterProgressIndicator = settingState.enableReadingChapterProgressIndicator,
                    readingChapterProgress = readingScreenUiState.contentUiState?.readingProgress
                        ?: 0f,
                )
            }
        }
    }
}

private fun updateReaderImmersiveMode(
    window: Window,
    immersive: Boolean,
    enableHideStatusBar: Boolean,
) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)

    controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

    if (immersive) {
        if (enableHideStatusBar) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    } else {
        controller.show(WindowInsetsCompat.Type.statusBars())
    }

    if (immersive) {
        controller.hide(WindowInsetsCompat.Type.navigationBars())
    } else {
        controller.show(WindowInsetsCompat.Type.navigationBars())
    }
}

@Composable
private fun TopBar(
    onClickBackButton: () -> Unit,
    title: String,
    scrollBehavior: TopAppBarScrollBehavior
) {
    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = onClickBackButton
            ) {
                Icon(painterResource(id = R.drawable.arrow_back_24px), "back")
            }
        },
        title = {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimatedContent(title, label = "TitleAnimate") { text ->
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.displayLarge,
                        fontWeight = FontWeight.W400,
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
            }
        },
        scrollBehavior = scrollBehavior
    )
}

@Composable
private fun BottomBar(
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onClickPrevChapter: () -> Unit,
    onClickNextChapter: () -> Unit,
    onClickSettings: () -> Unit,
    onClickChapterSelector: () -> Unit
) {
    // LNR 引入适配：material3 BottomAppBar → 容器化背景 Row（内容与交互不变，贴 MiuiX 主题）
    Box(Modifier.fillMaxWidth().background(MiuixTheme.colorScheme.background).padding(vertical = 6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = onClickPrevChapter,
                enabled = hasPrevChapter
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_back_24px),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.previous_chapter),
                        style = MiuixTheme.textStyles.labelSmall
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    enabled = false,
                    onClick = {
                        // TODO 添加至书签
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.outline_bookmark_24px),
                        contentDescription = "mark"
                    )
                }

                IconButton(onClick = onClickChapterSelector) {
                    Icon(
                        painter = painterResource(id = R.drawable.menu_24px),
                        contentDescription = "menu"
                    )
                }

                IconButton(onClick = onClickSettings) {
                    Icon(
                        painter = painterResource(R.drawable.outline_settings_24px),
                        contentDescription = "setting"
                    )
                }
            }

            TextButton(
                onClick = onClickNextChapter,
                enabled = hasNextChapter
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.arrow_forward_24px),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.next_chapter),
                        style = MiuixTheme.textStyles.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
fun Indicator(
    modifier: Modifier = Modifier,
    enableBatteryIndicator: Boolean,
    enableTimeIndicator: Boolean,
    enableChapterTitle: Boolean,
    chapterTitle: String,
    enableReadingChapterProgressIndicator: Boolean,
    readingChapterProgress: Float
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(46.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enableBatteryIndicator) {
                val batteryManager =
                    LocalContext.current.getSystemService(BATTERY_SERVICE) as BatteryManager
                val batLevel: Int =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                Text(
                    text = "$batLevel".padStart(3, '0'),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MiuixTheme.textStyles.bodyLarge,
                    color = MiuixTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "%",
                    style = MiuixTheme.textStyles.bodyLarge,
                    fontWeight = FontWeight.W500,
                    color = MiuixTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    modifier = Modifier.size(20.dp),
                    painter =
                        when {
                            (batLevel in 0..15) -> painterResource(R.drawable.battery_android_alert_24px)
                            (batLevel in 16..35) -> painterResource(R.drawable.battery_android_3_24px)
                            (batLevel in 36..65) -> painterResource(R.drawable.battery_android_4_24px)
                            (batLevel in 66..80) -> painterResource(R.drawable.battery_android_5_24px)
                            (batLevel in 81..95) -> painterResource(R.drawable.battery_android_6_24px)
                            (batLevel in 96..100) -> painterResource(R.drawable.battery_android_full_24px)
                            else -> painterResource(R.drawable.battery_android_question_24px)
                        },
                    tint = MiuixTheme.colorScheme.onSurfaceVariant,
                    contentDescription = null
                )
                Spacer(Modifier.width(14.dp))
            }
            if (enableTimeIndicator) {
                AnimatedText(
                    modifier = Modifier.align(Alignment.CenterVertically),
                    text = String.format(
                        Locale.US,
                        "%d:%02d",
                        LocalTime.now().hour,
                        LocalTime.now().minute
                    ),
                    style = MiuixTheme.textStyles.bodyLarge.copy(
                        letterSpacing = 1.sp
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (enableChapterTitle) {
                AnimatedTextLine(
                    modifier = Modifier.fillMaxWidth(),
                    text = chapterTitle,
                    textAlign = TextAlign.End,
                    style = MiuixTheme.textStyles.bodyLarge,
                    color = MiuixTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (enableReadingChapterProgressIndicator) {
                Text(
                    text = "${(readingChapterProgress * 100).toInt()}".padStart(3, '0'),
                    modifier = Modifier.align(Alignment.CenterVertically),
                    style = MiuixTheme.textStyles.bodyLarge.copy(
                        fontWeight = FontWeight.W500
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "%",
                    style = MiuixTheme.textStyles.bodyLarge,
                    fontWeight = FontWeight.W500,
                    color = MiuixTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
