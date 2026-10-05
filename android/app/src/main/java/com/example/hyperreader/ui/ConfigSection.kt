package com.example.hyperreader.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hyperreader.settings.configExportFileName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 设置 → 配置导入导出 的二级页面。
 *
 * 只搬运「环境配置」（主题 + 阅读器）。登录 Cookie、密码、Token、书架条目与
 * EPUB 本体都不在这里——它们既不在导出模型里，也不该由本页面触碰。
 */
data class ConfigUiState(
    val busy: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
)

/**
 * @param onExportRequest 点击「导出配置文件」，由外部启动
 *   [androidx.activity.result.contract.ActivityResultContracts.CreateDocument] 写入。
 * @param onImportRequest 点击「从文件导入」，由外部启动
 *   [androidx.activity.result.contract.ActivityResultContracts.OpenDocument] 选择 .json。
 */
@Composable
fun ConfigSection(
    state: ConfigUiState,
    onBack: () -> Unit,
    onExportRequest: () -> Unit,
    onImportRequest: () -> Unit,
) {
    SettingsScaffold("配置导入导出", onBack, listOf(
        {
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(UiDimens.cardInset)) {
                Column(verticalArrangement = Arrangement.spacedBy(UiDimens.spaceXS)) {
                    Text("导出内容", fontWeight = FontWeight.Bold, fontSize = UiDimens.section)
                    Text("仅主题与阅读器设置。", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f))
                }
            }
        },
        { SectionTitle("导出") },
        {
            TextButton(
                text = "导出配置文件",
                onClick = { if (!state.busy) onExportRequest() },
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            )
        },
        { SectionTitle("导入") },
        {
            TextButton(
                text = "从文件导入",
                onClick = { if (!state.busy) onImportRequest() },
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            )
        },
        {
            if (state.busy) {
                Text("处理中…", fontSize = UiDimens.caption, color = MiuixTheme.colorScheme.primary)
            }
        },
        {
            val message = state.message
            if (message != null) {
                Text(
                    text = message,
                    fontSize = UiDimens.caption,
                    color = if (state.isError) MiuixTheme.colorScheme.error else MiuixTheme.colorScheme.primary,
                )
            }
        },
        { SectionTitle("导出包含什么") },
        { ConfigBullet("主题：配色模式、动态色开关、强调色") },
        { ConfigBullet("阅读器：背景、文字色、字号、字重、行高、段距、左右边距、翻页方式、屏幕常亮、沉浸模式") },
        { SectionTitle("绝不包含") },
        { ConfigBullet("Wenku8 登录 Cookie、PHPSESSID、jieqiUserInfo 与任何 Token") },
        { ConfigBullet("账号与密码（应用从不保存密码）") },
        { ConfigBullet("书架条目、阅读记录与阅读统计——属用户内容，需单独迁移") },
        { ConfigBullet("EPUB 与字体文件本体；自定义字体是设备本地授权，不随配置同步") },
        { SectionTitle("导入规则") },
        { ConfigBullet("配置版本不匹配时整份拒绝并提示，不会静默降级") },
        { ConfigBullet("单个字段非法只跳过该字段并计数，其余照常导入") },
        { ConfigBullet("越界数值按合法区间钳制，与设置页滑杆范围一致") },
    ))
}

@Composable
private fun ConfigBullet(text: String) {
    Text(
        text = "· $text",
        fontSize = UiDimens.captionSmall,
        color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
    )
}

/**
 * 配置文件的读写。刻意只与 [android.content.ContentResolver] 打交道，
 * 不申请任何存储权限，走 SAF 用户授权的单个 Uri。
 */
object ConfigTransferFile {

    /** `CreateDocument` 的建议文件名，按当前时间生成。 */
    fun suggestedName(now: Long = System.currentTimeMillis()): String = configExportFileName(now)

    /** `OpenDocument` / `CreateDocument` 的 MIME 过滤。 */
    val mimeTypes: Array<String> = arrayOf("application/json", "text/json", "text/plain", "*/*")

    suspend fun write(context: Context, uri: Uri, text: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("无法写入所选文件")
        }
    }

    /** 读取配置文本；限制长度避免把超大文件读进内存。 */
    suspend fun read(context: Context, uri: Uri, maxBytes: Int = 256 * 1024): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val buffer = ByteArray(maxBytes + 1)
                var total = 0
                while (total <= maxBytes) {
                    val read = stream.read(buffer, total, buffer.size - total)
                    if (read <= 0) break
                    total += read
                }
                buffer.copyOf(total)
            } ?: error("无法读取所选文件")
            if (bytes.size > maxBytes) error("配置文件过大")
            bytes.toString(Charsets.UTF_8)
        }
    }
}
