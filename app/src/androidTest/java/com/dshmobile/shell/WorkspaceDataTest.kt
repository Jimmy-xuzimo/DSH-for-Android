package com.dshmobile.shell

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class WorkspaceDataTest {
    @Test fun exportsExcludeCredentialsAndImportsCannotOverwriteOrEscape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WorkspaceStore(context)
        val previous = store.current()
        val created = mutableListOf<File>()
        val archive = File(context.cacheDir, "workspace-export-test.zip")
        val malicious = File(context.cacheDir, "workspace-invalid-test.zip")
        try {
            val project = store.create("Test-${UUID.randomUUID()}").also { created += it }
            File(project, "visible.txt").writeText("中文 workspace roundtrip")
            File(project, ".env").writeText("API_KEY=must-not-export")
            File(project, ".npmrc").writeText("//example.invalid/:_authToken=must-not-export")
            File(project, ".ssh").mkdir()
            File(project, ".ssh/id_ed25519").writeText("must-not-export")
            java.nio.file.Files.createSymbolicLink(File(project, "linked-secret").toPath(), File(project, ".env").toPath())
            store.exportZip(project, Uri.fromFile(archive))
            ZipFile(archive).use { zip -> assertEquals(listOf("visible.txt"), zip.entries().asSequence().map { it.name }.toList()) }
            val first = store.importZip(Uri.fromFile(archive)).also { created += it }
            val second = store.importZip(Uri.fromFile(archive)).also { created += it }
            assertNotEquals(first.name, second.name)
            assertEquals("中文 workspace roundtrip", File(first, "visible.txt").readText())
            assertEquals("中文 workspace roundtrip", File(second, "visible.txt").readText())
            ZipOutputStream(malicious.outputStream()).use { zip -> zip.putNextEntry(ZipEntry("../outside-test.txt")); zip.write(byteArrayOf(1)); zip.closeEntry() }
            val rejected = assertThrows(Exception::class.java) { store.importZip(Uri.fromFile(malicious)) }
            assertTrue(rejected.toString(), rejected is IllegalArgumentException || rejected is java.util.zip.ZipException)
            assertFalse(File(store.root, "outside-test.txt").exists())
            assertEquals(second, store.current())
        } finally {
            store.select(previous)
            created.forEach { it.deleteRecursively() }; archive.delete(); malicious.delete()
        }
    }
}
