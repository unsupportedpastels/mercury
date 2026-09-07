package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.mercury.core.relay.RelayPlatformCrypto
import com.unsupportedpastels.mercury.core.relay.RelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayFingerprint
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayPairingAck
import com.unsupportedpastels.mercury.core.relay.RelayApprovalProbe
import com.unsupportedpastels.mercury.core.relay.RelayApprovalProbeResult
import com.unsupportedpastels.mercury.core.relay.RelayPairingPayload
import com.unsupportedpastels.mercury.core.relay.RelayProtocolException
import com.unsupportedpastels.mercury.core.relay.RelayProtocolFailure
import com.unsupportedpastels.mercury.core.relay.RelaySecureChannel
import com.unsupportedpastels.mercury.core.relay.RelayTargetCodec
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

private const val RELAY_PAIRING_NEGOTIATION_TIMEOUT_MILLIS = 10_000L

enum class RelayPairingFailure {
    MalformedQr,
    UnsupportedVersion,
    MissingRelayOrigin,
    ExpiredOffer,
    OfferRejected,
    Offline,
    ProtocolViolation,
    StorageFailed,
    TargetLimitReached,
}

class RelayPairingException(
    val failure: RelayPairingFailure,
) : Exception("Mercury Relay pairing failed")

class RelayPairingCoordinator(
    private val socketFactory: RelayBinarySocketFactory,
    private val targets: RelayTargetRepository,
    private val crypto: RelayCrypto = RelayPlatformCrypto,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
    private val makeId: () -> String = { UUID.randomUUID().toString() },
    private val makeDeviceKey: () -> ByteArray = { RelayPlatformCrypto.randomBytes(32) },
    private val deterministicEphemeralPrivateKey: ByteArray? = null,
    private val diagnostics: RelayDiagnostics = RelayDiagnostics.shared,
) {
    suspend fun pair(scannedText: String): RelayPairedTarget {
        val attempt = diagnostics.beginAttempt(RelayDiagnosticOperation.Pairing)
        val payload = try {
            RelayPairingPayload.parse(scannedText, nowEpochSeconds())
        } catch (error: RelayProtocolException) {
            attempt.recordFailure(RelayDiagnosticPhase.Attempt, error.failure.toPairingFailure().toDiagnosticReason())
            throw RelayPairingException(error.failure.toPairingFailure())
        }
        val existing = try {
            targets.load()
        } catch (cancelled: CancellationException) {
            payload.capability.fill(0)
            attempt.recordCancellation()
            throw cancelled
        } catch (_: Exception) {
            payload.capability.fill(0)
            attempt.recordFailure(RelayDiagnosticPhase.Attempt, RelayDiagnosticReason.StorageFailed)
            throw RelayPairingException(RelayPairingFailure.StorageFailed)
        }
        if (existing.size >= RelayTargetCodec.maxTargets) {
            payload.capability.fill(0)
            attempt.recordFailure(RelayDiagnosticPhase.Attempt, RelayDiagnosticReason.StorageFailed)
            throw RelayPairingException(RelayPairingFailure.TargetLimitReached)
        }
        val deviceKey = try {
            makeDeviceKey()
        } catch (cancelled: CancellationException) {
            payload.capability.fill(0)
            attempt.recordCancellation()
            throw cancelled
        } catch (error: Exception) {
            payload.capability.fill(0)
            attempt.recordFailure(RelayDiagnosticPhase.Attempt, RelayDiagnosticReason.ProtocolViolation)
            throw error
        }
        if (deviceKey.size != 32) {
            payload.capability.fill(0)
            attempt.recordFailure(RelayDiagnosticPhase.Attempt, RelayDiagnosticReason.ProtocolViolation)
            throw RelayPairingException(RelayPairingFailure.ProtocolViolation)
        }
        val socket = try {
            socketFactory.connectRelaySocket(payload.deviceSocketUrl, payload.pairingRoutingToken, attempt)
        } catch (cancelled: CancellationException) {
            payload.capability.fill(0)
            attempt.recordCancellation()
            throw cancelled
        } catch (_: Exception) {
            payload.capability.fill(0)
            attempt.recordFailure(RelayDiagnosticPhase.Open, RelayDiagnosticReason.Offline)
            throw RelayPairingException(RelayPairingFailure.Offline)
        }
        attempt.recordSuccess(RelayDiagnosticPhase.Open)

        var channel: RelaySecureChannel? = null
        var phase = RelayDiagnosticPhase.Handshake
        var closeReason = RelayDiagnosticCloseReason.LocalFailure
        try {
            val createdChannel = RelaySecureChannel(
                crypto = crypto,
                isInitiator = true,
                staticPrivateKey = deviceKey,
                installationId = payload.installationId,
                remoteStaticPublicKey = payload.hostPublicKey,
                deterministicEphemeralPrivateKey = deterministicEphemeralPrivateKey,
            )
            channel = createdChannel
            val pairingAck = withTimeoutOrNull(RELAY_PAIRING_NEGOTIATION_TIMEOUT_MILLIS) {
                socket.send(createdChannel.writeHandshake())
                val second = socket.receive() ?: throw RelayPairingException(RelayPairingFailure.Offline)
                createdChannel.readHandshake(second)
                attempt.recordSuccess(RelayDiagnosticPhase.Handshake)
                phase = RelayDiagnosticPhase.Admission
                socket.send(createdChannel.writeHandshake(payload.capability))
                payload.capability.fill(0)
                val ack = socket.receive() ?: throw RelayPairingException(RelayPairingFailure.OfferRejected)
                RelayPairingAck.parseEnvelope(createdChannel.decrypt(ack))
                    .also { attempt.recordSuccess(RelayDiagnosticPhase.Admission) }
            } ?: run {
                currentCoroutineContext().ensureActive()
                attempt.recordTimeout(phase)
                closeReason = RelayDiagnosticCloseReason.Timeout
                throw RelayPairingException(RelayPairingFailure.Offline)
            }
            payload.capability.fill(0)
            val fingerprint = RelayFingerprint.shortAuthenticationString(crypto, createdChannel.channelBinding)
            closeRelayResources(socket, createdChannel)
            channel = null
            attempt.recordDisconnect(RelayDiagnosticCloseReason.PairingComplete)
            val target = RelayPairedTarget(
                id = makeId(),
                label = "",
                relayOrigin = payload.relayOrigin,
                installationId = payload.installationId.copyOf(),
                hostPublicKey = payload.hostPublicKey.copyOf(),
                deviceId = pairingAck.deviceId,
                deviceStaticPrivateKey = deviceKey.copyOf(),
                fingerprint = fingerprint,
                status = RelayTargetStatus.Pending,
                createdAtEpochSeconds = maxOf(0, nowEpochSeconds()),
                lastUsedEpochSeconds = null,
                relayRoutingToken = pairingAck.relayRoutingToken,
            )
            return try {
                targets.add(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RelayTargetStoreException) {
                throw RelayPairingException(
                    if (error.failure == RelayTargetStoreFailure.TargetLimitReached) {
                        RelayPairingFailure.TargetLimitReached
                    } else {
                        RelayPairingFailure.StorageFailed
                    }
                )
            } catch (_: Exception) {
                throw RelayPairingException(RelayPairingFailure.StorageFailed)
            }
        } catch (cancelled: CancellationException) {
            payload.capability.fill(0)
            attempt.recordCancellation()
            closeRelayResources(socket, channel)
            attempt.recordDisconnect(RelayDiagnosticCloseReason.Cancelled)
            throw cancelled
        } catch (error: RelayPairingException) {
            payload.capability.fill(0)
            attempt.recordFailure(phase, error.failure.toDiagnosticReason())
            closeRelayResources(socket, channel)
            attempt.recordDisconnect(closeReason)
            throw error
        } catch (_: RelayProtocolException) {
            payload.capability.fill(0)
            attempt.recordFailure(phase, RelayDiagnosticReason.ProtocolViolation)
            closeRelayResources(socket, channel)
            attempt.recordDisconnect(RelayDiagnosticCloseReason.ProtocolViolation)
            throw RelayPairingException(RelayPairingFailure.ProtocolViolation)
        } catch (_: Exception) {
            payload.capability.fill(0)
            attempt.recordFailure(phase, RelayDiagnosticReason.ProtocolViolation)
            closeRelayResources(socket, channel)
            attempt.recordDisconnect(RelayDiagnosticCloseReason.LocalFailure)
            throw RelayPairingException(RelayPairingFailure.ProtocolViolation)
        }
    }

    suspend fun probeApproval(target: RelayPairedTarget, profile: String): Boolean {
        val connected = try {
            RelayConnector.connect(target, profile, socketFactory, crypto, diagnostics = diagnostics)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        val chat = RelayHermesChatSocket(connected)
        return try {
            chat.sendText("{\"jsonrpc\":\"2.0\",\"id\":\"pairing-probe\",\"method\":\"gateway.ping\",\"params\":{}}")
            val approved = withTimeoutOrNull(10_000) {
                repeat(32) {
                    val response = chat.receiveText() ?: return@withTimeoutOrNull false
                    when (RelayApprovalProbe.evaluateGatewayPing(response)) {
                        RelayApprovalProbeResult.Approved -> return@withTimeoutOrNull true
                        RelayApprovalProbeResult.Rejected -> return@withTimeoutOrNull false
                        RelayApprovalProbeResult.Ignore -> Unit
                    }
                }
                false
            } == true
            if (approved) targets.markApproved(target.id, nowEpochSeconds())
            approved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            chat.close()
        }
    }

    private fun RelayProtocolFailure.toPairingFailure(): RelayPairingFailure = when (this) {
        RelayProtocolFailure.UnsupportedVersion -> RelayPairingFailure.UnsupportedVersion
        RelayProtocolFailure.MissingRelayOrigin -> RelayPairingFailure.MissingRelayOrigin
        RelayProtocolFailure.ExpiredOffer -> RelayPairingFailure.ExpiredOffer
        RelayProtocolFailure.MalformedPayload,
        RelayProtocolFailure.UnsupportedScheme,
        RelayProtocolFailure.InvalidEnvelope,
        -> RelayPairingFailure.MalformedQr
    }
}
