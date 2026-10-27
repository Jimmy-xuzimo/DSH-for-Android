package com.dshmobile.shell

import android.os.Build
import android.webkit.WebView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import org.json.JSONObject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class NativePreferences(val theme: String = "system", val text: Int = 14, val code: Int = 11, val terminal: Int = 13, val locale: String = "", val textFamily: String = "", val codeFamily: String = "", val terminalFamily: String = "", val palette: String = "dynamic", val font: String = "system", val animations: Boolean = true)

data class NativeSession(val id: String, val title: String)
data class NativePanel(val id: String, val title: String)
data class NativeMenu(val title: String, val choices: List<Pair<String, () -> Unit>>)
data class NativePrompt(val title: String, val message: String, val confirm: String? = null, val accept: (() -> Unit)? = null)
data class NativeProjectForm(val clone: Boolean = false)

private enum class Destination(val cn: String, val en: String, val icon: ImageVector) {
    Chat("对话", "Chats", Icons.AutoMirrored.Filled.List),
    Projects("工作区", "Projects", Icons.Default.Home),
    Tools("工具", "Tools", Icons.Default.Build),
    Settings("设置", "Settings", Icons.Default.Settings)
}

/** Google Material 3 owns navigation, sheets, forms, preferences and motion. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DshMaterialShell(
    web: WebView, engine: EngineState, preferences: NativePreferences,
    sessions: List<NativeSession>, panels: List<NativePanel>, projects: List<String>, currentProject: String,
    menu: NativeMenu?, prompt: NativePrompt?, form: NativeProjectForm?,
    onAction: (String) -> Unit, onSession: (String) -> Unit, onProject: (String) -> Unit,
    onPreference: (JSONObject) -> Unit, onCommand: (JSONObject) -> Unit,
    onDismissMenu: () -> Unit, onDismissPrompt: () -> Unit, onDismissForm: () -> Unit,
    preferenceRequest: Int = 0, commandsAllowed: Boolean = false, preferencesReady: Boolean = false,
    settingsSnapshot: JSONObject = JSONObject(), onLink: (String) -> Unit = {},
    onSubmitProject: (String, String?) -> Unit
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val zh = if (preferences.locale.isNotBlank()) preferences.locale.startsWith("zh") else configuration.locales[0].language == "zh"
    fun label(cn: String, en: String) = if (zh) cn else en
    val dark = when (preferences.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val colors = if (preferences.palette == "dynamic" && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else appColorScheme(preferences.palette, dark)
    var destination by rememberSaveable { mutableStateOf(Destination.Chat) }
    androidx.activity.compose.BackHandler(enabled = destination != Destination.Chat) { destination = Destination.Chat }
    LaunchedEffect(preferenceRequest) { if (preferenceRequest > 0) destination = Destination.Settings }
    var history by rememberSaveable { mutableStateOf(false) }
    var sidebar by rememberSaveable { mutableStateOf(true) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(history) { drawerScope.launch { drawerState.close();history=false } }
    LaunchedEffect(destination, preferencesReady, settingsSnapshot.optBoolean("ready")) {
        if (destination != Destination.Settings || preferencesReady) onCommand(JSONObject().put("action", if (destination == Destination.Settings) "settings" else "settingsClose"))
    }
    val family = when (preferences.font) { "noto" -> FontFamily(Font(R.font.noto_sans_sc)); "mono" -> FontFamily.Monospace; else -> FontFamily.Default }
    val baseTypography = Typography()
    val typography = baseTypography.copy(displayLarge=baseTypography.displayLarge.copy(fontFamily=family), displayMedium=baseTypography.displayMedium.copy(fontFamily=family), displaySmall=baseTypography.displaySmall.copy(fontFamily=family), headlineLarge=baseTypography.headlineLarge.copy(fontFamily=family), headlineMedium=baseTypography.headlineMedium.copy(fontFamily=family), headlineSmall=baseTypography.headlineSmall.copy(fontFamily=family), titleLarge=baseTypography.titleLarge.copy(fontFamily=family), titleMedium=baseTypography.titleMedium.copy(fontFamily=family), titleSmall=baseTypography.titleSmall.copy(fontFamily=family), bodyLarge=baseTypography.bodyLarge.copy(fontFamily=family), bodyMedium=baseTypography.bodyMedium.copy(fontFamily=family), bodySmall=baseTypography.bodySmall.copy(fontFamily=family), labelLarge=baseTypography.labelLarge.copy(fontFamily=family), labelMedium=baseTypography.labelMedium.copy(fontFamily=family), labelSmall=baseTypography.labelSmall.copy(fontFamily=family))
    MaterialTheme(colorScheme = colors, typography = typography) {
        Surface(Modifier.fillMaxSize()) {
            val adaptiveInfo = currentWindowAdaptiveInfo()
            val calculatedNavigation = NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(adaptiveInfo)
            val navigationType = if (WindowInsets.isImeVisible && calculatedNavigation == NavigationSuiteType.NavigationBar) NavigationSuiteType.None else calculatedNavigation
            NavigationSuiteScaffold(
                modifier = Modifier.fillMaxSize().safeDrawingPadding().imePadding().testTag("dsh-material-shell"),
                layoutType = navigationType,
                navigationSuiteItems = {
                    Destination.entries.forEach { entry ->
                        item(modifier = Modifier.testTag("nav-${entry.name.lowercase()}"), selected = destination == entry, onClick = {
                            destination = entry
                            if (entry == Destination.Chat) onCommand(JSONObject().put("action", "conversation"))
                        }, icon = { Icon(entry.icon, null) }, label = { Text(label(entry.cn, entry.en)) })
                    }
                }
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val permanentSidebar = maxWidth >= 760.dp && maxHeight >= 480.dp
                    fun closeConversations(after: () -> Unit = {}) {
                        if(!permanentSidebar && history) drawerScope.launch { drawerState.close();history=false;after() }
                        else after()
                    }
                    val conversations: @Composable () -> Unit = {
                        Column(Modifier.fillMaxSize().testTag("conversation-sidebar")) {
                            Row(Modifier.fillMaxWidth().padding(start=16.dp, end=8.dp), verticalAlignment=Alignment.CenterVertically) {
                                Text(label("对话", "Conversations"), Modifier.weight(1f), style=MaterialTheme.typography.titleLarge)
                                IconButton(onClick={ if(permanentSidebar) sidebar=false else closeConversations() }, modifier=Modifier.testTag("hide-conversation-sidebar")) { Icon(Icons.Default.Close, label("收起侧栏", "Hide sidebar")) }
                            }
                            FilledTonalButton(onClick={ closeConversations { onCommand(JSONObject().put("action","new")) } }, modifier=Modifier.padding(16.dp).fillMaxWidth().testTag("new-conversation")) { Icon(Icons.Default.Add,null); Spacer(Modifier.width(8.dp)); Text(label("新建对话","New chat")) }
                            LazyColumn(Modifier.weight(1f)) { items(sessions,key={it.id}) { session -> NavigationDrawerItem(label={Text(session.title.ifBlank{label("新对话","New conversation")},maxLines=2)}, selected=false, onClick={ closeConversations { onSession(session.id) } }, modifier=Modifier.padding(horizontal=8.dp, vertical=2.dp)) } }
                            TextButton(onClick={ closeConversations { onCommand(JSONObject().put("action","manageSessions")) } }, modifier=Modifier.padding(8.dp)) { Text(label("管理会话","Manage conversations")) }
                        }
                    }
                    Row(Modifier.fillMaxSize()) {
                    AnimatedVisibility(visible=destination==Destination.Chat && permanentSidebar && sidebar,
                        enter=expandHorizontally(tween(300,easing=FastOutSlowInEasing))+fadeIn(tween(180)), exit=shrinkHorizontally(tween(240,easing=FastOutSlowInEasing))+fadeOut(tween(120))) {
                        Surface(Modifier.width(280.dp).fillMaxHeight(),color=MaterialTheme.colorScheme.surfaceContainerLow) { conversations() }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                    if(destination==Destination.Chat) TopAppBar(title={Text("DSH",style=MaterialTheme.typography.titleLarge)},navigationIcon={
                        IconButton(onClick={if(permanentSidebar) sidebar=!sidebar else history=!history},modifier=Modifier.testTag("toggle-conversation-sidebar")) { Icon(Icons.Default.Menu,label("展开或收起会话列表","Toggle conversations")) }
                    },actions={ IconButton(enabled=preferencesReady,onClick={onCommand(JSONObject().put("action","new"))},modifier=Modifier.testTag("new-conversation-top")){Icon(Icons.Default.Add,label("新建对话","New chat"))} })
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                    // A single resident WebView preserves DSH editors, streams and sessions.
                    AndroidView(factory = { (web.parent as? android.view.ViewGroup)?.removeView(web); web },
                        modifier = Modifier.fillMaxSize(), update = {
                            // Keep the controller document active: DSH defers
                            // provider/plugin loading when the WebView is hidden.
                            // Opaque native surfaces cover it and own input/a11y.
                            it.importantForAccessibility = if (destination == Destination.Chat) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                            it.isFocusable = destination == Destination.Chat
                            it.isFocusableInTouchMode = destination == Destination.Chat
                        })
                    AnimatedContent(targetState = destination, transitionSpec={ (fadeIn(tween(220,easing=FastOutSlowInEasing))+slideInVertically(tween(300,easing=FastOutSlowInEasing)){it/24}) togetherWith fadeOut(tween(120)) }, label = "Material destination") { page ->
                    if (page != Destination.Chat) Surface(Modifier.fillMaxSize()) {
                        Scaffold(topBar = { if (page != Destination.Settings) TopAppBar(title = { Text(label(page.cn, page.en)) }) }) { padding ->
                            if (page == Destination.Settings) MaterialSettings(Modifier.padding(padding), preferences, preferencesReady, settingsSnapshot, zh, onPreference, onCommand, onLink, { onAction("about") })
                            else
                            LazyColumn(Modifier.padding(padding).widthIn(max = 840.dp).fillMaxSize().align(Alignment.TopCenter).testTag("native-preferences-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                when (page) {
                                    Destination.Projects -> {
                                        item { Text(label("本机项目", "Local projects"), style = MaterialTheme.typography.titleMedium) }
                                        items(projects, key = { it }) { project ->
                                            ListItem(headlineContent = { Text(project) }, supportingContent = { if (project == currentProject) Text(label("当前工作区", "Current workspace")) },
                                                trailingContent = { if (project == currentProject) Icon(Icons.Default.Check, null) }, modifier = Modifier.clickable { onProject(project); destination = Destination.Chat })
                                        }
                                        item { FilledTonalButton(onClick = { onAction("newProject") }, modifier = Modifier.testTag("new-project")) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text(label("新建项目", "New project")) } }
                                        item { OutlinedButton(onClick = { onAction("cloneProject") }) { Text(label("克隆 Git 仓库", "Clone Git repository")) } }
                                        item { OutlinedButton(onClick = { onAction("importProject") }) { Text(label("导入 ZIP", "Import ZIP")) } }
                                        item { TextButton(onClick = { onAction("exportProject") }) { Text(label("导出当前项目", "Export current project")) } }
                                    }
                                    Destination.Tools -> {
                                        item { ListItem(headlineContent = { Text(label("文件与终端", "Files and terminal")) }, supportingContent = { Text(label("浏览工作区文件，运行交互终端", "Browse workspace files and open a terminal")) }, modifier = Modifier.clickable { destination = Destination.Chat; onCommand(JSONObject().put("action", "tools")) }) }
                                        items(panels, key = { it.id }) { panel ->
                                            ListItem(headlineContent = { Text(panel.title) }, modifier = Modifier.clickable { destination = Destination.Chat; onCommand(JSONObject().put("action", "panel").put("id", panel.id)) })
                                        }
                                        item { HorizontalDivider() }
                                        item { ListItem(headlineContent = { Text(label("命令执行权限", "Command permission")) }, supportingContent = { Text(label("由你决定是否允许执行命令", "Choose whether commands can run")) }, trailingContent = { Switch(checked = commandsAllowed, onCheckedChange = { onAction("commands") }) }, modifier = Modifier.clickable { onAction("commands") }) }
                                        item { ListItem(headlineContent = { Text(label("引擎与诊断", "Engine and diagnostics")) }, modifier = Modifier.clickable { onAction("engine") }) }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                    }
                    if (destination == Destination.Chat && engine.phase != "running") Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Text("DSH", style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(24.dp))
                            if (engine.phase == "starting") CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(engine.message ?: label(if (engine.phase == "starting") "正在启动本机引擎…" else "引擎已停止", if (engine.phase == "starting") "Starting the on-device engine…" else "Engine stopped"))
                            if (engine.phase != "starting") Button(onClick = { onAction("start") }) { Text(label("启动引擎", "Start engine")) }
                        }
                    }
                    }
                    }
                    }
                    if(history && !permanentSidebar && destination==Destination.Chat) {
                        LaunchedEffect(Unit) { drawerState.open(); snapshotFlow { drawerState.currentValue }.first { it == DrawerValue.Closed }; history=false }
                        ModalNavigationDrawer(modifier=Modifier.fillMaxSize(),drawerState=drawerState,gesturesEnabled=true,drawerContent={ ModalDrawerSheet(Modifier.widthIn(max=320.dp).fillMaxHeight()) { conversations() } }) { }
                    }
                }
            }
            if (menu != null) ModalBottomSheet(onDismissRequest = onDismissMenu) {
                Text(menu.title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.headlineSmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(menu.choices) { (title, action) -> ListItem(headlineContent = { Text(title) }, modifier = Modifier.clickable { onDismissMenu(); action() }) }
                }
            }
            if (prompt != null) AlertDialog(onDismissRequest = onDismissPrompt, title = { Text(prompt.title) }, text = {
                LazyColumn(Modifier.heightIn(max = 400.dp)) { item { SelectionContainer { Text(prompt.message) } } }
            }, confirmButton = { TextButton(onClick = { onDismissPrompt(); prompt.accept?.invoke() }) { Text(prompt.confirm ?: label("确定", "OK")) } },
                dismissButton = { if (prompt.accept != null) TextButton(onClick = onDismissPrompt) { Text(label("取消", "Cancel")) } })
            if (form != null) {
                var name by rememberSaveable(form.clone) { mutableStateOf("") }
                var address by rememberSaveable(form.clone) { mutableStateOf("") }
                AlertDialog(onDismissRequest = onDismissForm, title = { Text(if (form.clone) label("克隆 Git 仓库", "Clone Git repository") else label("新建项目", "New project")) }, text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (form.clone) OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text(label("HTTPS 仓库地址", "HTTPS repository URL")) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label("项目名称", "Project name")) }, singleLine = true, modifier = Modifier.testTag("project-name-input"))
                    }
                }, confirmButton = { TextButton(enabled = name.isNotBlank() && (!form.clone || address.startsWith("https://")), onClick = { onSubmitProject(name.trim(), address.takeIf { form.clone }?.trim()); onDismissForm() }) { Text(label("创建", "Create")) } }, dismissButton = { TextButton(onClick = onDismissForm) { Text(label("取消", "Cancel")) } })
            }
        }
    }
}
