package com.dshmobile.shell

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** The APK owns executable code; only checksummed data is extracted here. */
class RuntimeInstaller(private val context: Context) {
    fun install(): File = synchronized(installationLock) {
        val manifestBytes = context.assets.open("runtime-manifest.json").use { it.readBytes() }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8))
        val fingerprint = sha256(manifestBytes)
        val root = File(context.filesDir, "runtimes").apply { mkdirs() }
        val destination = File(root, fingerprint)
        if (File(destination, ".complete").readTextOrNull() == fingerprint) { prepareCommands(destination); return destination }
        val staging = File(root, "$fingerprint.pending")
        check(!staging.exists() || staging.deleteRecursively()) { "Cannot remove incomplete runtime" }
        check(staging.mkdirs())
        try {
            val entries = manifest.getJSONObject("files")
            val expected = entries.keys().asSequence().toSet()
            val extracted = mutableSetOf<String>()
            var total = 0L
            val maximum = manifest.getLong("unpackedBytes")
            val buffer = ByteArray(65536)
            context.assets.open("runtime.zip").use { source ->
                ZipInputStream(source).use { archive ->
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Runtime initialization interrupted" }
                        val entry = archive.nextEntry ?: break
                        if (entry.isDirectory) continue
                        require(entry.name in expected && extracted.add(entry.name)) { "Unexpected runtime entry" }
                        val target = safeChild(staging, entry.name)
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        val digest = MessageDigest.getInstance("SHA-256")
                        target.outputStream().use { output ->
                            while (true) {
                                val count = archive.read(buffer)
                                if (count < 0) break
                                total += count
                                check(total <= maximum) { "Runtime exceeds declared size" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                        require(hex(digest.digest()) == entries.getString(entry.name)) { "Runtime integrity check failed" }
                    }
                }
            }
            require(extracted == expected && total == maximum) { "Incomplete runtime" }
            val links = manifest.getJSONObject("links")
            links.keys().forEach { name ->
                val link = safeChild(staging, name)
                val value = links.getString(name)
                require(!File(value).isAbsolute)
                val target = File(link.parentFile, value).canonicalFile
                require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Runtime link escapes destination" }
                check(link.parentFile!!.isDirectory || link.parentFile!!.mkdirs())
                java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(value))
            }
            File(staging, ".complete").writeText(fingerprint)
            if (destination.exists()) check(destination.deleteRecursively())
            check(staging.renameTo(destination)) { "Cannot activate runtime" }
            prepareCommands(destination)
            File(root, "last-initialization-error.txt").delete()
            return destination
        } catch (error: Exception) {
            // Keep the failed staging state until an explicit retry. User home
            // and workspaces are outside this tree and are never replaced.
            File(root, "last-initialization-error.txt").writeText(
                "${error.javaClass.simpleName}: ${error.message.orEmpty().take(1024)}")
            throw error
        }
    }

    private fun prepareCommands(runtime: File) {
        val native = File(context.applicationInfo.nativeLibraryDir)
        val commands = JSONObject(File(runtime, "android/native-map.json").readText()).getJSONObject("commands")
        val bin = File(runtime, "bin").apply { check(isDirectory || mkdirs()) }
        commands.keys().forEach { command ->
            require(command.matches(Regex("[a-z0-9-]+")))
            val filename = commands.getString(command)
            require(filename.matches(Regex("lib[a-zA-Z0-9_+.-]+\\.so")))
            val target = File(native, filename).toPath()
            check(java.nio.file.Files.isExecutable(target)) { "Missing installed executable: $command" }
            val link = File(bin, command).toPath()
            if (java.nio.file.Files.isSymbolicLink(link) && java.nio.file.Files.readSymbolicLink(link) == target) return@forEach
            check(!java.nio.file.Files.exists(link) || java.nio.file.Files.isSymbolicLink(link)) { "Unexpected command entry: $command" }
            val temporary = File(bin, "$command.${java.util.UUID.randomUUID()}.pending").toPath()
            try {
                java.nio.file.Files.createSymbolicLink(temporary, target)
                java.nio.file.Files.move(temporary, link, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { java.nio.file.Files.deleteIfExists(temporary) }
        }
    }

    companion object {
        private val installationLock = Any()
        fun safeChild(root: File, name: String): File {
            require(name.isNotEmpty() && !name.contains('\\') && !name.startsWith('/')) { "Invalid archive path" }
            val canonicalRoot = root.canonicalFile
            val child = File(canonicalRoot, name).canonicalFile
            require(child.path.startsWith(canonicalRoot.path + File.separator)) { "Archive path escapes destination" }
            return child
        }
        fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            val alphabet = "0123456789abcdef"
            for (byte in bytes) { val value = byte.toInt() and 255; append(alphabet[value ushr 4]); append(alphabet[value and 15]) }
        }
        private fun File.readTextOrNull(): String? = if (isFile) readText() else null
    }
}
