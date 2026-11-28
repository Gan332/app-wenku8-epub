package com.example.hyperreader.ui

import androidx.compose.runtime.Composable
import com.example.hyperreader.ui.theme.StudioTheme

/**
 * 应用主题门面。
 *
 * 具体实现已迁到 [StudioTheme]（主题解析 + MiuiX 色板 + 语义令牌）。保留这个入口是为了
 * 让所有历史调用点（`AppMiuixTheme { … }`）无需改动，也避免出现两套并行的主题实现。
 */
@Composable
fun AppMiuixTheme(content: @Composable () -> Unit) = StudioTheme(content)
