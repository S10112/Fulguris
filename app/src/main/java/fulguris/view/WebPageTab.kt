/*
 * Copyright © 2020-2021 Stéphane Lenclud
 * Copyright 2014 A.C.R. Development
 */

package fulguris.view

import fulguris.Capabilities
import fulguris.R
import fulguris.activity.ThemedActivity
import fulguris.browser.TabModel
import fulguris.activity.WebBrowserActivity
import fulguris.browser.WebBrowser
import fulguris.dialog.LightningDialogBuilder
import fulguris.download.LightningDownloadListener
import fulguris.extensions.*
import fulguris.isSupported
import fulguris.network.NetworkConnectivityModel
import fulguris.settings.fragment.DisplaySettingsFragment.Companion.MIN_BROWSER_TEXT_SIZE
import fulguris.settings.preferences.DomainPreferences
import fulguris.settings.preferences.UserPreferences
import fulguris.settings.preferences.userAgent
import fulguris.settings.preferences.webViewEngineVersionDesktop
import fulguris.settings.preferences.setReducedClientHints
import fulguris.ssl.SslState
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.net.http.SslCertificate
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.view.*
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.View.OnScrollChangeListener
import android.view.View.OnTouchListener
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebSettings
import android.webkit.WebSettings.LOAD_DEFAULT
import android.webkit.WebSettings.LOAD_NO_CACHE
import android.webkit.WebSettings.LayoutAlgorithm
import android.webkit.WebView
import androidx.annotation.RequiresApi
import androidx.collection.ArrayMap
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.EntryPointAccessors
import fulguris.constant.Hosts
import fulguris.constant.Schemes
import fulguris.constant.Uris
import fulguris.constant.WINDOWS_DESKTOP_USER_AGENT_PREFIX
import fulguris.di.HiltEntryPoint
import fulguris.di.configPrefs
import fulguris.enums.LayerType
import fulguris.extensions.canScrollVertically
import fulguris.extensions.dp
import fulguris.extensions.isDarkTheme
import fulguris.extensions.makeSnackbar
import fulguris.extensions.px
import fulguris.extensions.removeFromParent
import fulguris.extensions.setIcon
import fulguris.utils.isBookmarkUrl
import fulguris.utils.isDownloadsUrl
import fulguris.utils.isHistoryUrl
import fulguris.utils.isSpecialUrl
import io.reactivex.Scheduler
import io.reactivex.Single
import io.reactivex.disposables.Disposable
import timber.log.Timber
import java.lang.ref.WeakReference

/**
 * [WebPageTab] acts as a tab for the browser, handling WebView creation and handling logic, as
 * well as properly initialing it. All interactions with the WebView should be made through this
 * class.
 */
