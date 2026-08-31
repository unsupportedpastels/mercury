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
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun twoLoopbackPortsDoNotShareCachedSessions() = runTest {
        val first = CacheScope(ServerOrigin.parse("http://127.0.0.1:9119"), "default")
        val second = CacheScope(ServerOrigin.parse("http://127.0.0.1:9120"), "default")
        repository.writeMetadata(first, listOf(summary("port-9119")), nowEpochSeconds = 10)
        repository.writeMetadata(second, listOf(summary("port-9120")), nowEpochSeconds = 10)

        assertEquals(listOf("port-9119"), repository.read(first, 11).sessions.map { it.summary.id.value })
        assertEquals(listOf("port-9120"), repository.read(second, 11).sessions.map { it.summary.id.value })
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

    private fun summary(id: String) = SessionSummary(
        id = DurableSessionId(id),
        title = id,
        preview = "preview",
        profile = "default",
    )

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
