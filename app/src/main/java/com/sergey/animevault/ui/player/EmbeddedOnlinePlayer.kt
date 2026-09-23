package com.sergey.animevault.ui.player

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.sergey.animevault.BuildConfig
import com.sergey.animevault.data.online.OnlineProviderIds
import com.sergey.animevault.data.online.OnlineStream

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun EmbeddedOnlinePlayer(
    stream: OnlineStream,
    onLoadingChanged: (Boolean) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val allowedHosts = remember(stream.failureKey()) { allowedEmbeddedPlayerHosts(stream) }
    val webView = remember(stream.failureKey()) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.userAgentString = settings.userAgentString + " AnimeVault/${BuildConfig.VERSION_NAME}"
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(
                this,
                stream.providerId in THIRD_PARTY_COOKIE_PROVIDERS,
            )
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    onLoadingChanged(true)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    onLoadingChanged(false)
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    if (request?.isForMainFrame != true) return false
                    val target = request.url
                    val allowed = target.scheme.equals("https", ignoreCase = true) &&
                        target.host?.let { host ->
                            allowedHosts.any { trusted -> host == trusted || host.endsWith(".$trusted") }
                        } == true
                    if (allowed) return false
                    if (target.scheme.equals("https", ignoreCase = true)) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    return true
                }
            }
            loadUrl(stream.url, stream.headers)
        }
    }
    AndroidView(modifier = modifier, factory = { webView }, update = {})
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }
}

internal fun allowedEmbeddedPlayerHosts(stream: OnlineStream): Set<String> = buildSet {
    Uri.parse(stream.url).host?.lowercase()?.let(::add)
    when (stream.providerId) {
        OnlineProviderIds.KODIK -> addAll(listOf("kodik.info", "kodik.biz", "kodik.cc", "kodik-api.com"))
        OnlineProviderIds.JUT_SU -> addAll(listOf("jut.su", "jutsu.tv"))
        OnlineProviderIds.ANI_LIBERTY -> addAll(listOf("aniliberty.top", "anilibria.tv"))
    }
}

private val THIRD_PARTY_COOKIE_PROVIDERS = setOf(OnlineProviderIds.KODIK, OnlineProviderIds.JUT_SU)
