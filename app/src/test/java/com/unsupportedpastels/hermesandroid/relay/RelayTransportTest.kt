package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.mercury.core.relay.AndroidRelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayBase64
import com.unsupportedpastels.mercury.core.relay.RelayFraming
import com.unsupportedpastels.mercury.core.relay.RelayFrameReassembler
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelaySecureChannel
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayTransportTest {
    @Test
    fun connectorAdmitsAndCarriesHermesTextBothWays() = runTest {
        val pair = binarySocketPair()
        val factory = FakeRelayBinarySocketFactory(pair.device)
        val target = target()
        val diagnostics = RelayDiagnostics(
            capacity = 16,
            attemptIdFactory = { "connect-attempt" },
            appBuildMetadata = RelayAppBuildMetadata("0.2.2", 1),
            logger = {},
        )

        val host = RelaySecureChannel(
            crypto = AndroidRelayCrypto,
            isInitiator = false,
            staticPrivateKey = HOST_PRIVATE,
            installationId = INSTALLATION,
            remoteStaticPublicKey = null,
            deterministicEphemeralPrivateKey = HOST_EPHEMERAL,
        )
        val hostTask = backgroundScope.launch {
            host.readHandshake(requireNotNull(pair.host.receive()))
            pair.host.send(host.writeHandshake())
            host.readHandshake(requireNotNull(pair.host.receive()))
            val envelope = host.decrypt(requireNotNull(pair.host.receive())).decodeToString()
            assertEquals(
                "{\"device_id\":\"${target.deviceId}\",\"profile\":\"default\",\"type\":\"controller.open\"}",
                envelope,
            )
        }

        val connected = RelayConnector.connect(
            target = target,
            profile = "default",
            socketFactory = factory,
            crypto = AndroidRelayCrypto,
            deterministicEphemeralPrivateKey = DEVICE_EPHEMERAL,
            diagnostics = diagnostics,
        )
        hostTask.join()
        val chat = RelayHermesChatSocket(connected) { ByteArray(16) { 0x22 } }

        val outbound = "  {\"jsonrpc\":\"2.0\",\"method\":\"prompt.submit\"}  "
        chat.sendText(outbound)
        val hostReassembler = RelayFrameReassembler(host.channelBinding.copyOfRange(0, 16))
        var hostMessage: ByteArray? = null
        while (hostMessage == null) {
            hostMessage = hostReassembler.push(host.decrypt(requireNotNull(pair.host.receive())))
        }
        assertEquals(outbound, hostMessage.decodeToString())

        val reply = "{\"method\":\"event\",\"params\":{\"type\":\"message.delta\",\"payload\":{\"text\":\" leading\"}}}"
        RelayFraming.encodeMessage(
            host.channelBinding.copyOfRange(0, 16),
            ByteArray(16) { 0x23 },
            reply.encodeToByteArray(),
        ).forEach { pair.host.send(host.encrypt(it)) }
        assertEquals(reply, chat.receiveText())

        pair.host.close()
        assertNull(chat.receiveText())
        chat.close()
        assertArrayEquals(INSTALLATION, factory.connectedInstallationRoute)
        assertEquals("routing.device.token", factory.connectedRoutingToken)
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
        assertEquals(RelayDiagnosticCloseReason.RemoteEndOfStream, diagnostics.snapshot().last().closeReason)
    }

    @Test
    fun connectorFailureRecordsSafePhaseAndReason() = runTest {
        val logs = mutableListOf<String>()
        val diagnostics = RelayDiagnostics(
            capacity = 8,
            attemptIdFactory = { "failed-attempt" },
            logger = logs::add,
        )
        val error = runCatching {
            RelayConnector.connect(
                target = target(),
                profile = "default",
                socketFactory = RelayBinarySocketFactory { _, _ ->
                    throw RelayConnectionException(RelayConnectionFailure.NotAuthorized)
                },
                diagnostics = diagnostics,
            )
        }.exceptionOrNull()

        assertTrue(error is RelayConnectionException)
        assertEquals(
            listOf(RelayDiagnosticPhase.Attempt, RelayDiagnosticPhase.Open),
            diagnostics.snapshot().map { it.phase },
        )
        assertEquals(RelayDiagnosticStatus.Failed, diagnostics.snapshot().last().status)
        assertEquals(RelayDiagnosticReason.NotAuthorized, diagnostics.snapshot().last().reason)
        assertFalse(logs.joinToString().contains("Mercury Relay connection failed"))
    }

    @Test
    fun connectorRecoveryAdmissionPreservesOptInWireBytes() = runTest {
        val pair = binarySocketPair()
        val target = target()
        val host = RelaySecureChannel(
            crypto = AndroidRelayCrypto,
            isInitiator = false,
            staticPrivateKey = HOST_PRIVATE,
            installationId = INSTALLATION,
            remoteStaticPublicKey = null,
            deterministicEphemeralPrivateKey = HOST_EPHEMERAL,
        )
        val hostTask = backgroundScope.launch {
            host.readHandshake(requireNotNull(pair.host.receive()))
            pair.host.send(host.writeHandshake())
            host.readHandshake(requireNotNull(pair.host.receive()))
            assertEquals(
                "{\"device_id\":\"${target.deviceId}\",\"profile\":\"default\",\"resume_cursor\":42,\"type\":\"controller.open\",\"recovery_version\":1}",
                host.decrypt(requireNotNull(pair.host.receive())).decodeToString(),
            )
        }
        val connected = RelayConnector.connect(
            target = target,
            profile = "default",
            socketFactory = FakeRelayBinarySocketFactory(pair.device),
            resumeCursor = 42,
            recoveryVersion = 1,
            deterministicEphemeralPrivateKey = DEVICE_EPHEMERAL,
        )
        hostTask.join()
        RelayHermesChatSocket(connected).close()
        host.close()
    }

    private fun target() = RelayPairedTarget(
        id = "00000000-0000-4000-8000-000000000001",
        label = "study",
        relayOrigin = "https://relay.example.com",
        installationId = INSTALLATION,
        hostPublicKey = AndroidRelayCrypto.x25519PublicKey(HOST_PRIVATE),
        deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { (it + 3).toByte() }),
        deviceStaticPrivateKey = DEVICE_PRIVATE,
        fingerprint = "0123456789abcdef",
        status = RelayTargetStatus.Approved,
        createdAtEpochSeconds = 1,
        lastUsedEpochSeconds = null,
        relayRoutingToken = "routing.device.token",
    )

    private class FakeRelayBinarySocketFactory(
        private val socket: RelayBinarySocket,
    ) : RelayBinarySocketFactory {
        var connectedInstallationRoute: ByteArray? = null
        var connectedRoutingToken: String? = null
        override suspend fun connect(url: String, routingToken: String?): RelayBinarySocket {
            val encoded = url.substringAfterLast('/')
            connectedInstallationRoute = RelayBase64.urlSafeDecodeExact(encoded, 32)
            connectedRoutingToken = routingToken
            return socket
        }
    }

    private data class SocketPair(val device: RelayBinarySocket, val host: RelayBinarySocket)

    private fun binarySocketPair(): SocketPair {
        val toDevice = Channel<ByteArray>(Channel.UNLIMITED)
        val toHost = Channel<ByteArray>(Channel.UNLIMITED)
        return SocketPair(
            ChannelRelayBinarySocket(toDevice, toHost),
            ChannelRelayBinarySocket(toHost, toDevice),
        )
    }

    private class ChannelRelayBinarySocket(
        private val incoming: Channel<ByteArray>,
        private val outgoing: Channel<ByteArray>,
    ) : RelayBinarySocket {
        override suspend fun send(data: ByteArray) { outgoing.send(data.copyOf()) }
        override suspend fun receive(): ByteArray? = incoming.receiveCatching().getOrNull()
        override suspend fun close() { outgoing.close() }
    }

    companion object {
        private val INSTALLATION = ByteArray(32) { (it + 0x80).toByte() }
        private val DEVICE_PRIVATE = ByteArray(32) { it.toByte() }
        private val HOST_PRIVATE = ByteArray(32) { (it + 0x20).toByte() }
        private val DEVICE_EPHEMERAL = ByteArray(32) { (it + 0x40).toByte() }
        private val HOST_EPHEMERAL = ByteArray(32) { (it + 0x60).toByte() }
    }
}
