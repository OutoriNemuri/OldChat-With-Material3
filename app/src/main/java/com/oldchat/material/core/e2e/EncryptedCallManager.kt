package com.oldchat.material.core.e2e

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 加密通话（"加密通话"）状态机。
 *
 * ## 为什么是这套做法
 * `shared/enigmaj-re/REPORT.md` 里 enigmaj 并没有"通话"功能，它提供的是
 * **基于消息通道的端到端加密层**，原理是：
 *   ① 用普通私聊消息承载控制帧（`PQC_BEGIN` / `PQC_REPLY` / `ENC` 三种前缀）；
 *   ② 发起方发 `PQC_BEGIN\n<base64 ek>`，响应方**收到即自动**封装并回
 *      `PQC_REPLY\n<base64 ct>`，双方各自算出同一把 32 B 共享密钥（ML-KEM-768）；
 *   ③ 之后所有负载用 AES-256-GCM，载荷 `nonce(12) ‖ ct ‖ tag(16)` 再 base64，前缀 `ENC`；
 *   ④ 已经有共享密钥时可以直接用（"已使用提供的共享密钥，无需握手"），并持久化到
 *      每对端一条的 `shared_secrets`。
 *
 * 本实现**对称照搬**这套原理来做"加密通话"：
 *   - 拨出 = 发 `PQC_BEGIN`（既是握手也是呼叫信令）；
 *   - 接听 = 收到 `PQC_BEGIN` 即自动回 `PQC_REPLY`（等同 enigmaj 的响应方行为）；
 *   - 通话中 = 双方各持 32 B SS，用 `ENC` 帧发控制心跳（ping/pong）；
 *   - 挂断 = `ENC` 帧里 `{"t":"end"}`，对端解出后同样结束通话（对称退出）；
 *   - 密钥持久化 = `shared_secrets` 等价物（每对端一条，base64 44 字符）。
 *
 * ## 与 enigmaj 的已知差异（有意为之，均已在代码里标注）
 *   - enigmaj 用 aws-lc-rs 的 ML-KEM-768；Android 侧优先用 BouncyCastle 的 ML-KEM-768，
 *     依赖缺失时**降级为 ECDH P-256 + SHA-256**，帧格式/SS 长度/AEAD 全部不变，
 *     接收端按载荷长度自动区分（1184 B → ML-KEM-768，91 B → P-256）。
 *   - 本版**不含音频**：通话是"加密会话"（握手 + 心跳 + 挂断），音频管线未接入。
 *     因此它诚实地说自己是「加密通话通道已建立」，而不是假装在传语音。
 */
