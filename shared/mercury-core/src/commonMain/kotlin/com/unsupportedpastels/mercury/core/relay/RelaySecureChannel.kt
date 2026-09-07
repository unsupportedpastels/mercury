package com.unsupportedpastels.mercury.core.relay

interface RelayCrypto {
    fun sha256(input: ByteArray): ByteArray
    fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray
    fun x25519PublicKey(privateKey: ByteArray): ByteArray
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray?
    fun chachaPolyEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray
    fun chachaPolyDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray?
    fun randomBytes(count: Int): ByteArray
}

internal enum class RelaySecureChannelFailure(val wireReason: String) {
    AuthenticationFailed("authentication_failed"),
    ChannelClosed("channel_closed"),
    HandshakeNotFinished("handshake_not_finished"),
    PlaintextLimit("plaintext_limit"),
    CiphertextLimit("ciphertext_limit"),
    TransportFailed("transport_failed"),
}

internal class RelaySecureChannelException(
    val failure: RelaySecureChannelFailure,
) : Exception("Mercury Relay secure channel failed")

object RelaySecureChannelPolicy {
    const val keyBytes = 32
    const val pairingCapabilityBytes = 32
    const val maxCiphertextRecordBytes = 65_535
    const val maxPlaintextRecordBytes = 65_519
    const val protocolName = "Noise_XK_25519_ChaChaPoly_SHA256"
    val prologuePrefix = "mercury-relay/v1".encodeToByteArray() + byteArrayOf(0)
}

private class NoiseCipherState(
    private val crypto: RelayCrypto,
    var key: ByteArray? = null,
    var nonce: ULong = 0u,
) {
    private fun nextNonce(): ByteArray {
        if (nonce == ULong.MAX_VALUE) fail(RelaySecureChannelFailure.TransportFailed)
        val bytes = ByteArray(12)
        var value = nonce
        repeat(8) { index ->
            bytes[4 + index] = (value and 0xffu).toByte()
            value = value shr 8
        }
        nonce += 1u
        return bytes
    }

    fun encrypt(associatedData: ByteArray, plaintext: ByteArray): ByteArray {
        val currentKey = key ?: return plaintext.copyOf()
        return crypto.chachaPolyEncrypt(currentKey, nextNonce(), associatedData, plaintext)
    }

    fun decrypt(associatedData: ByteArray, ciphertext: ByteArray): ByteArray {
        val currentKey = key ?: return ciphertext.copyOf()
        if (ciphertext.size < 16) fail(RelaySecureChannelFailure.AuthenticationFailed)
        return crypto.chachaPolyDecrypt(currentKey, nextNonce(), associatedData, ciphertext)
            ?: fail(RelaySecureChannelFailure.AuthenticationFailed)
    }
}

private fun noiseHkdf(
    crypto: RelayCrypto,
    chainingKey: ByteArray,
    input: ByteArray,
    outputs: Int,
): List<ByteArray> {
    val tempKey = crypto.hmacSha256(chainingKey, input)
    val results = mutableListOf<ByteArray>()
    var previous = byteArrayOf()
    for (index in 1..outputs) {
        previous = crypto.hmacSha256(tempKey, previous + byteArrayOf(index.toByte()))
        results += previous
    }
    return results
}

private class NoiseSymmetricState(
    private val crypto: RelayCrypto,
    protocolName: String,
) {
    var cipher = NoiseCipherState(crypto)
    var chainingKey: ByteArray
    var handshakeHash: ByteArray

    init {
        val name = protocolName.encodeToByteArray()
        handshakeHash = if (name.size <= 32) name + ByteArray(32 - name.size) else crypto.sha256(name)
        chainingKey = handshakeHash.copyOf()
    }

    fun mixHash(data: ByteArray) {
        handshakeHash = crypto.sha256(handshakeHash + data)
    }

    fun mixKey(input: ByteArray) {
        val derived = noiseHkdf(crypto, chainingKey, input, 2)
        chainingKey = derived[0]
        cipher = NoiseCipherState(crypto, derived[1])
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ciphertext = cipher.encrypt(handshakeHash, plaintext)
        mixHash(ciphertext)
        return ciphertext
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plaintext = cipher.decrypt(handshakeHash, ciphertext)
        mixHash(ciphertext)
        return plaintext
    }

    fun split(): Pair<NoiseCipherState, NoiseCipherState> {
        val derived = noiseHkdf(crypto, chainingKey, byteArrayOf(), 2)
        return NoiseCipherState(crypto, derived[0]) to NoiseCipherState(crypto, derived[1])
    }
}

