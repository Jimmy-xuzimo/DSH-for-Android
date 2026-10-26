package com.dshmobile.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class EngineState(val phase: String = "stopped", val origin: String? = null, val token: String? = null, val message: String? = null)

class EngineService : Service() {
    private var task: java.util.concurrent.Future<*>? = null
    private var active = false
    private var process: Process? = null
    @Volatile private var stopping = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        owner = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("engine", getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stop(this); return START_NOT_STICKY }
        if (active) return START_NOT_STICKY
        active = true
        stopping = false
        getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean("engineRequested", true).apply()
        startForeground(1, notification())
        state.value = EngineState("starting")
        task = engineWorker.submit { startEngine() }
        return START_NOT_STICKY
    }
    private fun startEngine() {
        try {
            val runtime = RuntimeInstaller(this).install()
            if (stopping) return
            val native = File(applicationInfo.nativeLibraryDir)
            val node = File(native, "libdsh_node.so")
            check(node.canExecute()) { getString(R.string.missing_runtime) }
            val data = File(filesDir, "home").apply { mkdirs() }
            val project = WorkspaceStore(this).current()
            val preferences = getSharedPreferences("dsh", MODE_PRIVATE)
            val requestedPort = preferences.getInt("enginePort", 0).takeIf { it in 1024..65535 } ?: 0
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            val command = listOf(node.path, "--expose-internals", "--import", File(runtime, "android/preload.mjs").path,
                File(runtime, "node_modules/@deepseek-ai/dsh/lib/bin.js").path,
                "--profile", "web",
                "--patch", File(runtime, "android/cordis.patch.yml").path,
                "--no-open", "--host", "127.0.0.1", "--port", requestedPort.toString())
            val builder = ProcessBuilder(command).directory(project).redirectErrorStream(true)
            builder.environment().apply {
                put("HOME", data.path); put("DSH_HOME", File(data, ".dsh").path)
                put("TMPDIR", cacheDir.path); put("DSH_ANDROID_NATIVE_DIR", native.path)
                put("LANG", "C.UTF-8"); put("LC_ALL", "C.UTF-8")
                put("DSH_ANDROID_TOKEN", token); put("DSH_ANDROID_RUNTIME", runtime.path)
                put("DSH_ANDROID_DATA_KEY", KeyVault(this@EngineService).runtimeKey())
                put("DSH_ANDROID_COMMANDS", if (getSharedPreferences("dsh", MODE_PRIVATE).getBoolean("commands", false)) "1" else "0")
                put("PATH", File(runtime, "bin").path + ":/system/bin")
                put("SHELL", File(native, "libdsh_bash.so").path)
                put("LD_LIBRARY_PATH", native.path)
                put("NODE_EXTRA_CA_CERTS", File(runtime, "android/cacert.pem").path)
                put("GIT_SSL_CAINFO", File(runtime, "android/cacert.pem").path)
                put("CURL_CA_BUNDLE", File(runtime, "android/cacert.pem").path)
                put("GIT_EXEC_PATH", File(runtime, "bin").path)
            }
            if (stopping) return
            val child = builder.start()
            synchronized(this) {
                process = child
                if (stopping) { child.destroy(); return }
            }
            val log = File(cacheDir, "engine.log")
            if (log.length() > 2 * 1024 * 1024) log.writeText("")
            val started = System.nanoTime()
            var portBusy = false
            val watchdog = Thread {
              try {
                while (!stopping && child.isAlive && state.value?.phase == "starting") {
                    if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(90)) {
                        publish(EngineState("error", message = "Engine startup timed out"))
                        child.destroyForcibly()
                        break
                    }
                    Thread.sleep(250)
                }
              } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            }.apply { isDaemon = true; start() }
            child.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.startsWith("DSH_ANDROID_READY ")) {
                        val port = line.substringAfter(' ').toIntOrNull()
                        require(port != null && port in 1..65535)
                        val origin = "http://127.0.0.1:$port"
                        if (healthy(origin, token) && !stopping) {
                            preferences.edit().putInt("enginePort", port).apply()
                            publish(EngineState("running", origin, token))
                        }
                    } else {
                        if (line.contains("EADDRINUSE")) portBusy = true
                        val redacted = line.replace(token, "[redacted]")
                            .replace(Regex("(?i)(bearer\\s+|api[_-]?key[=: ]+)[^\\s,]+"), "$1[redacted]")
                        if (log.length() < 2 * 1024 * 1024) log.appendText(redacted.take(8192) + "\n")
                    }
                }
            }
            val exit = child.waitFor()
            watchdog.interrupt()
            if (!stopping && state.value?.phase != "error") {
                if (portBusy) preferences.edit().remove("enginePort").apply()
                publish(EngineState("error", message = if (portBusy) getString(R.string.port_busy) else "Engine exited ($exit). See app diagnostics."))
            }
        } catch (error: Exception) {
            if (!stopping) publish(EngineState("error", message = error.message ?: error.javaClass.simpleName))
        } finally {
            val child = synchronized(this) { process.also { process = null } }
            if (child != null) terminate(child)
            main.post { if (owner === this) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }
        }
    }
    private fun publish(next: EngineState) {
        main.post { if (owner === this && !stopping) state.value = next }
    }
    private fun terminate(child: Process) {
        var interrupted = Thread.interrupted()
        child.destroy()
        try { if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly() }
        catch (_: InterruptedException) { interrupted = true; child.destroyForcibly() }
        while (child.isAlive) {
            try { child.waitFor() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
    private fun healthy(origin: String, token: String): Boolean {
        var diagnostic = "no response"
        repeat(100) {
            if (stopping) return false
            try {
                val connection = URL("$origin/?token=$token").openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 1000; connection.readTimeout = 1000
                    // The upstream token exchange sets its browser cookie then redirects.
                    // HttpURLConnection has no WebView cookie jar; do not follow that redirect.
                    connection.instanceFollowRedirects = false
                    connection.setRequestProperty("Cookie", "dsh_android=$token")
                    val status = connection.responseCode
                    diagnostic = "HTTP $status"
                    if (status == 200 || (status in setOf(302, 303) && connection.getHeaderField("Set-Cookie") != null)) return true
                } finally { connection.disconnect() }
            } catch (error: java.io.IOException) { diagnostic = error.javaClass.simpleName }
            Thread.sleep(200)
        }
        throw IllegalStateException("Engine health check failed ($diagnostic)")
    }
    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "engine").setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name)).setContentText(getString(R.string.engine_running))
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build()
    }
    override fun onDestroy() {
        stopping = true
        if (owner === this && state.value?.phase != "error") state.value = EngineState("stopping")
        synchronized(this) { process?.destroy() }
        task?.cancel(true)
        val child = synchronized(this) { process }
        if (child != null) Thread { terminate(child) }.apply { isDaemon = true; start() }
        // A process-wide serial queue prevents a replacement Service from
        // starting its Node before the previous owner's process has exited.
        engineWorker.execute {
            main.post {
                if (owner === this) {
                    if (state.value?.phase != "error") state.value = EngineState()
                    owner = null
                }
            }
        }
        super.onDestroy()
    }
    companion object {
        const val STOP = "com.dshmobile.shell.STOP"
        val state = MutableLiveData(EngineState())
        private val engineWorker = Executors.newSingleThreadExecutor()
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        @Volatile private var owner: EngineService? = null
        private var restartObserver: androidx.lifecycle.Observer<EngineState>? = null

        fun stop(context: android.content.Context) {
            restartObserver?.let { state.removeObserver(it) }; restartObserver = null
            context.getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean("engineRequested", false).apply()
            state.value = EngineState("stopping")
            if (!context.stopService(Intent(context, EngineService::class.java)) && owner == null) state.value = EngineState()
        }

        /** Called on main. Restart ownership survives Activity recreation. */
        fun restart(context: android.content.Context) {
            if (restartObserver != null) return
            val app = context.applicationContext
            app.getSharedPreferences("dsh", MODE_PRIVATE).edit().putBoolean("engineRequested", true).apply()
            fun launch() {
                try { app.startForegroundService(Intent(app, EngineService::class.java)) }
                catch (_: Exception) { state.value = EngineState("error", message = app.getString(R.string.restart_restricted)) }
            }
            state.value = EngineState("stopping")
            val observer = object : androidx.lifecycle.Observer<EngineState> {
                override fun onChanged(value: EngineState) {
                    if (value.phase == "stopped" || value.phase == "error") {
                        state.removeObserver(this); restartObserver = null; launch()
                    }
                }
            }
            restartObserver = observer
            state.observeForever(observer)
            if (!app.stopService(Intent(app, EngineService::class.java))) {
                state.removeObserver(observer); restartObserver = null; launch()
            }
        }
    }
}