class EncryptedCallManager(
    private val sendRaw: suspend (peerUid: String, body: String) -> Boolean,
    private val keyStore: E2eKeyStore
) {

    sealed interface CallState {
        /** 空闲：未在通话 */
        data object Idle : CallState

        /** 已发出 PQC_BEGIN（或已回 PQC_REPLY），等待握手完成 */
        data class Establishing(
            val peer: String,
            val role: Role,
            val startedAt: Long,
            val kem: String
        ) : CallState

        /** 通话中：双方已共享 32 B SS */
        data class Connected(
            val peer: String,
            val role: Role,
            val startedAt: Long,
            val kem: String,
            val fingerprint: String,
            /** true = 该密钥来自本地 shared_secrets（可跨通话复用） */
            val persisted: Boolean
        ) : CallState

        /** 已结束（UI 展示数秒后自行转 Idle） */
        data class Ended(val peer: String, val reason: String, val at: Long) : CallState
    }

    enum class Role { INITIATOR, RESPONDER }

    /**
     * 入站帧的分流结果（由网络层在**派发之前**决定，避免帧进入消息流）。
     */
    sealed interface Inbound {
        /** 不是 E2E 帧：按普通消息继续派发 */
        data object NotE2e : Inbound

        /** 已被通话层消费（握手帧 / ENC 控制帧）：不要派发 */
        data object Consumed : Inbound

        /** ENC 帧里装的是普通消息：用 [Message.plainBody] 作为 body 继续派发 */
        data class Message(val plainBody: String) : Inbound

        /** 是 E2E 帧但解不开（无密钥/认证失败）：丢弃，不要派发 */
        data object Undecryptable : Inbound
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<CallState>(CallState.Idle)
    val state: StateFlow<CallState> = _state.asStateFlow()

    /** 通话时长（秒），非通话状态为 0 */
    private val _elapsedSeconds = MutableStateFlow(0L)
    val elapsedSeconds: StateFlow<Long> = _elapsedSeconds.asStateFlow()

    /** 最近一次要展示给用户的一句话（对应 enigmaj 的各类 System 文案） */
    private val _systemText = MutableStateFlow<String?>(null)
    val systemText: StateFlow<String?> = _systemText.asStateFlow()

    private var callKem: E2eKem = KemFactory.preferred()
    private var pendingPrivateKey: ByteArray? = null
    private var sessionSecret: ByteArray? = null
    private var peerUid: String? = null
    private var lastFrameAt = 0L
    private var timerJob: Job? = null
    private var heartbeatJob: Job? = null
    private var handshakeJob: Job? = null

    /** 当前使用的 KEM（UI/日志用它说明到底跑的是 ML-KEM-768 还是降级方案） */
    val activeKemId: String get() = callKem.id

    // ---- 拨出 ----

    /**
     * 发起加密通话。若本地已有该对端的共享密钥，按 enigmaj 语义直接使用它
     * （"已使用提供的共享密钥，无需握手"）并额外发一帧 `ENC` 控制帧通知对方开始，
     * 否则发 `PQC_BEGIN` 走完整握手（每次通话都是新的临时密钥，具备前向保密）。
     */
    fun start(peer: String) {
        if (peer.isBlank()) return
        val current = _state.value
        if (current is CallState.Connected && current.peer == peer) return
        reset()

        peerUid = peer
        lastFrameAt = System.currentTimeMillis()
        val now = System.currentTimeMillis()

        val stored = keyStore.get(peer)
        if (stored != null && stored.size == 32) {
            sessionSecret = stored
            _state.value = CallState.Connected(
                peer = peer,
                role = Role.INITIATOR,
                startedAt = now,
                kem = callKem.id,
                fingerprint = E2eFingerprint.of(stored),
                persisted = true
            )
            _systemText.value = "已使用本地共享密钥，无需握手"
            startTimers()
            // 已有密钥时不需要握手，但仍要通知对端「通话开始」（ENC 控制帧）
            scope.launch { sendControl(peer, "start") }
            return
        }

        // 每次拨号都重新挑一次 KEM（万一运行环境里 BC 缺失/异常，立刻降级而不是卡在「握手中」）
        callKem = KemFactory.preferred()
        var pair = callKem.generateKeyPair()
        if (pair.publicKey.isEmpty()) {
            // ML-KEM 反射路径失败 → 降级 ECDH P-256（帧格式不变，接收端按长度识别）
            callKem = KemFactory.fallback()
            pair = callKem.generateKeyPair()
        }
        if (pair.publicKey.isEmpty()) {
            _state.value = CallState.Ended(peer, "本机加密模块不可用", System.currentTimeMillis())
            return
        }
        pendingPrivateKey = pair.privateKey
        _state.value = CallState.Establishing(peer, Role.INITIATOR, now, callKem.id)
        _systemText.value = "已发起加密通话，正在握手…"

        // 对端离线/未响应时不能永远停在「握手中」——超时即结束（对称：两端行为一致）
        handshakeJob?.cancel()
        handshakeJob = scope.launch {
            delay(HANDSHAKE_TIMEOUT_MS)
            val s = _state.value
            if (s is CallState.Establishing && s.role == Role.INITIATOR) {
                pendingPrivateKey = null
                _state.value = CallState.Ended(peer, "对方未响应，加密通话未建立", System.currentTimeMillis())
                _systemText.value = "对方未响应"
            }
        }
        scope.launch {
            val ok = sendRaw(peer, E2eFrame.encode(E2eFrame.Kind.PQC_BEGIN, pair.publicKey))
            if (!ok) {
                _state.value = CallState.Ended(peer, "呼叫发送失败", System.currentTimeMillis())
                pendingPrivateKey = null
            }
        }
    }

    // ---- 通话内普通消息的加解密（enigmaj 的「发消息入口：查密钥→加密→发送」） ----

    /** 当前是否与该对端处于「已接通」状态（只有这时才有可用密钥） */
    fun isConnectedWith(peer: String): Boolean =
        (_state.value as? CallState.Connected)?.peer == peer

    /** 是否正在与对端握手（此时还没有密钥，消息需要暂存） */
    fun isEstablishingWith(peer: String): Boolean =
        (_state.value as? CallState.Establishing)?.peer == peer

    /**
     * 把明文消息体（v2 JSON 或任意文本）包成 `ENC\n<base64(nonce12‖AES-GCM(plain)‖tag16)>`。
     *
     * 返回 null 表示「当前没有可用密钥」——调用方应按原样明文发送（enigmaj 也没有
     * 「必须加密」的强制策略）。
     */
    fun wrapMessage(peer: String, plainBody: String): String? {
        if (!isConnectedWith(peer)) return null
        val ss = sessionSecret ?: return null
        val payload = E2eAead.encrypt(ss, plainBody.toByteArray(Charsets.UTF_8)) ?: return null
        return E2eFrame.encode(E2eFrame.Kind.ENC, payload)
    }

    /**
     * 展示/入库用的解包：非 E2E 原样返回；ENC 消息帧返回明文；
     * 握手帧、ENC 控制帧、解不开的帧一律返回 null（表示「不该出现在消息列表里」）。
     *
     * 用在历史回源路径（`mergeMessages`）——WS 路径已在网络层解包。
     */
    fun unwrapForDisplay(body: String, peer: String): String? {
        if (!E2eFrame.isE2e(body)) return body
        if (E2eFrame.kind(body) != E2eFrame.Kind.ENC) return null
        val ss = sessionSecret ?: keyStore.get(peer) ?: return null
        val payload = runCatching {
            Base64.decode(E2eFrame.payloadOf(body, E2eFrame.Kind.ENC), Base64.NO_WRAP)
        }.getOrNull() ?: return null
        val plain = E2eAead.decrypt(ss, payload) ?: return null
        val text = String(plain, Charsets.UTF_8)
        val isControl = runCatching { JSONObject(text).has("t") }.getOrDefault(false)
        return if (isControl) null else text
    }

    /**
     * 入站分流（网络层单点调用，**必须发生在派发之前**）。
     * @param selfEcho 该帧是不是自己发出的回显（fromUid == 我的 uid）
     */
    fun onInbound(body: String, fromUid: String, selfEcho: Boolean): Inbound {
        val kind = E2eFrame.kind(body)
        if (kind == E2eFrame.Kind.PLAIN) return Inbound.NotE2e
        lastFrameAt = System.currentTimeMillis()

        if (selfEcho) {
            // 自己发出的帧：握手帧丢弃；ENC 消息帧解包后交回消息流
            // （用于把本地 pending 替换成服务端确认的真实消息）
            val plain = unwrapForDisplay(body, fromUid)
            return if (plain != null) Inbound.Message(plain) else Inbound.Consumed
        }

        return when (kind) {
            E2eFrame.Kind.PQC_BEGIN -> {
                handlePqcBegin(E2eFrame.payloadOf(body, kind), fromUid)
                Inbound.Consumed
            }
            E2eFrame.Kind.PQC_REPLY -> {
                handlePqcReply(E2eFrame.payloadOf(body, kind), fromUid)
                Inbound.Consumed
            }
            E2eFrame.Kind.ENC -> handleEncFrame(E2eFrame.payloadOf(body, kind), fromUid)
            E2eFrame.Kind.PLAIN -> Inbound.NotE2e
        }
    }

    // ---- 挂断（"再次点击并确认后退出"） ----

    fun hangUp(reason: String = "已结束加密通话") {
        val peer = peerUid ?: (_state.value.let { if (it is CallState.Connected) it.peer else null })
        stopTimers()
        if (peer != null && sessionSecret != null) {
            scope.launch { sendControl(peer, "end") }
        }
        _state.value = CallState.Ended(peer ?: "", reason, System.currentTimeMillis())
        _systemText.value = reason
        pendingPrivateKey = null
        // 保留 sessionSecret 与 keyStore：与 enigmaj 一样，密钥跨会话复用
    }

    /** UI 看完 Ended 提示后回到 Idle */
    fun acknowledgeEnded() {
        if (_state.value is CallState.Ended) {
            _state.value = CallState.Idle
            peerUid = null
            sessionSecret = null
        }
    }

    /** 是否正在进行某对端的通话 */
    fun isInCallWith(peer: String): Boolean = when (val s = _state.value) {
        is CallState.Connected -> s.peer == peer
        is CallState.Establishing -> s.peer == peer
        else -> false
    }

    // ---- 入站帧 ----


    /** 响应方：收到对方公钥立即封装并回 PQC_REPLY（enigmaj 的自动应答行为） */
    private fun handlePqcBegin(payloadB64: String, fromUid: String) {
        val ek = runCatching { Base64.decode(payloadB64, Base64.NO_WRAP) }.getOrNull()
        if (ek == null || ek.isEmpty()) {
            Log.w(TAG, "PQC_BEGIN 载荷无法解码")
            return
        }
        // 按载荷长度选择与对端一致的 KEM
        val kem = KemFactory.forPublicKeySize(ek.size)
        val encapsulated = kem.encapsulate(ek)
        if (encapsulated == null) {
            Log.w(TAG, "处理 PQC_BEGIN 失败（公钥可能太短或损坏）")
            return
        }

        peerUid = fromUid
        sessionSecret = encapsulated.sharedSecret
        keyStore.put(fromUid, encapsulated.sharedSecret)
        callKem = kem

        scope.launch {
            sendRaw(fromUid, E2eFrame.encode(E2eFrame.Kind.PQC_REPLY, encapsulated.ciphertext))
        }
        _state.value = CallState.Connected(
            peer = fromUid,
            role = Role.RESPONDER,
            startedAt = System.currentTimeMillis(),
            kem = kem.id,
            fingerprint = E2eFingerprint.of(encapsulated.sharedSecret),
            persisted = true
        )
        _systemText.value = "与 $fromUid 握手成功（响应方），加密通话已建立"
        startTimers()
    }

    /** 发起方：收到密文 → 用挂起中的私钥解封装 */
    private fun handlePqcReply(payloadB64: String, fromUid: String) {
        val ct = runCatching { Base64.decode(payloadB64, Base64.NO_WRAP) }.getOrNull()
        val priv = pendingPrivateKey
        if (ct == null || priv == null) {
            // enigmaj 原文案：「收到 PQC_REPLY 但没有对应私钥」
            Log.w(TAG, "收到 PQC_REPLY 但没有对应私钥")
            return
        }
        val ss = callKem.decapsulate(ct, priv)
        if (ss == null) {
            Log.w(TAG, "PQC_REPLY 解封装失败")
            return
        }
        peerUid = fromUid
        sessionSecret = ss
        keyStore.put(fromUid, ss)
        pendingPrivateKey = null
        _state.value = CallState.Connected(
            peer = fromUid,
            role = Role.INITIATOR,
            startedAt = System.currentTimeMillis(),
            kem = callKem.id,
            fingerprint = E2eFingerprint.of(ss),
            persisted = true
        )
        handshakeJob?.cancel(); handshakeJob = null
        _systemText.value = "与 $fromUid 握手完成（发起方），加密通话已建立"
        startTimers()
    }

    /**
     * 通话中的 ENC 帧分流：
     * - 载荷是控制帧（JSON 含 `t`）→ 通话层处理，返回 [Inbound.Consumed]
     * - 载荷是普通消息体（v2 JSON 等）→ 返回 [Inbound.Message]，交回消息流展示
     */
    private fun handleEncFrame(payloadB64: String, fromUid: String): Inbound {
        val ss = sessionSecret
        if (ss == null) {
            // enigmaj 原文案：「收到来自 X 的加密消息，但无共享密钥」
            _systemText.value = "收到来自 $fromUid 的加密消息，但没有共享密钥"
            return Inbound.Undecryptable
        }
        val payload = runCatching { Base64.decode(payloadB64, Base64.NO_WRAP) }.getOrNull()
            ?: return Inbound.Undecryptable
        val plain = E2eAead.decrypt(ss, payload)
        if (plain == null) {
            // 长度不足 / 认证失败（enigmaj：密文长度不足 / 解密失败）
            Log.w(TAG, "ENC 帧解密失败（长度不足或 MAC 校验不通过）")
            return Inbound.Undecryptable
        }

        val text = String(plain, Charsets.UTF_8)
        val type = runCatching { JSONObject(text).optString("t") }.getOrDefault("")
        if (type.isEmpty()) {
            // 不是控制帧 → 这是一条「通话内加密消息」
            return Inbound.Message(text)
        }
        when (type) {
            "ping" -> scope.launch { peerUid?.let { sendControl(it, "pong") } }
            "pong" -> lastFrameAt = System.currentTimeMillis()
            "start" -> {
                // 对端用本地已有密钥直接开始通话
                if (_state.value !is CallState.Connected) {
                    peerUid = fromUid
                    _state.value = CallState.Connected(
                        peer = fromUid,
                        role = Role.RESPONDER,
                        startedAt = System.currentTimeMillis(),
                        kem = callKem.id,
                        fingerprint = E2eFingerprint.of(ss),
                        persisted = true
                    )
                    startTimers()
                }
                _systemText.value = "$fromUid 发起了加密通话"
            }
            "end" -> {
                stopTimers()
                _state.value = CallState.Ended(fromUid, "对方结束了加密通话", System.currentTimeMillis())
                _systemText.value = "对方结束了加密通话"
                pendingPrivateKey = null
            }
            else -> Log.d(TAG, "忽略未知控制帧: $type")
        }
        return Inbound.Consumed
    }

    // ---- 定时器 ----

    private fun startTimers() {
        stopTimers()
        val started = System.currentTimeMillis()
        timerJob = scope.launch {
            while (isActive) {
                _elapsedSeconds.value = (System.currentTimeMillis() - started) / 1000
                delay(1_000)
            }
        }
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                val peer = peerUid ?: continue
                sendControl(peer, "ping")
                // 长时间收不到任何帧 → 判定对端已掉线（对称退出）
                if (System.currentTimeMillis() - lastFrameAt > HEARTBEAT_TIMEOUT_MS) {
                    val s = _state.value
                    if (s is CallState.Connected) {
                        stopTimers()
                        _state.value = CallState.Ended(s.peer, "心跳超时，通话已结束", System.currentTimeMillis())
                        _systemText.value = "心跳超时，通话已结束"
                    }
                }
            }
        }
    }

    private fun stopTimers() {
        timerJob?.cancel(); timerJob = null
        heartbeatJob?.cancel(); heartbeatJob = null
        handshakeJob?.cancel(); handshakeJob = null
        _elapsedSeconds.value = 0L
    }

    private suspend fun sendControl(peer: String, type: String) {
        val ss = sessionSecret ?: return
        val plain = JSONObject().apply {
            put("t", type)
            put("ts", System.currentTimeMillis() / 1000)
            put("v", 1)
        }.toString().toByteArray(Charsets.UTF_8)
        val payload = E2eAead.encrypt(ss, plain) ?: return
        sendRaw(peer, E2eFrame.encode(E2eFrame.Kind.ENC, payload))
    }

    /** 复位到可开始新通话的状态（保留 keyStore —— 密钥跨通话复用，与 enigmaj 一致） */
    private fun reset() {
        stopTimers()
        pendingPrivateKey = null
        sessionSecret = null
        peerUid = null
        _systemText.value = null
        _state.value = CallState.Idle
    }

    fun destroy() {
        stopTimers()
        pendingPrivateKey = null
    }

    companion object {
        private const val TAG = "EncryptedCall"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L

        /** 发起方等待 PQC_REPLY 的上限 */
        private const val HANDSHAKE_TIMEOUT_MS = 30_000L
    }
}

/** 按可用性挑选 KEM：优先 ML-KEM-768（与 enigmaj 同算法），否则降级 ECDH P-256。 */
object KemFactory {
    private val mlKem by lazy { MlKem768Kem() }
    private val ecdh by lazy { EcdhP256Kem() }

    fun preferred(): E2eKem = if (mlKem.available) mlKem else ecdh

    /** 强制降级实现（ML-KEM 不可用或运行期失败时使用） */
    fun fallback(): E2eKem = ecdh

    /** 接收端按公钥长度自动识别对端 KEM（1184 → ML-KEM-768，其余 → P-256） */
    fun forPublicKeySize(size: Int): E2eKem =
        if (mlKem.available && size >= MLKEM_768_PUBLIC_KEY_SIZE - 8) mlKem else ecdh

    /** ML-KEM-768 封装公钥固定 1184 B（enigmaj 实测：base64 后 1580 字符） */
    private const val MLKEM_768_PUBLIC_KEY_SIZE = 1184
}
