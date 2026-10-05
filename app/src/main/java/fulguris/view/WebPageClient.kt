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
import fulguris.utils.DnsPortResolver
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
                aView.evaluateJavascript(setMetaViewport.provideJs().replaceFirst("\$width\$", "${aView.context.configPrefs.desktopWidth}"), null)
            }
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val reqUri = request.url
        val scheme = reqUri.scheme?.lowercase() ?: ""
        val rawUrl = reqUri.toString()

        if (request.isForMainFrame) {
            // 防火墙 1：严禁任何 https 带着明文 802 端口发起请求
            if (scheme == "https" && reqUri.port == 802) {
                val fixedUrl = rawUrl.replace(":802", ":803")
                activity.runOnUiThread {
                    view.stopLoading()
                    view.loadUrl(fixedUrl)
                }
                return WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
            }

            // 防火墙 2：针对未显式带端口的主文档请求，先查动态端口
            if (reqUri.port == -1 && (scheme == "http" || scheme == "https")) {
                val targetUrl = DnsPortResolver.getCachedUrl(rawUrl)
                if (targetUrl != null && targetUrl != rawUrl) {
                    activity.runOnUiThread {
                        view.stopLoading()
                        view.loadUrl(targetUrl)
                    }
                    return WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(ByteArray(0)))
                } else {
                    kotlinx.coroutines.runBlocking {
                        val resolved = DnsPortResolver.resolveTargetUrl(rawUrl)
                        if (resolved != rawUrl) {
                            activity.runOnUiThread {
                                view.stopLoading()
                                view.loadUrl(resolved)
                            }
                        }
                    }
                }
            }
            // 防火墙 3：针对恢复会话、历史记录点击等带有端口的请求，静默验证以更新地址栏状态
            else if (reqUri.port != -1 && (scheme == "http" || scheme == "https")) {
                if (!DnsPortResolver.isCachedAsDefaultPort(rawUrl)) {
                    kotlinx.coroutines.runBlocking {
                        if (DnsPortResolver.verifyAndCachePort(rawUrl)) {
                            activity.runOnUiThread {
                                webPageTab.lastUrl = "" // 强制失效触发 UI 更新
                                updateUrlIfNeeded(rawUrl)
                            }
                        }
                    }
                }
            }
        }

        val response = adBlock.shouldBlock(request, currentUrl)
        val wasBlocked = response != null
        val url = request.url.toString()

        if (request.isForMainFrame) {
            if (url.endsWith(".user.js")) {
                handleUserScriptInstallation(url)
            }
            if (webPageTab.targetUrl != request.url) {
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
    }

    fun updateUrlIfNeeded(url: String) {
        val cleanUrl = DnsPortResolver.cleanUrlForDisplay(url)
        if (webPageTab.lastUrl != cleanUrl) {
            webPageTab.lastUrl = cleanUrl
            webBrowser.onTabChangedUrl(webPageTab)
        }
    }

    override fun onPageFinished(view: WebView, url: String) {
        val skip = onPageFinishedDone || view.progress != 100
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
                view.evaluateJavascript(scriptCode, null)
            }
            val idleScriptCode = userScriptManager.getInjectionCode(url, fulguris.userscript.RunAt.DOCUMENT_IDLE)
            if (idleScriptCode != null) {
                view.postDelayed({
                    view.evaluateJavascript(idleScriptCode, null)
                }, 3000)
            }
        }

        webBrowser.onTabChanged(webPageTab)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
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
        super.onReceivedClientCertRequest(view, request)
    }

    override fun onReceivedHttpAuthRequest(
        view: WebView,
        handler: HttpAuthHandler,
        host: String,
        realm: String
    ) {
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
            }
            setNegativeButton(R.string.action_cancel) { _, _ ->
                handler.cancel()
            }
        }.launch()
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
    }

    @Deprecated("Deprecated in Java")
    override fun onReceivedError(webview: WebView, errorCode: Int, error: String, failingUrl: String) {
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
        webview.evaluateJavascript(script) {}
    }

    override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
        if (view.isShown && webPageTab.userPreferences.textReflowEnabled) {
            if (isRunning) return
            val changeInPercent = abs(100 - 100 / zoomScale * newScale)
            if (changeInPercent > 2.5f && !isRunning) {
                isRunning = view.postDelayed({
                    zoomScale = newScale
                    view.evaluateJavascript(textReflowJs.provideJs()) { isRunning = false }
                }, 100)
            }
        }
    }

    @SuppressLint("WebViewClientOnReceivedSslError")
    override fun onReceivedSslError(webView: WebView, handler: SslErrorHandler, error: SslError) {
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
        }
    }

    override fun onFormResubmission(view: WebView, dontResend: Message, resend: Message) {
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
            return
        }
        domainPreferences = DomainPreferences(app, aHost)
    }

    private var debounceLaunch: Runnable? = null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        val uri = request.url
        val scheme = uri.scheme?.lowercase() ?: ""

        if (uri.port != -1) {
            if (scheme == "https" && uri.port == 802) {
                val fixedUrl = url.replace(":802", ":803")
                view.loadUrl(fixedUrl)
                return true
            }
            return false
        }

        val cachedUrl = DnsPortResolver.getCachedUrl(url)
        if (cachedUrl != null && cachedUrl != url) {
            view.loadUrl(cachedUrl)
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
                debounceLaunch = Runnable { debounceLaunch = null }
                view.postDelayed(debounceLaunch, 1000)

                if (appLaunched) {
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
        when (domainPreferences.launchApp) {
            NoYesAsk.YES -> return activity.startActivityWithFallback(view, intent, false)
            NoYesAsk.NO -> return false
            NoYesAsk.ASK -> {
                if (appLaunchDialog == null) {
                    val packageManager = activity.packageManager
                    val allResolveInfos = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    val url = intent.data
                    val specializedApps = allResolveInfos.filter { info ->
                        info.filter?.isSpecializedFor(url) ?: false
                    }
                    val resolveInfos = if (specializedApps.isNotEmpty()) specializedApps else allResolveInfos

                    if (resolveInfos.isEmpty()) {
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
                    } else {
                        dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_with_checkbox, null)
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
                            }
                            activity.startActivityWithFallback(view, intent, false)
                            appLaunchDialog = null
                        }
                        .setNegativeButton(activity.getText(R.string.action_cancel)) { _, _ ->
                            if (checkboxView.isChecked) {
                                domainPreferences.launchAppOverride = true
                                domainPreferences.launchAppLocal = NoYesAsk.NO
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
        if (!URLUtil.isNetworkUrl(url)
            && !URLUtil.isFileUrl(url)
            && !URLUtil.isAboutUrl(url)
            && !URLUtil.isDataUrl(url)
            && !URLUtil.isJavaScriptUrl(url)
        ) {
            webView.stopLoading()
            return true
        }
        return when {
            headers.isEmpty() -> false
            else -> {
                webView.loadUrl(url, headers)
                true
            }
        }
    }

    private fun handleUserScriptInstallation(url: String) {
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
        } else {
            activity.makeSnackbar(activity.getString(R.string.error_installing_userscript), KDuration, Gravity.BOTTOM).show()
        }
    }

    private fun getAllSslErrorMessageCodes(error: SslError): List<Int> {
        val errorCodeMessageCodes = ArrayList<Int>(1)
        if (error.hasError(SslError.SSL_DATE_INVALID)) errorCodeMessageCodes.add(R.string.message_certificate_date_invalid)
        if (error.hasError(SslError.SSL_EXPIRED)) errorCodeMessageCodes.add(R.string.message_certificate_expired)
        if (error.hasError(SslError.SSL_IDMISMATCH)) errorCodeMessageCodes.add(R.string.message_certificate_domain_mismatch)
        if (error.hasError(SslError.SSL_NOTYETVALID)) errorCodeMessageCodes.add(R.string.message_certificate_not_yet_valid)
        if (error.hasError(SslError.SSL_UNTRUSTED)) errorCodeMessageCodes.add(R.string.message_certificate_untrusted)
        if (error.hasError(SslError.SSL_INVALID)) errorCodeMessageCodes.add(R.string.message_certificate_invalid)
        if (BuildConfig.DEBUG) errorCodeMessageCodes.add(R.string.message_certificate_invalid)
        return errorCodeMessageCodes
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        return webPageTab.onRenderProcessGone(view, detail)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        updateUrlIfNeeded(url)
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        super.onReceivedHttpError(view, request, errorResponse)
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
    }

    override fun shouldOverrideKeyEvent(view: WebView?, event: KeyEvent?): Boolean {
        return super.shouldOverrideKeyEvent(view, event)
    }

    override fun onUnhandledKeyEvent(view: WebView?, event: KeyEvent?) {
        super.onUnhandledKeyEvent(view, event)
    }

    override fun onReceivedLoginRequest(view: WebView?, realm: String?, account: String?, args: String?) {
        super.onReceivedLoginRequest(view, realm, account, args)
    }

    override fun onSafeBrowsingHit(view: WebView?, request: WebResourceRequest?, threatType: Int, callback: SafeBrowsingResponse?) {
        super.onSafeBrowsingHit(view, request, threatType, callback)
    }
}
