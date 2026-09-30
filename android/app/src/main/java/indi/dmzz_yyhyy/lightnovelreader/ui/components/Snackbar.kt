package indi.dmzz_yyhyy.lightnovelreader.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Snackbar
import top.yukonga.miuix.kmp.basic.SnackbarData

// LNR 引入适配：material3 Snackbar 的外观参数（shape/颜色族）在 MiuiX 里不存在 ——
// 薄封装只保留 data + modifier，颜色形状交给 MiuiX 主题（AGENTS 4.1 统一主题）。
// 调用方（ReaderScreen）只传 snackbarData，签名兼容。
@Composable
fun LnrSnackbar(
    snackbarData: SnackbarData,
    modifier: Modifier = Modifier,
) {
    Snackbar(data = snackbarData, modifier = modifier.padding(12.dp))
}
