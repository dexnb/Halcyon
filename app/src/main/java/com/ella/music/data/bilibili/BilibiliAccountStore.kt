package com.ella.music.data.bilibili

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class BilibiliAccount(
    val cookie: String = "",
    val mid: Long = 0,
    val name: String = "",
    val face: String = ""
) {
    val loggedIn get() = mid > 0 && bilibiliCookies(cookie)["SESSDATA"].orEmpty().isNotBlank()
    override fun toString() = "BilibiliAccount(mid=$mid, loggedIn=$loggedIn)"
}

/** Account secrets are Keystore-encrypted, outside Android backup and app preference exports. */
class BilibiliAccountStore private constructor(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "bilibili_account.enc"))
    private val mutable = MutableStateFlow(read())
    val account = mutable.asStateFlow()

    @Synchronized fun save(account: BilibiliAccount) {
        val plain = JSONObject().put("cookie", account.cookie).put("mid", account.mid)
            .put("name", account.name).put("face", account.face).toString().toByteArray()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val stream = file.startWrite()
        try { stream.write(cipher.iv + cipher.doFinal(plain)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
        mutable.value = account
    }

    @Synchronized fun clear() { file.delete(); mutable.value = BilibiliAccount() }

    private fun read(): BilibiliAccount = runCatching {
        val data = file.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        val json = JSONObject(cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8))
        BilibiliAccount(json.optString("cookie"), json.optLong("mid"), json.optString("name"), json.optString("face"))
    }.getOrDefault(BilibiliAccount())

    private fun key(): SecretKey {
        val name = "halcyon_bilibili_account_v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(name, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(name, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    companion object {
        @Volatile private var instance: BilibiliAccountStore? = null
        fun getInstance(context: Context): BilibiliAccountStore = instance ?: synchronized(this) {
            instance ?: BilibiliAccountStore(context.applicationContext).also { instance = it }
        }
    }
}

internal fun bilibiliCookies(cookie: String): Map<String, String> = cookie.split(';').mapNotNull {
    val pair = it.trim().split('=', limit = 2)
    if (pair.size == 2 && pair[0] in setOf("SESSDATA", "bili_jct", "DedeUserID", "DedeUserID__ckMd5", "sid", "buvid3"))
        pair[0] to pair[1].trim() else null
}.toMap()
