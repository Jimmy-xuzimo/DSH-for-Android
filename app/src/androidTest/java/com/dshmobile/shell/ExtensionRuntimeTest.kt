package com.dshmobile.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExtensionRuntimeTest {
    @Test fun compatibleJavaScriptPluginAndBothMcpTransports() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val runtime = RuntimeInstaller(context).install()
        val directory = File(context.cacheDir, "extension-fixture").apply { mkdirs() }
        val fixture = File(directory, "extensions.mjs")
        instrumentation.context.assets.open("extensions.mjs").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        val native = context.applicationInfo.nativeLibraryDir
        val builder = ProcessBuilder(File(native, "libdsh_node.so").path, "--import", File(runtime, "android/native-loader.mjs").path, fixture.path)
            .directory(directory).redirectErrorStream(true)
        builder.environment().apply {
            put("DSH_ANDROID_NATIVE_DIR", native); put("DSH_ANDROID_RUNTIME", runtime.path)
            put("DSH_ANDROID_COMMANDS", "1"); put("DSH_ANDROID_TOKEN", "a".repeat(64)); put("DSH_ANDROID_DATA_KEY", "test-secret")
            put("LD_LIBRARY_PATH", native); put("HOME", directory.path); put("TMPDIR", context.cacheDir.path)
            put("PATH", File(runtime, "bin").path + ":/system/bin"); put("SHELL", File(native, "libdsh_bash.so").path)
        }
        // Resolve dependencies through the installed closure, independent of fixture location.
        val entry = File(runtime, "android/extensions-test.mjs")
        fixture.copyTo(entry, overwrite = true)
        builder.command()[builder.command().lastIndex] = entry.path
        val executor = Executors.newSingleThreadExecutor()
        val process = builder.start()
        val output = executor.submit<String> { process.inputStream.bufferedReader().readText() }
        try {
            assertTrue("Extension runtime timed out", process.waitFor(45, TimeUnit.SECONDS))
            val text = output.get(5, TimeUnit.SECONDS)
            assertEquals(text, 0, process.exitValue())
            assertTrue(text, text.contains("JS_PLUGIN_OK") && text.contains("STDIO_MCP_OK") && text.contains("HTTP_MCP_OK"))
        } finally { process.destroyForcibly(); executor.shutdownNow(); entry.delete(); directory.deleteRecursively() }
    }
}
