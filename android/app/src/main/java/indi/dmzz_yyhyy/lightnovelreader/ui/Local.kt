/*
 * Copyright (C) 2025 走路 simply
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package indi.dmzz_yyhyy.lightnovelreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color


/**
 * LNR 引入适配：`LocalAppTheme` 的 MiuiX 等价物。
 *
 * 上游这里是 `staticCompositionLocalOf<AppTheme>`，`AppTheme` 包着 material3 的
 * `ColorScheme` + 深浅色标记。本工程不引入 material3（AGENTS.md 4.4 有守卫单测），
 * 也**不引入 LNR 的 theme 包**（那是它整套自定义配色 + 主题名体系的入口）。
 *
 * 替代方案：把 `LocalAppTheme` 换成一个从 `MiuixTheme.colorScheme` 现算的只读对象，
 * 深浅色用 `isSystemInDarkTheme()`。这样 LNR reader 里凡是 `LocalAppTheme.current.isDark`
 * 与 `.MiuixTheme.colorScheme.xxx` 的地方都不用改逻辑，只是不再依赖 material 的 ColorScheme。
 *
 * 注意：provider 在组合时现算，值用 [remember] 缓存——`AppMiuixTheme` 的动态色/强调色
 * 变化会被立刻读到，而不是像上游那样在根节点算一次就静态持有（上游靠重建整个
 * AppTheme 对象来失效，本工程改用 provider 包裹阅读器根节点）。
 */
class AppTheme(
    val isDark: Boolean,
    val colorScheme: MiuixThemeColorScheme,
) {
    /** 兼容 LNR 上游 `appTheme.MiuixTheme.colorScheme.x` 的投影写法 */
    val MiuixTheme: MiuixThemeColorScheme get() = colorScheme
}

/**
 * LNR reader 实际用到的 ColorScheme 字段的最小投影。
 *
 * reader 代码里出现的 material 字段只有：`background` / `onSurface` /
 * `onSurfaceVariant` / `surfaceContainerHigh` / `surfaceContainerLow` /
 * `surfaceContainer`。列在这里而不是直接暴露 MiuiX 的整个 ColorScheme，
 * 是为了让「这份拷贝代码依赖了哪些颜色字段」保持可审计。
 */
class MiuixThemeColorScheme(
    val background: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
)

val LocalAppTheme = staticCompositionLocalOf<AppTheme> {
    error("No AppTheme provided: wrap the reader in LnrAppTheme { }")
}

/**
 * 把 MiuiX 主题投影成 LNR reader 期望的 [AppTheme] 并注入 [LocalAppTheme]。
 * 阅读器根（P3/P4 接线处）用这个包一层即可，拷贝过来的 reader 代码无需改动。
 */
@Composable
fun LnrAppTheme(content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val scheme = MiuixTheme.colorScheme
    val theme = remember(isDark, scheme) {
        AppTheme(
            isDark = isDark,
            colorScheme = MiuixThemeColorScheme(
                background = scheme.background,
                onSurface = scheme.onSurface,
                // MiuiX Colors 无 material 的 onSurfaceVariant，取语义最近的摘要文字色
                onSurfaceVariant = scheme.onSurfaceVariantSummary,
                surfaceContainerHigh = scheme.background,
                surfaceContainerLow = scheme.background,
                surfaceContainer = scheme.background,
            ),
        )
    }
    CompositionLocalProvider(LocalAppTheme provides theme, content = content)
}
