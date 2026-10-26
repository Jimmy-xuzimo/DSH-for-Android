package com.dshmobile.shell

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ArchivePolicyTest {
    @Test fun archiveCannotEscapeWorkspace() {
        val root = File(System.getProperty("java.io.tmpdir"), "workspace-policy")
        listOf("../outside", "/absolute", "dir/../../outside", "a\\..\\outside").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { RuntimeInstaller.safeChild(root, name) }
        }
        assertEquals(File(root, "project/file.txt").canonicalFile, RuntimeInstaller.safeChild(root, "project/file.txt"))
    }
    @Test fun defaultExportOmitsCommonSecretFiles() {
        listOf(".env", ".env.production", ".credentials.yaml", "id.key", "private.pem", "release.jks", ".npmrc", ".netrc", "id_rsa", "auth.json", "secret.json", "release.p12").forEach {
            assertFalse(it, WorkspaceStore.exportable(it))
        }
        assertTrue(WorkspaceStore.exportable("Main.kt"))
    }
}
