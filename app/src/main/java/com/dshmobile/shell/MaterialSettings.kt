package com.dshmobile.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

/** All visible settings controls are real Compose Material 3 widgets.
 * DSH remains the sole owner of validation, credentials and persistence.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialSettings(
    modifier: Modifier = Modifier, preferences: NativePreferences, ready: Boolean,
    snapshot: JSONObject, zh: Boolean, onPreference: (JSONObject) -> Unit,
    onCommand: (JSONObject) -> Unit, onLink: (String) -> Unit, onAbout: () -> Unit
) {
    fun text(cn: String, en: String) = if (zh) cn else en
    var selection by rememberSaveable { mutableStateOf("appearance") }
    var overview by rememberSaveable { mutableStateOf(true) }
    var pendingSelection by remember { mutableStateOf<String?>(null) }
    val sections = snapshot.optJSONArray("sections").objects().sortedBy { listOf("models","general","agent","plugins").indexOf(it.optString("key")).takeIf { rank -> rank>=0 } ?: 99 }
    val acceptedSelection = sections.firstOrNull { it.optBoolean("selected") }?.optString("key")
    LaunchedEffect(snapshot.optBoolean("ready"), acceptedSelection) {
        if (!snapshot.optBoolean("ready")) pendingSelection = null
        else if (selection != "appearance" && !overview && acceptedSelection != null && (pendingSelection == null || pendingSelection == acceptedSelection)) {
            selection = acceptedSelection; pendingSelection = null
        }
    }
    val selectionLabel = sections.firstOrNull { it.optString("key") == selection }?.optString("label") ?: selection
    fun select(section: JSONObject) {
        selection = section.optString("key"); overview = false
        pendingSelection = selection.takeUnless { it == acceptedSelection }
        onCommand(JSONObject().put("action", "settingsControl").put("id", section.optString("id")).put("operation", "click"))
    }
    BackHandler(!overview) { overview = true }
    BoxWithConstraints(modifier.fillMaxSize().testTag("material-settings")) {
        val wide = maxWidth >= 760.dp && maxHeight >= 480.dp
        val categories: @Composable () -> Unit = {
            LazyColumn(Modifier.fillMaxSize().testTag("settings-categories"), contentPadding = PaddingValues(vertical = 8.dp)) {
                item { ListItem(headlineContent = { Text(text("外观与交互", "Appearance and interaction")) }, supportingContent = { Text(text("配色、统一字体、文字大小和动画", "Colors, shared font, text sizes and motion")) },
                    trailingContent = { if (selection == "appearance" && !overview) Icon(Icons.Default.Check, null) }, modifier = Modifier.testTag("settings-appearance").clickable { selection = "appearance"; overview = false }) }
                items(sections, key = { it.optString("id") }) { section ->
                    ListItem(headlineContent = { Text(section.optString("label")) },
                        supportingContent = { Text(when(section.optString("key")){"models"->text("提供商、API 地址与在线模型目录","Providers, API endpoints and live models");"general"->text("语言、工作步骤与会话行为","Language, work steps and conversation behavior");"plugins"->text("扩展能力与兼容性","Extensions and compatibility");else->text("DSH 功能设置","DSH feature settings")}) },
                        trailingContent = { if (selection == section.optString("key") && !overview) Icon(Icons.Default.Check, null) },
                        modifier = Modifier.testTag("settings-section-${section.optString("label")}").clickable { select(section) })
                }
                if (!snapshot.optBoolean("ready")) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp)) }
                item { HorizontalDivider(Modifier.padding(horizontal = 16.dp)) }
                item { ListItem(headlineContent = { Text(text("关于 DSH for Android", "About DSH for Android")) }, modifier = Modifier.clickable(onClick = onAbout)) }
                item { TextButton(onClick={onLink("https://jimmy-xuzimo.github.io/DSH-for-Android/")},modifier=Modifier.padding(horizontal=16.dp)) { Text(text("项目网站与使用指南","Project website and guide")) } }
            }
        }
        val detail: @Composable () -> Unit = {
            Column(Modifier.fillMaxSize()) {
                if (wide) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (selection == "appearance") text("外观与文字", "Appearance and text") else selectionLabel,
                        Modifier.padding(16.dp), style = MaterialTheme.typography.titleLarge)
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                    if (selection == "appearance") AppearanceSettings(Modifier.widthIn(max = 720.dp).fillMaxSize(), preferences, ready, zh, onPreference)
                    else {
                        val selected = sections.firstOrNull { it.optBoolean("selected") }?.optString("key")
                        if (selected != selection) LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        else SettingsControls(snapshot.optJSONArray("nodes").objects().filterIndexed { index, node -> !(index == 0 && node.optString("kind") == "heading" && node.optString("label") == selectionLabel) }, Modifier.widthIn(max = 720.dp).fillMaxSize(), onCommand, onLink)
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text(if (wide || overview) text("设置", "Settings") else if (selection == "appearance") text("外观与文字", "Appearance and text") else selectionLabel) }, navigationIcon = {
                if (!wide && !overview) IconButton(onClick = { overview = true }, modifier = Modifier.testTag("settings-back")) { Icon(Icons.AutoMirrored.Filled.ArrowBack, text("返回设置分类", "Back to settings")) }
            })
        Box(Modifier.fillMaxWidth().weight(1f)) {
        if (wide) Row(Modifier.fillMaxSize().testTag("settings-two-pane")) {
            Box(Modifier.width(240.dp).fillMaxHeight()) { categories() }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        } else AnimatedContent(targetState=overview to selection,transitionSpec={ (fadeIn(tween(180,easing=FastOutSlowInEasing))+slideInHorizontally(tween(260,easing=FastOutSlowInEasing)){it/18}) togetherWith fadeOut(tween(100)) },label="Settings detail") { state -> if(state.first) categories() else detail() }
        }
        }
    }
    snapshot.optJSONArray("overlays").objects().lastOrNull()?.let { overlay ->
        val dismiss = { onCommand(JSONObject().put("action", "settingsControl").put("id", overlay.optString("id")).put("operation", "dismiss")) }
        BackHandler { dismiss() }
        val nodes = overlay.optJSONArray("nodes").objects()
        if (overlay.optString("kind") in setOf("menu", "listbox")) ModalBottomSheet(onDismissRequest = dismiss) {
            SettingsControls(nodes, Modifier.fillMaxWidth().heightIn(max = 520.dp), onCommand, onLink)
        } else BasicAlertDialog(onDismissRequest = dismiss) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.widthIn(max = 560.dp).padding(24.dp)) {
                    SettingsControls(nodes, Modifier.fillMaxWidth().heightIn(max = 480.dp), onCommand, onLink)
                    TextButton(onClick = dismiss, modifier = Modifier.align(Alignment.End)) { Text(text("关闭", "Close")) }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AppearanceSettings(modifier: Modifier, preferences: NativePreferences, ready: Boolean, zh: Boolean, onPreference: (JSONObject) -> Unit) {
    fun text(cn: String, en: String) = if (zh) cn else en
    LazyColumn(modifier.testTag("appearance-settings-list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!ready) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item { Text(text("主题", "Theme"), style = MaterialTheme.typography.titleMedium) }
        item {
            // A dropdown keeps all three labels reachable at 200% text size.
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(enabled = ready, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("appearance-theme")) {
                    Text(when (preferences.theme) { "light" -> text("浅色", "Light"); "dark" -> text("深色", "Dark"); else -> text("跟随系统", "Follow system") }, Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    listOf("system", "light", "dark").forEach { value -> DropdownMenuItem(text = { Text(when (value) { "light" -> text("浅色", "Light"); "dark" -> text("深色", "Dark"); else -> text("跟随系统", "Follow system") }) },
                        onClick = { expanded = false; onPreference(JSONObject().put("theme", value)) }, trailingIcon = { if (preferences.theme == value) Icon(Icons.Default.Check, null) }) }
                }
            }
        }
        item { Text(text("Material 配色", "Material color scheme"), style=MaterialTheme.typography.titleMedium) }
        item {
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                appPalettes.forEach { (id, names) -> FilterChip(selected=preferences.palette==id,onClick={onPreference(JSONObject().put("palette",id))},label={Text(if(zh)names.first else names.second)},leadingIcon={Box(Modifier.size(16.dp).background(appColorScheme(id,false).primary,CircleShape))},modifier=Modifier.testTag("palette-$id")) }
            }
        }
        item { Text(text("动态配色取自 Android 12+ 的壁纸；其他配色提供配套的浅色和深色方案。", "Dynamic colors follow your Android 12+ wallpaper. Every palette includes coordinated light and dark schemes."),style=MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider() }
        item { Text(text("统一字体", "Shared font"),style=MaterialTheme.typography.titleMedium) }
        item {
            var expanded by remember { mutableStateOf(false) }
            val fonts=listOf("system" to text("系统默认（推荐）","System default (recommended)"),"noto" to "Noto Sans SC · 思源黑体", "mono" to text("系统等宽字体","System monospace"))
            Box {
                OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth().testTag("shared-font")){Text(fonts.firstOrNull{it.first==preferences.font}?.second ?: fonts.first().second,Modifier.weight(1f));Icon(Icons.Default.ArrowDropDown,null)}
                DropdownMenu(expanded,{expanded=false}) {fonts.forEach { (id,name)->DropdownMenuItem(text={Text(name)},onClick={expanded=false;onPreference(JSONObject().put("font",id))},trailingIcon={if(preferences.font==id)Icon(Icons.Default.Check,null)})}}
            }
        }
        item { Text(text("默认字体保持不变。一个选择统一应用；Noto Sans SC 随包提供，采用 SIL OFL 1.1，可免费商用。中文等宽字形由系统回退。", "The default stays unchanged. One selection applies throughout. Bundled Noto Sans SC uses SIL OFL 1.1 and permits commercial use. Monospace CJK glyphs use system fallback."),style=MaterialTheme.typography.bodySmall) }
        item { HorizontalDivider() }
        item { ListItem(headlineContent={Text(text("界面动画","Interface animations"))},supportingContent={Text(text("开启细致过渡，略增图形开销；关闭后直接切换。始终遵循系统减少动画设置。","Enable refined transitions with some rendering cost. Turn off for instant changes. System reduced-motion settings are respected."))},trailingContent={Switch(preferences.animations,onCheckedChange={onPreference(JSONObject().put("animations",it))},modifier=Modifier.testTag("animations-toggle"))}) }
        item { HorizontalDivider() }
        item { FontSetting(text("会话字号", "Conversation text size"), "text", preferences.text, 10..22, ready, onPreference) }
        item { FontSetting(text("代码字号", "Code text size"), "code", preferences.code, 10..16, ready, onPreference) }
        item { FontSetting(text("终端字号", "Terminal text size"), "terminal", preferences.terminal, 10..20, ready, onPreference) }
        item { Text(text("字号同时遵循系统字体缩放。所有设置仅在此处调整。", "Sizes also follow system font scaling. These settings have one shared source."), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun FontSetting(title: String, role: String, accepted: Int, range: IntRange, enabled: Boolean, onPreference: (JSONObject) -> Unit) {
    var value by remember(accepted) { mutableFloatStateOf(accepted.toFloat()) }
    Column {
        Text("$title · ${value.toInt()} sp", style = MaterialTheme.typography.titleMedium)
        Slider(value, onValueChange = { value = it }, enabled = enabled,
            onValueChangeFinished = { onPreference(JSONObject().put("role", role).put("fontSize", value.toInt())) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
            modifier = Modifier.fillMaxWidth().testTag(if (role == "text") "conversation-font-slider" else "$role-font-slider"))
    }
}

@Composable
private fun SettingsControls(nodes: List<JSONObject>, modifier: Modifier, onCommand: (JSONObject) -> Unit, onLink: (String) -> Unit) {
    // Drafts survive LazyColumn recycling, but never leave memory or enter a
    // saved-state Bundle. Remove them as soon as their editor/control closes.
    val inputDrafts = remember { mutableStateMapOf<String,TextFieldValue>() }
    LaunchedEffect(nodes.map { it.optString("id") }) { inputDrafts.keys.retainAll(nodes.filter { it.optString("kind")=="input" }.map { it.optString("id") }.toSet()) }
    LazyColumn(modifier.testTag("settings-controls"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(nodes, key = { it.optString("id") }) { node ->
            val id = node.optString("id")
            fun action(operation: String, value: String? = null) { onCommand(JSONObject().put("action", "settingsControl").put("id", id).put("operation", operation).apply { if (value != null) put("value", value) }) }
            val label = node.optString("label")
            val enabled = !node.optBoolean("disabled")
            when (node.optString("kind")) {
                "heading" -> Text(label, style = MaterialTheme.typography.titleMedium)
                "text" -> Text(label, style = MaterialTheme.typography.bodyMedium)
                "divider" -> HorizontalDivider()
                "switch" -> ListItem(headlineContent = { Text(label) }, trailingContent = { Switch(node.optBoolean("checked"), onCheckedChange = { action("click") }, enabled = enabled, modifier = Modifier.testTag("control-$id")) })
                "radio" -> ListItem(headlineContent = { Text(label) }, leadingContent = { RadioButton(node.optBoolean("checked"), onClick = { action("click") }, enabled = enabled) }, modifier = Modifier.clickable(enabled = enabled) { action("click") })
                "reorder" -> ListItem(headlineContent = { Text(label) }, trailingContent = {
                    Row {
                        IconButton(onClick = { action("key", "ArrowUp") }, enabled = enabled && node.optBoolean("canUp"), modifier = Modifier.testTag("control-$id-up")) { Icon(Icons.Default.KeyboardArrowUp, if (label.contains("模型")) "上移模型" else "Move model up") }
                        IconButton(onClick = { action("key", "ArrowDown") }, enabled = enabled && node.optBoolean("canDown"), modifier = Modifier.testTag("control-$id-down")) { Icon(Icons.Default.KeyboardArrowDown, if (label.contains("模型")) "下移模型" else "Move model down") }
                    }
                })
                "input" -> SettingInput(node, inputDrafts) { action("input", it) }
                "select" -> {
                    var expanded by remember { mutableStateOf(false) }
                    val options = node.optJSONArray("options").objects()
                    val selected = options.firstOrNull { it.optString("value") == node.optString("value") }
                    Box {
                        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("control-$id")) {
                            Text(listOf(label, selected?.optString("label").orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f)); Icon(Icons.Default.ArrowDropDown, null)
                        }
                        DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(option.optString("label")) }, enabled = !option.optBoolean("disabled"), onClick = { expanded = false; action("input", option.optString("value")) }) } }
                    }
                }
                "slider" -> {
                    val minimum = node.optString("min").toFloatOrNull() ?: 0f
                    val maximum = (node.optString("max").toFloatOrNull() ?: 100f).coerceAtLeast(minimum + 1)
                    var value by remember(node.optString("value")) { mutableFloatStateOf(node.optString("value").toFloatOrNull() ?: minimum) }
                    Text("$label · $value")
                    Slider(value.coerceIn(minimum, maximum), onValueChange = { value = it }, onValueChangeFinished = { action("input", value.toString()) }, valueRange = minimum..maximum, enabled = enabled)
                }
                "link" -> TextButton(onClick = { onLink(node.optString("href")) }) { Text(label) }
                "button" -> if (node.optBoolean("popup")) OutlinedButton(onClick = { action("click") }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("control-$id")) {
                    Text(label, Modifier.weight(1f))
                    Icon(Icons.Default.ArrowDropDown, null)
                } else if (Regex("^(保存|添加|创建|完成|确认|应用|Save|Add|Create|Done|Confirm|Apply)", RegexOption.IGNORE_CASE).containsMatchIn(label)) {
                    FilledTonalButton(onClick = { action("click") }, enabled = enabled, modifier = Modifier.testTag("control-$id")) { Text(label) }
                } else ListItem(headlineContent = { Text(label) }, trailingContent = { Icon(if (node.optBoolean("checked")) Icons.Default.Check else Icons.Default.KeyboardArrowRight, null) },
                    modifier = Modifier.fillMaxWidth().testTag("control-$id").clickable(enabled = enabled) { action("click") })
            }
        }
    }
}

@Composable
private fun SettingInput(node: JSONObject, drafts: MutableMap<String,TextFieldValue>, onChange: (String) -> Unit) {
    val id = node.optString("id")
    val secret = node.optBoolean("secret")
    val accepted = node.optString("value")
    // Neither secret input nor an in-progress IME composition is saved in a Bundle.
    var local by remember(id) { mutableStateOf(drafts[id] ?: TextFieldValue(accepted)) }
    var focused by remember(id) { mutableStateOf(false) }
    var revealed by remember(id) { mutableStateOf(false) }
    LaunchedEffect(accepted, focused) { if (!focused && !secret && local.text != accepted) { local = TextFieldValue(accepted, TextRange(accepted.length));drafts[id]=local } }
    OutlinedTextField(value = local, onValueChange = { next -> local = next; drafts[id]=next; if (next.composition == null) onChange(next.text) },
        enabled = !node.optBoolean("disabled"), singleLine = !node.optBoolean("multiline"), minLines = if (node.optBoolean("multiline")) 3 else 1, maxLines = if (node.optBoolean("multiline")) 8 else 1,
        modifier = Modifier.fillMaxWidth().testTag("control-$id").onFocusChanged { state -> focused = state.isFocused },
        label = { if (node.optString("label").isNotBlank()) Text(node.optString("label")) },
        placeholder = { Text(node.optString("placeholder")) }, isError = node.optBoolean("invalid"),
        trailingIcon = if(secret) { { IconButton(onClick={revealed=!revealed},modifier=Modifier.testTag("secret-visibility-$id")) {
            Icon(painterResource(if(revealed) R.drawable.ic_visibility_off else R.drawable.ic_visibility), if(node.optString("label").contains("密钥")) {if(revealed)"隐藏密钥" else "显示密钥"} else {if(revealed)"Hide key" else "Show key"})
        } } } else null,
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else when (node.optString("inputType")) { "number" -> KeyboardType.Decimal; "url" -> KeyboardType.Uri; "email" -> KeyboardType.Email; else -> KeyboardType.Text }))
}
