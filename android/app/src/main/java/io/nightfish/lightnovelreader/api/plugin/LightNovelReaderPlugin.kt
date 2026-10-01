package io.nightfish.lightnovelreader.api.plugin

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable

/**
 * LightNovelReader 插件接口
 * 所有插件需实现此接口, 并使用[@Plugin]注解标注元数据
 * 支持依赖注入
 *
 * @since Api 2
 */
interface LightNovelReaderPlugin {
    /**
     * 插件被加载时回调
     *
     * @since Api 2
     */
    fun onLoad() {}

    /**
     * 插件被卸载时回调
     *
     * @since Api 2
     */
    fun onUnload() {}

    // 上游此处有 `EntryProviderScope<NavKey>.onBuildNavHost()` 导航注入钩子；
    // 本工程不引入 navigation3（AGENTS §4.8），该钩子一并去掉。

    /**
     * 插件向软件注入的页面内容
     * 不需要使用时可以不实现
     *
     * @param paddingValues 系统内边距
     *
     * @since Api 2
     */
    @Composable
    fun PageContent(paddingValues: PaddingValues) {
    }
}