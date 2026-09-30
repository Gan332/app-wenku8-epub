package indi.dmzz_yyhyy.lightnovelreader.utils

import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarResult
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// LNR 引入适配：material3 Snackbar 类型 → MiuiX（0.9.4 的 showSnackbar 签名与 M3 对齐，逻辑零改动）

val LocalSnackbarHost = compositionLocalOf { SnackbarHostState() }

val LocalClaimSnackbarHost = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

private var snackbarJob: Job? = null

fun showSnackbar(
    coroutineScope: CoroutineScope? = null,
    hostState: SnackbarHostState,
    message: String? = null,
    actionLabel: String? = null,
    withDismissAction: Boolean = false,
    duration: SnackbarDuration = SnackbarDuration.Short,
    result: (SnackbarResult) -> Unit = {}
) {
    snackbarJob?.cancel()
    val scope = coroutineScope ?: CoroutineScope(Dispatchers.Main)

    if (message == null) {
        // LNR 引入适配：MiuiX 没有 M3 的 currentSnackbarData 属性，
        // dismiss 走 newestSnackbarData()（suspend），因此放进协程。
        snackbarJob = scope.launch(Dispatchers.Main) {
            hostState.newestSnackbarData()?.dismiss()
        }
        return
    }

    snackbarJob = scope.launch(Dispatchers.Main) {
        val res = hostState.showSnackbar(
            message = message,
            actionLabel = actionLabel,
            withDismissAction = withDismissAction,
            duration = duration
        )
        result(res)
    }
}
