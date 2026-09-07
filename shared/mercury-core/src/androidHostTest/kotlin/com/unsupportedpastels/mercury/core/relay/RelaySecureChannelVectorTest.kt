package com.unsupportedpastels.mercury.core.relay

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RelaySecureChannelVectorTest {
    private val crypto = RelayPlatformCrypto
    private val installation = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
    private val initiatorStatic = hex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")
    private val responderStatic = hex("202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f")
    private val initiatorEphemeral = hex("404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f")
    private val responderEphemeral = hex("606162636465666768696a6b6c6d6e6f707172737475767778797a7b7c7d7e7f")

    @Test
    fun reproducesTheCanonicalNoiseXkVector() {
        val mobile = RelaySecureChannel(
            crypto = crypto,
            isInitiator = true,
            staticPrivateKey = initiatorStatic,
            installationId = installation,
            remoteStaticPublicKey = crypto.x25519PublicKey(responderStatic),
            deterministicEphemeralPrivateKey = initiatorEphemeral,
        )
        val host = RelaySecureChannel(
            crypto = crypto,
            isInitiator = false,
            staticPrivateKey = responderStatic,
            installationId = installation,
            remoteStaticPublicKey = null,
            deterministicEphemeralPrivateKey = responderEphemeral,
        )
        val capability = hex("a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf")

        val first = mobile.writeHandshake()
        assertContentEquals(byteArrayOf(), host.readHandshake(first))
        val second = host.writeHandshake()
        assertContentEquals(byteArrayOf(), mobile.readHandshake(second))
        val third = mobile.writeHandshake(capability)
        assertContentEquals(capability, host.readHandshake(third))

        assertEquals(
            listOf(
                "79a631eede1bf9c98f12032cdeadd0e7a079398fc786b88cc846ec89af85a51a7048ea999fb97158d9191debb321903e",
                "675dd574ed7789310b3d2e7681f3790b466c773b1521fecf36577958371ea52fac1241ed64f88c316d477c626119be4f",
                "da3e5f03959b6df33cd42baa389c8b9c7373e3d6521c4ffbaa0c54aabb5071a3b551e612df9469e0e4c7f1c2e60e79ec7df8cabde9df9270fbea57b0ceb455ab8b07f5e2ef4198261355100d38267df1bf295b1bceade0bc8e4af5582ad561ed",
            ),
            listOf(first.toHex(), second.toHex(), third.toHex()),
        )
        assertEquals("9b112311dd467931d5ade1904bd013edb473921930352f50bfd55d4a632dd3e2", mobile.channelBinding.toHex())
        assertContentEquals(mobile.channelBinding, host.channelBinding)
        assertContentEquals(crypto.x25519PublicKey(initiatorStatic), host.remoteStaticPublicKey)

        val plaintext = hex("66697874757265206d6f62696c6520746f20686f7374")
        val ciphertext = mobile.encrypt(plaintext)
        assertEquals("f3c9f250444f2fb3d47e2c3dd1e4b562b18595adedf7c7098ea8f7111a9dfa6bc3f0aa4b450d", ciphertext.toHex())
        assertContentEquals(plaintext, host.decrypt(ciphertext))
        assertFailsWith<RelaySecureChannelException> { host.decrypt(ciphertext) }
        assertTrue(host.closed)
    }

    @Test
    fun productionEphemeralKeysAreFresh() {
        fun first() = RelaySecureChannel(
            crypto,
            true,
            initiatorStatic,
            installation,
            crypto.x25519PublicKey(responderStatic),
            null,
        ).writeHandshake()

        assertTrue(!first().contentEquals(first()))
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}
