package com.shilapi.xcertplay.connection

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import com.shilapi.xcertplay.DiPlayPalette
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.progress.ConnectionOutcome
import com.shilapi.xcertplay.orchestration.progress.ConnectionProgress
import com.shilapi.xcertplay.orchestration.progress.ConnectionStep
import com.shilapi.xcertplay.orchestration.progress.ConnectionTask
import com.shilapi.xcertplay.orchestration.progress.ConnectionTaskState
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Apple/CarPlay-style connection waiting screen, rendered from the bundled
 * `carplay-connection.html` asset.
 *
 * This is the single owner of the [ConnectionProgress] model. The original
 * [ConnectionProgressPanel] is kept alongside it: every call is mirrored to it so
 * that a head unit without a working WebView can fall back to the native layout
 * without losing attempt history or timing. See [isAvailable] / [onFallback].
 *
 * Native actions are sent through a private `diplay://action/<name>` scheme that
 * [WebViewClient.shouldOverrideUrlLoading] intercepts. No JavascriptInterface is
 * exposed and no network/file navigation is allowed, so a compromised page cannot
 * reach app internals or the vehicle filesystem.
 */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
internal class CamryConnectionScreen(
    context: Context,
    private val legacyPanel: ConnectionProgressPanel,
) : FrameLayout(context) {

    /** Called with the last path segment of `diplay://action/<name>` on the UI thread. */
    var onAction: ((String) -> Unit)? = null

    /** Called when the page cannot be loaded; the host should reveal the native waiting layout. */
    var onFallback: (() -> Unit)? = null

    private val history = ConnectionProgressHistory(context)
    private var model: ConnectionProgress? = null
    private var pendingWireless = false
    private var pendingChannel = com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.FRAMEWORK
    private val displayedChannel get() = model?.channel ?: pendingChannel
    private var night = true
    private var motionEnabled = false
    private var pageReady = false
    private var tickerPosted = false
    private var initialized = false
    private var fallbackTriggered = false
    private var loadStarted = false
    private var stageText: String? = null
    private var guidanceText: String? = null
    private var actionState = JSONObject()
    private val webView: WebView?

    /** False when WebView is missing or the asset failed to load: use the native waiting screen. */
    val isAvailable: Boolean get() = webView != null && !fallbackTriggered

    private val tick = object : Runnable {
        override fun run() {
            tickerPosted = false
            if (!isShown || !isAttachedToWindow) return
            render()
            if (model?.snapshot()?.outcome in listOf(ConnectionOutcome.CONNECTING, ConnectionOutcome.RETRYING)) schedule()
        }
    }

    init {
        val created = try {
            createWebView()
        } catch (t: Throwable) {
            null
        }
        webView = created
        if (created != null) {
            addView(created, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        } else {
            fallbackTriggered = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        initialized = true
    }

    // ---- Public API: identical to ConnectionProgressPanel, plus the native action bridge ----

    /** Select the waiting layout before a controller exists, without starting attempt timing. */
    fun pendingTransport(wireless: Boolean, vehicleWireless: Boolean) {
        pendingWireless = wireless
        pendingChannel = when {
            wireless && vehicleWireless -> com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.PIONEER_WIRELESS
            wireless -> com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.WIRELESS
            else -> com.shilapi.xcertplay.orchestration.progress.ConnectionChannel.FRAMEWORK
        }
        legacyPanel.pendingTransport(wireless, vehicleWireless)
        render()
    }

    fun begin(config: CarPlayRuntimeConfig): ConnectionProgress {
        val progress = history.create(config, model)
        model = progress
        legacyPanel.bind(progress)
        render()
        schedule()
        return progress
    }

    fun bind(progress: ConnectionProgress?) {
        model = progress
        legacyPanel.bind(progress)
        render()
        schedule()
    }

    fun retrying() { legacyPanel.retrying(); render(); schedule() }
    fun failed() { legacyPanel.failed(); render() }
    fun cancelled() { legacyPanel.cancelled(); render() }

    fun setNight(value: Boolean) {
        night = value
        legacyPanel.setNight(value)
        render()
        val palette = DiPlayPalette.of(value)
        push("window.applyPalette&&window.applyPalette(" +
            JSONObject().put("accent", hex(palette.accent)).toString() + ");")
    }

    /** The connection screen is DiPlay chrome: keep the native headline in sync with the status line. */
    fun setStage(text: String) {
        if (stageText == text) return
        stageText = text
        render()
    }

    /** Failure guidance from ConnectionFailureGuidance, shown in the alert row on failure. */
    fun setGuidance(text: String?) {
        if (guidanceText == text) return
        guidanceText = text
        render()
    }

    /** Mirrors the native retry / Wi-Fi recovery / feedback buttons. */
    fun showActions(retry: Boolean, reset: Boolean, feedback: Boolean, note: String = "") {
        val next = JSONObject()
            .put("retry", retry)
            .put("reset", reset)
            .put("feedback", feedback)
            .put("note", note)
        if (next.toString() == actionState.toString()) return
        actionState = next
        pushActions()
    }

    /** Vehicle head units have weak GPUs: motion is off unless explicitly enabled. */
    fun setMotionEnabled(enabled: Boolean) {
        motionEnabled = enabled
        render()
    }

    // ---- Lifecycle: same ticker contract as the native panel ----

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Load only once attached: a detached WebView can finish rendering before the
        // host has registered its action/fallback callbacks.
        if (!loadStarted) {
            loadStarted = true
            webView?.loadUrl(ASSET_URL)
        }
        schedule()
    }
    override fun onDetachedFromWindow() { removeCallbacks(tick); tickerPosted = false; super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (!initialized) return
        if (visibility == VISIBLE) schedule() else { removeCallbacks(tick); tickerPosted = false }
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!initialized) return
        if (visibility == VISIBLE) schedule() else { removeCallbacks(tick); tickerPosted = false }
    }

    private fun schedule() {
        if (webView == null || !pageReady) return
        if (!tickerPosted && isAttachedToWindow && isShown) {
            tickerPosted = true
            postDelayed(tick, 500)
        }
    }

    // ---- WebView plumbing ----

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.allowContentAccess = false
        settings.cacheMode = WebSettings.LOAD_NO_CACHE
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!url.startsWith(ASSET_BASE)) return
                // Probe the page before trusting it: a missing asset, a stripped JS engine or
                // a broken WebView must fall back to the native panel instead of showing a blank screen.
                view.evaluateJavascript("typeof window.applyConnectionState") { value ->
                    if (value == null || !value.contains("function")) {
                        triggerFallback()
                    } else {
                        pageReady = true
                        render()
                        pushActions()
                        pushMeta()
                        schedule()
                    }
                }
            }

            // The String overload is used on every API level: WebViewClient's default
            // WebResourceRequest overload delegates to it. Avoiding that overload keeps
            // the module's minSdk (23) clean of NewApi findings.
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = intercept(url)

            override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
                if (failingUrl.startsWith(ASSET_BASE)) triggerFallback()
            }
        }
    }

    private fun intercept(url: String): Boolean {
        if (url.startsWith(ACTION_PREFIX)) {
            val name = try { Uri.parse(url).lastPathSegment } catch (t: Throwable) { null }
            if (!name.isNullOrEmpty()) post { onAction?.invoke(name) }
            return true
        }
        // Only the bundled asset is allowed to navigate. Inline data: images never go
        // through this callback, so the car illustration still renders.
        return !url.startsWith(ASSET_BASE)
    }

    private fun triggerFallback() {
        if (fallbackTriggered) return
        fallbackTriggered = true
        post { onFallback?.invoke() }
    }

    private fun push(script: String) {
        val view = webView ?: return
        if (!pageReady || fallbackTriggered) return
        try {
            view.evaluateJavascript(script, null)
        } catch (t: Throwable) {
            triggerFallback()
        }
    }

    private fun pushActions() {
        push("window.applyActions&&window.applyActions(" + actionState.toString() + ");")
    }

    @Suppress("DEPRECATION")
    private fun pushMeta() {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (t: Throwable) {
            null
        }
        if (version.isNullOrEmpty()) return
        push("window.applyMeta&&window.applyMeta(" +
            JSONObject().put("version", version).put("session", "01").toString() + ");")
    }

    // ---- State projection: every visible string comes from existing R.string resources ----

    private fun render() {
        if (webView == null || !pageReady) return
        val state = model?.snapshot()
        val steps = state?.steps ?: ConnectionProgress.stepsFor(pendingWireless, pendingChannel)
        val outcome = state?.outcome
        val stepArray = JSONArray()
        var doneCount = 0
        var hasActive = false
        var currentTitle = ""
        var stepHint = ""
        var dangerHint = ""

        steps.forEach { step ->
            val current = state != null && state.current == step && outcome != ConnectionOutcome.COMPLETE
            val visited = state != null && step in state.visited
            val finished = visited && !current
            val stepState = when {
                current && outcome == ConnectionOutcome.FAILED -> "failed"
                current -> "active"
                finished -> "done"
                else -> "pending"
            }
            val time = when {
                visited -> context.getString(R.string.connect_progress_seconds, seconds(state!!.durations.getValue(step)))
                outcome == ConnectionOutcome.COMPLETE -> context.getString(R.string.connect_progress_skipped)
                else -> context.getString(R.string.connect_progress_pending)
            }
            val title = context.getString(title(step))
            if (stepState == "done") doneCount++
            if (stepState == "active" || stepState == "failed") { hasActive = true; currentTitle = title }

            // Verbatim parity with the native panel's PIONEER_WIRELESS task hints.
            val hintRes: Int? = if (displayedChannel.name == "PIONEER_WIRELESS") {
                when (step) {
                    ConnectionStep.DEVICE -> when (state?.tasks?.firstOrNull {
                        it.task == ConnectionTask.WIRELESS_LINK
                    }?.detail) {
                        "vehicle_bluetooth_wait" -> R.string.vehicle_bluetooth_connect_hint
                        "vehicle_bluetooth_not_connected" -> R.string.vehicle_bluetooth_missing_hint
                        else -> null
                    }
                    ConnectionStep.NETWORK -> state?.tasks?.firstOrNull {
                        it.task == ConnectionTask.WIRELESS_NETWORK && it.state == ConnectionTaskState.RUNNING
                    }?.let { task -> when (task.detail) {
                        "vehicle_wifi_enabling" -> R.string.connect_progress_wifi_enabling
                        "vehicle_wifi_wait" -> R.string.connect_progress_wifi_wait
                        else -> null
                    } }
                    else -> null
                }
            } else null
            if (hintRes != null) {
                val text = context.getString(hintRes)
                if (stepHint.isEmpty()) stepHint = text
                if (hintRes == R.string.vehicle_bluetooth_missing_hint) dangerHint = text
            }
            stepArray.put(JSONObject()
                .put("title", title)
                .put("state", stepState)
                .put("time", time))
        }

        val headline = currentTitle.ifEmpty {
            when (outcome) {
                ConnectionOutcome.COMPLETE -> context.getString(R.string.carplay_connected)
                ConnectionOutcome.FAILED -> context.getString(R.string.connect_progress_failed)
                ConnectionOutcome.CANCELLED -> context.getString(R.string.connect_progress_cancelled)
                ConnectionOutcome.RETRYING -> context.getString(R.string.connect_progress_retrying)
                else -> context.getString(R.string.getting_carplay_ready)
            }
        }
        val sub = stageText?.takeIf { it.isNotBlank() } ?: context.getString(
            if (pendingWireless) R.string.keep_your_iphone_nearby_with_bluetooth_and_wi_fi_on_allow
            else R.string.use_a_usb_data_cable_and_unlock_your_iphone_allow_trust_an
        )
        val estimate = if (state == null) context.getString(R.string.connect_progress_learning) else when (outcome) {
            ConnectionOutcome.COMPLETE -> context.getString(R.string.connect_progress_complete, seconds(state.elapsedMillis))
            ConnectionOutcome.FAILED -> context.getString(R.string.connect_progress_failed)
            ConnectionOutcome.CANCELLED -> context.getString(R.string.connect_progress_cancelled)
            ConnectionOutcome.RETRYING -> context.getString(R.string.connect_progress_retrying)
            else -> state.remainingMillis?.let {
                context.getString(R.string.connect_progress_seconds, seconds(it))
            } ?: context.getString(R.string.connect_progress_learning)
        }
        val summary = if (state == null) context.getString(R.string.connect_progress_waiting) else context.getString(
            R.string.connect_progress_summary,
            NumberFormat.getIntegerInstance().format(state.attempt),
            seconds(state.elapsedMillis),
            seconds(state.overallMillis),
        )
        val percent = if (outcome == ConnectionOutcome.COMPLETE) 100 else
            ((doneCount + if (hasActive) 0.5f else 0f) / steps.size.coerceAtLeast(1) * 100).roundToInt()

        val json = JSONObject()
            .put("night", if (night) "night" else "light")
            .put("motion", motionEnabled)
            .put("outcome", outcome?.name ?: "WAITING")
            .put("channel", channelKey())
            .put("attempt", state?.attempt ?: 0)
            .put("percent", percent)
            .put("currentTitle", headline)
            .put("sub", sub)
            .put("hint", guidanceText ?: dangerHint.ifEmpty { stepHint })
            .put("estimate", estimate)
            .put("summary", summary)
            .put("steps", stepArray)
        push("window.applyConnectionState&&window.applyConnectionState(" + json.toString() + ");")
    }

    private fun channelKey(): String = when (displayedChannel.name) {
        "PIONEER_WIRELESS" -> "pioneer"
        "WIRELESS" -> "wireless"
        else -> "wired"
    }

    private fun title(step: ConnectionStep): Int {
        if (displayedChannel.name == "PIONEER_WIRELESS") return when (step) {
            ConnectionStep.NETWORK -> R.string.connect_progress_vehicle_hotspot
            ConnectionStep.DEVICE -> R.string.connect_progress_vehicle_bluetooth
            ConnectionStep.CONTROL -> R.string.connect_progress_vehicle_auth
            ConnectionStep.SESSION -> R.string.connect_progress_vehicle_session
            else -> ordinaryTitle(step)
        }
        return ordinaryTitle(step)
    }

    private fun ordinaryTitle(step: ConnectionStep) = when (step) {
        ConnectionStep.WIRELESS_BOOTSTRAP -> R.string.connect_progress_vehicle_bootstrap
        ConnectionStep.WIRELESS_JOIN -> R.string.connect_progress_vehicle_join
        ConnectionStep.PERMISSIONS -> R.string.connect_progress_permissions
        ConnectionStep.CHANNEL -> R.string.connect_progress_channel
        ConnectionStep.PREPARATION -> R.string.connect_progress_preparation
        ConnectionStep.DEVICE -> R.string.connect_progress_device
        ConnectionStep.CONTROL -> R.string.connect_progress_control
        ConnectionStep.NETWORK -> R.string.connect_progress_network
        ConnectionStep.SESSION -> R.string.connect_progress_session
        ConnectionStep.VIDEO -> R.string.connect_progress_video
    }

    private fun seconds(millis: Long): String = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }.format(millis / 1000.0)

    private fun hex(color: Int) = "#%06X".format(Locale.US, color and 0xFFFFFF)

    private companion object {
        const val ASSET_BASE = "file:///android_asset/carplay-connection.html"
        const val ASSET_URL = "file:///android_asset/carplay-connection.html?embedded=1"
        const val ACTION_PREFIX = "diplay://action/"
    }
}
