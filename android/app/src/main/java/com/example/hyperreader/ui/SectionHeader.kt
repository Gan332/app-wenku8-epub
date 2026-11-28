package com.example.hyperreader.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 分区标题行：左侧标题，右侧可选操作。
 *
 * 此前有三份同义实现（探索页 `SectionHeader`、阅读首页 `SectionHeaderText`、
 * 设置页 `SectionTitle`），字号都是 `UiDimens.section`、字重都是 Bold，但只有探索页那份
 * 支持行尾操作、行高也不一致（另两份没有 48dp 下限）。合并成一份：
 * 需要行尾操作就传 [trailing]；行尾有按钮的行（如「本地书目」的「更新缓存」）
 * 需要 48dp 行高才够触摸目标，纯文字标题则不需要，故由 [minRowHeight] 显式区分。
 */
@Composable
internal fun SectionHeader(
    title: String,
    minRowHeight: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (minRowHeight) Modifier.heightIn(min = UiDimens.touchMin) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MiuixTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(UiDimens.spaceS))
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.textStyles.headline2,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onBackground,
        )
        trailing?.invoke()
    }
}

/**
 * 次要说明文字的颜色（区块说明、卡片副文本、行尾读数）。
 *
 * 全项目此前用 `onSurface.copy(alpha = 0.6f / 0.68f / 0.7f / 0.72f / 0.75f / 0.8f)`
 * 六种几乎不可分辨的写法表达同一件事，改一处主题色要翻十几个文件。
 * 这里收成两档语义：`secondary` 用于「读得到的说明」，`tertiary` 用于「弱化的元信息」
 * （关联的读法见 AGENTS §4.4：颜色一律取 `MiuixTheme.colorScheme`，不写死色值）。
 *
 * 0.19.0 起改用 Miuix 的语义色，不再对 `onSurface` 做 alpha 叠乘：
 * `onSurfaceSecondary`（约 80%）承担「读得到的说明」，
 * `onSurfaceVariantSummary`（约 60%）承担「弱化的元信息」。
 * 动态色/暗色下由主题统一换算，页面不再各自写透明度。
 */
@Composable
internal fun secondaryTextColor(): Color = MiuixTheme.colorScheme.onSurfaceSecondary

@Composable
internal fun tertiaryTextColor(): Color = MiuixTheme.colorScheme.onSurfaceVariantSummary
