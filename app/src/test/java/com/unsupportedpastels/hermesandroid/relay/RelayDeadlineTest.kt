package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.mercury.core.relay.AndroidRelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayBase64
import com.unsupportedpastels.mercury.core.relay.RelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RelayDeadlineTest {
    @Test
    fun connectorDeadlineClosesSocketAfterStalledNoiseHandshake() = runTest {
        val socket = BlockingRelaySocket()
        val diagnostics = RelayDiagnostics(
            capacity = 8,
            attemptIdFactory = { "timeout-connect" },
            logger = {},
        )
        val failure = async {
            runCatching {
                RelayConnector.connect(
                    target = target(TestCrypto),
                    profile = "default",
                    socketFactory = RelayBinarySocketFactory { _, _ -> socket },
                    crypto = TestCrypto,
                    deterministicEphemeralPrivateKey = DEVICE_EPHEMERAL,
                    diagnostics = diagnostics,
                )
            }.exceptionOrNull()
        }
        runCurrent()
        advanceTimeBy(10_001)

        val error = failure.await()
        assertTrue(error is RelayConnectionException)
        assertEquals(RelayConnectionFailure.Offline, (error as RelayConnectionException).failure)
        assertTrue("deadline cleanup must close the socket", socket.closed)
        assertEquals(RelayDiagnosticStatus.TimedOut, diagnostics.snapshot()[2].status)
        assertEquals(RelayDiagnosticPhase.Handshake, diagnostics.snapshot()[2].phase)
        assertEquals(RelayDiagnosticCloseReason.Timeout, diagnostics.snapshot()[3].closeReason)
    }

    @Test
    fun pairingDeadlineClosesSocketAfterStalledNoiseHandshake() = runTest {
        val socket = BlockingRelaySocket()
        val diagnostics = RelayDiagnostics(
            capacity = 8,
            attemptIdFactory = { "timeout-pairing" },
            logger = {},
        )
        val now = 1_756_400_000L
        val coordinator = RelayPairingCoordinator(
            socketFactory = RelayBinarySocketFactory { _, _ -> socket },
            targets = EmptyTargetRepository,
            crypto = TestCrypto,
            nowEpochSeconds = { now },
            makeDeviceKey = { DEVICE_PRIVATE.copyOf() },
            deterministicEphemeralPrivateKey = DEVICE_EPHEMERAL,
            diagnostics = diagnostics,
        )
        val failure = async {
            runCatching { coordinator.pair(qr(now + 300, TestCrypto)) }.exceptionOrNull()
        }
        runCurrent()
        advanceTimeBy(10_001)

        val error = failure.await()
        assertTrue(error is RelayPairingException)
        assertEquals(RelayPairingFailure.Offline, (error as RelayPairingException).failure)
        assertTrue("deadline cleanup must close the socket", socket.closed)
        assertEquals(RelayDiagnosticStatus.TimedOut, diagnostics.snapshot()[2].status)
        assertEquals(RelayDiagnosticPhase.Handshake, diagnostics.snapshot()[2].phase)
        assertEquals(RelayDiagnosticCloseReason.Timeout, diagnostics.snapshot()[3].closeReason)
    }

    @Test
    fun connectorCancellationRecordsCancellationAndSafeDisconnect() = runTest {
        val socket = BlockingRelaySocket()
        val diagnostics = RelayDiagnostics(
            capacity = 8,
            attemptIdFactory = { "cancel-connect" },
            logger = {},
        )
        val job = async {
            runCatching {
                RelayConnector.connect(
                    target = target(TestCrypto),
                    profile = "default",
                    socketFactory = RelayBinarySocketFactory { _, _ -> socket },
                    crypto = TestCrypto,
                    deterministicEphemeralPrivateKey = DEVICE_EPHEMERAL,
                    diagnostics = diagnostics,
                )
            }
        }
        runCurrent()
        job.cancel()
        job.join()

        assertTrue("cancellation cleanup must close the socket", socket.closed)
        assertEquals(
            listOf(
                RelayDiagnosticPhase.Attempt,
                RelayDiagnosticPhase.Open,
                RelayDiagnosticPhase.Cancellation,
                RelayDiagnosticPhase.Disconnect,
            ),
            diagnostics.snapshot().map { it.phase },
        )
        assertEquals(RelayDiagnosticStatus.Cancelled, diagnostics.snapshot()[2].status)
        assertEquals(RelayDiagnosticCloseReason.Cancelled, diagnostics.snapshot()[3].closeReason)
    }

    private class BlockingRelaySocket : RelayBinarySocket {
        var closed = false
            private set

        override suspend fun send(data: ByteArray) = Unit

        override suspend fun receive(): ByteArray? {
            awaitCancellation()
            return null
        }

        override suspend fun close() {
            closed = true
        }
    }

    private object EmptyTargetRepository : RelayTargetRepository {
        override suspend fun load(): List<RelayPairedTarget> = emptyList()
        override suspend fun add(target: RelayPairedTarget): RelayPairedTarget = target
        override suspend fun markApproved(id: String, nowEpochSeconds: Long) = Unit
        override suspend fun touch(id: String, nowEpochSeconds: Long) = Unit
        override suspend fun updateLabel(id: String, label: String) = Unit
        override suspend fun remove(id: String) = Unit
    }

    private object TestCrypto : RelayCrypto {
        override fun sha256(input: ByteArray): ByteArray = ByteArray(32) { index ->
            if (input.isEmpty()) index.toByte() else (input[index % input.size].toInt() xor index).toByte()
        }

        override fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray = sha256(key + input)

        override fun x25519PublicKey(privateKey: ByteArray): ByteArray = privateKey.copyOf()

        override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
            ByteArray(32) { index -> (privateKey[index].toInt() xor publicKey[index].toInt()).toByte() }

        override fun chachaPolyEncrypt(
            key: ByteArray,
            nonce: ByteArray,
            associatedData: ByteArray,
            plaintext: ByteArray,
        ): ByteArray = plaintext.copyOf() + ByteArray(16)

        override fun chachaPolyDecrypt(
            key: ByteArray,
            nonce: ByteArray,
            associatedData: ByteArray,
            ciphertext: ByteArray,
        ): ByteArray? = ciphertext.takeIf { it.size >= 16 }?.copyOfRange(0, ciphertext.size - 16)

        override fun randomBytes(count: Int): ByteArray = ByteArray(count)
    }

    private fun target(crypto: RelayCrypto = AndroidRelayCrypto) = RelayPairedTarget(
        id = "00000000-0000-4000-8000-000000000001",
        label = "study",
        relayOrigin = "https://relay.example.com",
        installationId = INSTALLATION,
        hostPublicKey = crypto.x25519PublicKey(HOST_PRIVATE),
        deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { (it + 3).toByte() }),
        deviceStaticPrivateKey = DEVICE_PRIVATE,
        fingerprint = "0123456789abcdef",
        status = RelayTargetStatus.Approved,
        createdAtEpochSeconds = 1,
        lastUsedEpochSeconds = null,
        relayRoutingToken = "routing.device.token",
    )

    private fun qr(expires: Long, crypto: RelayCrypto = AndroidRelayCrypto): String =
        "{\"c\":\"${RelayBase64.standardEncode(CAPABILITY)}\"," +
            "\"i\":\"${RelayBase64.standardEncode(INSTALLATION)}\"," +
            "\"k\":\"${RelayBase64.standardEncode(crypto.x25519PublicKey(HOST_PRIVATE))}\", " +
            "\"o\":\"https://relay.example.com\",\"s\":\"mercury-relay\"," +
            "\"t\":\"routing.offer.token\",\"v\":1,\"x\":$expires}"

    companion object {
        private val INSTALLATION = ByteArray(32) { (it + 0x80).toByte() }
        private val CAPABILITY = ByteArray(32) { (it + 0xA0).toByte() }
        private val DEVICE_PRIVATE = ByteArray(32) { (it + 0x40).toByte() }
        private val HOST_PRIVATE = ByteArray(32) { (it + 0x20).toByte() }
        private val DEVICE_EPHEMERAL = ByteArray(32) { (it + 0x50).toByte() }
    }
}
