package com.dshmobile.shell

import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class UnifiedSettingsTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun everyDshSettingsSectionUsesNativeControlsWithoutDuplicateAppearance() {
        rule.waitUntil(300_000) { rule.activity.preferencesReady }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(30_000) { rule.activity.settingsUi.optBoolean("ready") && rule.activity.settingsUi.optJSONArray("sections").objects().isNotEmpty() }
        val labels = rule.activity.settingsUi.optJSONArray("sections").objects().map { it.optString("label") }
        assertTrue("Missing DSH model settings: $labels", labels.any { it.contains("模型") || it.contains("Model", true) })
        labels.forEachIndexed { index, label ->
            categories()
            rule.onNodeWithTag("settings-categories").performScrollToNode(hasTestTag("settings-section-$label"))
            rule.onNodeWithTag("settings-section-$label").performClick()
            rule.waitUntil(30_000) { rule.activity.settingsUi.optJSONArray("sections").objects().any { it.optString("label") == label && it.optBoolean("selected") } }
            if (label.contains("模型") || label.contains("Model", true) || label.contains("插件") || label.contains("Plugin", true)) rule.waitUntil(30_000) {
                rule.activity.settingsUi.optJSONArray("nodes").objects().any { it.optString("kind") in setOf("button", "input", "switch") }
            }
            rule.onNodeWithTag("settings-controls").assertIsDisplayed()
            val nodes = rule.activity.settingsUi.optJSONArray("nodes").objects()
            assertTrue("Section $label has no native content", nodes.isNotEmpty())
            assertFalse("The old appearance/size controller is still mounted", evaluate("Boolean(document.querySelector('[data-shortcut-modal=settings] input[type=number][aria-label*=字号]'))") == "true")
            println("NATIVE_SETTINGS_SECTION: $label ${nodes.size} nodes ${nodes.map { it.optString("kind") }.distinct()}")
            rule.onRoot().captureToImage().asAndroidBitmap().let { image ->
                File(rule.activity.externalCacheDir, "unified-settings-section-$index.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            }
        }
        categories()
        rule.onNodeWithTag("settings-appearance").performClick()
        rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("conversation-font-slider"))
        rule.onAllNodesWithTag("conversation-font-slider").assertCountEquals(1)
        rule.onNodeWithTag("conversation-font-slider").assertIsDisplayed()
    }

    @Test fun passwordValuesAreRedactedAndForgedControlIdsCannotChangeThePage() {
        rule.waitUntil(300_000) { rule.activity.preferencesReady }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(30_000) { rule.activity.settingsUi.optBoolean("ready") }
        evaluate("""(()=>{const p=document.querySelector('[data-shortcut-modal=settings] [data-slot="settings.section"]');const i=document.createElement('input');i.type='password';i.id='native-security-test';i.setAttribute('aria-label','Native secret test');i.value='synthetic-native-secret';p.append(i);return true})()""")
        rule.waitUntil(10_000) { rule.activity.settingsUi.optJSONArray("nodes").objects().any { it.optString("label") == "Native secret test" } }
        assertFalse(rule.activity.settingsUi.toString().contains("synthetic-native-secret"))
        val node = rule.activity.settingsUi.optJSONArray("nodes").objects().first { it.optString("label") == "Native secret test" }
        assertTrue(node.optBoolean("secret")); assertEquals("", node.optString("value"))
        evaluate("window.dispatchEvent(new CustomEvent('dsh-material-command',{detail:{action:'settingsControl',id:'forged-id',operation:'input',value:'changed'}}))")
        assertEquals("\"synthetic-native-secret\"", evaluate("document.getElementById('native-security-test').value"))
        evaluate("document.getElementById('native-security-test').remove()")
    }

    @Test fun nativeDropdownSwitchAndTextFieldDriveTheActualDshControllers() {
        rule.waitUntil(300_000) { rule.activity.preferencesReady }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(30_000) { rule.activity.settingsUi.optBoolean("ready") }
        fun section(key: String) {
            categories()
            val item = rule.activity.settingsUi.optJSONArray("sections").objects().first { it.optString("key") == key }
            val tag = "settings-section-${item.optString("label") }"
            rule.onNodeWithTag("settings-categories").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).performClick()
            rule.waitUntil(20_000) { rule.activity.settingsUi.optJSONArray("sections").objects().any { it.optString("key") == key && it.optBoolean("selected") } }
        }
        fun controls() = rule.activity.settingsUi.optJSONArray("nodes").objects()
        fun click(node: JSONObject) {
            val tag = "control-${node.optString("id") }"
            rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(tag))
            rule.onNodeWithTag(tag).performClick()
        }
        section("general")
        val toggle = controls().first { it.optString("kind") == "switch" && !it.optBoolean("disabled") }
        val before = toggle.optBoolean("checked"); val toggleId = toggle.optString("id")
        click(toggle)
        rule.waitUntil(15_000) { controls().any { it.optString("id") == toggleId && it.optBoolean("checked") != before } }
        click(controls().first { it.optString("id") == toggleId })
        rule.waitUntil(15_000) { controls().any { it.optString("id") == toggleId && it.optBoolean("checked") == before } }

        val choice = controls().first { it.optBoolean("popup") && it.optString("label") in setOf("详细", "Detailed", "简洁", "Compact", "隐藏", "Hidden") }
        val original = choice.optString("label")
        click(choice)
        rule.waitUntil(10_000) { rule.activity.settingsUi.optJSONArray("overlays").objects().isNotEmpty() }
        val options = rule.activity.settingsUi.optJSONArray("overlays").objects().last().optJSONArray("nodes").objects()
        val alternate = options.first { it.optString("kind") == "button" && it.optString("label") != original && !it.optBoolean("disabled") }
        rule.onNodeWithTag("control-${alternate.optString("id")}").performClick()
        rule.waitUntil(15_000) { controls().any { it.optBoolean("popup") && it.optString("label") == alternate.optString("label") } }
        click(controls().first { it.optBoolean("popup") && it.optString("label") == alternate.optString("label") })
        rule.waitUntil(10_000) { rule.activity.settingsUi.optJSONArray("overlays").objects().isNotEmpty() }
        val restore = rule.activity.settingsUi.optJSONArray("overlays").objects().last().optJSONArray("nodes").objects().first { it.optString("label") == original }
        rule.onNodeWithTag("control-${restore.optString("id")}").performClick()
        rule.waitUntil(15_000) { controls().any { it.optBoolean("popup") && it.optString("label") == original } }

        section("plugins")
        rule.waitUntil(20_000) { controls().any { it.optString("kind") == "input" } }
        val field = controls().first { it.optString("kind") == "input" && !it.optBoolean("secret") }
        val fieldTag = "control-${field.optString("id") }"
        rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(fieldTag))
        rule.onNodeWithTag(fieldTag).performTextReplacement("tool-bash")
        rule.waitUntil(15_000) { controls().any { it.optString("label").contains("tool-bash") } && controls().none { it.optString("label").contains("tool-fs") } }
        rule.onNodeWithTag(fieldTag).performTextReplacement("")
        rule.waitUntil(15_000) { controls().any { it.optString("label").contains("tool-fs") } }
    }

    private fun categories() {
        if (rule.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("settings-back").performClick()
    }

    @Test fun nativeModelReorderingMovesTheRealDraftAndDoesNotWriteCredentials() {
        rule.waitUntil(300_000) { rule.activity.preferencesReady }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(30_000) { rule.activity.settingsUi.optBoolean("ready") }
        val models = rule.activity.settingsUi.optJSONArray("sections").objects().first { it.optString("key") == "models" }
        rule.onNodeWithTag("settings-section-${models.optString("label")}").performClick()
        fun controls() = rule.activity.settingsUi.optJSONArray("nodes").objects()
        rule.waitUntil(30_000) { controls().any { it.optString("kind") == "input" && it.optBoolean("secret") } || controls().any { it.optString("kind") == "button" && it.optString("label").let { text -> text.startsWith("编辑 ") || text.startsWith("Edit ") } } }
        fun click(node: JSONObject) {
            val tag = "control-${node.optString("id") }"
            rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(tag)); rule.onNodeWithTag(tag).performClick()
        }
        if(controls().none { it.optString("kind") == "input" && it.optBoolean("secret") }) click(controls().first { it.optString("kind") == "button" && it.optString("label").let { text -> text.startsWith("编辑 ") || text.startsWith("Edit ") } })
        val modelIdLabel = Regex("(?i)(模型\\s*ID|Model\\s*ID)\\s*\\d+")
        fun fields() = controls().filter { it.optString("kind") == "input" && modelIdLabel.matches(it.optString("label")) }
        rule.waitUntil(20_000) { controls().any { it.optString("label") in setOf("添加模型", "Add model") } }
        val seeded = fields().isEmpty()
        if(seeded) {
            click(controls().first { it.optString("label") in setOf("添加模型", "Add model") })
            rule.waitUntil(15_000) { fields().size == 1 }
            val tag="control-${fields().first().optString("id")}"
            rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(tag));rule.onNodeWithTag(tag).performTextReplacement("native-reorder-base")
            rule.waitUntil(10_000){fields().first().optString("value")=="native-reorder-base"}
        }
        val original = fields().map { it.optString("value") }
        click(controls().first { it.optString("label") in setOf("添加模型", "Add model") })
        rule.waitUntil(15_000) { fields().size == original.size + 1 }
        val added = fields().last(); val fieldTag = "control-${added.optString("id") }"
        rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(fieldTag))
        rule.onNodeWithTag(fieldTag).performTextReplacement("native-reorder-check")
        rule.waitUntil(15_000) { fields().last().optString("value") == "native-reorder-check" }
        val mover = controls().filter { it.optString("kind") == "reorder" }.last()
        val upTag = "control-${mover.optString("id")}-up"
        rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(upTag)); rule.onNodeWithTag(upTag).performClick()
        rule.waitUntil(15_000) { fields()[original.size - 1].optString("value") == "native-reorder-check" }
        val position = original.size
        click(controls().first { it.optString("label") in setOf("删除模型 $position", "Delete model $position") })
        rule.waitUntil(15_000) { fields().map { it.optString("value") } == original }
        if(seeded) { click(controls().first { it.optString("label") in setOf("删除模型 1", "Delete model 1") });rule.waitUntil(10_000){fields().isEmpty()} }
        // No Save is clicked: this is the upstream staged draft, with no key write.
    }
    private fun evaluate(script: String): String {
        val latch = CountDownLatch(1); var result = ""
        rule.activity.runOnUiThread { web(rule.activity.window.decorView)!!.evaluateJavascript(script) { result = it; latch.countDown() } }
        check(latch.await(10, TimeUnit.SECONDS)); return result
    }
    private fun web(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) web(view.getChildAt(index))?.let { return it }
        return null
    }
}
