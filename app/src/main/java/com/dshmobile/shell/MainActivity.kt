package com.dshmobile.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
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
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var menuButton: Button
    private val io = Executors.newSingleThreadExecutor()
    private var loadedOrigin: String? = null
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
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(layout) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, keyboard.bottom))
            insets
        }
        val toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        menuButton = Button(this).apply {
            text = getString(R.string.workspaces)
            minHeight = dp(48)
            contentDescription = getString(R.string.workspaces)
            setOnClickListener { openMenu(this) }
        }
        status = TextView(this).apply { textSize = 14f; setPadding(dp(8), 0, dp(8), 0) }
        toolbar.addView(menuButton)
        toolbar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        layout.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        layout.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        web = WebView(this).apply {
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
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (sameOrigin(request.url)) return false
                    if (request.isForMainFrame && request.hasGesture() && request.url.scheme in setOf("http", "https")) {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    }
                    return true
                }
                override fun onPageFinished(view: WebView, url: String) {
                    if (sameOrigin(Uri.parse(url))) {
                        view.evaluateJavascript("document.documentElement.dataset.android='true';window.dispatchEvent(new Event('resize'));", null)
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
        layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("""
                    (()=>{const drawer=document.querySelector('[data-android-drawer-backdrop]');
                    if(drawer){drawer.click();return true;}return false;})()
                """.trimIndent()) { handled ->
                    if (handled != "true") { if (web.canGoBack()) web.goBack() else moveTaskToBack(true) }
                }
            }
        })
        EngineService.state.observe(this) { state ->
            progress.visibility = if (state.phase == "starting") View.VISIBLE else View.GONE
            status.text = when (state.phase) {
                "starting" -> getString(R.string.preparing)
                "running" -> WorkspaceStore(this).current().name
                "error" -> state.message
                else -> getString(R.string.engine_stopped)
            }
            if (state.phase != "running") {
                rememberLocation()
                loadedOrigin = null
                web.stopLoading()
                if (web.url != "about:blank") web.loadUrl("about:blank")
                CookieManager.getInstance().removeAllCookies(null)
            }
            if (state.phase == "running" && state.origin != loadedOrigin) {
                val origin = requireNotNull(state.origin)
                configureDownloads(origin)
                CookieManager.getInstance().setCookie(origin, "dsh_android=${state.token}; Path=/; HttpOnly; SameSite=Strict") {
                    runOnUiThread {
                        val current = EngineService.state.value
                        if (isDestroyed || isFinishing || current?.phase != "running" || current.origin != origin || current.token != state.token) return@runOnUiThread
                        loadedOrigin = origin
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
    private fun startEngine() {
        val webViewVersion = WebViewCompat.getCurrentWebViewPackage(this)?.versionName?.substringBefore('.')?.toIntOrNull() ?: 0
        if (webViewVersion < 113) {
            AlertDialog.Builder(this).setMessage(R.string.update_webview)
                .setPositiveButton(R.string.update) { _, _ ->
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.webview")))
                }.setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean("engineRequested", true).apply()
        if (EngineService.state.value?.phase in setOf("stopping", "error")) EngineService.restart(applicationContext)
        else startForegroundService(Intent(this, EngineService::class.java))
    }
    private fun restart(resetRoute: Boolean = false) {
        runOnUiThread {
            if (resetRoute) { resumePath = "/"; resetRoutePending = true }
            EngineService.restart(applicationContext)
        }
    }
    private fun openMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.start)).setOnMenuItemClickListener { startEngine(); true }
            menu.add(getString(R.string.stop)).setOnMenuItemClickListener { EngineService.stop(this@MainActivity); true }
            menu.add(getString(R.string.restart)).setOnMenuItemClickListener { restart(); true }
            menu.add(getString(R.string.new_project)).setOnMenuItemClickListener {
                val field = EditText(this@MainActivity).apply { hint = getString(R.string.project_name); setSingleLine() }
                AlertDialog.Builder(this@MainActivity).setTitle(R.string.new_project).setView(field)
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.done) { _, _ ->
                        background { val store = WorkspaceStore(this@MainActivity); store.select(store.create(field.text.toString().trim())); restart(true) }
                    }.show()
                true
            }
            WorkspaceStore(this@MainActivity).list().forEach { project ->
                menu.add("↳ ${project.name}").setOnMenuItemClickListener {
                    WorkspaceStore(this@MainActivity).select(project); restart(true); true
                }
            }
            menu.add(getString(R.string.clone_project)).setOnMenuItemClickListener {
                val fields = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
                val address = EditText(this@MainActivity).apply { hint = getString(R.string.repository_url); setSingleLine(); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
                val name = EditText(this@MainActivity).apply { hint = getString(R.string.project_name); setSingleLine() }
                fields.addView(address); fields.addView(name)
                AlertDialog.Builder(this@MainActivity).setTitle(R.string.clone_project).setView(fields)
                    .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.done) { _, _ ->
                        background { WorkspaceStore(this@MainActivity).cloneRepository(address.text.toString().trim(), name.text.toString().trim()); restart(true) }
                    }.show()
                true
            }
            menu.add(getString(R.string.import_project)).setOnMenuItemClickListener { importProject.launch(arrayOf("application/zip", "application/x-zip-compressed")); true }
            menu.add(getString(R.string.export_project)).setOnMenuItemClickListener {
                exporting = WorkspaceStore(this@MainActivity).current(); exportProject.launch("${exporting!!.name}.zip"); true
            }
            menu.add(getString(R.string.commands)).setOnMenuItemClickListener { commandPermission(); true }
            menu.add(getString(R.string.diagnostics)).setOnMenuItemClickListener {
                background {
                    val log = File(cacheDir, "engine.log")
                    val text = if (log.isFile) log.readText().takeLast(32768) else getString(R.string.engine_stopped)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val content = TextView(this@MainActivity).apply { setText(text); setTextIsSelectable(true); textSize = 12f; setPadding(dp(16), dp(12), dp(16), dp(12)) }
                        val scroll = android.widget.ScrollView(this@MainActivity).apply { addView(content) }
                        AlertDialog.Builder(this@MainActivity).setTitle(R.string.diagnostics).setView(scroll).setPositiveButton(android.R.string.ok, null).show()
                    }
                }
                true
            }
            menu.add(getString(R.string.about)).setOnMenuItemClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("DSH for Android ${BuildConfig.VERSION_NAME}")
                    .setMessage(R.string.version_notice).setPositiveButton(android.R.string.ok, null).show()
                true
            }
            show()
        }
    }
    private fun commandPermission() {
        val preferences = getSharedPreferences("dsh", MODE_PRIVATE)
        val enabled = preferences.getBoolean("commands", false)
        AlertDialog.Builder(this).setTitle(R.string.commands).setMessage(R.string.command_warning)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(if (enabled) R.string.disable else R.string.enable) { _, _ ->
                preferences.edit().putBoolean("commands", !enabled).apply(); restart()
            }.show()
    }
    private fun background(action: () -> Unit) {
        io.submit {
            try { action() }
            catch (error: Exception) { runOnUiThread {
                if (!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle(R.string.error_title).setMessage(error.message).setPositiveButton(android.R.string.ok, null).show()
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
    }
    override fun onDestroy() {
        upload?.onReceiveValue(null); upload = null
        web.stopLoading(); web.destroy(); io.shutdown()
        super.onDestroy()
    }
}
