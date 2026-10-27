package com.dshmobile.shell

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AdaptiveSettingsMatrixTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
    }
    private fun evaluate(script: String): String {
        fun web(view: View): WebView? { if(view is WebView)return view; if(view is ViewGroup)for(index in 0 until view.childCount)web(view.getChildAt(index))?.let{return it}; return null }
        var result="";val latch=CountDownLatch(1)
        rule.activity.runOnUiThread { web(rule.activity.window.decorView)!!.evaluateJavascript(script){result=it;latch.countDown()} }
        check(latch.await(10,TimeUnit.SECONDS)); return result
    }
    @Test fun officialSettingsControlsRemainVisibleAndCallableAcrossWindowClasses() {
        val cases = listOf(
            Triple("phone-320", "720x1280", 360), Triple("phone-411", "1080x2400", 420),
            Triple("phone-landscape", "2160x1080", 420), Triple("split-500", "1000x1600", 320),
            Triple("tablet-600", "1200x1920", 320), Triple("tablet-839", "1678x2400", 320),
            Triple("tablet-840", "1680x2400", 320), Triple("tablet-4-3", "2160x1620", 320),
            Triple("tablet-16-10", "2160x1350", 320), Triple("tablet-16-9", "1920x1080", 240),
            Triple("large-1600", "2160x1600", 213), Triple("short-400", "1680x800", 320)
        )
        try {
            rule.waitUntil(300_000) { rule.activity.preferencesReady }
            for ((name, size, density) in cases) {
                shell("wm size $size"); shell("wm density $density")
                val actual = shell("wm size").lineSequence().last { it.contains("size:") }.substringAfter(": ").trim()
                val width = actual.substringBefore('x').toInt() * 160 / density
                val height = actual.substringAfter('x').toInt() * 160 / density
                rule.waitUntil(20_000) { abs(rule.activity.resources.configuration.screenWidthDp - width) < 90 }
                rule.waitUntil(30_000) { rule.activity.preferencesReady }
                rule.onNodeWithTag("nav-settings").performClick()
                rule.waitUntil(30_000) { rule.activity.settingsUi.optBoolean("ready") }
                if (rule.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("settings-back").performClick()
                rule.onNodeWithTag("settings-appearance").performClick()
                rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("conversation-font-slider"))
                val slider = rule.onNodeWithTag("conversation-font-slider").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
                assertTrue("$name clips the official slider", slider.left >= root.left - 1 && slider.right <= root.right + 1 && slider.width > 80)
                rule.waitUntil(15_000) {
                    val first = rule.onNodeWithTag("nav-chat").fetchSemanticsNode().boundsInRoot
                    val last = rule.onNodeWithTag("nav-settings").fetchSemanticsNode().boundsInRoot
                    val bar = abs(first.center.y - last.center.y) < 4
                    val rail = abs(first.center.x - last.center.x) < 4 && last.top > first.top
                    // The system can round a display override by one dp at an
                    // exact breakpoint. Both official layouts must stay usable.
                    if (width < 599 || height < 479) bar else if (width > 601 && height > 481) rail else bar || rail
                }
                rule.waitForIdle()
                val chat = rule.onNodeWithTag("nav-chat").fetchSemanticsNode().boundsInRoot
                val settings = rule.onNodeWithTag("nav-settings").fetchSemanticsNode().boundsInRoot
                if (width < 599 || height < 479) assertTrue("$name bar", abs(chat.center.y - settings.center.y) < 4)
                else if (width > 601 && height > 481) assertTrue("$name rail", abs(chat.center.x - settings.center.x) < 4 && settings.top > chat.top)
                rule.onRoot().captureToImage().asAndroidBitmap().let { image ->
                    File(rule.activity.externalCacheDir, "settings-matrix-$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
                }
                if (rule.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("settings-back").performClick()
                val general = rule.activity.settingsUi.optJSONArray("sections").objects().first { it.optString("key") == "general" }
                val category = "settings-section-${general.optString("label") }"
                rule.onNodeWithTag("settings-categories").performScrollToNode(hasTestTag(category))
                rule.onNodeWithTag(category).performClick()
                rule.waitUntil(20_000) { rule.activity.settingsUi.optJSONArray("sections").objects().any { it.optString("key") == "general" && it.optBoolean("selected") } }
                val toggle = rule.activity.settingsUi.optJSONArray("nodes").objects().first { it.optString("kind") == "switch" && !it.optBoolean("disabled") }
                val id = toggle.optString("id"); val original = toggle.optBoolean("checked")
                val tag = "control-$id"
                rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(tag))
                evaluate("window.__lastNativeSettingCommand=null;window.addEventListener('dsh-material-command',e=>{window.__lastNativeSettingCommand={action:e.detail?.action,id:e.detail?.id,operation:e.detail?.operation}})")
                rule.onNodeWithTag(tag).assertIsDisplayed().performClick()
                try { rule.waitUntil(15_000) { rule.activity.settingsUi.optJSONArray("nodes").objects().any { it.optString("id") == id && it.optBoolean("checked") != original && !it.optBoolean("disabled") } } }
                catch(error: Throwable) {
                    println("CONTROL_DIAGNOSTIC $name native=$toggle web="+evaluate("JSON.stringify({last:window.__lastNativeSettingCommand,switches:[...document.querySelectorAll('[data-shortcut-modal=settings] [role=switch]')].map(e=>({label:e.getAttribute('aria-label'),checked:e.getAttribute('aria-checked'),disabled:e.disabled,inert:!!e.closest('[inert]')}))})"))
                    throw error
                }
                rule.onNodeWithTag(tag).performClick()
                rule.waitUntil(15_000) { rule.activity.settingsUi.optJSONArray("nodes").objects().any { it.optString("id") == id && it.optBoolean("checked") == original && !it.optBoolean("disabled") } }
                println("SETTINGS_WINDOW_PASS $name actual=$actual density=$density dp=${width}x$height")
            }
        } finally { shell("wm size reset"); shell("wm density reset") }
    }
}
