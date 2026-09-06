package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.mercury.core.relay.AndroidRelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayBase64
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelaySecureChannel
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayPairingCoordinatorTest {
    @Test
    fun qrPairingCarriesCapabilityOnlyInNoiseAndStoresPendingTarget() = runTest {
        val sockets = socketPair()
        val repository = InMemoryTargetRepository()
        val deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { (it + 3).toByte() })
        val now = 1_756_400_000L
        var pairingRoutingToken: String? = null
        val diagnostics = RelayDiagnostics(
            capacity = 16,
            attemptIdFactory = { "pairing-attempt" },
            logger = {},
        )
        val coordinator = RelayPairingCoordinator(
            socketFactory = RelayBinarySocketFactory { _, token ->
                pairingRoutingToken = token
                sockets.device
            },
            targets = repository,
            crypto = AndroidRelayCrypto,
            nowEpochSeconds = { now },
            makeId = { "00000000-0000-4000-8000-000000000001" },
            makeDeviceKey = { DEVICE_PRIVATE.copyOf() },
            deterministicEphemeralPrivateKey = DEVICE_EPHEMERAL,
            diagnostics = diagnostics,
        )
        val hostTask = backgroundScope.async {
            val host = RelaySecureChannel(
                AndroidRelayCrypto,
                false,
                HOST_PRIVATE,
                INSTALLATION,
                null,
                HOST_EPHEMERAL,
            )
            host.readHandshake(requireNotNull(sockets.host.receive()))
            sockets.host.send(host.writeHandshake())
            val delivered = host.readHandshake(requireNotNull(sockets.host.receive()))
            assertArrayEquals(CAPABILITY, delivered)
            val ack = "{\"device_id\":\"$deviceId\",\"relay_token\":\"routing.device.token\",\"type\":\"pairing.pending\"}"
                .encodeToByteArray()
            sockets.host.send(host.encrypt(ack))
            sockets.host.close()
            host.channelBinding
        }

        val target = coordinator.pair(qr(now + 300))
        val binding = hostTask.await()

        assertEquals(RelayTargetStatus.Pending, target.status)
        assertEquals(deviceId, target.deviceId)
        assertEquals("routing.offer.token", pairingRoutingToken)
        assertEquals("routing.device.token", target.relayRoutingToken)
        assertEquals(
            com.unsupportedpastels.mercury.core.relay.RelayFingerprint.shortAuthenticationString(
                AndroidRelayCrypto,
                binding,
            ),
            target.fingerprint,
        )
        assertArrayEquals(DEVICE_PRIVATE, target.deviceStaticPrivateKey)
        assertEquals(listOf(target.id), repository.targets.map { it.id })
        assertEquals(
            listOf(
                RelayDiagnosticPhase.Attempt,
                RelayDiagnosticPhase.Open,
                RelayDiagnosticPhase.Handshake,
                RelayDiagnosticPhase.Admission,
                RelayDiagnosticPhase.Disconnect,
            ),
            diagnostics.snapshot().map { it.phase },
        )
        assertEquals(RelayDiagnosticCloseReason.PairingComplete, diagnostics.snapshot().last().closeReason)
    }

    private fun qr(expires: Long): String =
        "{\"c\":\"${RelayBase64.standardEncode(CAPABILITY)}\"," +
            "\"i\":\"${RelayBase64.standardEncode(INSTALLATION)}\"," +
            "\"k\":\"${RelayBase64.standardEncode(AndroidRelayCrypto.x25519PublicKey(HOST_PRIVATE))}\"," +
            "\"o\":\"https://relay.example.com\",\"s\":\"mercury-relay\"," +
            "\"t\":\"routing.offer.token\",\"v\":1,\"x\":$expires}"

    private class InMemoryTargetRepository : RelayTargetRepository {
        val targets = mutableListOf<RelayPairedTarget>()
        override suspend fun load(): List<RelayPairedTarget> = targets.toList()
        override suspend fun add(target: RelayPairedTarget): RelayPairedTarget = target.also(targets::add)
        override suspend fun markApproved(id: String, nowEpochSeconds: Long) {
            val index = targets.indexOfFirst { it.id == id }
            targets[index] = targets[index].copy(status = RelayTargetStatus.Approved, lastUsedEpochSeconds = nowEpochSeconds)
        }
        override suspend fun touch(id: String, nowEpochSeconds: Long) = Unit
        override suspend fun updateLabel(id: String, label: String) = Unit
        override suspend fun remove(id: String) { targets.removeAll { it.id == id } }
    }

    private data class Pair(val device: RelayBinarySocket, val host: RelayBinarySocket)
    private fun socketPair(): Pair {
        val toDevice = Channel<ByteArray>(Channel.UNLIMITED)
        val toHost = Channel<ByteArray>(Channel.UNLIMITED)
        return Pair(Socket(toDevice, toHost), Socket(toHost, toDevice))
    }
    private class Socket(
        private val incoming: Channel<ByteArray>,
        private val outgoing: Channel<ByteArray>,
    ) : RelayBinarySocket {
        override suspend fun send(data: ByteArray) { outgoing.send(data.copyOf()) }
        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()
        override suspend fun close() { outgoing.close() }
    }

    companion object {
        private val INSTALLATION = ByteArray(32) { (it + 0x80).toByte() }
        private val CAPABILITY = ByteArray(32) { (it + 0xA0).toByte() }
        private val DEVICE_PRIVATE = ByteArray(32) { (it + 0x40).toByte() }
        private val HOST_PRIVATE = ByteArray(32) { (it + 0x20).toByte() }
        private val DEVICE_EPHEMERAL = ByteArray(32) { (it + 0x50).toByte() }
        private val HOST_EPHEMERAL = ByteArray(32) { (it + 0x60).toByte() }
    }
}
