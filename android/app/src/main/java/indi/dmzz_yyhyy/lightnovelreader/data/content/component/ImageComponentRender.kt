package indi.dmzz_yyhyy.lightnovelreader.data.content.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.hyperreader.ui.cover.CoverViewerDialog
import indi.dmzz_yyhyy.lightnovelreader.ui.components.ZoomableImage
import io.nightfish.lightnovelreader.api.content.component.AbstractContentComponentRender
import io.nightfish.lightnovelreader.api.content.component.data.ImageComponentData

/**
 * LNR 引入适配：
 * - 去 `WebBookDataSourceManagerApi.imageHeader`（上游的 Referer 伪造防盗链）——
 *   本工程图片字节走 `CoverRepository`，其下载已固定带 wenku8 Referer（AGENTS 4.7）。
 * - 去 `LocalNavigator` / `navigateToImageViewerDialog`（Navigation 基建不引入）：
 *   全屏查看直接在组件内开本工程的 `CoverViewerDialog`。
 * - 无参构造：`ContentComponentRepository.initRegister` 经 `PluginInjector`
 *   反射实例化（INSTANCE → 无参构造两分支），不能带构造参数。
 */
class ImageComponentRender : AbstractContentComponentRender<ImageComponentData>() {
    override val id = ImageComponentData.id

    @Composable
    override fun Content(modifier: Modifier, data: ImageComponentData) {
        var showViewer by remember { mutableStateOf(false) }
        ZoomableImage(
            imageUri = data.uri,
            modifier = modifier.fillMaxSize(),
            onViewImage = { showViewer = true },
        )
        if (showViewer) {
            CoverViewerDialog(
                url = data.uri.toString(),
                title = "插图",
                onDismiss = { showViewer = false },
            )
        }
    }
}
