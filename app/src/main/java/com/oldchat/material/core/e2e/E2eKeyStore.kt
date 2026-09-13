package com.oldchat.material.core.e2e

import android.content.Context
import android.util.Base64

/**
 * 对端共享密钥的本地持久化。
 *
 * 对应 enigmaj 的 `config.json → shared_secrets: { "<对端UID>": "<base64 32B>" }`：
 * 同样是「每对端 32 字节 SS 的 base64」，同样在握手成功后写入。
 * （enigmaj 只在正常退出时写回；这里落盘即时，行为更安全，语义一致。）
 */
class E2eKeyStore(context: Context) {

    private val prefs = context.getSharedPreferences("e2e_secrets", Context.MODE_PRIVATE)

    fun get(peerUid: String): ByteArray? {
        val raw = prefs.getString(peerUid, null) ?: return null
        return runCatching { Base64.decode(raw, Base64.NO_WRAP) }.getOrNull()
    }

    fun put(peerUid: String, sharedSecret: ByteArray) {
        prefs.edit()
            .putString(peerUid, Base64.encodeToString(sharedSecret, Base64.NO_WRAP))
            .apply()
    }

    fun remove(peerUid: String) {
        prefs.edit().remove(peerUid).apply()
    }

    /** 与 enigmaj 一致：只保存过的对端才显示指纹。 */
    fun fingerprintOf(peerUid: String): String? = get(peerUid)?.let { E2eFingerprint.of(it) }
}
