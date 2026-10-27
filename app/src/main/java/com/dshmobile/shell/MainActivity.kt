package com.dshmobile.shell

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.compose.ui.graphics.toArgb
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var engineUi by mutableStateOf(EngineState())
    private var preferencesUi by mutableStateOf(NativePreferences())
    private var sessionsUi by mutableStateOf(emptyList<NativeSession>())
    private var panelsUi by mutableStateOf(emptyList<NativePanel>())
    private var projectsUi by mutableStateOf(emptyList<String>())
    private var menuUi by mutableStateOf<NativeMenu?>(null)
    private var promptUi by mutableStateOf<NativePrompt?>(null)
    private var formUi by mutableStateOf<NativeProjectForm?>(null)
    private var nativePreferencesRequest by mutableStateOf(0)
    private var commandsUi by mutableStateOf(false)
    internal var settingsUi by mutableStateOf(JSONObject())
        private set
    internal var preferencesReady by mutableStateOf(false)
        private set
    private var navigationReady = false
    private var settingsDocumentScript: androidx.webkit.ScriptHandler? = null
    private val pendingCommands = ArrayDeque<JSONObject>()
    private var keyboardVisible = false
    private val io = Executors.newSingleThreadExecutor()
    private var loadedOrigin: String? = null
    private var clearEngineHistory = true
    private var resumePath = "/"
    private var resetRoutePending = false
    private var upload: ValueCallback<Array<Uri>>? = null
    private data class Download(val id: String, val file: File, val name: String, var size: Long = 0, var ready: Boolean = false)
    private var download: Download? = null
    private val saveDownload = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val item = download
        download = null
        if (item != null) background {
            try {
                if (uri != null && item.ready) contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output); item.file.inputStream().use { it.copyTo(output) }
                }
            } finally { item.file.delete() }
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        upload?.onReceiveValue(uris.takeIf { it.isNotEmpty() }?.toTypedArray()); upload = null
    }
    private val importProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) background { WorkspaceStore(this).importZip(uri); restart(true) }
    }
    private var exporting: File? = null
    private val exportProject = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val project = exporting
        exporting = null
        if (uri != null && project != null) background { WorkspaceStore(this).exportZip(project, uri) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        resumePath = savedInstanceState?.getString("webPath")?.takeIf { it.startsWith('/') && !it.startsWith("//") } ?: "/"
        savedInstanceState?.getString("exportingProject")?.let { name ->
            exporting = WorkspaceStore(this).list().find { it.name == name }
        }
        savedInstanceState?.getString("downloadFile")?.takeIf { it.startsWith("dsh-download-") && !it.contains('/') }?.let { name ->
            val file = File(cacheDir, name)
            if (file.isFile) download = Download(requireNotNull(savedInstanceState.getString("downloadId")), file, savedInstanceState.getString("downloadName") ?: "download", file.length(), true)
        }
        enableEdgeToEdge()
        val appearance = getSharedPreferences("appearance", MODE_PRIVATE)
        preferencesUi = preferencesUi.copy(palette=appearance.getString("palette","dynamic")!!,font=appearance.getString("font","system")!!,animations=appearance.getBoolean("animations",true))
        web = WebView(this).apply {
            // WebView treats WRAP_CONTENT as an indefinite CSS viewport even
            // when Compose measures the AndroidView with an exact height.
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(75, 200)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    if (sameOrigin(request.url) && request.url.path == "/android-font/noto-sans-sc.ttf") {
                        return android.webkit.WebResourceResponse("font/ttf", null, bundledFont())
                    }
                    return null
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (sameOrigin(request.url)) return false
                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("http", "https")) {
                        openExternal(Intent(Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (loadedOrigin != null && sameOrigin(Uri.parse(url)) && sameOrigin(Uri.parse(view.url.orEmpty()))) {
                        view.evaluateJavascript(assets.open("settings-bridge.js").bufferedReader().use { it.readText() }, null)
                        if (clearEngineHistory) { view.clearHistory(); clearEngineHistory = false }
                        view.evaluateJavascript("document.documentElement.dataset.android='true';document.documentElement.dataset.androidIme='$keyboardVisible';window.dispatchEvent(new Event('resize'));", null)
                        applyAppearance()
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    if (!sameOrigin(Uri.parse(view.url ?: ""))) return false
                    upload?.onReceiveValue(null)
                    upload = callback
                    openFiles.launch(params.acceptTypes.filter { it.isNotBlank() }.toTypedArray().ifEmpty { arrayOf("*/*") })
                    return true
                }
            }
            setDownloadListener { url, _, disposition, mime, _ ->
                val origin = EngineService.state.value?.origin ?: return@setDownloadListener
                if (download != null) return@setDownloadListener
                val name = android.webkit.URLUtil.guessFileName(url, disposition, mime).substringAfterLast('/').take(128)
                val item = Download(java.util.UUID.randomUUID().toString(), File.createTempFile("dsh-download-", ".tmp", cacheDir), name)
                download = item
                if (url.startsWith("blob:$origin/") && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    val id = JSONObject.quote(item.id)
                    val address = JSONObject.quote(url)
                    evaluateJavascript("""
                        (async()=>{
                          try {
                            const response=await fetch($address),reader=response.body.getReader();let total=0;
                            while(true){const {value,done}=await reader.read();if(done)break;total+=value.length;
                              if(total>268435456)throw new Error('Download exceeds 256 MiB');
                              for(let offset=0;offset<value.length;offset+=49152){
                                const chunk=value.subarray(offset,offset+49152);
                                AndroidFiles.postMessage(JSON.stringify({id:$id,kind:'chunk',data:btoa(String.fromCharCode(...chunk))}));
                              }
                            }
                            AndroidFiles.postMessage(JSON.stringify({id:$id,kind:'end'}));
                          }catch(error){AndroidFiles.postMessage(JSON.stringify({id:$id,kind:'error',message:String(error)}));}
                        })()
                    """.trimIndent(), null)
                } else if (sameOrigin(Uri.parse(url))) {
                    val cookie = CookieManager.getInstance().getCookie(origin)
                    background {
                        try {
                            val connection = URL(url).openConnection() as java.net.HttpURLConnection
                            try {
                                connection.instanceFollowRedirects = false
                                connection.connectTimeout = 10000; connection.readTimeout = 30000
                                connection.setRequestProperty("Cookie", cookie)
                                connection.setRequestProperty("Origin", origin)
                                check(connection.responseCode in 200..299) { "Download failed (${connection.responseCode})" }
                                connection.inputStream.use { input -> item.file.outputStream().use { output ->
                                    val buffer = ByteArray(65536)
                                    while (true) { val count = input.read(buffer); if (count < 0) break; item.size += count; check(item.size <= 268435456); output.write(buffer, 0, count) }
                                } }
                            } finally { connection.disconnect() }
                            runOnUiThread {
                                if (isDestroyed || isFinishing) { item.file.delete(); download = null }
                                else { item.ready = true; saveDownload.launch(item.name) }
                            }
                        } catch (error: Exception) { item.file.delete(); runOnUiThread { download = null }; throw error }
                    }
                } else {
                    item.file.delete(); download = null
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        projectsUi = WorkspaceStore(this).list().map { it.name }
        commandsUi = getSharedPreferences("dsh", MODE_PRIVATE).getBoolean("commands", false)
        setContent {
            DshMaterialShell(web, engineUi, preferencesUi, sessionsUi, panelsUi, projectsUi, WorkspaceStore(this).current().name,
                menuUi, promptUi, formUi,
                onAction = ::nativeAction,
                onSession = { dispatchCommand(JSONObject().put("action", "session").put("id", it)) },
                onProject = { name -> WorkspaceStore(this).list().find { it.name == name }?.let { WorkspaceStore(this).select(it); restart(true) } },
                onPreference = ::updatePreference,
                onCommand = ::dispatchCommand,
                onDismissMenu = { menuUi = null }, onDismissPrompt = { promptUi = null }, onDismissForm = { formUi = null },
                preferenceRequest = nativePreferencesRequest, commandsAllowed = commandsUi, preferencesReady = preferencesReady,
                settingsSnapshot = settingsUi, onLink = { address -> Uri.parse(address).takeIf { it.scheme in setOf("https", "http") }?.let { openExternal(Intent(Intent.ACTION_VIEW, it)) } },
                onSubmitProject = { name, address -> background {
                    val store = WorkspaceStore(this)
                    if (address != null) store.cloneRepository(address, name) else store.select(store.create(name))
                    runOnUiThread { projectsUi = store.list().map { it.name } }; restart(true)
                } })
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("""
                    (()=>{if(document.documentElement.dataset.androidSheet){window.dispatchEvent(new Event('dsh-android-sheet-close'));return true;}
                    if(document.querySelector('[data-shortcut-modal="settings"]')){document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',bubbles:true}));return true;}
                    const right=document.querySelector('[data-sidebar-right-panel]:not([aria-hidden="true"]) [data-sidebar-right-toggle]');
                    if(right){right.click();return true;}return false;})()
                """.trimIndent()) { handled ->
                    if (handled != "true") { if (!clearEngineHistory && web.canGoBack()) web.goBack() else moveTaskToBack(true) }
                }
            }
        })
        EngineService.state.observe(this) { state ->
            engineUi = state
            if (state.phase != "running") {
                preferencesReady = false; navigationReady = false
                settingsUi = JSONObject()
                clearEngineHistory = true
                rememberLocation()
                loadedOrigin = null
                web.stopLoading()
                if (web.url != "about:blank") web.loadUrl("about:blank")
                CookieManager.getInstance().removeAllCookies(null)
            }
            if (state.phase == "running" && state.origin != loadedOrigin) {
                val origin = requireNotNull(state.origin)
                configureDownloads(origin)
                configureHostActions(origin)
                CookieManager.getInstance().setCookie(origin, "dsh_android=${state.token}; Path=/; HttpOnly; SameSite=Strict") {
                    runOnUiThread {
                        val current = EngineService.state.value
                        if (isDestroyed || isFinishing || current?.phase != "running" || current.origin != origin || current.token != state.token) return@runOnUiThread
                        loadedOrigin = origin
                        clearEngineHistory = true
                        resetRoutePending = false
                        val path = resumePath.substringBefore('#')
                        val fragment = resumePath.substringAfter('#', "").let { if (it.isEmpty()) "" else "#$it" }
                        web.loadUrl("$origin$path?token=${state.token}$fragment")
                    }
                }
            }
        }
        if (getSharedPreferences("dsh", MODE_PRIVATE).getBoolean("engineRequested", true) && EngineService.state.value?.phase == "stopped") startEngine()
    }

    private fun sameOrigin(uri: Uri): Boolean {
        val expected = EngineService.state.value?.origin?.let(Uri::parse) ?: return false
        return uri.scheme == expected.scheme && uri.host == expected.host && uri.port == expected.port && uri.userInfo == null
    }
    private fun configureDownloads(origin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.removeWebMessageListener(web, "AndroidFiles")
        WebViewCompat.addWebMessageListener(web, "AndroidFiles", setOf(origin)) { _, message, source, mainFrame, _ ->
            if (!mainFrame || !sameOrigin(source)) return@addWebMessageListener
            val json = try { JSONObject(message.data ?: return@addWebMessageListener) } catch (_: org.json.JSONException) { return@addWebMessageListener }
            val item = download ?: return@addWebMessageListener
            if (json.optString("id") != item.id) return@addWebMessageListener
            background {
                try {
                    when (json.optString("kind")) {
                        "chunk" -> {
                            val encoded = json.getString("data")
                            require(encoded.length <= 65536)
                            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                            item.size += bytes.size
                            require(item.size <= 268435456)
                            item.file.appendBytes(bytes)
                        }
                        "end" -> runOnUiThread { if (download === item) { item.ready = true; saveDownload.launch(item.name) } }
                        "error" -> throw java.io.IOException("Download failed")
                    }
                } catch (error: Exception) { item.file.delete(); runOnUiThread { download = null }; throw error }
            }
        }
    }
    private fun configureHostActions(origin: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        WebViewCompat.removeWebMessageListener(web, "AndroidHost")
        WebViewCompat.addWebMessageListener(web, "AndroidHost", setOf(origin)) { _, message, source, mainFrame, _ ->
            if (!mainFrame || !sameOrigin(source)) return@addWebMessageListener
            val payload = try { JSONObject(message.data ?: "") } catch (_: org.json.JSONException) { return@addWebMessageListener }
            when (payload.optString("action")) {
                "appearance" -> {
                    val dark = payload.optBoolean("dark", false)
                    web.setBackgroundColor(if (dark) 0xFF151517.toInt() else 0xFFFFFBFE.toInt())
                    WindowCompat.getInsetsController(window, web).apply {
                        isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
                    }
                }
                "workspace" -> openMenu("workspace")
                "engine" -> openMenu("engine")
                "commands" -> commandPermission()
                "openPreferences" -> nativePreferencesRequest++
                "settingsState" -> if (message.data.orEmpty().length <= 524288) settingsUi = payload
                "preferences" -> {
                    val families = payload.optJSONObject("fontFamilies")
                    preferencesUi = preferencesUi.copy(theme=payload.optString("theme", "system"),text=payload.optInt("text",14),code=payload.optInt("code",11),terminal=payload.optInt("terminal",13),locale=payload.optString("locale",""),textFamily=families?.optString("text").orEmpty(),codeFamily=families?.optString("code").orEmpty(),terminalFamily=families?.optString("terminal").orEmpty())
                    preferencesReady = true; flushCommands()
                    applyAppearance()
                }
                "navigationState" -> {
                    val sessions = payload.optJSONArray("sessions")
                    sessionsUi = (0 until minOf(sessions?.length() ?: 0, 5000)).mapNotNull { index -> sessions?.optJSONObject(index)?.let { NativeSession(it.optString("id"), it.optString("title")) } }
                    val panels = payload.optJSONArray("panels")
                    panelsUi = (0 until minOf(panels?.length() ?: 0, 100)).mapNotNull { index -> panels?.optJSONObject(index)?.let { NativePanel(it.optString("id"), it.optString("title")) } }
                    navigationReady = true; flushCommands()
                }
            }
        }
        settingsDocumentScript?.remove()
        settingsDocumentScript = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(web, assets.open("settings-bridge.js").bufferedReader().use { it.readText() }, setOf(origin))
        } else null
    }
    private fun startEngine() {
        val webViewVersion = WebEngineSupport.chromiumMajor(web.settings.userAgentString) ?: 0
        if (webViewVersion < 113) {
            promptUi = NativePrompt(getString(R.string.update), getString(R.string.update_webview), getString(R.string.update)) {
                val provider = WebViewCompat.getCurrentWebViewPackage(this)?.packageName
                openExternal(if (provider != null) Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$provider")) else Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS))
            }
            return
        }
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean("engineRequested", true).apply()
        if (EngineService.state.value?.phase in setOf("stopping", "error")) EngineService.restart(applicationContext)
        else startForegroundService(Intent(this, EngineService::class.java))
    }
    private fun openExternal(intent: Intent) {
        try { startActivity(intent) }
        catch (_: android.content.ActivityNotFoundException) {
            promptUi = NativePrompt(getString(R.string.error_title), getString(R.string.external_unavailable))
        } catch (_: SecurityException) {
            promptUi = NativePrompt(getString(R.string.error_title), getString(R.string.external_unavailable))
        }
    }
    private fun restart(resetRoute: Boolean = false) {
        runOnUiThread {
            if (resetRoute) { resumePath = "/"; resetRoutePending = true }
            EngineService.restart(applicationContext)
        }
    }
    private fun dispatchCommand(payload: JSONObject) {
        val ready = if (payload.optString("action") == "preference") preferencesReady else navigationReady
        if (!ready || loadedOrigin == null || !sameOrigin(Uri.parse(web.url ?: ""))) {
            if (pendingCommands.size >= 32) pendingCommands.removeFirst()
            pendingCommands.addLast(payload)
            return
        }
        web.evaluateJavascript("window.dispatchEvent(new CustomEvent('dsh-material-command',{detail:$payload}));", null)
    }
    private fun updatePreference(value: JSONObject) {
        val stored = getSharedPreferences("appearance",MODE_PRIVATE)
        when {
            value.has("palette") -> value.optString("palette").takeIf { it in appPalettes.map { p -> p.first } }?.let { preferencesUi=preferencesUi.copy(palette=it); stored.edit().putString("palette",it).apply(); applyAppearance() }
            value.has("font") -> value.optString("font").takeIf { it in setOf("system","noto","mono") }?.let { choice ->
                preferencesUi=preferencesUi.copy(font=choice); stored.edit().putString("font",choice).apply()
                val family=when(choice){"noto"->"'DSH Noto', sans-serif";"mono"->"monospace";else->""}
                listOf("text","code","terminal").forEach { role -> dispatchCommand(JSONObject().put("action","preference").put("value",JSONObject().put("role",role).put("fontFamily",family))) }
                applyAppearance()
            }
            value.has("animations") -> { preferencesUi=preferencesUi.copy(animations=value.optBoolean("animations")); stored.edit().putBoolean("animations",preferencesUi.animations).apply(); applyAppearance() }
            else -> dispatchCommand(JSONObject().put("action","preference").put("value",value))
        }
    }
    private fun applyAppearance() {
        if(loadedOrigin==null || !sameOrigin(Uri.parse(web.url.orEmpty()))) return
        val dark=when(preferencesUi.theme){"dark"->true;"light"->false;else->resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES}
        val colors=if(preferencesUi.palette=="dynamic" && Build.VERSION.SDK_INT>=31) { if(dark) androidx.compose.material3.dynamicDarkColorScheme(this) else androidx.compose.material3.dynamicLightColorScheme(this) } else appColorScheme(preferencesUi.palette,dark)
        fun css(color: androidx.compose.ui.graphics.Color) = "#%06x".format(color.toArgb() and 0xffffff)
        val payload=JSONObject().put("reduce",!preferencesUi.animations || !android.animation.ValueAnimator.areAnimatorsEnabled()).put("font",preferencesUi.font).put("primary",css(colors.primary)).put("onPrimary",css(colors.onPrimary)).put("surface",css(colors.surface)).put("onSurface",css(colors.onSurface)).put("container",css(colors.surfaceContainer)).put("outline",css(colors.outlineVariant))
        web.evaluateJavascript("""(()=>{const p=$payload,r=document.documentElement;r.dataset.androidReduceMotion=String(p.reduce);r.dataset.androidFont=p.font;let s=document.getElementById('android-appearance');if(!s){s=document.createElement('style');s.id='android-appearance';document.head.append(s)}s.textContent="@font-face{font-family:'DSH Noto';src:url('/android-font/noto-sans-sc.ttf') format('truetype');font-weight:100 900;font-display:swap} :root{--md-sys-color-primary:"+p.primary+";--md-sys-color-on-primary:"+p.onPrimary+";--md-sys-color-outline-variant:"+p.outline+";--dsw-alias-bg-base:"+p.surface+";--dsw-alias-text-primary:"+p.onSurface+";--md-sys-color-surface-container:"+p.container+";}";window.dispatchEvent(new Event('dsh-android-appearance'))})()""",null)
    }
    // Resources.openRawResource reads any uncompiled file resource, including
    // res/font TTFs. Lint's @RawRes annotation is narrower than this API behavior.
    @SuppressLint("ResourceType")
    private fun bundledFont(): java.io.InputStream = resources.openRawResource(R.font.noto_sans_sc)
    private fun flushCommands() {
        val commands = pendingCommands.toList()
        pendingCommands.clear()
        commands.forEach(::dispatchCommand)
    }
    private fun nativeAction(action: String) {
        when (action) {
            "start" -> startEngine()
            "stop" -> EngineService.stop(this)
            "restart" -> restart()
            "newProject" -> formUi = NativeProjectForm()
            "cloneProject" -> formUi = NativeProjectForm(clone = true)
            "importProject" -> importProject.launch(arrayOf("application/zip", "application/x-zip-compressed"))
            "exportProject" -> { exporting = WorkspaceStore(this).current(); exportProject.launch("${exporting!!.name}.zip") }
            "commands" -> commandPermission()
            "engine" -> openMenu("engine")
            "about" -> promptUi = NativePrompt("DSH for Android ${BuildConfig.VERSION_NAME}", getString(R.string.version_notice)+"\n\nDeepSeek Harness · 0.2.1-rc.3\nJetpack Compose · Material 3\nNoto Sans SC · SIL OFL 1.1\n\nhttps://jimmy-xuzimo.github.io/DSH-for-Android/", if(preferencesUi.locale.startsWith("zh")) "项目网站" else "Project website") { openExternal(Intent(Intent.ACTION_VIEW,Uri.parse("https://jimmy-xuzimo.github.io/DSH-for-Android/"))) }
            "diagnostics" -> background {
                val file = File(cacheDir, "engine.log")
                val text = if (file.isFile) file.readText().takeLast(32768) else getString(R.string.engine_stopped)
                runOnUiThread { promptUi = NativePrompt(getString(R.string.diagnostics), text) }
            }
        }
    }
    private fun openMenu(section: String) {
        val entries = if (section == "workspace") listOf(
            getString(R.string.new_project) to "newProject", getString(R.string.clone_project) to "cloneProject",
            getString(R.string.import_project) to "importProject", getString(R.string.export_project) to "exportProject"
        ) else listOf(getString(R.string.start) to "start", getString(R.string.stop) to "stop", getString(R.string.restart) to "restart", getString(R.string.diagnostics) to "diagnostics")
        menuUi = NativeMenu(if (section == "workspace") getString(R.string.workspaces) else "DSH", entries.map { (name, action) -> name to { nativeAction(action) } })
    }
    private fun commandPermission() {
        val preferences = getSharedPreferences("dsh", MODE_PRIVATE)
        val enabled = preferences.getBoolean("commands", false)
        promptUi = NativePrompt(getString(R.string.commands), getString(R.string.command_warning), getString(if (enabled) R.string.disable else R.string.enable)) {
            preferences.edit().putBoolean("commands", !enabled).apply(); commandsUi = !enabled; restart()
        }
    }
    private fun background(action: () -> Unit) {
        io.submit {
            try { action() }
            catch (error: Exception) { runOnUiThread {
                if (!isFinishing && !isDestroyed) promptUi = NativePrompt(getString(R.string.error_title), error.message ?: "Unknown error")
            } }
        }
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun rememberLocation() {
        if (resetRoutePending) return
        val origin = loadedOrigin?.let(Uri::parse) ?: return
        val uri = web.url?.let(Uri::parse) ?: return
        if (uri.scheme == origin.scheme && uri.host == origin.host && uri.port == origin.port) {
            resumePath = (uri.encodedPath ?: "/") + (uri.encodedFragment?.let { "#$it" } ?: "")
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        rememberLocation()
        outState.putString("webPath", resumePath)
        exporting?.let { outState.putString("exportingProject", it.name) }
        download?.takeIf { it.ready }?.let {
            outState.putString("downloadId", it.id); outState.putString("downloadFile", it.file.name); outState.putString("downloadName", it.name)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        web.settings.textZoom = (newConfig.fontScale * 100).toInt().coerceIn(75, 200)
        applyAppearance()
    }
    override fun onDestroy() {
        upload?.onReceiveValue(null); upload = null
        web.stopLoading(); web.destroy(); io.shutdown()
        super.onDestroy()
    }
}
