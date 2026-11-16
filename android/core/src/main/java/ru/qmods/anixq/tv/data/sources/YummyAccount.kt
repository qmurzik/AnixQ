package ru.qmods.anixq.tv.data.sources

import android.content.Context
import java.io.File
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Personal bearer token, encrypted by Android Keystore; never included in Gist, room or backups. */
object YummyAccount {
    private var tokenFile: File? = null
    val signedIn get() = !AnimeSources.yummy.userToken.isNullOrBlank()
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("anixq.yummy", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("anixq.yummy", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun init(context: Context, name: String) {
        val profileKey = java.security.MessageDigest.getInstance("SHA-256").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
        tokenFile = File(context.noBackupFilesDir, "yummy-$profileKey.session")
        val receipts = context.getSharedPreferences("yummy.application-receipts", Context.MODE_PRIVATE)
        AnimeSources.yummy.applicationReceipts = object : YummyApplicationReceipts {
            override fun get(ownerId: Int) = receipts.getInt("owner.$ownerId", 0).takeIf { it > 0 }
            override fun save(ownerId: Int, applicationId: Int) { check(receipts.edit().putInt("owner.$ownerId", applicationId).commit()) }
        }
        val saved = tokenFile?.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }
        val token = saved?.let { encoded -> runCatching {
            val parts = encoded.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull() }
        AnimeSources.yummy.restoreUserToken(token, tokenFile?.lastModified() ?: 0L)
        AnimeSources.yummy.onTokenChanged = ::save
    }
    private fun save(token: String?) {
        val storage = tokenFile ?: return
        if (token == null) { storage.delete(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val temporary = File(storage.path + ".tmp")
        temporary.writeText(Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP))
        check(temporary.renameTo(storage)) { "Не удалось сохранить сессию YummyAnime" }
    }
    suspend fun login(email: String, password: String) {
        val destination = tokenFile
        val token = AnimeSources.yummy.login(email.trim(), password)
        check(destination === tokenFile) { "Профиль изменился; повторите вход" }
        completeLogin(token, destination)
    }
    private suspend fun completeLogin(token: String, destination: File?) {
        val connection = AnimeSources.yummy.connectApplication(token)
        check(destination === tokenFile) { "Профиль изменился; повторите вход" }
        save(token)
        ru.qmods.anixq.tv.data.Store.setYummyApplication(connection.publicToken, connection.managedOwnerId)
        AnimeSources.yummy.userToken = token
    }
    /** Allows only the user's own token, verified against the official profile API before storing. */
    suspend fun usePersonalToken(token: String) {
        val destination = tokenFile
        completeLogin(token.trim(), destination)
    }
    suspend fun logout() {
        try { AnimeSources.yummy.logout() } finally {
            save(null); AnimeSources.yummy.userToken = null
            if (AnimeSources.yummy.managedApplicationOwner != null) ru.qmods.anixq.tv.data.Store.setYummyApplication(null, null)
        }
    }
}