private class NoiseXkHandshake(
    private val crypto: RelayCrypto,
    val isInitiator: Boolean,
    val localStaticPrivate: ByteArray,
    remoteStaticPublic: ByteArray?,
    prologue: ByteArray,
    private val deterministicEphemeralPrivate: ByteArray?,
) {
    val symmetric = NoiseSymmetricState(crypto, RelaySecureChannelPolicy.protocolName)
    var localEphemeralPrivate: ByteArray? = null
    var remoteStaticPublic: ByteArray? = remoteStaticPublic?.copyOf()
    var remoteEphemeralPublic: ByteArray? = null

    init {
        symmetric.mixHash(prologue)
        symmetric.mixHash(
            if (isInitiator) remoteStaticPublic ?: fail(RelaySecureChannelFailure.AuthenticationFailed)
            else crypto.x25519PublicKey(localStaticPrivate)
        )
    }

    private fun generateEphemeral(): ByteArray =
        (deterministicEphemeralPrivate?.copyOf() ?: crypto.randomBytes(RelaySecureChannelPolicy.keyBytes))
            .also { localEphemeralPrivate = it }

    fun writeMessage1(payload: ByteArray): ByteArray {
        val ephemeral = generateEphemeral()
        val publicKey = crypto.x25519PublicKey(ephemeral)
        symmetric.mixHash(publicKey)
        symmetric.mixKey(crypto.x25519(ephemeral, remoteStaticPublic!!)
            ?: fail(RelaySecureChannelFailure.AuthenticationFailed))
        return publicKey + symmetric.encryptAndHash(payload)
    }

    fun readMessage1(message: ByteArray): ByteArray {
        if (message.size < 32) fail(RelaySecureChannelFailure.AuthenticationFailed)
        val publicKey = message.copyOfRange(0, 32)
        remoteEphemeralPublic = publicKey
        symmetric.mixHash(publicKey)
        symmetric.mixKey(crypto.x25519(localStaticPrivate, publicKey)
            ?: fail(RelaySecureChannelFailure.AuthenticationFailed))
        return symmetric.decryptAndHash(message.copyOfRange(32, message.size))
    }

    fun writeMessage2(payload: ByteArray): ByteArray {
        val ephemeral = generateEphemeral()
        val publicKey = crypto.x25519PublicKey(ephemeral)
        symmetric.mixHash(publicKey)
        symmetric.mixKey(crypto.x25519(ephemeral, remoteEphemeralPublic!!)
            ?: fail(RelaySecureChannelFailure.AuthenticationFailed))
        return publicKey + symmetric.encryptAndHash(payload)
    }

    fun readMessage2(message: ByteArray): ByteArray {
        if (message.size < 32) fail(RelaySecureChannelFailure.AuthenticationFailed)
        val publicKey = message.copyOfRange(0, 32)
        remoteEphemeralPublic = publicKey
        symmetric.mixHash(publicKey)
        symmetric.mixKey(crypto.x25519(localEphemeralPrivate!!, publicKey)
            ?: fail(RelaySecureChannelFailure.AuthenticationFailed))
        return symmetric.decryptAndHash(message.copyOfRange(32, message.size))
    }

    fun writeMessage3(payload: ByteArray): ByteArray {
        val staticCiphertext = symmetric.encryptAndHash(crypto.x25519PublicKey(localStaticPrivate))
        symmetric.mixKey(crypto.x25519(localStaticPrivate, remoteEphemeralPublic!!)
            ?: fail(RelaySecureChannelFailure.AuthenticationFailed))
        return staticCiphertext + symmetric.encryptAndHash(payload)
    }

    fun readMessage3(message: ByteArray): ByteArray {
        if (message.size < 48) fail(RelaySecureChannelFailure.AuthenticationFailed)
        val staticPlain = symmetric.decryptAndHash(message.copyOfRange(0, 48))
        remoteStaticPublic = staticPlain
        symmetric.mixKey(crypto.x25519(localEphemeralPrivate!!, staticPlain)
            ?: fail(RelaySecureChannelFailure.AuthenticationFailed))
        return symmetric.decryptAndHash(message.copyOfRange(48, message.size))
    }
}

