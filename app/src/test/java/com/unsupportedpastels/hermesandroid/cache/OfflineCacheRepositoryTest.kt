package com.unsupportedpastels.hermesandroid.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.Aead
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import java.security.GeneralSecurityException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
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
class OfflineCacheRepositoryTest {
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var repository: EncryptedOfflineCacheRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences("offline-cache-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        repository = EncryptedOfflineCacheRepository(
            context = context,
            preferencesName = "offline-cache-test",
            aeadFactory = { TestAead() },
            clock = { 11L },
        )
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun cacheIsOriginAndProfileIsolatedAndWritesStayBounded() = runTest {
        val first = CacheScope(ServerOrigin.parse("https://one.example"), "default")
        val otherOrigin = CacheScope(ServerOrigin.parse("https://two.example"), "default")
        val otherProfile = CacheScope(first.origin, "work")
        val sessions = (0 until OfflineCachePolicy.MAX_SESSION_COUNT + 10).map { index ->
            summary("session-$index")
        }
        repository.writeMetadata(first, sessions, nowEpochSeconds = 10_000)
        repository.setTranscriptCachingEnabled(true)
        repository.writeTranscript(
            first,
            summary("session-0"),
            (0 until OfflineCachePolicy.MAX_MESSAGES_PER_SESSION + 5).map {
                ChatMessage(ChatMessageRole.Assistant, "x".repeat(OfflineCachePolicy.MAX_BODY_BYTES + 1))
            },
            nowEpochSeconds = 10_001,
        )

        val cached = repository.read(first, nowEpochSeconds = 10_002)
        assertTrue(cached.sessions.size <= OfflineCachePolicy.MAX_SESSION_COUNT)
        val transcript = cached.sessions.first { it.summary.id == DurableSessionId("session-0") }
        assertTrue(transcript.messages.size <= OfflineCachePolicy.MAX_MESSAGES_PER_SESSION)
        assertTrue(transcript.messages.all { it.text.toByteArray().size <= OfflineCachePolicy.MAX_BODY_BYTES })
        assertTrue(repository.read(otherOrigin, 10_002).sessions.isEmpty())
        assertTrue(repository.read(otherProfile, 10_002).sessions.isEmpty())
    }

    @Test
    fun corruptRowsAreIgnoredAndExpiredRowsArePruned() = runTest {
        val scope = CacheScope(ServerOrigin.parse("https://one.example"), "default")
        repository.writeMetadata(scope, listOf(summary("expired")), nowEpochSeconds = 1)
        preferences.edit().putString("row-corrupt", "not-a-cache-row").commit()

        val cached = repository.read(scope, nowEpochSeconds = OfflineCachePolicy.RETENTION_SECONDS + 2)

        assertTrue(cached.sessions.isEmpty())
    }

    @Test
    fun disablingTranscriptCachingClearsBodiesButKeepsMetadata() = runTest {
        val scope = CacheScope(ServerOrigin.parse("https://one.example"), "default")
        repository.setTranscriptCachingEnabled(true)
        repository.writeTranscript(
            scope,
            summary("session"),
            listOf(ChatMessage(ChatMessageRole.User, "private body")),
            nowEpochSeconds = 10,
        )
        repository.setTranscriptCachingEnabled(false)

        val cached = repository.read(scope, 11)

        assertEquals(1, cached.sessions.size)
        assertTrue(cached.sessions.single().messages.isEmpty())
    }

    @Test
    fun clearTranscriptTailsAndDeleteSessionAreScoped() = runTest {
        val scope = CacheScope(ServerOrigin.parse("https://one.example"), "default")
        val other = CacheScope(ServerOrigin.parse("https://two.example"), "default")
        repository.setTranscriptCachingEnabled(true)
        repository.writeTranscript(scope, summary("one"), listOf(ChatMessage(ChatMessageRole.User, "body")), 10)
        repository.writeTranscript(other, summary("two"), listOf(ChatMessage(ChatMessageRole.User, "body")), 10)

        repository.clearTranscriptTails(scope)
        repository.deleteSession(scope, DurableSessionId("one"))

        assertTrue(repository.read(scope, 11).sessions.isEmpty())
        assertEquals(1, repository.read(other, 11).sessions.single().messages.size)
    }

    @Test
    fun clearingAnOriginRemovesItsTranscriptTailsAcrossProfilesOnly() = runTest {
        val origin = ServerOrigin.parse("https://one.example")
        val first = CacheScope(origin, "default")
        val secondProfile = CacheScope(origin, "work")
        val otherOrigin = CacheScope(ServerOrigin.parse("https://two.example"), "default")
        repository.setTranscriptCachingEnabled(true)
        listOf(first, secondProfile, otherOrigin).forEachIndexed { index, scope ->
            repository.writeTranscript(
                scope,
                summary("session-$index"),
                listOf(ChatMessage(ChatMessageRole.User, "body")),
                10,
            )
        }

        repository.clearTranscriptTailsForOrigin(origin)

        assertTrue(repository.read(first, 11).sessions.single().messages.isEmpty())
        assertTrue(repository.read(secondProfile, 11).sessions.single().messages.isEmpty())
        assertEquals(1, repository.read(otherOrigin, 11).sessions.single().messages.size)
    }

