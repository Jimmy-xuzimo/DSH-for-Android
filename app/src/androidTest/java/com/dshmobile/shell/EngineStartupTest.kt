package com.dshmobile.shell

import android.content.Intent
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EngineStartupTest {
    @Test fun packagedDshStartsAndRendersWithAuthenticatedTransport() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("dsh", android.content.Context.MODE_PRIVATE).edit().putBoolean("engineRequested", true).commit()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(300)
            while (EngineService.state.value?.phase !in setOf("running", "error") && System.nanoTime() < end) Thread.sleep(200)
            val state = requireNotNull(EngineService.state.value)
            assertEquals(state.message ?: "Engine did not become ready", "running", state.phase)
            val connection = URL(requireNotNull(state.origin)).openConnection() as HttpURLConnection
            connection.connectTimeout = 3000; connection.readTimeout = 3000
            try { assertEquals(403, connection.responseCode) } finally { connection.disconnect() }
            var result = ""
            repeat(60) {
                if (result == "true") return@repeat
                val ready = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    val web = findWebView(activity.window.decorView)
                    assertNotNull(web)
                    web!!.evaluateJavascript("Boolean(document.getElementById('root')?.childElementCount && document.querySelector('button'))") {
                        result = it; ready.countDown()
                    }
                }
                assertTrue("WebView callback stalled", ready.await(20, TimeUnit.SECONDS))
                if (result != "true") Thread.sleep(500)
            }
            assertEquals("Upstream UI did not mount", "true", result)
            instrumentation.runOnMainSync {
                EngineService.restart(context)
                EngineService.restart(context) // repeated requests share one restart owner
            }
            val restarted = System.nanoTime() + TimeUnit.SECONDS.toNanos(90)
            while (EngineService.state.value?.phase !in setOf("running", "error") && System.nanoTime() < restarted) Thread.sleep(100)
            assertEquals("Engine restart failed", "running", EngineService.state.value?.phase)
            assertTrue("Restart reused its transport token", EngineService.state.value?.token != state.token)
        } finally {
            context.stopService(Intent(context, EngineService::class.java))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
