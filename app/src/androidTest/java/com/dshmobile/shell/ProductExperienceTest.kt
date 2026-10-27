package com.dshmobile.shell

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class ProductExperienceTest {
    @get:Rule val rule=createAndroidComposeRule<MainActivity>()
    private fun web(view:View):WebView? { if(view is WebView)return view;if(view is ViewGroup)for(i in 0 until view.childCount)web(view.getChildAt(i))?.let{return it};return null }
    private fun evaluate(script:String):String { val latch=CountDownLatch(1);var result="";rule.runOnUiThread{web(rule.activity.window.decorView)!!.evaluateJavascript(script){result=it;latch.countDown()}};check(latch.await(10,TimeUnit.SECONDS));return result }
    private fun ready() { rule.waitUntil(300_000){rule.activity.preferencesReady} }
    private fun appearance() {rule.onNodeWithTag("nav-settings").performClick();if(rule.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty())rule.onNodeWithTag("settings-back").performClick();rule.onNodeWithTag("settings-appearance").performClick()}
    @Test fun nativePasswordVisibilityShowsTypedKeyAndKeepsItAcrossFocusChanges() {
        ready();rule.onNodeWithTag("nav-settings").performClick();rule.waitUntil(30_000){rule.activity.settingsUi.optBoolean("ready")}
        val section=rule.activity.settingsUi.optJSONArray("sections").objects().first{it.optString("key")=="models"}
        rule.onNodeWithTag("settings-section-${section.optString("label")}").performClick()
        fun controls()=rule.activity.settingsUi.optJSONArray("nodes").objects()
        rule.waitUntil(30_000){controls().any{it.optBoolean("secret")}||controls().any{it.optString("label").startsWith("编辑 ")||it.optString("label").startsWith("Edit ")}}
        if(controls().none{it.optBoolean("secret")}) {
            val edit=controls().first{it.optString("label").startsWith("编辑 ")||it.optString("label").startsWith("Edit ")};val tag="control-${edit.optString("id")}"
            rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(tag));rule.onNodeWithTag(tag).performClick()
        }
        rule.waitUntil(15_000){controls().any{it.optBoolean("secret")}}
        val secret=controls().first{it.optBoolean("secret")};val id=secret.optString("id");val field="control-$id";val toggle="secret-visibility-$id"
        rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(field));rule.onNodeWithTag(field).performTextReplacement("native-key-visibility-check")
        assertTrue(rule.onNodeWithTag(field).fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        rule.onNodeWithTag(toggle).performClick()
        rule.waitUntil(10_000){rule.onAllNodes(hasContentDescription("隐藏密钥") or hasContentDescription("Hide key")).fetchSemanticsNodes().isNotEmpty()}
        val visibleLayout=mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(field).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(visibleLayout)}
        assertTrue("Revealed text must actually be rendered",visibleLayout.any{it.layoutInput.text.text=="native-key-visibility-check"})
        rule.onNodeWithTag(field).assertTextContains("native-key-visibility-check")
        rule.onNodeWithTag(toggle).performClick()
        assertTrue(rule.onNodeWithTag(field).fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        val other=controls().first{it.optString("kind")=="input"&&!it.optBoolean("secret")};val otherTag="control-${other.optString("id")}"
        rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(otherTag));rule.onNodeWithTag(otherTag).performClick()
        rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(field));rule.onNodeWithTag(toggle).performClick()
        rule.onNodeWithTag(field).assertTextContains("native-key-visibility-check")
        assertFalse(rule.activity.settingsUi.toString().contains("native-key-visibility-check"))
        // The synthetic draft is never saved or captured in screenshots.
    }
    @Test fun conversationSidebarIsReachableAndHideableOnPhoneAndTablet() {
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command:String)=automation.executeShellCommand(command).use{android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { input -> String(input.readBytes()) }}
        fun capture(name:String) {rule.waitForIdle();val bitmap=rule.onRoot().captureToImage().asAndroidBitmap();java.io.File(rule.activity.externalCacheDir,name).outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
        try {
            ready()
            val cases=listOf(Triple("phone-home","1080x2400",420),Triple("phone-landscape","2160x1080",420),Triple("tablet-home","1680x2400",320),Triple("tablet-landscape","2160x1600",320))
            for((name,size,density) in cases){shell("wm size $size");shell("wm density $density");rule.waitUntil(30_000){rule.activity.preferencesReady};rule.onNodeWithTag("nav-chat").performClick()
                rule.onNodeWithTag("new-conversation-top").performClick();rule.waitForIdle()
                val wide=rule.activity.resources.configuration.screenWidthDp>=840&&rule.activity.resources.configuration.screenHeightDp>=480
                if(rule.onAllNodesWithTag("conversation-sidebar").fetchSemanticsNodes().isEmpty())rule.onNodeWithTag("toggle-conversation-sidebar").performClick()
                rule.onNodeWithTag("conversation-sidebar").assertIsDisplayed();rule.onNodeWithTag("new-conversation").assertIsDisplayed()
                if(wide)capture("$name.png") else capture("$name-drawer.png")
                rule.onNodeWithTag("hide-conversation-sidebar").performClick();rule.waitForIdle()
                rule.onAllNodesWithTag("conversation-sidebar").assertCountEquals(0)
                if(!wide)capture("$name.png")
                if(wide){rule.onNodeWithTag("toggle-conversation-sidebar").performClick();rule.onNodeWithTag("conversation-sidebar").assertIsDisplayed()}
                println("CONVERSATION_WINDOW_PASS $name")
            }
        } finally {shell("wm size reset");shell("wm density reset")}
    }
    @Test fun sharedFontPaletteAndInstantModePersistWithoutChangingSizes() {
        ready();val original=evaluate("document.body.style.getPropertyValue('--dsh-content-font-size')")
        appearance()
        rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("palette-teal"))
        rule.onNodeWithTag("palette-teal").performClick()
        rule.waitUntil(10_000){rule.activity.getSharedPreferences("appearance",0).getString("palette","")=="teal"}
        rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("shared-font"))
        rule.onNodeWithTag("shared-font").performClick()
        rule.onAllNodesWithText("Noto Sans SC · 思源黑体").onLast().performClick()
        rule.waitUntil(15_000){evaluate("document.documentElement.dataset.androidFont")=="\"noto\""}
        assertEquals("true",evaluate("(()=>{window.__fontQa='loading';const f=new FontFace('qa-font',\"url('/android-font/noto-sans-sc.ttf')\");f.load().then(()=>window.__fontQa='loaded').catch(()=>window.__fontQa='failed');return true})()"))
        rule.waitUntil(20_000){evaluate("window.__fontQa")=="\"loaded\""}
        rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("animations-toggle"))
        val stored=rule.activity.getSharedPreferences("appearance",0)
        if(stored.getBoolean("animations",true))rule.onNodeWithTag("animations-toggle").performClick()
        rule.waitUntil(10_000){evaluate("document.documentElement.dataset.androidReduceMotion")=="\"true\""}
        assertEquals(original,evaluate("document.body.style.getPropertyValue('--dsh-content-font-size')"))
        rule.onNodeWithTag("nav-chat").performClick();rule.onNodeWithTag("toggle-conversation-sidebar").performClick()
        rule.onNodeWithTag("conversation-sidebar").assertIsDisplayed()
        rule.onNodeWithTag("new-conversation").assertIsDisplayed().performClick()
        appearance()
        rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("animations-toggle"));rule.onNodeWithTag("animations-toggle").performClick()
        rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("shared-font"));rule.onNodeWithTag("shared-font").performClick()
        rule.onNode(hasText("系统默认（推荐）") or hasText("System default (recommended)")).performClick()
        rule.onNodeWithTag("appearance-settings-list").performScrollToNode(hasTestTag("palette-dynamic"));rule.onNodeWithTag("palette-dynamic").performClick()
    }
    @Test fun modelFetchUsesTheActualEndpointAndRefreshesItsChangingList() {
        ready();val count=AtomicInteger();val authorized=AtomicInteger()
        val server=ServerSocket(0,4,java.net.InetAddress.getByName("127.0.0.1"));server.soTimeout=90_000
        val worker=thread(isDaemon=true) { try {repeat(2){server.accept().use { socket ->
            socket.soTimeout=15_000;val reader=socket.getInputStream().bufferedReader();val first=reader.readLine();val headers=mutableListOf<String>();while(true){val line=reader.readLine()?:break;if(line.isEmpty())break;headers.add(line)}
            if(first=="GET /models HTTP/1.1"&&headers.any{it.equals("authorization: Bearer android-fixture-key",true)})authorized.incrementAndGet()
            val n=count.incrementAndGet();val body="""{"data":[{"id":"android-live-model-$n","name":"Live model $n"}]}"""
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body".toByteArray())
        }}}catch(_:java.io.IOException){} }
        try {
            rule.onNodeWithTag("nav-settings").performClick();rule.waitUntil(30_000){rule.activity.settingsUi.optBoolean("ready")}
            val section=rule.activity.settingsUi.optJSONArray("sections").objects().first{it.optString("key")=="models"}
            rule.onNodeWithTag("settings-section-${section.optString("label")}").performClick()
            fun controls()=rule.activity.settingsUi.optJSONArray("nodes").objects()
            fun click(node:org.json.JSONObject) {val tag="control-${node.optString("id")}";rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(tag));rule.onNodeWithTag(tag).performClick()}
            rule.waitUntil(30_000){controls().any{it.optString("kind")=="input"&&it.optBoolean("secret")} || controls().any{it.optString("label").startsWith("编辑 ")||it.optString("label").startsWith("Edit ")}}
            if(controls().none{it.optString("kind")=="input"&&it.optBoolean("secret")})click(controls().first{it.optString("label").startsWith("编辑 ")||it.optString("label").startsWith("Edit ")})
            rule.waitUntil(15_000){controls().any{it.optString("label") in setOf("API 地址","API base URL","Base URL")&&it.optString("kind")=="input"}}
            val endpoint=controls().first{it.optString("kind")=="input"&&it.optString("label") in setOf("API 地址","API base URL","Base URL")&&!it.optBoolean("secret")}
            assertTrue("Effective endpoint must be filled (including a saved custom override)",endpoint.optString("value").startsWith("http"))
            fun fill(node:org.json.JSONObject,value:String) {val tag="control-${node.optString("id")}";rule.onNodeWithTag("settings-controls").performScrollToNode(hasTestTag(tag));rule.onNodeWithTag(tag).performTextReplacement(value)}
            fill(endpoint,"http://127.0.0.1:${server.localPort}/anthropic")
            fill(controls().first{it.optString("kind")=="input"&&it.optBoolean("secret")},"android-fixture-key")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent 4").close()
            rule.waitForIdle()
            evaluate("window.__fetchClicks=0;window.__lastRequest=null;document.querySelectorAll('[data-shortcut-modal=settings] button').forEach(b=>{if(/获取可用模型|Fetch available models/.test(b.textContent))b.addEventListener('click',()=>window.__fetchClicks++)});window.addEventListener('dsh-material-command',e=>window.__lastRequest={action:e.detail?.action,id:e.detail?.id,operation:e.detail?.operation})")
            repeat(2){ n ->
                click(controls().first{it.optString("label") in setOf("获取可用模型","Fetch available models","获取模型","Fetch models")})
                try { rule.waitUntil(20_000){rule.activity.settingsUi.toString().contains("android-live-model-${n+1}")} }
                catch(error:Throwable) { println("MODEL_DISCOVERY_DIAGNOSTIC calls=${count.get()} auth=${authorized.get()} events="+evaluate("JSON.stringify({clicks:window.__fetchClicks,last:window.__lastRequest})")+" snapshot=${rule.activity.settingsUi}");throw error }
                assertEquals(n+1,count.get());assertEquals(n+1,authorized.get())
                val overlay=rule.activity.settingsUi.optJSONArray("overlays").objects().last()
                evaluate("window.dispatchEvent(new CustomEvent('dsh-material-command',{detail:{action:'settingsControl',id:'${overlay.optString("id")}',operation:'dismiss'}}))")
                rule.waitUntil(10_000){rule.activity.settingsUi.optJSONArray("overlays").objects().isEmpty()}
            }
            assertFalse(rule.activity.settingsUi.toString().contains("android-fixture-key"))
            // No Save: endpoint, key and candidate models remain an unsaved draft.
        } finally {server.close();worker.join(1000)}
    }
}
