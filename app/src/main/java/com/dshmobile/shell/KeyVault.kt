package com.dshmobile.shell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps the runtime data key with a non-exportable Android Keystore key. */
class KeyVault(private val context: Context) {
    @Synchronized fun runtimeKey(): String {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "dsh-runtime-v1"
        if (!store.containsAlias(alias)) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                generateKey()
            }
        }
        val key = store.getKey(alias, null) as SecretKey
        val file = AtomicFile(File(context.noBackupFilesDir, "runtime-key.wrapped"))
        val plaintext: ByteArray
        if (file.baseFile.exists()) {
            val stored = file.readFully()
            require(stored.size >= 12 + 16)
            plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, stored.copyOfRange(0, 12)))
                doFinal(stored.copyOfRange(12, stored.size))
            }
        } else {
            plaintext = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
            val output = file.startWrite()
            try { output.write(cipher.iv + cipher.doFinal(plaintext)); file.finishWrite(output) }
            catch (error: Exception) { file.failWrite(output); throw error }
        }
        require(plaintext.size == 32)
        return Base64.encodeToString(plaintext, Base64.NO_WRAP).also { plaintext.fill(0) }
    }
}
