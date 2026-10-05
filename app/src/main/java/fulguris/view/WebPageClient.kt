package fulguris.view

import fulguris.BuildConfig
import fulguris.R
import fulguris.adblock.AbpBlockerManager
import fulguris.adblock.AdBlocker
import fulguris.adblock.NoOpAdBlocker
import fulguris.browser.WebBrowser
import fulguris.activity.FRAGMENT_CLASS_NAME
import fulguris.activity.SettingsActivity
import fulguris.activity.WebBrowserActivity
import fulguris.settings.fragment.PREFERENCE_KEY
import fulguris.di.HiltEntryPoint
import fulguris.di.configPrefs
import fulguris.extensions.getDrawable
import fulguris.extensions.getText
import fulguris.extensions.ihs
import fulguris.extensions.KDuration
import fulguris.extensions.makeSnackbar
import fulguris.extensions.launch
import fulguris.extensions.setIcon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch as coroutineLaunch
import kotlinx.coroutines.withContext
import fulguris.html.homepage.HomePageFactory
import fulguris.js.InvertPage
import fulguris.js.NestedScrollDetect
import fulguris.js.SetMetaViewport
import fulguris.js.TextReflow
import fulguris.permissions.PermissionsManager
import fulguris.settings.NoYesAsk
import fulguris.settings.preferences.DomainPreferences
import fulguris.settings.preferences.UserPreferences
import fulguris.ssl.SslState
import fulguris.userscript.UserScript
import fulguris.utils.*
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.util.Base64
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.webkit.*
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.EntryPointAccessors
import fulguris.enums.LogLevel
import fulguris.app
import fulguris.utils.ThemeUtils
import fulguris.utils.htmlColor
import fulguris.utils.isSpecialUrl
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.math.abs
import androidx.core.net.toUri

/**
 * We have one instance of this per [WebView] and our [WebPageTab] also as a reference to it.
 *
 * Page load events sequence tested against slions.net:
 * - [shouldOverrideUrlLoading] when applicable
 * - [shouldInterceptRequest]
 * - [onLoadResource] - For the page URL is called first
 * - [onPageStarted]  - Not called if interrupted before the first main frame resource completed
 * - [shouldInterceptRequest] For each resources
 * - [onLoadResource] - For each resources
 * - [onPageFinished] - Also called when cancelled - can be called even though onPageStarted was not called - YouTube can call it multiple times
 * - [shouldInterceptRequest] - Can still occur after onPageFinished even if load was cancelled
 * - [onLoadResource] - Can still occur after onPageFinished even if load was cancelled
 *
 */