class RelaySecureChannel @Throws(RelaySecureChannelException::class) constructor(
    private val crypto: RelayCrypto,
    val isInitiator: Boolean,
    staticPrivateKey: ByteArray,
    installationId: ByteArray,
    remoteStaticPublicKey: ByteArray?,
    deterministicEphemeralPrivateKey: ByteArray?,
) {
    private enum class NextAction { Write1, Read1, Write2, Read2, Write3, Read3, Complete }

    var closed: Boolean = false
        private set
    private var handshake: NoiseXkHandshake?
    private var nextAction: NextAction
    private var sendCipher = NoiseCipherState(crypto)
    private var receiveCipher = NoiseCipherState(crypto)
    private var binding: ByteArray? = null
    private var remoteStaticBytes: ByteArray? = null

    init {
        if (staticPrivateKey.size != RelaySecureChannelPolicy.keyBytes ||
            installationId.size != RelaySecureChannelPolicy.keyBytes ||
            (isInitiator && remoteStaticPublicKey?.size != RelaySecureChannelPolicy.keyBytes) ||
            (!isInitiator && remoteStaticPublicKey != null) ||
            (deterministicEphemeralPrivateKey != null && deterministicEphemeralPrivateKey.size != RelaySecureChannelPolicy.keyBytes)
        ) {
            fail(RelaySecureChannelFailure.AuthenticationFailed)
        }
        handshake = runCatching {
            NoiseXkHandshake(
                crypto,
                isInitiator,
                staticPrivateKey.copyOf(),
                remoteStaticPublicKey,
                RelaySecureChannelPolicy.prologuePrefix + installationId,
                deterministicEphemeralPrivateKey,
            )
        }.getOrElse { fail(RelaySecureChannelFailure.AuthenticationFailed) }
        nextAction = if (isInitiator) NextAction.Write1 else NextAction.Read1
    }

    @Throws(RelaySecureChannelException::class)
    constructor(
        crypto: RelayCrypto,
        initiatorStaticPrivateKey: ByteArray,
        installationId: ByteArray,
        hostStaticPublicKey: ByteArray,
    ) : this(crypto, true, initiatorStaticPrivateKey, installationId, hostStaticPublicKey, null)

    val handshakeFinished: Boolean get() = nextAction == NextAction.Complete && !closed

    val channelBinding: ByteArray
        get() = binding?.takeIf { handshakeFinished }?.copyOf()
            ?: fail(RelaySecureChannelFailure.HandshakeNotFinished)

    val remoteStaticPublicKey: ByteArray
        get() = remoteStaticBytes?.takeIf { handshakeFinished }?.copyOf()
            ?: fail(RelaySecureChannelFailure.HandshakeNotFinished)

    fun close() {
        closed = true
        handshake = null
        sendCipher = NoiseCipherState(crypto)
        receiveCipher = NoiseCipherState(crypto)
        binding = null
        remoteStaticBytes = null
    }

    @Throws(RelaySecureChannelException::class)
    fun writeHandshake(payload: ByteArray = byteArrayOf()): ByteArray {
        requireOpen()
        if (payload.size > RelaySecureChannelPolicy.pairingCapabilityBytes) {
            throw failAndClose(RelaySecureChannelFailure.AuthenticationFailed)
        }
        val state = handshake ?: throw failAndClose(RelaySecureChannelFailure.AuthenticationFailed)
        return try {
            val result: ByteArray
            var finishes = false
            when (nextAction) {
                NextAction.Write1 -> {
                    if (!isInitiator || payload.isNotEmpty()) fail(RelaySecureChannelFailure.AuthenticationFailed)
                    result = state.writeMessage1(payload)
                    nextAction = NextAction.Read2
                }
                NextAction.Write2 -> {
                    if (isInitiator || payload.isNotEmpty()) fail(RelaySecureChannelFailure.AuthenticationFailed)
                    result = state.writeMessage2(payload)
                    nextAction = NextAction.Read3
                }
                NextAction.Write3 -> {
                    if (!isInitiator || (payload.isNotEmpty() && payload.size != RelaySecureChannelPolicy.pairingCapabilityBytes)) {
                        fail(RelaySecureChannelFailure.AuthenticationFailed)
                    }
                    result = state.writeMessage3(payload)
                    nextAction = NextAction.Complete
                    finishes = true
                }
                else -> fail(RelaySecureChannelFailure.AuthenticationFailed)
            }
            if (result.size !in 32..RelaySecureChannelPolicy.maxCiphertextRecordBytes) {
                fail(RelaySecureChannelFailure.AuthenticationFailed)
            }
            if (finishes) finishHandshake()
            result
        } catch (_: Throwable) {
            throw failAndClose(RelaySecureChannelFailure.AuthenticationFailed)
        }
    }

    @Throws(RelaySecureChannelException::class)
    fun readHandshake(message: ByteArray): ByteArray {
        requireOpen()
        if (message.size !in 32..RelaySecureChannelPolicy.maxCiphertextRecordBytes) {
            throw failAndClose(RelaySecureChannelFailure.AuthenticationFailed)
        }
        val state = handshake ?: throw failAndClose(RelaySecureChannelFailure.AuthenticationFailed)
        return try {
            val payload: ByteArray
            var finishes = false
            when (nextAction) {
                NextAction.Read1 -> {
                    if (isInitiator) fail(RelaySecureChannelFailure.AuthenticationFailed)
                    payload = state.readMessage1(message)
                    nextAction = NextAction.Write2
                }
                NextAction.Read2 -> {
                    if (!isInitiator) fail(RelaySecureChannelFailure.AuthenticationFailed)
                    payload = state.readMessage2(message)
                    nextAction = NextAction.Write3
                }
                NextAction.Read3 -> {
                    if (isInitiator) fail(RelaySecureChannelFailure.AuthenticationFailed)
                    payload = state.readMessage3(message)
                    nextAction = NextAction.Complete
                    finishes = true
                }
                else -> fail(RelaySecureChannelFailure.AuthenticationFailed)
            }
            if ((!finishes && payload.isNotEmpty()) ||
                (finishes && payload.isNotEmpty() && payload.size != RelaySecureChannelPolicy.pairingCapabilityBytes)
            ) {
                fail(RelaySecureChannelFailure.AuthenticationFailed)
            }
            if (finishes) finishHandshake()
            payload
        } catch (_: Throwable) {
            throw failAndClose(RelaySecureChannelFailure.AuthenticationFailed)
        }
    }

    @Throws(RelaySecureChannelException::class)
    fun encrypt(plaintext: ByteArray): ByteArray {
        requireOpen()
        if (!handshakeFinished) fail(RelaySecureChannelFailure.HandshakeNotFinished)
        if (plaintext.size > RelaySecureChannelPolicy.maxPlaintextRecordBytes) {
            fail(RelaySecureChannelFailure.PlaintextLimit)
        }
        return try {
            sendCipher.encrypt(byteArrayOf(), plaintext).also {
                if (it.size > RelaySecureChannelPolicy.maxCiphertextRecordBytes) {
                    fail(RelaySecureChannelFailure.TransportFailed)
                }
            }
        } catch (_: Throwable) {
            throw failAndClose(RelaySecureChannelFailure.TransportFailed)
        }
    }

    @Throws(RelaySecureChannelException::class)
    fun decrypt(ciphertext: ByteArray): ByteArray {
        requireOpen()
        if (!handshakeFinished) fail(RelaySecureChannelFailure.HandshakeNotFinished)
        if (ciphertext.size !in 16..RelaySecureChannelPolicy.maxCiphertextRecordBytes) {
            fail(RelaySecureChannelFailure.CiphertextLimit)
        }
        return try {
            receiveCipher.decrypt(byteArrayOf(), ciphertext).also {
                if (it.size > RelaySecureChannelPolicy.maxPlaintextRecordBytes) {
                    fail(RelaySecureChannelFailure.TransportFailed)
                }
            }
        } catch (_: Throwable) {
            throw failAndClose(RelaySecureChannelFailure.TransportFailed)
        }
    }

    private fun finishHandshake() {
        val state = handshake ?: throw failAndClose(RelaySecureChannelFailure.AuthenticationFailed)
        val peer = state.remoteStaticPublic ?: throw failAndClose(RelaySecureChannelFailure.AuthenticationFailed)
        val split = state.symmetric.split()
        if (isInitiator) {
            sendCipher = split.first
            receiveCipher = split.second
        } else {
            sendCipher = split.second
            receiveCipher = split.first
        }
        binding = state.symmetric.handshakeHash.copyOf()
        remoteStaticBytes = peer.copyOf()
        handshake = null
        nextAction = NextAction.Complete
    }

    private fun requireOpen() {
        if (closed) fail(RelaySecureChannelFailure.ChannelClosed)
    }

    private fun failAndClose(failure: RelaySecureChannelFailure): RelaySecureChannelException {
        close()
        return RelaySecureChannelException(failure)
    }
}

object RelayFingerprint {
    fun shortAuthenticationString(crypto: RelayCrypto, channelBinding: ByteArray): String =
        crypto.sha256(channelBinding)
            .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
            .take(RelayProtocolPolicy.fingerprintHexCharacters)
}

private fun fail(failure: RelaySecureChannelFailure): Nothing =
    throw RelaySecureChannelException(failure)
