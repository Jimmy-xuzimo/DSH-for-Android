package com.dshmobile.shell

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class WorkspaceStore(private val context: Context) {
    val root = File(context.filesDir, "workspaces").apply { mkdirs() }
    fun list(): List<File> = root.listFiles()?.filter {
        it.isDirectory && !it.name.startsWith('.') && !java.nio.file.Files.isSymbolicLink(it.toPath()) && it.canonicalFile.parentFile == root.canonicalFile
    }?.sortedBy { it.name }.orEmpty()
    fun create(name: String): File {
        require(name.matches(Regex("[\\p{L}\\p{N} _.-]{1,64}")) && !name.startsWith('.')) { "Invalid project name" }
        return File(root, name).also { check(it.mkdir()) { "Project already exists" } }
    }
    fun current(): File {
        val name = context.getSharedPreferences("dsh", Context.MODE_PRIVATE).getString("workspace", null)
        return list().find { it.name == name } ?: list().firstOrNull() ?: create("Projects")
    }
    fun select(file: File) {
        require(file.canonicalFile.parentFile == root.canonicalFile && file.isDirectory)
        context.getSharedPreferences("dsh", Context.MODE_PRIVATE).edit().putString("workspace", file.name).apply()
    }
    fun cloneRepository(url: String, name: String): File {
        val uri = java.net.URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) { "Use an HTTPS URL without embedded credentials" }
        val destination = create(name)
        val runtime = RuntimeInstaller(context).install()
        val native = File(context.applicationInfo.nativeLibraryDir)
        val git = File(native, "libdsh_git.so")
        try {
            val builder = ProcessBuilder(git.path, "-c", "core.hooksPath=/dev/null", "clone", "--", url, destination.path).redirectErrorStream(true)
            builder.environment().apply {
                put("HOME", File(context.filesDir, "home").path)
                put("GIT_SSL_CAINFO", File(runtime, "android/cacert.pem").path)
                put("GIT_EXEC_PATH", File(runtime, "bin").path)
                put("GIT_TERMINAL_PROMPT", "0")
                put("PATH", File(runtime, "bin").path + ":/system/bin")
                put("LD_LIBRARY_PATH", native.path)
            }
            val child = builder.start()
            val output = StringBuilder()
            val reader = Thread { child.inputStream.bufferedReader().useLines { lines -> lines.forEach { if (output.length < 32768) output.appendLine(it) } } }.apply { start() }
            if (!child.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)) { child.destroyForcibly(); throw IllegalStateException("Git clone timed out") }
            reader.join(3000)
            check(child.exitValue() == 0) { "Git clone failed (${child.exitValue()}): ${output.takeLast(2048)}" }
            select(destination)
            return destination
        } catch (error: Exception) {
            destination.deleteRecursively()
            throw error
        }
    }
    fun importZip(uri: Uri): File {
        val staging = File(root, ".import-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            var total = 0L
            var count = 0
            val seen = mutableSetOf<String>()
            context.contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream)
                ZipInputStream(stream).use { archive ->
                    while (true) {
                        val entry = archive.nextEntry ?: break
                        require(++count <= 100000) { "Too many files" }
                        require(seen.add(entry.name)) { "Duplicate ZIP entry" }
                        val target = RuntimeInstaller.safeChild(staging, entry.name)
                        if (entry.isDirectory) { check(target.isDirectory || target.mkdirs()); continue }
                        check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                        target.outputStream().use { out ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                val read = archive.read(buffer)
                                if (read < 0) break
                                total += read
                                require(total <= 1024L * 1024 * 1024) { "Project exceeds 1 GiB import limit" }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            require(count > 0) { "Empty ZIP" }
            val destination = File(root, "Imported-${System.currentTimeMillis()}-${UUID.randomUUID()}")
            check(staging.renameTo(destination))
            select(destination)
            return destination
        } catch (error: Exception) { staging.deleteRecursively(); throw error }
    }
    fun exportZip(project: File, uri: Uri) {
        require(project.canonicalFile.parentFile == root.canonicalFile)
        context.contentResolver.openOutputStream(uri, "wt").use { stream ->
            requireNotNull(stream)
            ZipOutputStream(stream).use { archive ->
                project.walkTopDown().onEnter { dir ->
                    !java.nio.file.Files.isSymbolicLink(dir.toPath()) && dir.name !in setOf(".git", ".dsh", "node_modules", ".ssh", ".aws", ".gnupg", ".azure")
                }.filter { it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { file ->
                    if (!exportable(file.name)) return@forEach
                    archive.putNextEntry(ZipEntry(file.relativeTo(project).invariantSeparatorsPath))
                    file.inputStream().use { it.copyTo(archive) }
                    archive.closeEntry()
                }
            }
        }
    }
    companion object {
        fun exportable(name: String): Boolean = !name.startsWith(".env") &&
            name.lowercase() !in setOf(".npmrc", ".netrc", ".pypirc", "id_rsa", "id_ecdsa", "id_ed25519", "auth.json") &&
            !name.contains("credentials", true) && !name.contains("secret", true) &&
            listOf(".pem", ".key", ".jks", ".keystore", ".p12", ".pfx").none { name.endsWith(it, true) }
    }
}