    @Test
    fun disablingCachingWaitsForAnInFlightWriteBeforeClearingTails() = runTest {
        val enteredEncryption = CountDownLatch(1)
        val releaseEncryption = CountDownLatch(1)
        val clearReached = CountDownLatch(1)
        val allowClear = CountDownLatch(1)
        val aead = PausingAead(enteredEncryption, releaseEncryption)
        val scope = CacheScope(ServerOrigin.parse("https://one.example"), "default")
        repository = repository(aead) {
            clearReached.countDown()
            check(allowClear.await(10, TimeUnit.SECONDS))
            11L
        }
        repository.setTranscriptCachingEnabled(true)
        aead.pauseNextEncryption = true

        val writer = async(Dispatchers.IO) {
            repository.writeTranscript(
                scope,
                summary("session"),
                listOf(ChatMessage(ChatMessageRole.User, "private body")),
                nowEpochSeconds = 10,
            )
        }
        check(enteredEncryption.await(10, TimeUnit.SECONDS))
        val optOut = async(Dispatchers.IO) {
            repository.setTranscriptCachingEnabled(false)
        }
        try {
            assertFalse(
                "Opt-out started clearing while an earlier write was still encrypting",
                clearReached.await(1, TimeUnit.SECONDS),
            )
            releaseEncryption.countDown()
            allowClear.countDown()
            writer.await()
            optOut.await()

            val cached = repository.read(scope, 11)
            assertTrue(cached.sessions.single().messages.isEmpty())
        } finally {
            releaseEncryption.countDown()
            allowClear.countDown()
            writer.join()
            optOut.join()
        }
    }

    @Test
    fun clearWaitsForAnInFlightWriteBeforeDeletingItsResult() = runTest {
        val enteredEncryption = CountDownLatch(1)
        val releaseEncryption = CountDownLatch(1)
        val clearReached = CountDownLatch(1)
        val allowClear = CountDownLatch(1)
        val aead = PausingAead(enteredEncryption, releaseEncryption)
        val scope = CacheScope(ServerOrigin.parse("https://one.example"), "default")
        repository = repository(aead) {
            clearReached.countDown()
            check(allowClear.await(10, TimeUnit.SECONDS))
            11L
        }
        repository.setTranscriptCachingEnabled(true)
        aead.pauseNextEncryption = true

        val writer = async(Dispatchers.IO) {
            repository.writeTranscript(
                scope,
                summary("session"),
                listOf(ChatMessage(ChatMessageRole.User, "private body")),
                nowEpochSeconds = 10,
            )
        }
        check(enteredEncryption.await(10, TimeUnit.SECONDS))
        val clearer = async(Dispatchers.IO) {
            repository.clear(scope)
        }
        try {
            assertFalse(
                "Clear started before an earlier write finished",
                clearReached.await(1, TimeUnit.SECONDS),
            )
            releaseEncryption.countDown()
            allowClear.countDown()
            writer.await()
            clearer.await()

            assertTrue(repository.read(scope, 11).sessions.isEmpty())
        } finally {
            releaseEncryption.countDown()
            allowClear.countDown()
            writer.join()
            clearer.join()
        }
    }

