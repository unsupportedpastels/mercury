package com.unsupportedpastels.mercury.core.relay

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayProtocolTest {
    private val now = 1_756_400_000L
    private val installation = ByteArray(32) { (it + 0x80).toByte() }
    private val hostKey = hex("358072d6365880d1aeea329adf9121383851ed21a28e3b75e965d0d2cd166254")
    private val capability = ByteArray(32) { (it + 0xA0).toByte() }

    @Test
    fun pairingPayloadValidatesAndBuildsTheDeviceRoute() {
        val payload = RelayPairingPayload.parse(validQr(), now)

        assertContentEquals(installation, payload.installationId)
        assertContentEquals(hostKey, payload.hostPublicKey)
        assertContentEquals(capability, payload.capability)
        assertEquals("routing.test.token", payload.pairingRoutingToken)
        assertEquals(
            "wss://relay.example.com/v1/device/${RelayBase64.urlSafeEncode(installation)}",
            payload.deviceSocketUrl,
        )

        assertFailure(RelayProtocolFailure.MalformedPayload, "not json")
        assertFailure(RelayProtocolFailure.UnsupportedScheme, validQr().replace("mercury-relay", "other"))
        assertFailure(RelayProtocolFailure.UnsupportedVersion, validQr().replace("\"v\":1", "\"v\":2"))
        assertFailure(RelayProtocolFailure.ExpiredOffer, validQr(expires = now - 1))
        assertFailure(RelayProtocolFailure.MalformedPayload, validQr(expires = now + 100_000))
        assertFailure(RelayProtocolFailure.MalformedPayload, validQr(origin = "http://relay.example.com"))
        assertFailure(RelayProtocolFailure.MalformedPayload, validQr(origin = "https://relay.example.com/path"))
    }

    @Test
    fun base64AndAdmissionEnvelopeAreCanonical() {
        val deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { it.toByte() })
        assertEquals(22, deviceId.length)
        assertFalse(deviceId.contains('='))
        assertContentEquals(ByteArray(16) { it.toByte() }, RelayBase64.urlSafeDecodeExact(deviceId, 16))
        assertNull(RelayBase64.urlSafeDecodeExact("$deviceId=", 16))

        assertEquals(
            "{\"device_id\":\"$deviceId\",\"profile\":\"default\",\"resume_cursor\":42,\"type\":\"controller.open\"}",
            RelayAdmissionEnvelope.controllerOpen(deviceId, "default", 42).decodeToString(),
        )
        assertFailsWith<RelayProtocolException> {
            RelayAdmissionEnvelope.controllerOpen(deviceId, "bad profile")
        }
        // Channel-scoped leases: the field leads (sorted-key order) and the
        // legacy bytes above are untouched when no channel is given.
        assertEquals(
            "{\"channel\":\"s_1-A\",\"device_id\":\"$deviceId\",\"profile\":\"default\"," +
                "\"type\":\"controller.open\",\"recovery_version\":1}",
            RelayAdmissionEnvelope.controllerOpen(deviceId, "default", null, 1, "s_1-A").decodeToString(),
        )
        assertEquals(
            "{\"device_id\":\"$deviceId\",\"device_name\":\"Mark's Fold \\\"8\\\"\",\"profile\":\"default\",\"type\":\"controller.open\"}",
            RelayAdmissionEnvelope.controllerOpen(deviceId, "default", null, null, null, "  Mark's  Fold \"8\" ").decodeToString(),
        )
        assertEquals(null, RelayAdmissionEnvelope.cleanDeviceName("  \t "))
        assertEquals(null, RelayAdmissionEnvelope.cleanDeviceName("bad\u0001name"))
        assertEquals(64, RelayAdmissionEnvelope.cleanDeviceName("x".repeat(90))!!.length)
        assertEquals("s-20260906_034455_bfd2a6", RelayAdmissionEnvelope.channelForSession("20260906_034455_bfd2a6"))
        assertEquals("s-draft-1", RelayAdmissionEnvelope.channelForSession("draft-1"))
        assertEquals("s-a_b_c", RelayAdmissionEnvelope.channelForSession("a/b c"))
        assertEquals(64, RelayAdmissionEnvelope.channelForSession("x".repeat(100)).length)
        assertTrue(RelayAdmissionEnvelope.isValidChannel(RelayAdmissionEnvelope.channelForSession("\u00fc/?")))
        for (bad in listOf("", "has space", "a/b", "x".repeat(65), "\u00fc")) {
            assertFailsWith<RelayProtocolException> {
                RelayAdmissionEnvelope.controllerOpen(deviceId, "default", null, null, bad)
            }
        }
        assertTrue(RelayApprovalProbe.isSuccessfulGatewayPing(
            "{\"jsonrpc\":\"2.0\",\"id\":\"pairing-probe\",\"result\":{\"ok\":true}}"
        ))
        assertFalse(RelayApprovalProbe.isSuccessfulGatewayPing(
            "{\"jsonrpc\":\"2.0\",\"id\":\"pairing-probe\",\"error\":{\"code\":-1}}"
        ))
        assertEquals(
            RelayApprovalProbeResult.Ignore,
            RelayApprovalProbe.evaluateGatewayPing(
                "{\"jsonrpc\":\"2.0\",\"method\":\"event\",\"params\":{\"type\":\"sessions.changed\"}}"
            ),
        )
        assertEquals(
            RelayApprovalProbeResult.Ignore,
            RelayApprovalProbe.evaluateGatewayPing(
                "{\"jsonrpc\":\"2.0\",\"method\":\"relay.lease.attached\",\"params\":{}}"
            ),
        )
        val ack = RelayPairingAck.parseEnvelope(
            "{\"device_id\":\"$deviceId\",\"relay_token\":\"routing.device.token\",\"type\":\"pairing.pending\"}"
                .encodeToByteArray()
        )
        assertEquals(deviceId, ack.deviceId)
        assertEquals("routing.device.token", ack.relayRoutingToken)
    }

    @Test
    fun framingMatchesTheCanonicalSmallVectorAndReassemblesStrictly() {
        val channel = hex("10112233445566778899aabbccddeeff")
        val message = hex("0f0e0d0c0b0a09080706050403020100")
        val payload = hex("736d616c6c007061796c6f6164")

        val record = RelayFraming.encodeMessage(channel, message, payload).single()
        assertEquals(
            "4d5201010000000000010000000d0000000d10112233445566778899aabbccddeeff0f0e0d0c0b0a09080706050403020100736d616c6c007061796c6f6164",
            record.toHex(),
        )
        val frame = RelayFraming.decodeRecord(record)
        assertContentEquals(payload, frame.payload)

        val fragmented = RelayFraming.encodeMessage(
            channel,
            message,
            ByteArray(RelayFraming.maxPayloadBytes + 1) { 0x78 },
        )
        assertEquals(listOf(RelayFraming.maxNoisePlaintextBytes, 51), fragmented.map(ByteArray::size))
        val reassembler = RelayFrameReassembler(channel)
        assertNull(reassembler.push(fragmented[0]))
        assertFailsWith<RelayFramingException> { reassembler.push(fragmented[0]) }
        assertFalse(reassembler.inProgress)
    }

    @Test
    fun targetCodecRoundTripsAndFailsClosed() {
        val target = RelayPairedTarget(
            id = "00000000-0000-4000-8000-000000000001",
            label = "study",
            relayOrigin = "https://relay.example.com",
            installationId = installation,
            hostPublicKey = hostKey,
            deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { (it + 3).toByte() }),
            deviceStaticPrivateKey = ByteArray(32) { (it + 0x40).toByte() },
            fingerprint = "0123456789abcdef",
            status = RelayTargetStatus.Pending,
            createdAtEpochSeconds = now,
            lastUsedEpochSeconds = null,
            relayRoutingToken = "routing.device.token",
        )

        val encoded = RelayTargetCodec.encode(listOf(target))
        val decoded = RelayTargetCodec.decode(encoded).single()
        assertEquals(target.id, decoded.id)
        assertEquals(target.label, decoded.label)
        assertContentEquals(target.deviceStaticPrivateKey, decoded.deviceStaticPrivateKey)
        assertEquals(target.relayRoutingToken, decoded.relayRoutingToken)
        assertFailsWith<RelayTargetCodecException> { RelayTargetCodec.decode("corrupt".encodeToByteArray()) }
    }

    private fun validQr(
        origin: String = "https://relay.example.com",
        expires: Long = now + 300,
    ): String =
        "{\"c\":\"${RelayBase64.standardEncode(capability)}\"," +
            "\"i\":\"${RelayBase64.standardEncode(installation)}\"," +
            "\"k\":\"${RelayBase64.standardEncode(hostKey)}\"," +
            "\"o\":\"$origin\",\"s\":\"mercury-relay\",\"t\":\"routing.test.token\"," +
            "\"v\":1,\"x\":$expires}"

    private fun assertFailure(expected: RelayProtocolFailure, text: String) {
        val error = assertFailsWith<RelayProtocolException> {
            RelayPairingPayload.parse(text, now)
        }
        assertEquals(expected, error.failure)
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
