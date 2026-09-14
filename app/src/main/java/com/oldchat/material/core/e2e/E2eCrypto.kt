package com.oldchat.material.core.e2e

import android.util.Base64
import android.util.Log
import org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator
import org.bouncycastle.crypto.kems.MLKEMExtractor
import org.bouncycastle.crypto.kems.MLKEMGenerator
import org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMPublicKeyParameters
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * enigmaj 的线上帧格式（来自 shared/enigmaj-re/REPORT.md §7.2 / §8，100% 实测）：
 *
 * - `PQC_BEGIN\n<base64 ek>`  发起方公钥（ML-KEM-768 封装公钥 1184 B → 1580 字符 base64）
 * - `PQC_REPLY\n<base64 ct>`  响应方密文（ML-KEM-768 密文 1088 B → 1452 字符 base64）
 * - `ENC\n<base64 payload>`   AEAD 载荷，payload = nonce(12) ‖ ciphertext ‖ tag(16)
 * - 其它                       明文
 *
 * 本客户端**逐字对齐**这套前缀与载荷布局，因此两侧对同一帧的判定完全一致。
 */
object E2eFrame {
    const val PQC_BEGIN = "PQC_BEGIN\n"
    const val PQC_REPLY = "PQC_REPLY\n"
    const val ENC = "ENC\n"

    /** enigmaj 的 `密文长度不足` 阈值：12 nonce + 16 tag = 28 B */
    const val MIN_PAYLOAD = 28

    enum class Kind { PQC_BEGIN, PQC_REPLY, ENC, PLAIN }

    fun kind(body: String): Kind = when {
        body.startsWith(PQC_BEGIN) -> Kind.PQC_BEGIN
        body.startsWith(PQC_REPLY) -> Kind.PQC_REPLY
        body.startsWith(ENC) -> Kind.ENC
        else -> Kind.PLAIN
    }

    fun isE2e(body: String): Boolean = kind(body) != Kind.PLAIN

    fun payloadOf(body: String, kind: Kind): String = when (kind) {
        Kind.PQC_BEGIN -> body.removePrefix(PQC_BEGIN)
        Kind.PQC_REPLY -> body.removePrefix(PQC_REPLY)
        Kind.ENC -> body.removePrefix(ENC)
        Kind.PLAIN -> body
    }

    fun encode(kind: Kind, payload: ByteArray): String =
        when (kind) {
            Kind.PQC_BEGIN -> PQC_BEGIN
            Kind.PQC_REPLY -> PQC_REPLY
            Kind.ENC -> ENC
            Kind.PLAIN -> ""
        } + Base64.encodeToString(payload, Base64.NO_WRAP)
}

/** KEM 抽象。enigmaj 用 ML-KEM-768；实现见 MlKem768Kem / EcdhP256Kem。 */
interface E2eKem {
    /** 展示用算法名 */
    val id: String

    /** 本实现的公钥字节长度（用于接收端自动识别对端用的是哪种 KEM） */
    val publicKeySize: Int

    fun generateKeyPair(): E2eKeyPair

    /** 发起方：用对端公钥封装，得到密文与 32 B 共享密钥 */
    fun encapsulate(peerPublicKey: ByteArray): E2eEncapsulation?

    /** 响应方：用自己的私钥解封装 */
    fun decapsulate(ciphertext: ByteArray, privateKey: ByteArray): ByteArray?
}

data class E2eKeyPair(val publicKey: ByteArray, val privateKey: ByteArray)

data class E2eEncapsulation(val ciphertext: ByteArray, val sharedSecret: ByteArray)

/**
 * ML-KEM-768（FIPS 203），与 enigmaj 完全一致的 KEM。
 *
 * Android 没有内置 ML-KEM，这里用 BouncyCastle `bcprov-jdk18on`。
 *
 * ⚠️ 关键事实（2026-09-14 用 class 文件解析核对，网上说法有误）：
 *   - **1.78.1 里没有 ML-KEM**，只有老的 `pqc.crypto.crystals.kyber`（经典 Kyber，非 FIPS 203）；
 *   - 有 ML-KEM 的版本里，它**不在** `org.bouncycastle.pqc.crypto.mlkem` 下，而是：
 *       org.bouncycastle.crypto.params.MLKEMParameters        （静态字段 ml_kem_512/768/1024）
 *       org.bouncycastle.crypto.params.MLKEMKeyGenerationParameters(SecureRandom, MLKEMParameters)
 *       org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator（init(KeyGenerationParameters) / generateKeyPair()）
 *       org.bouncycastle.crypto.kems.MLKEMGenerator(SecureRandom).generateEncapsulated(AsymmetricKeyParameter)
 *       org.bouncycastle.crypto.kems.MLKEMExtractor(MLKEMPrivateKeyParameters).extractSecret(byte[])
 *     → 因此依赖已升到 **1.86**，这里改为**直接引用**（编译期校验 + R8 可见，不再靠反射）。
 *
 * 仍保留 [available] 探测：万一依赖被裁掉/移除，上层会自动降级 ECDH P-256，而不是崩。
 * 帧格式与 SS 长度与 enigmaj 完全一致（ek 1184 B、ct 1088 B、SS 32 B）。
 */
class MlKem768Kem : E2eKem {

    override val id = "ML-KEM-768"
    override val publicKeySize = 1184

    /**
     * 依赖是否可用（BC 被移除/被裁掉时上层降级）。用类探测而不是直接 try：
     * 直接引用只在这几个方法里出现，探测失败时不会走到它们，因此不会 NoClassDefFoundError。
     */
    val available: Boolean = runCatching {
        Class.forName("org.bouncycastle.crypto.params.MLKEMParameters")
        Class.forName("org.bouncycastle.crypto.generators.MLKEMKeyPairGenerator")
        Class.forName("org.bouncycastle.crypto.kems.MLKEMGenerator")
        Class.forName("org.bouncycastle.crypto.kems.MLKEMExtractor")
        true
    }.getOrElse { e ->
        Log.w(TAG, "BouncyCastle ML-KEM 不可用，将降级 ECDH P-256：${e.javaClass.simpleName} ${e.message}")
        false
    }

