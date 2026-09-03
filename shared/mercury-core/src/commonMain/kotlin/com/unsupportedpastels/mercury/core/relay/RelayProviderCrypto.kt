@file:OptIn(
    dev.whyoleg.cryptography.CryptographyProviderApi::class,
    dev.whyoleg.cryptography.DelicateCryptographyApi::class,
)

package com.unsupportedpastels.mercury.core.relay

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.ChaCha20Poly1305
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import dev.whyoleg.cryptography.random.CryptographyRandom

expect val relayCryptographyProvider: CryptographyProvider

object RelayPlatformCrypto : RelayCrypto by ProviderRelayCrypto(relayCryptographyProvider)

private class ProviderRelayCrypto(
    private val provider: CryptographyProvider,
) : RelayCrypto {
    override fun sha256(input: ByteArray): ByteArray =
        provider.get(SHA256).hasher().hashBlocking(input)

    override fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
        val decoded = provider.get(HMAC).keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
        return decoded.signatureGenerator().generateSignatureBlocking(input)
    }

    override fun x25519PublicKey(privateKey: ByteArray): ByteArray {
        val decoded = provider.get(XDH).privateKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, privateKey)
        return decoded.getPublicKeyBlocking().encodeToByteArrayBlocking(XDH.PublicKey.Format.RAW)
    }

    override fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray? = runCatching {
        val xdh = provider.get(XDH)
        val privateDecoded = xdh.privateKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArrayBlocking(XDH.PrivateKey.Format.RAW, privateKey)
        val publicDecoded = xdh.publicKeyDecoder(XDH.Curve.X25519)
            .decodeFromByteArrayBlocking(XDH.PublicKey.Format.RAW, publicKey)
        privateDecoded.sharedSecretGenerator().generateSharedSecretToByteArrayBlocking(publicDecoded)
    }.getOrNull()

    override fun chachaPolyEncrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        require(nonce.size == 12) { "Noise nonce must contain 12 bytes" }
        val decoded = provider.get(ChaCha20Poly1305).keyDecoder()
            .decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key)
        return decoded.cipher().encryptWithIvBlocking(nonce, plaintext, associatedData)
    }

    override fun chachaPolyDecrypt(
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray? = runCatching {
        require(nonce.size == 12) { "Noise nonce must contain 12 bytes" }
        val decoded = provider.get(ChaCha20Poly1305).keyDecoder()
            .decodeFromByteArrayBlocking(ChaCha20Poly1305.Key.Format.RAW, key)
        decoded.cipher().decryptWithIvBlocking(nonce, ciphertext, associatedData)
    }.getOrNull()

    override fun randomBytes(count: Int): ByteArray =
        CryptographyRandom.Default.nextBytes(count)
}
