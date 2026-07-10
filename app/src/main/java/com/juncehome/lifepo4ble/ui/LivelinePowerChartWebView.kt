package com.juncehome.lifepo4ble.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import kotlin.math.max
private const val LIVELINE_WEB_TAG = "KMF-LIVELINE"
private const val LIVELINE_ASSET_URL = "https://appassets.androidplatform.net/assets/liveline-power/index.html"

@Composable
fun LivelinePowerChartWebView(
    chart: LiveChartModel,
    modifier: Modifier = Modifier,
) {
    val payload = chart.toLivelinePowerChartPayload() ?: return
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var lastSentJson by remember { mutableStateOf<String?>(null) }
    var layoutLogged by remember { mutableStateOf(false) }

    val payloadJson = remember(payload) { payload.toJson() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            createLivelineWebView(
                context = context,
                onPageReady = {
                    // WebView is ready to accept the payload once the React app installs its bridge.
                    Log.d(LIVELINE_WEB_TAG, "web page ready")
                    pageReady = true
                },
                onBridgeEvent = { kind, nativeSentAtMs, jsEpochMs ->
                    // These events measure the delay between Android sending data and the canvas updating.
                    val latencyMs = if (nativeSentAtMs > 0L) {
                        max(0L, jsEpochMs.toLong() - nativeSentAtMs)
                    } else {
                        null
                    }
                    Log.d(
                        LIVELINE_WEB_TAG,
                        buildString {
                            append(kind)
                            append(" nativeSentAtMs=")
                            append(nativeSentAtMs)
                            append(" jsEpochMs=")
                            append(jsEpochMs.toLong())
                            latencyMs?.let {
                                append(" latencyMs=")
                                append(it)
                            }
                        },
                    )
                },
            ).also { webView = it }
        },
        update = { view ->
            webView = view
        },
    )

    LaunchedEffect(webView, pageReady, payloadJson) {
        val view = webView ?: return@LaunchedEffect
        if (!pageReady) {
            return@LaunchedEffect
        }
        if (lastSentJson == payloadJson) {
            return@LaunchedEffect
        }
        val script = "window.kmfPowerChart && window.kmfPowerChart.update($payloadJson);"
        // Send the current chart snapshot only after the page has registered the JS bridge.
        Log.d(LIVELINE_WEB_TAG, "send payload nativeSentAtMs=${payload.nativeSentAtMs} points=${payload.points.size}")
        view.evaluateJavascript(script, null)
        if (!layoutLogged) {
            view.logLivelineLayout()
            layoutLogged = true
        }
        lastSentJson = payloadJson
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            webView = null
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createLivelineWebView(
    context: Context,
    onPageReady: () -> Unit,
    onBridgeEvent: (kind: String, nativeSentAtMs: Long, jsEpochMs: Double) -> Unit,
): WebView {
    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    return LivelineFixedViewportWebView(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    LIVELINE_WEB_TAG,
                    "console ${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()} ${consoleMessage.message()}",
                )
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val uri: Uri = request?.url ?: return null
                return assetLoader.shouldInterceptRequest(uri)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(LIVELINE_WEB_TAG, "page finished url=$url")
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
        }
        addJavascriptInterface(
            object {
                private val handler = Handler(Looper.getMainLooper())

                @JavascriptInterface
                fun onChartEvent(kind: String, nativeSentAtMs: Long, jsEpochMs: Double) {
                    handler.post {
                        if (kind == "ready") {
                            onPageReady()
                        }
                        onBridgeEvent(kind, nativeSentAtMs, jsEpochMs)
                    }
                }
            },
            "KmfLivelineBridge",
        )
        loadUrl(LIVELINE_ASSET_URL)
    }
}

private fun WebView.logLivelineLayout() {
    evaluateJavascript(
        """
            (() => {
              function rect(element) {
                if (!element) return null;
                const r = element.getBoundingClientRect();
                return { x: r.x, y: r.y, width: r.width, height: r.height };
              }
              return JSON.stringify({
                text: document.body.innerText,
                viewport: { width: window.innerWidth, height: window.innerHeight, scrollY: window.scrollY },
                root: rect(document.getElementById('root')),
                screen: rect(document.querySelector('.screen')),
                shell: rect(document.querySelector('.chart-shell')),
                frame: rect(document.querySelector('.chart-frame'))
              });
            })()
        """.trimIndent(),
    ) { result ->
        Log.d(LIVELINE_WEB_TAG, "layout $result")
    }
}

private class LivelineFixedViewportWebView(context: Context) : WebView(context) {
    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(0, 0)
    }

    override fun overScrollBy(
        deltaX: Int,
        deltaY: Int,
        scrollX: Int,
        scrollY: Int,
        scrollRangeX: Int,
        scrollRangeY: Int,
        maxOverScrollX: Int,
        maxOverScrollY: Int,
        isTouchEvent: Boolean,
    ): Boolean {
        super.scrollTo(0, 0)
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            super.scrollTo(0, 0)
        }
        return super.onTouchEvent(event)
    }
}
