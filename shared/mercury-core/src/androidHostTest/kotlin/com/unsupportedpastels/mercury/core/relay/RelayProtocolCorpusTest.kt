package com.unsupportedpastels.mercury.core.relay

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the shared relay core through the canonical mercury-relay-plugin
 * vectors (vendored once under commonTest/resources/relay-protocol; iOS
 * bundles the same files). Both ends of the encrypted channel test against
 * the same bytes, so a drift here means phone and host disagree.
 */
class RelayProtocolCorpusTest {
    private val crypto = RelayPlatformCrypto

    private fun corpus(name: String): JsonObject {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("relay-protocol/$name")) {
            "missing relay corpus $name"
        }
        return Json.parseToJsonElement(stream.reader().readText()).jsonObject
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String): Int = checkNotNull(getValue(key).jsonPrimitive.intOrNull)
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /**
     * The JDK ChaCha20-Poly1305 provider refuses to reuse a nonce under a key
     * it has already seen in this process, so the deterministic vector keys
     * can be driven exactly once per JVM (the positive-vector test). Every
     * other case uses fresh ephemerals, which is also what production does.
     */
    private fun channelPair(keys: JsonObject, deterministic: Boolean): Pair<RelaySecureChannel, RelaySecureChannel> {
        val installation = hex(keys.text("installation_id"))
        val mobile = RelaySecureChannel(
            crypto = crypto,
            isInitiator = true,
            staticPrivateKey = hex(keys.text("initiator_static_private")),
            installationId = installation,
            remoteStaticPublicKey = hex(keys.text("responder_static_public")),
            deterministicEphemeralPrivateKey = hex(keys.text("initiator_ephemeral_private")).takeIf { deterministic },
        )
        val host = RelaySecureChannel(
            crypto = crypto,
            isInitiator = false,
            staticPrivateKey = hex(keys.text("responder_static_private")),
            installationId = installation,
            remoteStaticPublicKey = null,
            deterministicEphemeralPrivateKey = hex(keys.text("responder_ephemeral_private")).takeIf { deterministic },
        )
        return mobile to host
    }

    @Test
    fun noiseVectorsReproduceExactHandshakeAndTransportBytes() {
        val vectors = corpus("secure-channel-corpus.json").getValue("vectors").jsonArray
        assertTrue(vectors.isNotEmpty())
        for (element in vectors) {
            val vector = element.jsonObject
            val id = vector.text("id")
            val keys = vector.getValue("keys").jsonObject
            val (mobile, host) = channelPair(keys, deterministic = true)
            val payloads = vector.getValue("handshake_payloads").jsonArray.map { it.jsonPrimitive.content }
            val finalPayload = hex(payloads[2])

            val first = mobile.writeHandshake()
            assertContentEquals(byteArrayOf(), host.readHandshake(first), id)
            val second = host.writeHandshake()
            assertContentEquals(byteArrayOf(), mobile.readHandshake(second), id)
            val third = mobile.writeHandshake(finalPayload)
            assertContentEquals(finalPayload, host.readHandshake(third), id)
            assertEquals(
                vector.getValue("handshake_messages").jsonArray.map { it.jsonPrimitive.content },
                listOf(first.toHex(), second.toHex(), third.toHex()),
                id,
            )
            assertEquals(vector.text("channel_binding"), mobile.channelBinding.toHex(), id)
            assertContentEquals(mobile.channelBinding, host.channelBinding, id)
            assertContentEquals(hex(keys.text("initiator_static_public")), host.remoteStaticPublicKey, id)

            var lastReceiver: RelaySecureChannel? = null
            var lastCiphertext: ByteArray? = null
            for (record in vector.getValue("transport_messages").jsonArray) {
                val message = record.jsonObject
                val plaintext = hex(message.text("plaintext_hex"))
                val (sender, receiver) = if (message.text("direction") == "initiator_to_responder") {
                    mobile to host
                } else {
                    host to mobile
                }
                val ciphertext = sender.encrypt(plaintext)
                assertEquals(message.text("ciphertext_hex"), ciphertext.toHex(), id)
                assertContentEquals(plaintext, receiver.decrypt(ciphertext), id)
                lastReceiver = receiver
                lastCiphertext = ciphertext
            }
            // Replaying a record is a fatal authentication failure that closes the channel.
            val replayTarget = assertNotNull(lastReceiver, id)
            assertFailsWith<RelaySecureChannelException>(id) { replayTarget.decrypt(assertNotNull(lastCiphertext)) }
            assertTrue(replayTarget.closed, id)
        }
    }

    @Test
    fun secureChannelNegativeCasesFailClosed() {
        val vector = corpus("secure-channel-corpus.json").getValue("vectors").jsonArray
            .first { it.jsonObject.text("id") == "pairing-xk-capability" }.jsonObject
        val keys = vector.getValue("keys").jsonObject
        val expected = vector.getValue("negative_cases").jsonArray.associate {
            it.jsonObject.text("id") to it.jsonObject.text("expected")
        }
        assertEquals("authentication_failed_close_channel", expected["tampered-handshake"])
        run {
            val (mobile, host) = channelPair(keys, deterministic = false)
            val first = mobile.writeHandshake()
            first[first.lastIndex] = (first[first.lastIndex].toInt() xor 0x01).toByte()
            assertFailsWith<RelaySecureChannelException> { host.readHandshake(first) }
            assertTrue(host.closed)
        }
        assertEquals("authentication_failed_close_channel", expected["truncated-handshake"])
        run {
            val (mobile, host) = channelPair(keys, deterministic = false)
            val first = mobile.writeHandshake()
            assertFailsWith<RelaySecureChannelException> { host.readHandshake(first.copyOf(first.size - 1)) }
        }
        assertEquals("authentication_failed_close_channel", expected["wrong-responder-identity"])
        run {
            val (_, host) = channelPair(keys, deterministic = false)
            val wrongMobile = RelaySecureChannel(
                crypto = crypto,
                isInitiator = true,
                staticPrivateKey = hex(keys.text("initiator_static_private")),
                installationId = hex(keys.text("installation_id")),
                // A valid key that is not the host's static identity.
                remoteStaticPublicKey = crypto.x25519PublicKey(hex(keys.text("responder_ephemeral_private"))),
                deterministicEphemeralPrivateKey = null,
            )
            // XK message 1 is already bound to the assumed responder identity, so
            // the host cannot authenticate it and closes the channel.
            assertFailsWith<RelaySecureChannelException> { host.readHandshake(wrongMobile.writeHandshake()) }
            assertTrue(host.closed)
        }
        assertEquals("authentication_failed_close_channel", expected["tampered-ciphertext"])
        run {
            val (mobile, host) = channelPair(keys, deterministic = false)
            host.readHandshake(mobile.writeHandshake())
            mobile.readHandshake(host.writeHandshake())
            host.readHandshake(mobile.writeHandshake(hex(keys.text("pairing_capability"))))
            val ciphertext = mobile.encrypt(byteArrayOf(1, 2, 3))
            ciphertext[0] = (ciphertext[0].toInt() xor 0x80).toByte()
            assertFailsWith<RelaySecureChannelException> { host.decrypt(ciphertext) }
            assertTrue(host.closed)
        }
    }

    @Test
    fun fingerprintIsTheSha256PrefixOfTheChannelBinding() {
        val binding = hex("9b112311dd467931d5ade1904bd013edb473921930352f50bfd55d4a632dd3e2")
        assertEquals(RelayProtocolPolicy.fingerprintHexCharacters, 16)
        assertEquals("d75d801b6461aa1e", RelayFingerprint.shortAuthenticationString(crypto, binding))
    }

    @Test
    fun productionEphemeralKeysAreFresh() {
        val keys = corpus("secure-channel-corpus.json").getValue("vectors").jsonArray.first().jsonObject.getValue("keys").jsonObject
        fun first() = RelaySecureChannel(
            crypto,
            true,
            hex(keys.text("initiator_static_private")),
            hex(keys.text("installation_id")),
            hex(keys.text("responder_static_public")),
            null,
        ).writeHandshake()
        assertTrue(!first().contentEquals(first()))
    }

    private fun framePayload(spec: JsonObject): ByteArray = when (spec.text("encoding")) {
        "hex" -> hex(spec.text("hex"))
        "utf8" -> spec.text("text").encodeToByteArray()
        "repeat" -> ByteArray(spec.int("length")) { hex(spec.text("byte_hex"))[0] }
        else -> error("unknown payload encoding ${spec.text("encoding")}")
    }

    @Test
    fun frameCorpusMatchesEncoderDecoderAndReassembler() {
        val corpus = corpus("frames-corpus.json")
        val protocol = corpus.getValue("protocol").jsonObject
        assertEquals(protocol.int("header_size"), RelayFraming.headerSize)
        assertEquals(protocol.int("max_payload_bytes"), RelayFraming.maxPayloadBytes)
        assertEquals(protocol.int("max_logical_message_bytes"), RelayFraming.maxLogicalMessageBytes)
        assertEquals(protocol.int("max_fragment_count"), RelayFraming.maxFragmentCount)
        for (element in corpus.getValue("vectors").jsonArray) {
            val vector = element.jsonObject
            val id = vector.text("id")
            val payload = framePayload(vector.getValue("payload").jsonObject)
            val channelId = hex(vector.text("channel_id_hex"))
            val messageId = hex(vector.text("message_id_hex"))
            val records = RelayFraming.encodeMessage(channelId, messageId, payload)
            assertEquals(vector.int("fragment_count"), records.size, id)
            assertEquals(vector.int("logical_length"), payload.size, id)
            assertEquals(vector.text("payload_sha256"), sha256(payload), id)
            val expectedRecords = vector.getValue("records").jsonArray
            assertEquals(expectedRecords.size, records.size, id)
            for ((record, expectedElement) in records.zip(expectedRecords)) {
                val expected = expectedElement.jsonObject
                assertEquals(expected.int("length"), record.size, id)
                val recordHex = (expected["record_hex"] as? JsonPrimitive)?.contentOrNull
                if (recordHex != null) {
                    assertEquals(recordHex, record.toHex(), id)
                } else {
                    assertEquals(expected.text("sha256"), sha256(record), id)
                }
                val frame = RelayFraming.decodeRecord(record)
                assertContentEquals(record.copyOfRange(RelayFraming.headerSize, record.size), frame.payload, id)
            }
            val reassembler = RelayFrameReassembler(channelId)
            var result: ByteArray? = null
            for (record in records) result = reassembler.push(record)
            assertContentEquals(payload, assertNotNull(result, id), id)
        }
    }

    @Test
    fun frameNegativeCasesFailClosed() {
        val corpus = corpus("frames-corpus.json")
        val cases = corpus.getValue("negative_cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.isNotEmpty())
        val channelId = hex("00112233445566778899aabbccddeeff")
        val messageId = hex("ffeeddccbbaa99887766554433221100")
        val twoRecords = RelayFraming.encodeMessage(channelId, messageId, ByteArray(RelayFraming.maxPayloadBytes + 1) { 0x78 })
        val single = RelayFraming.encodeMessage(channelId, messageId, "small payload".encodeToByteArray()).single()
        fun mutated(setter: (ByteArray) -> ByteArray): ByteArray = setter(single.copyOf())
        val covered = mutableSetOf<String>()
        for (case in cases) {
            val id = case.text("id")
            val mutation = case.text("mutation")
            when (case.text("operation")) {
                "decode" -> {
                    val record = when (mutation) {
                        "version=2" -> mutated { it[2] = 2; it }
                        "kind=2" -> mutated { it[3] = 2; it }
                        "flags=1" -> mutated { it[4] = 1; it }
                        "reserved=1" -> mutated { it[5] = 1; it }
                        "fragment_count=0" -> mutated { it[8] = 0; it[9] = 0; it }
                        "fragment_count=258" -> mutated { it[8] = 1; it[9] = 2; it }
                        "payload_length=payload_length+1" -> mutated { it[17] = (it[17] + 1).toByte(); it }
                        "append one byte" -> single + byteArrayOf(0)
                        else -> continue
                    }
                    assertEquals("decode_error", case.text("expected"), id)
                    assertNotNull(RelayFraming.decodeFailureReason(record), id)
                    covered += id
                }
                "reassemble" -> {
                    if (mutation != "repeat fragment index 0 before index 1") continue
                    assertEquals("reassembly_error", case.text("expected"), id)
                    val reassembler = RelayFrameReassembler(channelId)
                    assertNull(reassembler.push(twoRecords[0]), id)
                    assertFailsWith<RelayFramingException>(id) { reassembler.push(twoRecords[0]) }
                    covered += id
                }
            }
        }
        // Every negative case the corpus ships must be exercised here; a new
        // case must not pass silently.
        assertEquals(cases.map { it.text("id") }.toSet(), covered)
        assertNull(RelayFraming.decodeFailureReason(single))
    }
}