class WebPageClient(
    private val activity: Activity,
    private val webPageTab: WebPageTab
) : WebViewClient() {

    private val webBrowser: WebBrowser = activity as WebBrowser

    private val hiltEntryPoint = EntryPointAccessors.fromApplication(activity.applicationContext, HiltEntryPoint::class.java)

    val userPreferences: UserPreferences = hiltEntryPoint.userPreferences
    val preferences: SharedPreferences = hiltEntryPoint.userSharedPreferences()
    val textReflowJs: TextReflow = hiltEntryPoint.textReflowJs
    val invertPageJs: InvertPage = hiltEntryPoint.invertPageJs
    val setMetaViewport: SetMetaViewport = hiltEntryPoint.setMetaViewport
    val nestedScrollDetectJs: NestedScrollDetect = hiltEntryPoint.nestedScrollDetectJs
    val blobHookJs: fulguris.js.BlobHook = hiltEntryPoint.blobHookJs
    val homePageFactory: HomePageFactory = hiltEntryPoint.homePageFactory
    val abpBlockerManager: AbpBlockerManager = hiltEntryPoint.abpBlockerManager
    val noopBlocker: NoOpAdBlocker = hiltEntryPoint.noopBlocker
    val networkEngineManager: fulguris.network.NetworkEngineManager = hiltEntryPoint.networkEngineManager
    val userScriptManager: fulguris.userscript.UserScriptManager = hiltEntryPoint.userScriptManager

    private var adBlock: AdBlocker

    private var sslErrorUrls = arrayListOf<String>()

    @Volatile private var isRunning = false
    private var zoomScale = 0.0f

    private var currentUrl: String = ""

    private var iResourceCount: Int = 0

    private var pageLoadStartTime: Long = 0

    private var onPageFinishedDone = false

    data class PageRequest(
        val url: String,
        val wasBlocked: Boolean,
        val timestamp: Long = System.currentTimeMillis()
    )

    private val pageRequests = mutableListOf<PageRequest>()

    fun getPageRequests(): List<PageRequest> = pageRequests.toList()

    fun clearPageRequests() {
        pageRequests.clear()
    }

    var sslState: SslState = SslState.None
        private set(value) {
            field = value
            webBrowser.updateSslState(field)
        }

    init {
        adBlock = chooseAdBlocker()
    }

    fun updatePreferences() {
        adBlock = chooseAdBlocker()
    }

    private fun chooseAdBlocker(): AdBlocker = if (userPreferences.adBlockEnabled) {
        abpBlockerManager
    } else {
        noopBlocker
    }

    private fun applyDesktopModeIfNeeded(aView: WebView) {
        aView.settings.useWideViewPort = false

        if (webPageTab.desktopMode) {
            if (aView.context.configPrefs.desktopWidth != 100F) {
                aView.settings.useWideViewPort = true
                Timber.w("evaluateJavascript: desktop mode")
                aView.evaluateJavascript(setMetaViewport.provideJs().replaceFirst("\$width\$", "${aView.context.configPrefs.desktopWidth}"), null)
            }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        Timber.v("$ihs : shouldInterceptRequest - ${if (request.isForMainFrame) "Main frame" else "Resource"} - ${request.url}")

        val response = adBlock.shouldBlock(request, currentUrl)
        val wasBlocked = response != null

        val url = request.url.toString()

        if (request.isForMainFrame) {
            if (url.endsWith(".user.js")) {
                handleUserScriptInstallation(url)
            }

            if (webPageTab.targetUrl != request.url) {
                Timber.i("$ihs : Main frame navigation detected, updating targetUrl: $url")
                webPageTab.targetUrl = request.url
            }

            pageLoadStartTime = System.currentTimeMillis()
            onPageFinishedDone = false
        }

        synchronized(pageRequests) {
            pageRequests.add(PageRequest(url, wasBlocked))
        }

        if (response != null) {
            return response
        }

        val engine = networkEngineManager.getCurrentEngine()
        if (engine != null) {
            val engineResponse = engine.handleRequest(request)
            if (engineResponse != null) {
                Timber.v("$ihs : Request handled by ${engine.displayName}: ${request.url}")
                return engineResponse
            }
        }

        return null
    }

    fun resetLocationPermissionIfNeeded() {
        if (domainPreferences.isDefault) {
            return
        }

        val hasLocationPermission = PermissionsManager.getInstance().hasPermission(
            activity,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) || PermissionsManager.getInstance().hasPermission(
            activity,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (!hasLocationPermission) {
            domainPreferences.hasLocationPermission {
                if (it) {
                    domainPreferences.clearLocationPermission()
                }
            }
        }
    }

    override fun onLoadResource(view: WebView, url: String?) {
        super.onLoadResource(view, url)

        val isForMainFrame = webPageTab.targetUrl.toString() == url
        if (isForMainFrame) {
            iResourceCount = 0
            clearPageRequests()
            webPageTab.clearConsoleMessages()
        }

        iResourceCount++
        Timber.d("$ihs : onLoadResource - ${if (isForMainFrame) "Main frame" else "Resource"} - $iResourceCount - $url")
    }

    /**
     * 原生 URL 状态同步更新（不强行用不存在的工具类隐藏端口，保持系统稳定性）
     */
    fun updateUrlIfNeeded(url: String) {
        if (webPageTab.lastUrl != url) {
            webPageTab.lastUrl = url
            webBrowser.onTabChangedUrl(webPageTab)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        val pageLoadDuration = System.currentTimeMillis() - pageLoadStartTime
        val skip = onPageFinishedDone || view.progress != 100
        Timber.i("$ihs : onPageFinished ${if (skip) "- skipping -" else "-"} Shown: ${view.isShown} - Progress: ${view.progress} - $url - Load time: ${pageLoadDuration}ms - Resources: $iResourceCount")

        updateUrlIfNeeded(url)

        if (skip) {
            return
        }

        onPageFinishedDone = true
        webPageTab.isLoading = false

        if (view.context.configPrefs.pullToRefresh && view.settings.javaScriptEnabled) {
            view.evaluateJavascript(nestedScrollDetectJs.provideJs(), null)
        }

        if (view.settings.javaScriptEnabled) {
            view.evaluateJavascript(blobHookJs.provideJs(), null)
        }

        webPageTab.onLoadCompleteCallback?.invoke()
        webPageTab.onLoadCompleteCallback = null

        applyDesktopModeIfNeeded(view)

        if (view.title == null || (view.title as String).isEmpty()) {
            webPageTab.titleInfo.setTitle(activity.getString(R.string.untitled))
        } else {
            view.title?.let { webPageTab.titleInfo.setTitle(it) }
        }
        if (webPageTab.invertPage) {
            Timber.w("evaluateJavascript: invert page colors")
            view.evaluateJavascript(invertPageJs.provideJs(), null)
        }

        if (userPreferences.forceZoom) {
            view.loadUrl(
                "javascript:(function() { document.querySelector('meta[name=\"viewport\"]').setAttribute(\"content\",\"width=device-width\"); })();"
            )
        }

        if (userPreferences.extensionsEnabled) {
            val scriptCode = userScriptManager.getInjectionCode(url, fulguris.userscript.RunAt.DOCUMENT_END)
            if (scriptCode != null) {
                Timber.d("Injecting DOCUMENT_END userscripts for $url")
                view.evaluateJavascript(scriptCode, null)
            }

            val idleScriptCode = userScriptManager.getInjectionCode(url, fulguris.userscript.RunAt.DOCUMENT_IDLE)
            if (idleScriptCode != null) {
                view.postDelayed({
                    Timber.d("Injecting DOCUMENT_IDLE userscripts for $url")
                    view.evaluateJavascript(idleScriptCode, null)
                }, 3000)
            }
        }

        webBrowser.onTabChanged(webPageTab)

        if (userPreferences.isLog(LogLevel.VERBOSE)) {
            val cookies = CookieManager.getInstance().getCookie(url)?.split(';')
            Timber.v("Cookies count: ${cookies?.count()}")
            cookies?.forEach {
                Timber.v(it.trim())
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        Timber.i("$ihs : onPageStarted - $url")

        onPageFinishedDone = false
        webPageTab.isLoading = true

        currentUrl = url

        updateUrlIfNeeded(url)

        val uri = url.toUri()
        loadDomainPreferences(uri.host ?: "", false)
        resetLocationPermissionIfNeeded()
        applyDesktopModeIfNeeded(view)

        if (userPreferences.extensionsEnabled) {
            val scriptCode = userScriptManager.getInjectionCode(url, fulguris.userscript.RunAt.DOCUMENT_START)
            if (scriptCode != null) {
                Timber.d("Injecting DOCUMENT_START userscripts for $url")
                view.evaluateJavascript(scriptCode, null)
            }
        }

        (view as WebViewEx).proxy.apply {
            if (!darkModeBypassDomainSettings) {
                darkMode = domainPreferences.darkMode
            }

            if (!desktopModeBypassDomainSettings) {
                desktopMode = domainPreferences.desktopMode
            }

            if (domainPreferences.javaScriptEnabled) {
                view.settings.javaScriptEnabled = true
                view.settings.javaScriptCanOpenWindowsAutomatically = true
            } else {
                view.settings.javaScriptEnabled = false
                view.settings.javaScriptCanOpenWindowsAutomatically = false
            }
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(view, domainPreferences.thirdPartyCookies)

        sslState = if (sslErrorUrls.contains(url)) {
            SslState.Invalid
        } else {
            if (URLUtil.isHttpsUrl(url)) {
                SslState.Valid
            } else if (url.isSpecialUrl() || !url.isScheme("http") && !url.isScheme("https")) {
                SslState.None
            } else {
                SslState.Insecure
            }
        }
        webPageTab.titleInfo.resetFavicon()
        if (webPageTab.isShown) {
            webBrowser.showActionBar()
        }

        webPageTab.shouldFetchMetaTags = true
        webBrowser.onPageStarted(webPageTab)
    }

    override fun onReceivedClientCertRequest(view: WebView?, request: ClientCertRequest?) {
        Timber.d("$ihs : onReceivedClientCertRequest")
        super.onReceivedClientCertRequest(view, request)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
        Timber.d("$ihs : onReceivedHttpAuthRequest")
        MaterialAlertDialogBuilder(activity).apply {
            val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_auth_request, null)

            val realmLabel = dialogView.findViewById<TextView>(R.id.auth_request_realm_textview)
            val name = dialogView.findViewById<EditText>(R.id.auth_request_username_edittext)
            val password = dialogView.findViewById<EditText>(R.id.auth_request_password_edittext)

            realmLabel.text = activity.getString(R.string.label_realm, realm)

            setView(dialogView)
            setTitle(R.string.title_sign_in)
            setCancelable(true)
            setPositiveButton(R.string.title_sign_in) { _, _ ->
                val user = name.text.toString()
                val pass = password.text.toString()
                handler.proceed(user.trim(), pass.trim())
                Timber.i("Attempting HTTP Authentication")
            }
            setNegativeButton(R.string.action_cancel) { _, _ ->
                handler.cancel()
            }
        }.launch()
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            Timber.w("$ihs : onReceivedError (modern): ${request?.url} - Code: ${error?.errorCode} - ${error?.description}")
        } else {
            Timber.w("$ihs : onReceivedError (modern): ${request?.url}")
        }
        super.onReceivedError(view, request, error)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(webview: WebView, errorCode: Int, error: String, failingUrl: String) {
        Timber.e("onReceivedError: ${domainPreferences.domain}")

        val output = ByteArrayOutputStream()
        val bitmap = activity.getDrawable(R.drawable.ic_about, android.R.attr.state_enabled).toBitmap()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        val imageBytes: ByteArray = output.toByteArray()
        val imageString = "data:image/png;base64," + Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val script = """(function() {
        document.getElementsByTagName('style')[0].innerHTML += "body { margin: 10px; background-color: ${htmlColor(ThemeUtils.getSurfaceColor(activity))}; color: ${htmlColor(ThemeUtils.getOnSurfaceColor(activity))};}"
        var img = document.getElementsByTagName('img')[0]
        img.src = "$imageString"
        img.width = ${bitmap.width}
        img.height = ${bitmap.height}
        })()"""

        Thread.sleep(100)
        Timber.w("evaluateJavascript: error page theming")
        webview.evaluateJavascript(script) {}
    }

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        Timber.d("$ihs : onScaleChanged")
        if (view.isShown && webPageTab.userPreferences.textReflowEnabled) {
            if (isRunning)
                return
            val changeInPercent = abs(100 - 100 / zoomScale * newScale)
            if (changeInPercent > 2.5f && !isRunning) {
                isRunning = view.postDelayed({
                    zoomScale = newScale
                    Timber.w("evaluateJavascript: text reflow")
                    view.evaluateJavascript(textReflowJs.provideJs()) { isRunning = false }
                }, 100)
            }
        }
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
        Timber.d("$ihs : onReceivedSslError")
        Timber.e("WebView URL: ${webView.url}")
        Timber.e("SSL error URL: ${error.url}")

        if (!sslErrorUrls.contains(error.url)) {
            sslErrorUrls.add(error.url)
        }
        if (sslState != SslState.Invalid) {
            sslState = SslState.Invalid
        }

        when (domainPreferences.sslError) {
            NoYesAsk.YES -> return handler.proceed()
            NoYesAsk.NO -> {
                val errorDomain = domainPreferences.domain
                activity.makeSnackbar(activity.getString(R.string.message_ssl_error_aborted), 5000, Gravity.BOTTOM)
                    .setIcon(R.drawable.ic_encrypted_off_outline)
                    .setAction(R.string.settings) {
                        (activity as? WebBrowserActivity)?.showDomainSettings(errorDomain)
                    }
                    .show()
                return handler.cancel()
            }
            else -> {}
        }

        val errorCodeMessageCodes = getAllSslErrorMessageCodes(error)
        val stringBuilder = StringBuilder()
        for (messageCode in errorCodeMessageCodes) {
            stringBuilder.append("❌ ").append(activity.getString(messageCode)).append("\n\n")
        }

        val alertMessage = activity.getText(R.string.message_ssl_error, domainPreferences.domain, stringBuilder.toString().trim() + "\n")?.trim()

        MaterialAlertDialogBuilder(activity).apply {
            val view = LayoutInflater.from(activity).inflate(R.layout.dialog_with_checkbox, null)
            val dontAskAgain = view.findViewById<CheckBox>(R.id.checkBoxDontAskAgain)
            setTitle(activity.getString(R.string.title_warning))
            setMessage(alertMessage)
            setCancelable(true)
            setView(view)
            setIcon(R.drawable.ic_encrypted_off_outline)
            setOnCancelListener { handler.cancel() }
            setPositiveButton(activity.getString(R.string.action_yes)) { _, _ ->
                if (dontAskAgain.isChecked) {
                    applySslErrorToDomainSettings(NoYesAsk.YES)
                }
                handler.proceed()
            }
            setNegativeButton(activity.getString(R.string.action_no)) { _, _ ->
                if (dontAskAgain.isChecked) {
                    applySslErrorToDomainSettings(NoYesAsk.NO)
                }
                handler.cancel()
            }
        }.launch()
    }

    private fun applySslErrorToDomainSettings(aSslError: NoYesAsk) {
        if (!domainPreferences.isDefault) {
            domainPreferences.sslErrorOverride = true
            domainPreferences.sslErrorLocal = aSslError
        } else {
            Timber.w("Domain settings should have been loaded already")
        }
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
        Timber.d("$ihs : onFormResubmission")
        MaterialAlertDialogBuilder(activity).apply {
            setTitle(activity.getString(R.string.title_form_resubmission))
            setMessage(activity.getString(R.string.message_form_resubmission))
            setCancelable(true)
            setPositiveButton(activity.getString(R.string.action_yes)) { _, _ ->
                resend.sendToTarget()
            }
            setNegativeButton(activity.getString(R.string.action_no)) { _, _ ->
                dontResend.sendToTarget()
            }
        }.launch()
    }

    var appLaunchDialog: Dialog? = null
    var domainPreferences = DomainPreferences(app)

    private fun loadDomainPreferences(aHost: String, aEntryPoint: Boolean = false) {
        if (domainPreferences.domain == aHost) {
            Timber.v("$ihs : loadDomainPreferences: already loaded")
            return
        }

        Timber.d("$ihs : loadDomainPreferences for $aHost")
        domainPreferences = DomainPreferences(app, aHost)
    }

    private var debounceLaunch: Runnable? = null

    /**
     * 核心拦截与放行逻辑：
     * 1. 凡是已由 WebPageTab 换装了 802/803 端口的请求，直接放行直连；
     * 2. 网页内部相对链接/超链接如果再次触发 g.6z.ee，自动补齐 802/803 端口，杜绝撞回失效的 80/443；
     * 3. 彻底杜绝异步 DNS 重复请求，避免 Android 系统把请求认定为 cancelled。
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        Timber.i("$ihs : shouldOverrideUrlLoading - ${request.url}")

        val url = request.url.toString()
        val uri = request.url
        val host = uri.host?.lowercase() ?: ""
        val scheme = uri.scheme?.lowercase() ?: "http"

        // 已经带有端口的请求直接放行
        if (uri.port != -1) {
            return false
        }

        // 网页内超链接点击：命中 g.6z.ee 且未显式带端口时，自动以 802/803 接管默认功能
        if (host == "g.6z.ee" && (scheme == "http" || scheme == "https")) {
            val targetPort = if (scheme == "https") 803 else 802
            val path = uri.encodedPath ?: ""
            val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
            val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
            val targetUrl = "$scheme://$host:$targetPort$path$query$fragment"
            view.loadUrl(targetUrl)
            return true
        }

        val headers = webPageTab.requestHeaders

        if (webPageTab.isIncognito || url.isSpecialUrl() || URLUtil.isAboutUrl(url)) {
            return shouldStopUrlLoading(view, url, headers)
        }

        if (url.endsWith(".user.js") && userPreferences.extensionsEnabled && request.isForMainFrame) {
            handleUserScriptInstallation(url)
            return true
        }

        val intent = activity.intentForUrl(view, uri)
        if (intent != null) {
            if (webPageTab.isForeground) {
                var appLaunched = false

                if (debounceLaunch == null) {
                    appLaunched = launchAppIfNeeded(view, intent)
                }
                view.removeCallbacks(debounceLaunch)
                debounceLaunch = Runnable {
                    debounceLaunch = null
                }
                view.postDelayed(debounceLaunch, 1000)

                if (appLaunched) {
                    Timber.d("$ihs : Override loading after app launch")
                    view.stopLoading()
                    if (activity is WebBrowserActivity) {
                        activity.closeCurrentTabIfEmpty()
                    }
                    return true
                }
            }
        }

        return shouldStopUrlLoading(view, url, headers, intent != null)
    }

    private fun launchAppIfNeeded(view: WebView, intent: Intent): Boolean {
        Timber.d("$ihs : launchAppIfNeeded: $intent")

        when (domainPreferences.launchApp) {
            NoYesAsk.YES -> {
                Timber.d("$ihs : Launch app - YES")
                return activity.startActivityWithFallback(view, intent, false)
            }
            NoYesAsk.NO -> {
                Timber.d("$ihs : Launch app - NO")
                return false
            }
            NoYesAsk.ASK -> {
                Timber.d("$ihs : Launch app - ASK")

                if (appLaunchDialog == null) {
                    val packageManager = activity.packageManager
                    val allResolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    val url = intent.data
                    val specializedApps = allResolveInfos.filter { info ->
                        info.filter?.isSpecializedFor(url) ?: false
                    }

                    val resolveInfos = if (specializedApps.isNotEmpty()) specializedApps else allResolveInfos

                    if (resolveInfos.isEmpty()) {
                        Timber.w("No apps found to handle intent")
                        return activity.startActivityWithFallback(view, intent, true)
                    }

                    val hasSingleApp = resolveInfos.size == 1
                    val dialogView: android.view.View

                    if (hasSingleApp) {
                        val resolveInfo = resolveInfos.first()
                        val appLabel = resolveInfo.loadLabel(packageManager).toString()
                        val appIcon = resolveInfo.loadIcon(packageManager)

                        dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_app_launch, null)
                        dialogView.findViewById<android.widget.ImageView>(R.id.app_icon)?.setImageDrawable(appIcon)
                        dialogView.findViewById<TextView>(R.id.app_label)?.text = appLabel

                        Timber.d("$ihs : Single app: $appLabel")
                    } else {
                        dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_with_checkbox, null)
                        Timber.d("$ihs : Multiple apps available (${resolveInfos.size})")
                    }

                    val checkboxView = dialogView.findViewById<CheckBox>(R.id.checkBoxDontAskAgain)

                    appLaunchDialog = MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.dialog_title_third_party_app)
                        .setMessage(R.string.dialog_message_third_party_app)
                        .setView(dialogView)
                        .setPositiveButton(activity.getText(R.string.action_launch)) { _, _ ->
                            if (checkboxView.isChecked) {
                                domainPreferences.launchAppOverride = true
                                domainPreferences.launchAppLocal = NoYesAsk.YES
                                Timber.d("$ihs : Saved preference: Launch app = YES for domain ${domainPreferences.domain}")
                            }
                            activity.startActivityWithFallback(view, intent, false)
                            appLaunchDialog = null
                        }
                        .setNegativeButton(activity.getText(R.string.action_cancel)) { _, _ ->
                            if (checkboxView.isChecked) {
                                domainPreferences.launchAppOverride = true
                                domainPreferences.launchAppLocal = NoYesAsk.NO
                                Timber.d("$ihs : Saved preference: Launch app = NO for domain ${domainPreferences.domain}")
                            }
                            activity.startActivityWithFallback(view, intent, true)
                            appLaunchDialog = null
                        }.setOnCancelListener {
                            appLaunchDialog = null
                        }.launch()
                }
                return false
            }
        }
    }

    private fun shouldStopUrlLoading(webView: WebView, url: String, headers: Map<String, String>, aSkipErrorPage: Boolean = true): Boolean {
        Timber.d("$ihs : shouldStopUrlLoading")

        if (!URLUtil.isNetworkUrl(url)
            && !URLUtil.isFileUrl(url)
            && !URLUtil.isAboutUrl(url)
            && !URLUtil.isDataUrl(url)
            && !URLUtil.isJavaScriptUrl(url)
        ) {
            webView.stopLoading()
            Timber.w("$ihs : Stop loading unsupported scheme: $url")
            return true
        }
        return when {
            headers.isEmpty() -> false
            else -> {
                webView.loadUrl(url, headers)
                Timber.w("$ihs : Load URL with headers")
                true
            }
        }
    }

    private fun handleUserScriptInstallation(url: String) {
        Timber.i("$ihs : Detected userscript URL: $url")

        CoroutineScope(Dispatchers.IO).coroutineLaunch {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val scriptContent = connection.inputStream.bufferedReader().use { it.readText() }

                    withContext(Dispatchers.Main) {
                        showUserScriptInstallDialog(scriptContent)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        activity.makeSnackbar(activity.getString(R.string.error_downloading_userscript), KDuration, Gravity.BOTTOM).show()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to download userscript")
                withContext(Dispatchers.Main) {
                    activity.makeSnackbar(activity.getString(R.string.error_downloading_userscript), KDuration, Gravity.BOTTOM).show()
                }
            }
        }
    }

    private fun showUserScriptInstallDialog(scriptContent: String) {
        try {
            val metadata = UserScript.extractMetadata(scriptContent)
            val scriptName = metadata["name"] ?: activity.getString(R.string.extension_name_not_specified)
            val version = metadata["version"] ?: activity.getString(R.string.extension_version_not_specified)
            val author = metadata["author"] ?: activity.getString(R.string.extension_author_not_specified)

            MaterialAlertDialogBuilder(activity).apply {
                setIcon(R.drawable.ic_extension_outline)
                setTitle(R.string.dialog_title_install_extension)
                setMessage(activity.getString(R.string.dialog_message_install_extension, scriptName, version, author))
                setPositiveButton(R.string.action_install) { _, _ ->
                    installUserScript(scriptContent, scriptName)
                }
                setNegativeButton(R.string.action_cancel, null)
            }.launch()
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse userscript")
            activity.makeSnackbar(activity.getString(R.string.error_parsing_userscript), KDuration, Gravity.BOTTOM).show()
        }
    }

    private fun installUserScript(scriptContent: String, scriptName: String) {
        val scriptId = userScriptManager.installScript(scriptContent)
        if (scriptId != null) {
            val snackbar = activity.makeSnackbar(
                activity.getString(R.string.extension_installed_successfully, scriptName),
                KDuration,
                Gravity.BOTTOM
            )
            snackbar.setAction(R.string.settings) {
                val intent = Intent(activity, SettingsActivity::class.java).apply {
                    putExtra(FRAGMENT_CLASS_NAME, "fulguris.settings.fragment.ExtensionsSettingsFragment")
                    putExtra(PREFERENCE_KEY, "script_$scriptId")
                }
                activity.startActivity(intent)
            }
            snackbar.show()
            Timber.i("Userscript installed: $scriptName")
        } else {
            activity.makeSnackbar(activity.getString(R.string.error_installing_userscript), KDuration, Gravity.BOTTOM).show()
            Timber.e("Failed to install userscript: $scriptName")
        }
    }

    private fun getAllSslErrorMessageCodes(error: SslError): List<Int> {
        val errorCodeMessageCodes = ArrayList<Int>(1)

        if (error.hasError(SslError.SSL_DATE_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_date_invalid)
        }
        if (error.hasError(SslError.SSL_EXPIRED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_expired)
        }
        if (error.hasError(SslError.SSL_IDMISMATCH)) {
            errorCodeMessageCodes.add(R.string.message_certificate_domain_mismatch)
        }
        if (error.hasError(SslError.SSL_NOTYETVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_not_yet_valid)
        }
        if (error.hasError(SslError.SSL_UNTRUSTED)) {
            errorCodeMessageCodes.add(R.string.message_certificate_untrusted)
        }
        if (error.hasError(SslError.SSL_INVALID)) {
            errorCodeMessageCodes.add(R.string.message_certificate_invalid)
        }

        if (BuildConfig.DEBUG) {
            errorCodeMessageCodes.add(R.string.message_certificate_invalid)
        }

        return errorCodeMessageCodes
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        Timber.e("onRenderProcessGone")
        return webPageTab.onRenderProcessGone(view, detail)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        Timber.d("$ihs : doUpdateVisitedHistory: $isReload - $url - ${view.url}")
        super.doUpdateVisitedHistory(view, url, isReload)
        updateUrlIfNeeded(url)
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?
    ) {
        Timber.w("$ihs : onReceivedHttpError: ${request?.url} - Status: ${errorResponse?.statusCode}")
        super.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        Timber.d("$ihs : onPageCommitVisible: $url")
        super.onPageCommitVisible(view, url)
    }

    override fun shouldOverrideKeyEvent(view: WebView?, event: KeyEvent?): Boolean {
        Timber.d("$ihs : shouldOverrideKeyEvent: $event")
        return super.shouldOverrideKeyEvent(view, event)
    }

    override fun onUnhandledKeyEvent(view: WebView?, event: KeyEvent?) {
        Timber.d("$ihs : onUnhandledKeyEvent: $event")
        super.onUnhandledKeyEvent(view, event)
    }

    override fun onReceivedLoginRequest(view: WebView?, realm: String?, account: String?, args: String?) {
        Timber.d("$ihs : onReceivedLoginRequest: $realm")
        super.onReceivedLoginRequest(view, realm, account, args)
    }

    override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
        Timber.d("$ihs : onSafeBrowsingHit: $threatType")
        super.onSafeBrowsingHit(view, request, threatType, callback)
    }
}
