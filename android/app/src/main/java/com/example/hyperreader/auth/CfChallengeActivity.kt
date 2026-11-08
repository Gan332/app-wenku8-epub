package com.example.hyperreader.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.hyperreader.core.Wenku8Endpoint
import com.example.hyperreader.core.Wenku8NetProtocols
import com.example.hyperreader.core.Wenku8SessionStore
import com.example.hyperreader.core.Wenku8Url
import com.example.hyperreader.core.Wenku8Urls
import com.example.hyperreader.ui.AppMiuixTheme
import com.example.hyperreader.ui.UiDimens
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Cloudflare 验证窗口（0.18.0）。
 *
 * 何时弹：任意抓取链路收到 Cloudflare 拦截页（`UPSTREAM_CHALLENGE`）时，
 * 由 `StudioViewModel.challengeUrl` 驱动自动打开。
 *
 * 做什么：**承载用户本人完成 Cloudflare 官方的人机交互**（WebView 原生执行其脚本）。
 * 通过后 Cloudflare 下发 `cf_clearance`，CookieManager 落盘 → `Wenku8SessionStore`
 * 加密保存 → 后续 OkHttp 请求自动携带（与 `LoginActivity` 同一套会话链路），随后本窗口自动关闭。
 *
 * 不做什么（AGENTS §4.2）：不注入脚本破解、不伪造 token、不绕过登录墙或付费墙；
 * 只在用户亲手完成交互后复用结果，且随时可关闭。
 */
class CfChallengeActivity : ComponentActivity() {

    private lateinit var sessionStore: Wenku8SessionStore

    /** 只允许 wenku8 自身域名（与全局白名单一致），非法则退回首页。 */
    private val targetUrl: String by lazy {
        val raw = intent.getStringExtra(EXTRA_URL).orEmpty()
        runCatching { Wenku8Url.assertAllowed(raw).toString() }.getOrElse { Wenku8Urls.BASE }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = Wenku8SessionStore(this)
        setContent {
            AppMiuixTheme {
                CfChallengeScreen(
                    onClose = { finish() },
                    onWebViewReady = { webView -> webView.startVerification(targetUrl) { closeWithResult() } },
                )
            }
        }
    }

    /** 通过后：按验证页 host 落盘凭证 → 钉住该入口 → 回报调用方 → 自动关闭。 */
    private fun closeWithResult() {
        if (isFinishing) return
        val host = targetUrl.toHttpUrlOrNull()?.host
        sessionStore.saveWebViewSession(targetUrl)
        // 刚验证过的是哪个镜像，后续请求就先打哪个：clearance 与 host 绑定，
        // 换到另一个入口上会重新 403（用户看到的就是「验证了还被拦」）。
        if (host != null && sessionStore.hasClearanceFor(host)) {
            Wenku8Endpoint.applyPreferredHost("https://$host")
        }
        setResult(RESULT_OK)
        finish()
    }

    companion object {
        private const val EXTRA_URL = "cf_challenge_url"

        fun intent(context: Context, url: String): Intent =
            Intent(context, CfChallengeActivity::class.java).putExtra(EXTRA_URL, url)
    }
}

/**
 * 配置 WebView 并在验证通过后回调。
 *
 * 判定顺序（见 [CfChallengeState]）：`cf_clearance` 出现即通过（给它 0.9s 让用户看到结果页）；
 * 无 clearance 但页面标题已不是挑战页，视作软挑战通过（立即回调）。
 */
private fun WebView.startVerification(url: String, onSatisfied: () -> Unit) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.userAgentString = Wenku8NetProtocols.USER_AGENT
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    var finished = false
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, pageUrl: String) {
            if (finished) return
            CookieManager.getInstance().flush()
            val cookieHeader = CookieManager.getInstance().getCookie(pageUrl).orEmpty()
            val hasClearance = cookieHeader.split(';')
                .any { part -> part.trim().startsWith("${CfChallengeState.CLEARANCE_COOKIE}=") }
            when {
                hasClearance -> {
                    finished = true
                    view.postDelayed(onSatisfied, 900)
                }

                CfChallengeState.isSatisfied(view.title, hasClearance = false) -> {
                    finished = true
                    view.postDelayed(onSatisfied, 300)
                }
                // 仍在验证：留在窗口里等用户完成交互
            }
        }
    }
    loadUrl(url)
}

/** 验证窗口界面：MiuiX 顶部栏 + 提示 + 进度 + WebView + 关闭按钮。 */
@Composable
private fun CfChallengeScreen(onClose: () -> Unit, onWebViewReady: (WebView) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = "完成 Cloudflare 验证") }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = UiDimens.pagePadding),
            verticalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
        ) {
            Text(
                "验证由 Cloudflare 提供，请在下方完成；完成后本窗口会自动关闭。",
                fontSize = UiDimens.caption,
                color = MiuixTheme.colorScheme.onSurface.copy(alpha = .72f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(UiDimens.spaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(size = 18.dp)
                Text("等待验证完成…", fontSize = UiDimens.captionSmall, color = MiuixTheme.colorScheme.primary)
            }
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { context -> WebView(context).also(onWebViewReady) },
                // 窗口关闭时销毁 WebView，避免其持有 Activity / 渲染进程直到 GC。
                onRelease = { web ->
                    web.stopLoading()
                    web.destroy()
                },
            )
            TextButton(
                text = "关闭",
                onClick = onClose,
                modifier = Modifier.fillMaxWidth().heightIn(min = UiDimens.touchMin),
            )
        }
    }
}