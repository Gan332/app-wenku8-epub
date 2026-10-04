package com.xyreader.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.xyreader.core.ReaderDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 阅读器 UI 共用小组件。
 *
 * 上游这个文件还包含书库扫描控制器、导入 FAB、空状态等**书库管理**界面；本工程没有
 * 那套本地仓库体系（数据源只有 wenku8），因此只保留阅读器真正引用的两个符号：
 * [CapsuleTab]（目录/设置弹层的胶囊分组钮）与 [formatDate]（书签时间）。
 */

/** epoch 毫秒 -> yyyy-MM-dd HH:mm（书签列表用） */
private val bookmarkDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

fun formatDate(epochMs: Long): String = bookmarkDateFormat.format(Date(epochMs))

/** 允许的颜色：仅主题色；此处仅供内部半透明浮层使用 */
internal val ScrimColor: Color = Color.Black.copy(alpha = 0.45f)

/**
 * 顶部胶囊分组钮（阅读设置弹层用）：
 * 选中填充主色容器、未选中用中性容器（Surface(onClick) 带涟漪反馈）。
 */
@Composable
fun CapsuleTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) {
            MiuixTheme.colorScheme.primaryContainer
        } else {
            MiuixTheme.colorScheme.surfaceContainer
        },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = ReaderDimens.spaceL, vertical = ReaderDimens.spaceXS),
            fontSize = ReaderDimens.body,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MiuixTheme.colorScheme.onPrimaryContainer
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}