    @Test
    fun deleteWaitsForAnInFlightWriteBeforeDeletingItsResult() = runTest {
        val enteredEncryption = CountDownLatch(1)
        val releaseEncryption = CountDownLatch(1)
        val aead = PausingAead(enteredEncryption, releaseEncryption)
        val scope = CacheScope(ServerOrigin.parse("https://one.example"), "default")
        repository = repository(aead)
        repository.setTranscriptCachingEnabled(true)
        repository.writeTranscript(
            scope,
            summary("session"),
            listOf(ChatMessage(ChatMessageRole.User, "old body")),
            nowEpochSeconds = 9,
        )
        aead.pauseNextEncryption = true

        val writer = async(Dispatchers.IO) {
            repository.writeTranscript(
                scope,
                summary("session"),
                listOf(ChatMessage(ChatMessageRole.User, "new body")),
                nowEpochSeconds = 10,
            )
        }
        check(enteredEncryption.await(10, TimeUnit.SECONDS))
        val deleted = CountDownLatch(1)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key?.startsWith("row-") == true && !prefs.contains(key)) deleted.countDown()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        val deleter = async(Dispatchers.IO) {
            repository.deleteSession(scope, DurableSessionId("session"))
        }
        try {
            assertFalse(
                "Delete committed before an earlier write finished",
                deleted.await(1, TimeUnit.SECONDS),
            )
            releaseEncryption.countDown()
            writer.await()
            deleter.await()

            assertTrue(repository.read(scope, 11).sessions.isEmpty())
        } finally {
            releaseEncryption.countDown()
            writer.join()
            deleter.join()
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    @Test
    fun concurrentMetadataAndTranscriptWritesPreserveTheTranscript() = runTest {
        val metadataEntered = CountDownLatch(1)
        val releaseMetadata = CountDownLatch(1)
        val transcriptEntered = CountDownLatch(1)
        val releaseTranscript = CountDownLatch(1)
        val aead = MetadataAndTranscriptAead(
            metadataEntered = metadataEntered,
            releaseMetadata = releaseMetadata,
            transcriptEntered = transcriptEntered,
            releaseTranscript = releaseTranscript,
        )
        val scope = CacheScope(ServerOrigin.parse("https://one.example"), "default")
        repository = repository(aead)
        repository.setTranscriptCachingEnabled(true)
        aead.pauseMetadataEncryption = true

        val metadata = async(Dispatchers.IO) {
            repository.writeMetadata(scope, listOf(summary("session")), nowEpochSeconds = 10)
        }
        check(metadataEntered.await(10, TimeUnit.SECONDS))
        val transcript = async(Dispatchers.IO) {
            repository.writeTranscript(
                scope,
                summary("session"),
                listOf(ChatMessage(ChatMessageRole.User, "concurrent-body")),
                nowEpochSeconds = 11,
            )
        }
        try {
            assertFalse(
                "Transcript write overtook an earlier metadata read/modify/write",
                transcriptEntered.await(1, TimeUnit.SECONDS),
            )
            releaseMetadata.countDown()
            metadata.await()
            releaseTranscript.countDown()
            transcript.await()

            val cached = repository.read(scope, 12).sessions.single()
            assertEquals("concurrent-body", cached.messages.single().text)
        } finally {
            releaseMetadata.countDown()
            releaseTranscript.countDown()
            metadata.join()
            transcript.join()
        }
    }

    private fun repository(
        aead: Aead,
        clock: () -> Long = { 11L },
    ) = EncryptedOfflineCacheRepository(
        context = ApplicationProvider.getApplicationContext(),
        preferencesName = "offline-cache-test",
        aeadFactory = { aead },
        clock = clock,
    )

    private fun summary(id: String) = SessionSummary(
        id = DurableSessionId(id),
        title = id,
        preview = "preview",
        profile = "default",
    )

    private class PausingAead(
        private val enteredEncryption: CountDownLatch,
        private val releaseEncryption: CountDownLatch,
    ) : Aead {
        @Volatile
        var pauseNextEncryption = false

        private val delegate = TestAead()

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
            if (pauseNextEncryption) {
                pauseNextEncryption = false
                enteredEncryption.countDown()
                check(releaseEncryption.await(10, TimeUnit.SECONDS))
            }
            return delegate.encrypt(plaintext, associatedData)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
            delegate.decrypt(ciphertext, associatedData)
    }

    private class MetadataAndTranscriptAead(
        private val metadataEntered: CountDownLatch,
        private val releaseMetadata: CountDownLatch,
        private val transcriptEntered: CountDownLatch,
        private val releaseTranscript: CountDownLatch,
    ) : Aead {
        @Volatile
        var pauseMetadataEncryption = false

        @Volatile
        private var pauseTranscriptEncryption = true

        private val delegate = TestAead()

        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
            val text = plaintext.toString(Charsets.UTF_8)
            if (pauseMetadataEncryption) {
                pauseMetadataEncryption = false
                metadataEntered.countDown()
                check(releaseMetadata.await(10, TimeUnit.SECONDS))
            } else if (pauseTranscriptEncryption && text.contains("concurrent-body")) {
                pauseTranscriptEncryption = false
                transcriptEntered.countDown()
                check(releaseTranscript.await(10, TimeUnit.SECONDS))
            }
            return delegate.encrypt(plaintext, associatedData)
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray =
            delegate.decrypt(ciphertext, associatedData)
    }

    private class TestAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray?): ByteArray {
            val aad = associatedData ?: ByteArray(0)
            return byteArrayOf(aad.size.toByte()) + aad + plaintext
        }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray?): ByteArray {
            val aad = associatedData ?: ByteArray(0)
            val length = ciphertext.firstOrNull()?.toInt() ?: throw GeneralSecurityException()
            if (length != aad.size || !ciphertext.copyOfRange(1, length + 1).contentEquals(aad)) {
                throw GeneralSecurityException()
            }
            return ciphertext.copyOfRange(length + 1, ciphertext.size)
        }
    }
}
