package com.unsupportedpastels.hermesandroid.relay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.unsupportedpastels.mercury.core.relay.AndroidRelayCrypto
import com.unsupportedpastels.mercury.core.relay.RelayBase64
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayTargetStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferencesName = "relay_target_store_tests"

    @Before
    fun clear() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun roundTripApprovalRenameAndRemovalArePersisted() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = EncryptedRelayTargetStore(context, preferencesName, dispatcher) { XorAead }
        val target = target()

        store.add(target)
        store.updateLabel(target.id, "Study host")
        store.markApproved(target.id, 200)

        val reloaded = EncryptedRelayTargetStore(context, preferencesName, dispatcher) { XorAead }
        val approved = reloaded.load().single()
        assertEquals("Study host", approved.label)
        assertEquals(RelayTargetStatus.Approved, approved.status)
        assertEquals(200L, approved.lastUsedEpochSeconds)
        assertEquals(target.relayRoutingToken, approved.relayRoutingToken)

        reloaded.remove(target.id)
        assertTrue(EncryptedRelayTargetStore(context, preferencesName, dispatcher) { XorAead }.load().isEmpty())
    }

    @Test
    fun persistedPreferencesNeverContainPrivateKeyMaterial() = runTest {
        val store = EncryptedRelayTargetStore(
            context,
            preferencesName,
            StandardTestDispatcher(testScheduler),
        ) { XorAead }
        val target = target()

        store.add(target)

        val persisted = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all.values.joinToString("|")
        assertFalse(persisted.contains(RelayBase64.standardEncode(target.deviceStaticPrivateKey)))
        assertFalse(persisted.contains(target.deviceId))
        assertTrue(persisted.isNotBlank())
    }

    @Test
    fun corruptCiphertextFailsClosedWithoutOverwritingIt() = runTest {
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        preferences.edit().putString(EncryptedRelayTargetStore.RECORD_KEY, "not ciphertext").commit()
        val before = preferences.getString(EncryptedRelayTargetStore.RECORD_KEY, null)
        val store = EncryptedRelayTargetStore(
            context,
            preferencesName,
            StandardTestDispatcher(testScheduler),
        ) { XorAead }

        val failure = runCatching { store.load() }.exceptionOrNull()

        assertTrue(failure is RelayTargetStoreException)
        assertEquals(before, preferences.getString(EncryptedRelayTargetStore.RECORD_KEY, null))
    }

    private fun target() = RelayPairedTarget(
        id = "00000000-0000-4000-8000-000000000001",
        label = "",
        relayOrigin = "https://relay.example.com",
        installationId = ByteArray(32) { (it + 0x80).toByte() },
        hostPublicKey = AndroidRelayCrypto.x25519PublicKey(ByteArray(32) { (it + 0x20).toByte() }),
        deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { (it + 3).toByte() }),
        deviceStaticPrivateKey = ByteArray(32) { (it + 0x40).toByte() },
        fingerprint = "0123456789abcdef",
        status = RelayTargetStatus.Pending,
        createdAtEpochSeconds = 100,
        lastUsedEpochSeconds = null,
        relayRoutingToken = "routing.device.token",
    )

    private object XorAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
            ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }
}