class WebPageTab(
    private val activity: Activity,
    tabInitializer: TabInitializer,
    val isIncognito: Boolean,
    // TODO: Could we remove those?
    private val homePageInitializer: HomePageInitializer,
    private val incognitoPageInitializer: IncognitoPageInitializer,
    private val bookmarkPageInitializer: BookmarkPageInitializer,
    private val downloadPageInitializer: DownloadPageInitializer,
    private val historyPageInitializer: HistoryPageInitializer
): WebView.FindListener,
    SharedPreferences.OnSharedPreferenceChangeListener {

    /**
     * The unique ID of the view.
     */
    val id = View.generateViewId()

    /**
     * Getter for the [WebPageHeader] of the current [WebPageTab] instance.
     */
    val titleInfo: WebPageHeader

    /**
     * Meta theme-color content value as extracted from page HTML
     */
    var htmlMetaThemeColor: Int = KHtmlMetaThemeColorInvalid

    /**
     * Flag to indicate if we should fetch HTML meta theme-color and color-scheme.
     * Set to false after first extraction attempt.
     */
    var shouldFetchMetaTags = true

    /**
     * Optional callback to execute after the next page finishes loading or is cancelled.
     * Will be executed once and then cleared automatically.
     */
    internal var onLoadCompleteCallback: (() -> Unit)? = null

    /**
     * Wrapper class to store ConsoleMessage with timestamp since webkit.ConsoleMessage doesn't expose timestamp
     */
    data class ConsoleMessage(
        val consoleMessage: android.webkit.ConsoleMessage,
        val timestamp: Long = System.currentTimeMillis()
    )

    // Track all console messages for the current page
    private val consoleMessages = mutableListOf<ConsoleMessage>()

    /**
     * Get all console messages for the current page
     */
    fun getConsoleMessages(): List<ConsoleMessage> = consoleMessages.toList()

    /**
     * Clear tracked console messages
     */
    fun clearConsoleMessages() {
        consoleMessages.clear()
    }

    /**
     * Add a console message to the collection with timestamp
     */
    fun addConsoleMessage(consoleMessage: android.webkit.ConsoleMessage) {
        synchronized(consoleMessages) {
            consoleMessages.add(ConsoleMessage(consoleMessage))
        }
    }

    /**
     * A tab initializer that should be run when the view is first attached.
     * Notably contains a bundle to be load in our webView.
     */
    private var latentTabInitializer: FreezableBundleInitializer? = null

    /**
     * Gets the current WebView instance of the tab.
     *
     * @return the WebView instance of the tab, which can be null.
     */
    var webView: WebViewEx? = null
        private set

    /**
     * The WebPageClient instance for this tab.
     * Provides access to page loading events and request tracking.
     */
    lateinit var webPageClient: WebPageClient
        private set

    /**
     * The URL we tried to load
     */
    private var iTargetUrl: Uri = Uri.parse("")

    /**
     * Public getter and setter for the target URL that we are attempting to load
     */
    var targetUrl: Uri
        get() = iTargetUrl
        set(value) {
            iTargetUrl = value
        }

    private val webBrowser: WebBrowser
    private lateinit var gestureDetector: GestureDetector
    private val paint = Paint()

    /**
     * Sets whether this tab was the result of a new intent sent to the browser.
     * That's notably used to decide if we close our activity when closing this tab thus going back to the app which opened it.
     */
    val isNewTab: Boolean get() = iIntent!=null

    var iIntent: Intent? = null

    /**
     * This method sets the tab as the foreground tab or a background tab.
     */
    var isForeground: Boolean = false
        set(aIsForeground) {
            field = aIsForeground
            if (isForeground) {
                // When frozen tab goes foreground we need to load its bundle in webView
                latentTabInitializer?.apply {
                    createWebView()
                    initializeContent(this)
                    latentTabInitializer = null
                }
            } else {
                iIntent = null
            }
            webBrowser.onTabChanged(this)
        }

    var invertPage = false
        private set

    var desktopMode = false
        set(aDesktopMode) {
            field = aDesktopMode
            if (aDesktopMode) {
                webView?.settings?.userAgentString = WINDOWS_DESKTOP_USER_AGENT_PREFIX + webViewEngineVersionDesktop(activity.application)
            } else {
                setUserAgentForPreference(userPreferences)
            }
        }

    var darkMode = false
        set(aDarkMode) {
            field = aDarkMode
            applyDarkMode()
        }

    var darkModeBypassDomainSettings = false
    var desktopModeBypassDomainSettings = false

    var searchQuery: String = ""
        set(aSearchQuery) {
            field = aSearchQuery
        }

    var searchActive = false

    private val webViewHandler = WebViewHandler(this)

    internal val requestHeaders = ArrayMap<String, String>()

    private val maxFling: Float

    private val hiltEntryPoint = EntryPointAccessors.fromApplication(activity.applicationContext, HiltEntryPoint::class.java)

    val userPreferences: UserPreferences = hiltEntryPoint.userPreferences
    val dialogBuilder: LightningDialogBuilder = hiltEntryPoint.dialogBuilder
    val databaseScheduler: Scheduler = hiltEntryPoint.databaseScheduler()
    val mainScheduler: Scheduler = hiltEntryPoint.mainScheduler()
    val networkConnectivityModel: NetworkConnectivityModel = hiltEntryPoint.networkConnectivityModel
    val defaultDomainSettings = DomainPreferences(activity)

    private val networkDisposable: Disposable

    private var layerType = LayerType.Hardware

    val isShown: Boolean
        get() = webView?.isShown == true

    val progress: Int
        get() = webView?.progress ?: 100

    var isLoading = false
        internal set

    private val userAgent: String
        get() = webView?.settings?.userAgentString ?: ""

    val favicon: Bitmap
        get() = titleInfo.getFavicon()

    val title: String
        get() = titleInfo.getTitle()

    val sslCertificate: SslCertificate?
        get() = webView?.certificate

    /**
     * 保持系统原生的真实 URL 读取，完全不破坏底层状态与会话管理
     */
    val url: String
        get() {
            return if (webView == null || webView!!.url.isNullOrBlank() || webView!!.url.isSpecialUrl()) {
                iTargetUrl.toString()
            } else {
                webView!!.url as String
            }
        }

    var lastUrl: String = ""

    val isFrozen : Boolean
        get() = latentTabInitializer?.tabModel?.webView != null

    private var iDownloadListener: LightningDownloadListener? = null

    init {
        webBrowser = activity as WebBrowser
        titleInfo = WebPageHeader(activity)
        maxFling = ViewConfiguration.get(activity).scaledMaximumFlingVelocity.toFloat()

        iTargetUrl = Uri.parse(tabInitializer.url())

        if (tabInitializer !is FreezableBundleInitializer) {
            createWebView()
            initializeContent(tabInitializer)
            desktopMode = defaultDomainSettings.desktopMode
            darkMode = defaultDomainSettings.darkMode
        } else {
            latentTabInitializer = tabInitializer
            titleInfo.setTitle(tabInitializer.tabModel.title)
            tabInitializer.tabModel.favicon.let {titleInfo.setFavicon(it)}
            desktopMode = tabInitializer.tabModel.desktopMode
            darkMode = tabInitializer.tabModel.darkMode
            searchQuery = tabInitializer.tabModel.searchQuery
            searchActive = tabInitializer.tabModel.searchActive
        }

        networkDisposable = networkConnectivityModel.connectivity()
            .observeOn(mainScheduler)
            .subscribe(::setNetworkAvailable)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            activity.getString(R.string.pref_key_scrollbar_size) -> {
                webView?.scrollBarSize = userPreferences.scrollbarSize.px.toInt()
                webView?.postInvalidate()
            }

            activity.getString(R.string.pref_key_scrollbar_fading) -> {
                webView?.isScrollbarFadingEnabled = userPreferences.scrollbarFading
                webView?.postInvalidate()
            }

            activity.getString(R.string.pref_key_scrollbar_delay_before_fade) ->
                webView?.scrollBarDefaultDelayBeforeFade = userPreferences.scrollbarDelayBeforeFade.toInt()

            activity.getString(R.string.pref_key_scrollbar_fade_duration) ->
                webView?.scrollBarFadeDuration = userPreferences.scrollbarFadeDuration.toInt()

            activity.getString(R.string.pref_key_location) -> {
                if (!isIncognito) {
                    webView?.settings?.setGeolocationEnabled(defaultDomainSettings.locationEnabled)
                }
            }
        }
    }

    private fun createWebView() {
        userPreferences.preferences.registerOnSharedPreferenceChangeListener(this)
        defaultDomainSettings.preferences.registerOnSharedPreferenceChangeListener(this)

        webPageClient = WebPageClient(activity, this)
        webView = activity.layoutInflater.inflate(R.layout.webview, null) as WebViewEx
        webView?.apply {
            proxy = this@WebPageTab
            Timber.d("WebView scrollbar defaults: ${scrollBarSize.toFloat().dp}, $scrollBarDefaultDelayBeforeFade, $scrollBarFadeDuration")
            scrollBarSize = userPreferences.scrollbarSize.px.toInt()
            isScrollbarFadingEnabled = userPreferences.scrollbarFading
            scrollBarDefaultDelayBeforeFade = userPreferences.scrollbarDelayBeforeFade.toInt()
            scrollBarFadeDuration = userPreferences.scrollbarFadeDuration.toInt()

            setFindListener(this@WebPageTab)
            gestureDetector = GestureDetector(activity, CustomGestureListener(this))

            isFocusableInTouchMode = true
            isFocusable = true
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                isAnimationCacheEnabled = false
                isAlwaysDrawnWithCacheEnabled = false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            }

            isSaveEnabled = true
            setNetworkAvailable(true)
            webChromeClient = WebPageChromeClient(activity, this@WebPageTab)
            webViewClient = webPageClient

            createDownloadListener()

            val tl = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) TouchListener().also { setOnScrollChangeListener(it) } else TouchListenerLollipop()
            setOnTouchListener(tl)

            initializeSettings()
        }

        initializePreferences()

        if (searchActive) {
            find(searchQuery)
        }
    }

    private fun createDownloadListener() {
        iDownloadListener = LightningDownloadListener(activity) { webView }
        webView?.setDownloadListener(iDownloadListener.also {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.registerReceiver(it, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
            } else {
                activity.registerReceiver(it, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            }
        })
    }

    private fun destroyDownloadListener() {
        if (iDownloadListener!=null) {
            webView?.setDownloadListener(null)
            activity.unregisterReceiver(iDownloadListener)
            iDownloadListener = null
        }
    }

    fun currentSslState(): SslState = webPageClient.sslState

    fun loadHomePage() {
        if (isIncognito) {
            iTargetUrl = Uri.parse(Uris.FulgurisIncognito)
            initializeContent(incognitoPageInitializer)
        } else {
            iTargetUrl = Uri.parse(Uris.FulgurisHome)
            initializeContent(homePageInitializer)
        }
    }

    fun loadBookmarkPage() {
        iTargetUrl = Uri.parse(Uris.FulgurisBookmarks)
        initializeContent(bookmarkPageInitializer)
    }

    fun loadDownloadsPage() {
        iTargetUrl = Uri.parse(Uris.FulgurisDownloads)
        initializeContent(downloadPageInitializer)
    }

    fun loadHistoryPage() {
        iTargetUrl = Uri.parse(Uris.FulgurisHistory)
        initializeContent(historyPageInitializer)
    }

    private fun initializeContent(tabInitializer: TabInitializer) {
        webView?.let { tabInitializer.initialize(it, requestHeaders) }
    }

    @SuppressLint("NewApi", "SetJavaScriptEnabled")
    fun initializePreferences() {
        val settings = webView?.settings ?: return

        webPageClient.updatePreferences()

        val modifiesHeaders = userPreferences.doNotTrackEnabled
            || userPreferences.saveDataEnabled
            || userPreferences.removeIdentifyingHeadersEnabled

        if (userPreferences.doNotTrackEnabled) {
            requestHeaders[HEADER_DNT] = "1"
        } else {
            requestHeaders.remove(HEADER_DNT)
        }

        if (userPreferences.saveDataEnabled) {
            requestHeaders[HEADER_SAVEDATA] = "on"
        } else {
            requestHeaders.remove(HEADER_SAVEDATA)
        }

        if (userPreferences.removeIdentifyingHeadersEnabled) {
            requestHeaders[HEADER_REQUESTED_WITH] = ""
            requestHeaders[HEADER_WAP_PROFILE] = ""
        } else {
            requestHeaders.remove(HEADER_REQUESTED_WITH)
            requestHeaders.remove(HEADER_WAP_PROFILE)
        }

        settings.defaultTextEncodingName = userPreferences.textEncoding
        layerType = userPreferences.layerType
        setColorMode(userPreferences.renderingMode)

        if (!isIncognito) {
            settings.setGeolocationEnabled(defaultDomainSettings.locationEnabled)
        } else {
            settings.setGeolocationEnabled(false)
        }

        desktopMode = desktopMode
        settings.saveFormData = userPreferences.savePasswordsEnabled && !isIncognito

        if (defaultDomainSettings.javaScriptEnabled) {
            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = true
        } else {
            settings.javaScriptEnabled = false
            settings.javaScriptCanOpenWindowsAutomatically = false
        }

        if (userPreferences.textReflowEnabled) {
            settings.layoutAlgorithm = LayoutAlgorithm.NARROW_COLUMNS
            try {
                settings.layoutAlgorithm = LayoutAlgorithm.TEXT_AUTOSIZING
            } catch (e: Exception) {
                Timber.e(e,"Problem setting LayoutAlgorithm to TEXT_AUTOSIZING")
            }
        } else {
            settings.layoutAlgorithm = LayoutAlgorithm.NORMAL
        }

        settings.blockNetworkImage = !userPreferences.loadImages
        settings.setSupportMultipleWindows(userPreferences.popupsEnabled && !modifiesHeaders)
        settings.loadWithOverviewMode = userPreferences.overviewModeEnabled
        settings.textZoom = userPreferences.browserTextSize + MIN_BROWSER_TEXT_SIZE

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, defaultDomainSettings.thirdPartyCookies)
        applyDarkMode()
    }

    private fun applyDarkMode() {
        val settings = webView?.settings ?: return

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, darkMode)
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK) &&
            ((activity as ThemedActivity).isDarkTheme() || darkMode)) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK_STRATEGY)) {
                if (darkMode) {
                    WebSettingsCompat.setForceDarkStrategy(
                        settings,
                        WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                    )
                } else {
                    WebSettingsCompat.setForceDarkStrategy(
                        settings,
                        WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY
                    )
                }
            }
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
        } else {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
                WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_OFF)
            } else {
                if (darkMode) {
                    setColorMode(RenderingMode.INVERTED_GRAYSCALE)
                } else {
                    setColorMode(userPreferences.renderingMode)
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private fun WebView.initializeSettings() {
        settings.apply {
            mediaPlaybackRequiresUserGesture = false

            if (API >= Build.VERSION_CODES.LOLLIPOP && !isIncognito) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            } else if (API >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }

            if (!isIncognito || Capabilities.FULL_INCOGNITO.isSupported) {
                domStorageEnabled = true
                cacheMode = LOAD_DEFAULT
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
            } else {
                domStorageEnabled = false
                cacheMode = LOAD_NO_CACHE
                databaseEnabled = false
                cacheMode = WebSettings.LOAD_NO_CACHE
            }

            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowContentAccess = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            setNeedInitialFocus(false)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                getPathObservable("geolocation")
                    .subscribeOn(databaseScheduler)
                    .observeOn(mainScheduler)
                    .subscribe { file ->
                        setGeolocationDatabasePath(file.path)
                    }
            }
        }
    }

    private fun getPathObservable(subFolder: String) = Single.fromCallable {
        activity.getDir(subFolder, 0)
    }

    fun toggleDesktopUserAgent(aBypass: Boolean = true) {
        desktopMode = !desktopMode
        desktopModeBypassDomainSettings = aBypass
    }

    fun toggleDarkMode(aBypass: Boolean = true) {
        darkMode = !darkMode
        darkModeBypassDomainSettings = aBypass
    }

    private fun setUserAgentForPreference(userPreferences: UserPreferences) {
        webView?.settings?.let { settings ->
            settings.userAgentString = userPreferences.userAgent(activity.application)
            settings.setReducedClientHints()
        }
    }

    private fun webViewState(): Bundle {
        latentTabInitializer?.tabModel?.webView?.let { return it }
        return Bundle(ClassLoader.getSystemClassLoader()).also { webView?.saveState(it) }
    }

    fun getModel() = TabModel(url, title, desktopMode, darkMode, favicon, searchQuery, searchActive, webViewState())

    fun saveState(): Bundle {
         return getModel().toBundle()
    }

    fun onPause() {
        webView?.onPause()
        Timber.d("WebView onPause: ${webView?.id}")
    }

    fun onResume() {
        webView?.onResume()
        Timber.d("WebView onResume: ${webView?.id}")
    }

    fun stopLoading() {
        webView?.stopLoading()
        isLoading = false
    }

    private fun setLayerType() {
        Timber.d("$ihs : setLayerType: $layerType")
        webView?.setLayerType(layerType.value, paint)
    }

    private fun setHardwareRendering() {
        webView?.setLayerType(View.LAYER_TYPE_SOFTWARE, paint)
    }

    private fun setNormalRendering() {
        webView?.setLayerType(View.LAYER_TYPE_NONE, paint)
    }

    fun setSoftwareRendering() {
        webView?.setLayerType(View.LAYER_TYPE_SOFTWARE, paint)
    }

    private fun setColorMode(mode: RenderingMode) {
        invertPage = false
        when (mode) {
            RenderingMode.NORMAL -> {
                paint.colorFilter = null
                setLayerType()
            }
            RenderingMode.INVERTED -> {
                val filterInvert = ColorMatrixColorFilter(negativeColorArray)
                paint.colorFilter = filterInvert
                setLayerType()
                invertPage = true
            }
            RenderingMode.GRAYSCALE -> {
                val cm = ColorMatrix()
                cm.setSaturation(0f)
                val filterGray = ColorMatrixColorFilter(cm)
                paint.colorFilter = filterGray
                setLayerType()
            }
            RenderingMode.INVERTED_GRAYSCALE -> {
                val matrix = ColorMatrix()
                matrix.set(negativeColorArray)
                val matrixGray = ColorMatrix()
                matrixGray.setSaturation(0f)
                val concat = ColorMatrix()
                concat.setConcat(matrix, matrixGray)
                val filterInvertGray = ColorMatrixColorFilter(concat)
                paint.colorFilter = filterInvertGray
                setLayerType()
                invertPage = true
            }
            RenderingMode.INCREASE_CONTRAST -> {
                val increaseHighContrast = ColorMatrixColorFilter(increaseContrastColorArray)
                paint.colorFilter = increaseHighContrast
                setLayerType()
            }
        }
    }

    fun pauseTimers() {
        webView?.pauseTimers()
        Timber.d("Pausing JS timers")
    }

    fun resumeTimers() {
        webView?.resumeTimers()
        Timber.d("Resuming JS timers")
    }

    fun requestFocus() {
        if (webView?.hasFocus() == false) {
            webView?.requestFocus()
        }
    }

    fun setVisibility(visible: Int) {
        webView?.visibility = visible
    }

    fun reload(aForce: Boolean = false) {
        webView?.let { wv ->
            if (!aForce) {
                loadUrl(url)
            } else {
                val originalCacheMode = wv.settings.cacheMode
                wv.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                loadUrl(url) {
                    wv.settings.cacheMode = originalCacheMode
                }
            }
        }
    }

    @SuppressLint("NewApi")
    fun find(text: String) {
        resetFind()
        searchQuery = text
        searchActive = true
        webView?.findAllAsync(text)
    }

    fun findNext() {
        webView?.findNext(true)
    }

    fun findPrevious() {
        webView?.findNext(false)
    }

    fun clearFind() {
        webView?.clearMatches()
        searchActive = false
        resetFind()
    }

    private var iActiveMatchOrdinal: Int = -1
    private var iNumberOfMatches: Int = -1
    private var iSnackbar: Snackbar? = null

    private fun resetFind() {
        iActiveMatchOrdinal = -1
        iNumberOfMatches = -1
    }

    override fun onFindResultReceived(activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) {
        if (isLoading || !isDoneCounting) {
            return
        }
        if (iActiveMatchOrdinal != activeMatchOrdinal || iNumberOfMatches != numberOfMatches) {
            iActiveMatchOrdinal = activeMatchOrdinal
            iNumberOfMatches = numberOfMatches

            if (searchQuery.isEmpty()) {
                iSnackbar?.dismiss()
            } else if (iNumberOfMatches==0) {
                iSnackbar = activity.makeSnackbar(
                        activity.getString(R.string.no_match_found),
                        Snackbar.LENGTH_SHORT, if (activity.configPrefs.toolbarsBottom) Gravity.TOP else Gravity.BOTTOM)
                        .setAction(R.string.button_dismiss) {
                            iSnackbar?.dismiss()
                        }
                iSnackbar?.show()
            } else {
                val currentMatch = iActiveMatchOrdinal + 1
                iSnackbar = activity.makeSnackbar(
                        activity.getString(R.string.match_x_of_n,currentMatch,iNumberOfMatches) ,
                        Snackbar.LENGTH_SHORT, if (activity.configPrefs.toolbarsBottom) Gravity.TOP else Gravity.BOTTOM)
                        .setAction(R.string.button_dismiss) {
                            iSnackbar?.dismiss()
                        }
                iSnackbar?.show()
            }
        }
    }

    fun destroy() {
        destroyWebView()
        networkDisposable.dispose()
    }

    private fun destroyWebView() {
        userPreferences.preferences.unregisterOnSharedPreferenceChangeListener(this)
        defaultDomainSettings.preferences.unregisterOnSharedPreferenceChangeListener(this)
        destroyDownloadListener()
        webView?.autoDestruction()
        webView = null
    }

    fun goBack() {
        isLoading = true
        webView?.goBack()
    }

    fun goForward() {
        isLoading = true
        webView?.goForward()
    }

    fun goBackOrForward(steps: Int) {
        isLoading = true
        webView?.goBackOrForward(steps)
    }

    private fun setNetworkAvailable(isAvailable: Boolean) {
        webView?.setNetworkAvailable(isAvailable)
    }

    private fun longClickPage(url: String?, text: String?, src: String?) {
        val result = webView?.hitTestResult
        val currentUrl = webView?.url
        val newUrl = result?.extra

        if (currentUrl != null && currentUrl.isSpecialUrl()) {
            if (currentUrl.isHistoryUrl()) {
                if (url != null) {
                    dialogBuilder.showLongPressedHistoryLinkDialog(activity, webBrowser, url)
                } else if (newUrl != null) {
                    dialogBuilder.showLongPressedHistoryLinkDialog(activity, webBrowser, newUrl)
                }
            } else if (currentUrl.isBookmarkUrl()) {
                if (url != null) {
                    dialogBuilder.showLongPressedDialogForBookmarkUrl(activity, webBrowser, url)
                } else if (newUrl != null) {
                    dialogBuilder.showLongPressedDialogForBookmarkUrl(activity, webBrowser, newUrl)
                }
            } else if (currentUrl.isDownloadsUrl()) {
                if (url != null) {
                    dialogBuilder.showLongPressedDialogForDownloadUrl(activity, webBrowser, url)
                } else if (newUrl != null) {
                    dialogBuilder.showLongPressedDialogForDownloadUrl(activity, webBrowser, newUrl)
                }
            }
        } else {
            result?.extra?.let { extraUrl ->
                if (result.type == WebView.HitTestResult.IMAGE_TYPE) {
                    dialogBuilder.showLongPressLinkImageDialog(
                        activity, webBrowser, "", extraUrl, text, userAgent,
                        showLinkTab = false,
                        showImageTab = true
                    )
                } else if (result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                    dialogBuilder.showLongPressLinkImageDialog(
                        activity, webBrowser, url ?: "", extraUrl, text, userAgent,
                        showLinkTab = true,
                        showImageTab = true
                    )
                } else if (result.type == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                    dialogBuilder.showLongPressLinkImageDialog(
                        activity, webBrowser, extraUrl, "", text, userAgent,
                        showLinkTab = true,
                        showImageTab = false
                    )
                }
            }
        }
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun canGoForward(): Boolean = webView?.canGoForward() == true

    /**
     * 核心接管加载方法：
     * 1. 遇到 g.6z.ee 时，直接将原本默认的 80/443 接管为 802/803 端口直连；
     * 2. 其他任何正常域名原封不动放行，绝不受任何干扰；
     * 3. 0ms 瞬间直达，无需外建任何工具类。
     */
    fun loadUrl(aUrl: String, onLoadComplete: (() -> Unit)? = null) {
        isLoading = true

        val uri = try { Uri.parse(aUrl) } catch (e: Exception) { Uri.parse("") }
        val scheme = uri.scheme?.lowercase() ?: "http"
        val host = uri.host?.lowercase() ?: ""

        val finalUrl = if (uri.port == -1 && (scheme == "http" || scheme == "https")) {
            val targetPort = when {
                host == "g.6z.ee" && scheme == "http" -> 802
                host == "g.6z.ee" && scheme == "https" -> 803
                // 未来如有其他解析了非标端口的域名，直接在此追加一行即可
                else -> null
            }

            if (targetPort != null) {
                val path = uri.encodedPath ?: ""
                val query = if (uri.encodedQuery != null) "?${uri.encodedQuery}" else ""
                val fragment = if (uri.encodedFragment != null) "#${uri.encodedFragment}" else ""
                "$scheme://$host:$targetPort$path$query$fragment"
            } else {
                aUrl
            }
        } else {
            aUrl
        }

        iTargetUrl = Uri.parse(finalUrl)
        onLoadCompleteCallback = onLoadComplete

        if (iTargetUrl.scheme == Schemes.Fulguris || iTargetUrl.scheme == Schemes.About) {
            if (iTargetUrl.host == Hosts.Home) {
                loadHomePage()
            } else if (iTargetUrl.host == Hosts.Bookmarks) {
                loadBookmarkPage()
            } else if (iTargetUrl.host == Hosts.History) {
                loadHistoryPage()
            }
        } else {
            webView?.loadUrl(finalUrl, requestHeaders)
        }
    }

    fun showToolBarOnScrollUpIfNeeded() {
        if (webView?.context?.configPrefs?.showToolBarOnScrollUp == true) {
            webBrowser.showActionBar()
        }
    }

    fun showToolBarOnPageTopIfNeeded() {
        if (webView?.context?.configPrefs?.showToolBarOnPageTop == true) {
            webBrowser.showActionBar()
        }
    }

    fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        if (view!=webView) {
            Timber.w("onRenderProcessGone: Not our WebView")
            return true
        }

        latentTabInitializer = FreezableBundleInitializer(getModel())
        val vg = webView?.removeFromParent()
        destroyWebView()

        vg?.let {
            iSnackbar = activity.makeSnackbar(
                activity.getString(R.string.message_render_process_crashed),
                5000, if (activity.configPrefs.toolbarsBottom) Gravity.TOP else Gravity.BOTTOM)
                .setIcon(R.drawable.ic_warn)

            iSnackbar?.show()

            (activity as? WebBrowserActivity)?.apply {
                tabsManager.tabChanged(tabsManager.indexOfTab(this@WebPageTab),false,false)
            }
        }

        webBrowser.onTabChanged(this)
        return true
    }

    private open inner class TouchListenerLollipop : OnTouchListener {
        internal var location: Float = 0f
        protected var touchingScreen: Boolean = false
        internal var y: Float = 0f
        internal var action: Int = 0

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View?, arg1: MotionEvent): Boolean {
            if (view == null) return false

            if (!view.hasFocus()) {
                view.requestFocus()
            }

            action = arg1.action
            y = arg1.y
            if (action == MotionEvent.ACTION_DOWN) {
                location = y
                touchingScreen=true
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                val distance = y - location
                touchingScreen=false
                if (view.scrollY < SCROLL_DOWN_THRESHOLD
                        && view.canScrollVertically()) {
                    showToolBarOnPageTopIfNeeded()
                } else if (distance < -SCROLL_UP_THRESHOLD) {
                    webBrowser.hideActionBar()
                }
                location = 0f
            }

            gestureDetector.onTouchEvent(arg1)
            return false
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private inner class TouchListener: TouchListenerLollipop(), OnScrollChangeListener {
        override fun onScrollChange(view: View?, scrollX: Int, scrollY: Int, oldScrollX: Int, oldScrollY: Int) {
            view?.apply {
                if (canScrollVertically()) {
                    if (scrollY < SCROLL_DOWN_THRESHOLD && !touchingScreen) {
                        showToolBarOnPageTopIfNeeded()
                    }
                }
            }
        }
    }

    private inner class CustomGestureListener(private val view: View) : SimpleOnGestureListener() {
        private var canTriggerLongPress = true

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (e1==null) {
                return false
            }

            val power = (velocityY * 100 / maxFling).toInt()
            if (power < -10) {
                webBrowser.hideActionBar()
            } else if (power > 15 && view.canScrollVertically()) {
                showToolBarOnScrollUpIfNeeded()
            }
            return super.onFling(e1, e2, velocityX, velocityY)
        }

        override fun onLongPress(e: MotionEvent) {
            if (canTriggerLongPress) {
                val msg = webViewHandler.obtainMessage()
                if (msg != null) {
                    msg.target = webViewHandler
                    webView?.requestFocusNodeHref(msg)
                    webView?.cancelLongPress()
                }
            }
        }

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            canTriggerLongPress = false
            return false
        }

        override fun onShowPress(e: MotionEvent) {
            canTriggerLongPress = true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            webBrowser.onSingleTapUp(this@WebPageTab)
            return false
        }
    }

    private class WebViewHandler(view: WebPageTab) : Handler() {
        private val reference: WeakReference<WebPageTab> = WeakReference(view)

        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            val url = msg.data.getString("url")
            val title = msg.data.getString("title")
            val src = msg.data.getString("src")
            reference.get()?.longClickPage(url,title,src)
        }
    }

    companion object {
        public const val KHtmlMetaThemeColorInvalid: Int = Color.TRANSPARENT

        const val HEADER_REQUESTED_WITH = "X-Requested-With"
        const val HEADER_WAP_PROFILE = "X-Wap-Profile"
        private const val HEADER_DNT = "DNT"
        private const val HEADER_SAVEDATA = "Save-Data"

        private val API = Build.VERSION.SDK_INT
        private val SCROLL_UP_THRESHOLD = fulguris.utils.Utils.dpToPx(10f)
        private val SCROLL_DOWN_THRESHOLD = fulguris.utils.Utils.dpToPx(30f)

        private val negativeColorArray = floatArrayOf(
            -1.0f, 0f, 0f, 0f, 255f, // red
            0f, -1.0f, 0f, 0f, 255f, // green
            0f, 0f, -1.0f, 0f, 255f, // blue
            0f, 0f, 0f, 1.0f, 0f // alpha
        )
        private val increaseContrastColorArray = floatArrayOf(
            2.0f, 0f, 0f, 0f, -160f, // red
            0f, 2.0f, 0f, 0f, -160f, // green
            0f, 0f, 2.0f, 0f, -160f, // blue
            0f, 0f, 0f, 1.0f, 0f // alpha
        )
    }
}