    override fun generateKeyPair(): E2eKeyPair {
        if (!available) return E2eKeyPair(ByteArray(0), ByteArray(0))
        return runCatching {
            val kpg = MLKEMKeyPairGenerator()
            kpg.init(MLKEMKeyGenerationParameters(SecureRandom(), MLKEMParameters.ml_kem_768))
            val pair = kpg.generateKeyPair()
            E2eKeyPair(
                (pair.getPublic() as MLKEMPublicKeyParameters).encoded,
                (pair.getPrivate() as MLKEMPrivateKeyParameters).encoded
            )
        }.getOrElse { e ->
            Log.e(TAG, "ML-KEM 生成密钥对失败", e)
            E2eKeyPair(ByteArray(0), ByteArray(0))
        }
    }

    override fun encapsulate(peerPublicKey: ByteArray): E2eEncapsulation? {
        if (!available) return null
        return runCatching {
            val peerPub = MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, peerPublicKey)
            val encapsulated = MLKEMGenerator(SecureRandom()).generateEncapsulated(peerPub)
            E2eEncapsulation(encapsulated.encapsulation, encapsulated.secret)
        }.getOrElse { e ->
            Log.w(TAG, "ML-KEM 封装失败（对端公钥可能不是 ML-KEM-768 格式）", e)
            null
        }
    }

    override fun decapsulate(ciphertext: ByteArray, privateKey: ByteArray): ByteArray? {
        if (!available) return null
        return runCatching {
            val priv = MLKEMPrivateKeyParameters(MLKEMParameters.ml_kem_768, privateKey)
            MLKEMExtractor(priv).extractSecret(ciphertext)
        }.getOrElse { e ->
            Log.w(TAG, "ML-KEM 解封装失败", e)
            null
        }
    }

    companion object {
        private const val TAG = "MlKem768Kem"
    }
}

/**
 * 降级 KEM：ECDH P-256 + SHA-256 → 32 B 共享密钥（与 ML-KEM 的 SS 长度一致）。
 *
 * 与 ML-KEM 的区别只有「密钥封装原语」本身，帧格式、SS 长度、AEAD（AES-256-GCM）
 * 与 enigmaj 完全一致：
 * - 发起方 `PQC_BEGIN` 载荷 = 自己的 P-256 公钥（X.509 SPKI DER，91 B）
 * - 响应方 `PQC_REPLY` 载荷 = 临时 P-256 公钥（91 B），SS = SHA-256(ECDH 共享点)
 * 接收端按载荷长度自动区分（1184 → ML-KEM-768；约 91 → P-256）。
 */
class EcdhP256Kem : E2eKem {

    override val id = "ECDH-P256(降级)"
    override val publicKeySize = 91

    override fun generateKeyPair(): E2eKeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        return E2eKeyPair(kp.public.encoded, kp.private.encoded)
    }

    override fun encapsulate(peerPublicKey: ByteArray): E2eEncapsulation? = runCatching {
        val peerPub = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(peerPublicKey))
        val ephemeral = generateKeyPair()
        val ephPriv = KeyFactory.getInstance("EC")
            .generatePrivate(PKCS8EncodedKeySpec(ephemeral.privateKey))
        val ss = ecdh(ephPriv, peerPub) ?: return null
        E2eEncapsulation(ephemeral.publicKey, ss)
    }.getOrNull()

    override fun decapsulate(ciphertext: ByteArray, privateKey: ByteArray): ByteArray? = runCatching {
        val priv = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateKey))
        val peerEphPub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(ciphertext))
        ecdh(priv, peerEphPub)
    }.getOrNull()

    private fun ecdh(priv: java.security.PrivateKey, pub: java.security.PublicKey): ByteArray? = runCatching {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        // 32 B：与 ML-KEM 的 SS 长度对齐
        MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
    }.getOrNull()
}

/** AES-256-GCM：载荷布局 nonce(12) ‖ ciphertext ‖ tag(16)，与 enigmaj 一致。 */
object E2eAead {

    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128

    fun encrypt(sharedSecret: ByteArray, plaintext: ByteArray): ByteArray? = runCatching {
        val nonce = ByteArray(NONCE_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(normalizeKey(sharedSecret), "AES"),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        nonce + cipher.doFinal(plaintext)
    }.getOrNull()

    fun decrypt(sharedSecret: ByteArray, payload: ByteArray): ByteArray? {
        // enigmaj：长度 ≤27 直接判「密文长度不足」
        if (payload.size < E2eFrame.MIN_PAYLOAD) return null
        return runCatching {
            val nonce = payload.copyOfRange(0, NONCE_LEN)
            val body = payload.copyOfRange(NONCE_LEN, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(normalizeKey(sharedSecret), "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.doFinal(body)
        }.getOrNull()
    }

    /** 固定成 32 B AES-256 密钥（ML-KEM/E 的 SS 本身就是 32 B，ECDH 侧已 SHA-256） */
    private fun normalizeKey(secret: ByteArray): ByteArray =
        if (secret.size == 32) secret else MessageDigest.getInstance("SHA-256").digest(secret)
}

/** 会话密钥的人类可读指纹（双方相同即证明握手一致）。 */
object E2eFingerprint {
    fun of(sharedSecret: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(sharedSecret)
        return digest.take(8).joinToString("") { "%02X".format(it) }
    }
}
