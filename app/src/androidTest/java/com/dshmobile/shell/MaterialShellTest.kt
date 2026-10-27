package com.dshmobile.shell

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class MaterialShellTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun largeSystemTextAndTheImeKeepProjectInputReachable() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }; Unit
        }
        val original = rule.activity.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 1.6")
            rule.waitUntil(15_000) { rule.activity.resources.configuration.fontScale >= 1.59f }
            rule.onNodeWithTag("nav-projects").performClick()
            rule.onNodeWithTag("native-preferences-list").performScrollToNode(hasTestTag("new-project"))
            rule.onNodeWithTag("new-project").performClick()
            rule.onNodeWithTag("project-name-input").performClick().performTextInput("preview-check")
            rule.waitUntil(15_000) {
                androidx.core.view.ViewCompat.getRootWindowInsets(rule.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            rule.onNodeWithTag("project-name-input").assertIsDisplayed()
            rule.onNodeWithTag("project-name-input").captureToImage().asAndroidBitmap().let { bitmap ->
                File(rule.activity.externalCacheDir, "material3-large-font-input.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
            }
        } finally {
            shell("input keyevent 4")
            shell("settings put system font_scale $original")
        }
    }

    @Test fun navigationFollowsTheLiveWindowInBothOrientations() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { String(it.readBytes()) }
        }
        val cases = listOf(
            Triple("phone-portrait", "1080x2400", 420),
            Triple("phone-landscape", "2400x1080", 420),
            Triple("tablet-portrait", "1600x2560", 320),
            Triple("tablet-landscape", "2560x1600", 320)
        )
        try {
            cases.forEach { (name, size, density) ->
                shell("wm size $size"); shell("wm density $density")
                // Android 10 may cap a size override at twice the physical
                // display width. Verify the actual window, not the request.
                val actualSize = shell("wm size").lineSequence().filter { it.contains("size:") }.last().substringAfter(": ").trim()
                val expectedWidth = actualSize.substringBefore('x').toInt() * 160 / density
                println("Window case $name: requested=$size actual=$actualSize density=$density")
                // Configuration width excludes the landscape system navigation
                // inset on API 29; it is smaller than the display width in dp.
                rule.waitUntil(15_000) { abs(rule.activity.resources.configuration.screenWidthDp - expectedWidth) < 90 }
                rule.waitUntil(30_000) { rule.activity.preferencesReady }
                rule.onNodeWithTag("nav-settings").performClick()
                if (rule.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("settings-back").performClick()
                rule.onNodeWithTag("settings-appearance").performClick()
                rule.waitForIdle()
                val chat = rule.onNodeWithTag("nav-chat").fetchSemanticsNode().boundsInRoot
                val settings = rule.onNodeWithTag("nav-settings").fetchSemanticsNode().boundsInRoot
                if (name.startsWith("phone")) assertTrue("$name should use the official navigation bar", abs(chat.center.y - settings.center.y) < 4)
                else assertTrue("$name should use the official navigation rail", abs(chat.center.x - settings.center.x) < 4 && settings.top > chat.top)
                rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("conversation-font-slider"))
                rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("conversation-font-slider"))
        rule.onNodeWithTag("conversation-font-slider").assertIsDisplayed()
                // Capture the synchronized Compose frame. Display-level screen
                // capture can return the previous stretched Surface after a wm resize.
                val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
                File(rule.activity.externalCacheDir, "material3-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            shell("wm size reset"); shell("wm density reset")
        }
    }

    @Test fun materialSliderWritesTheRealDshPreferenceAndSurvivesEngineRestart() {
        rule.waitUntil(300_000) { EngineService.state.value?.phase == "running" }
        rule.waitUntil(30_000) { rule.activity.preferencesReady }
        rule.waitUntil(30_000) { evaluate("Boolean(document.body.style.getPropertyValue('--dsh-content-font-size'))") == "true" }
        val before = evaluate("parseInt(document.body.style.getPropertyValue('--dsh-content-font-size'))").toFloat()
        val next = if (before == 17f) 16f else 17f
        rule.onNodeWithTag("nav-settings").performClick()
        if (rule.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("settings-back").performClick()
        rule.onNodeWithTag("settings-appearance").performClick()
        rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("conversation-font-slider"))
        rule.onNodeWithTag("conversation-font-slider").assertIsDisplayed()
        try {
            rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("conversation-font-slider"))
            rule.onNodeWithTag("conversation-font-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(next) }
            rule.waitUntil(20_000) { evaluate("parseInt(document.body.style.getPropertyValue('--dsh-content-font-size'))") == next.toInt().toString() }
            // DSH settings writes are asynchronous; restart only after the
            // accepted value reaches the real profile, not its optimistic UI.
            val profile = File(rule.activity.filesDir, "home/.dsh/profiles/web/cordis.patch.yml")
            rule.waitUntil(20_000) { Regex("(?m)^\\s+fontSize: ${next.toInt()}\\s*$").containsMatchIn(profile.readText()) }
            rule.runOnUiThread { EngineService.restart(rule.activity) }
            rule.waitUntil(90_000) { EngineService.state.value?.phase == "running" }
            rule.waitUntil(30_000) { rule.activity.preferencesReady }
            rule.waitUntil(30_000) { evaluate("parseInt(document.body.style.getPropertyValue('--dsh-content-font-size'))") == next.toInt().toString() }
            assertEquals(next.toInt().toString(), evaluate("parseInt(document.body.style.getPropertyValue('--dsh-content-font-size'))"))
        } finally {
            rule.waitUntil(30_000) { rule.activity.preferencesReady }
            rule.onNodeWithTag("nav-settings").performClick()
            if (rule.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithTag("settings-back").performClick()
            rule.onNodeWithTag("settings-appearance").performClick()
            rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("conversation-font-slider"))
            rule.onNodeWithTag("conversation-font-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(before) }
            rule.waitUntil(20_000) { evaluate("parseInt(document.body.style.getPropertyValue('--dsh-content-font-size'))") == before.toInt().toString() }
            val restoredProfile = File(rule.activity.filesDir, "home/.dsh/profiles/web/cordis.patch.yml")
            rule.waitUntil(20_000) { Regex("(?m)^\\s+fontSize: ${before.toInt()}\\s*$").containsMatchIn(restoredProfile.readText()) }
        }
        rule.onNodeWithTag("nav-projects").performClick()
        rule.onNodeWithTag("nav-tools").performClick()
        rule.onNodeWithTag("nav-chat").performClick()
        assertTrue("DSH content disappeared after native navigation", evaluate("Boolean(document.getElementById('root')?.childElementCount && document.documentElement.getBoundingClientRect().height > 100)") == "true")
    }

    private fun evaluate(script: String): String {
        val ready = CountDownLatch(1)
        var result = ""
        rule.activity.runOnUiThread {
            findWebView(rule.activity.window.decorView)!!.evaluateJavascript(script) { result = it; ready.countDown() }
        }
        check(ready.await(10, TimeUnit.SECONDS)) { "WebView evaluation timed out" }
        return result
    }
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }
}
