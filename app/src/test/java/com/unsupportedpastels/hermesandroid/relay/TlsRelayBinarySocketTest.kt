package com.unsupportedpastels.hermesandroid.relay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.HandshakeCompletedListener
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsRelayBinarySocketTest {
    @Test
    fun upgradeRequestPreservesBearerAndTerminatesHeadersExactly() {
        val request = buildRelayUpgradeRequest("/fixture", "relay.invalid", "fixture-key", "fixture-token")
        org.junit.Assert.assertEquals(
            "GET /fixture HTTP/1.1\r\n" +
                "Host: relay.invalid\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Key: fixture-key\r\n" +
                "Sec-WebSocket-Version: 13\r\n" +
                "Authorization: " + "Bearer " + "fixture-token" + "\r\n\r\n",
            request,
        )
        val anonymous = buildRelayUpgradeRequest("/fixture", "relay.invalid", "fixture-key", null)
        assertTrue(anonymous.endsWith("Sec-WebSocket-Version: 13\r\n\r\n"))
        org.junit.Assert.assertFalse(anonymous.contains("Authorization"))
    }

    @Test
    fun cancellingBlockedReceiveClosesUnderlyingTlsSocket() = runBlocking {
        val enteredRead = CountDownLatch(1)
        val releasedRead = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val fakeTls = FakeTlsSocket(
            input = object : InputStream() {
                override fun read(): Int {
                    enteredRead.countDown()
                    releasedRead.await()
                    return -1
                }
            },
            output = ByteArrayOutputStream(),
        ) {
            closed.countDown()
            releasedRead.countDown()
        }
        val socket = newRelaySocket(fakeTls)
        val reader = launch(Dispatchers.IO) { socket.receive() }

        try {
            assertTrue(enteredRead.await(5, TimeUnit.SECONDS))
            reader.cancel()
            assertTrue("cancellation must close the native TLS socket", closed.await(1, TimeUnit.SECONDS))
            assertTrue("cancelled receive must settle", withTimeoutOrNull(1_000) {
                reader.join()
                true
            } == true)
        } finally {
            socket.close()
            reader.join()
        }
    }

    @Test
    fun cancellingBlockedSendClosesUnderlyingTlsSocket() = runBlocking {
        val enteredWrite = CountDownLatch(1)
        val releasedWrite = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val fakeTls = FakeTlsSocket(
            input = emptyInput(),
            output = object : OutputStream() {
                override fun write(value: Int) {
                    enteredWrite.countDown()
                    releasedWrite.await()
                }
            },
        ) {
            closed.countDown()
            releasedWrite.countDown()
        }
        val socket = newRelaySocket(fakeTls)
        val writer = launch(Dispatchers.IO) { socket.send(byteArrayOf(0x01)) }

        try {
            assertTrue(enteredWrite.await(5, TimeUnit.SECONDS))
            writer.cancel()
            assertTrue("cancellation must close the native TLS socket", closed.await(1, TimeUnit.SECONDS))
            assertTrue("cancelled send must settle", withTimeoutOrNull(1_000) {
                writer.join()
                true
            } == true)
        } finally {
            socket.close()
            writer.join()
        }
    }

    private fun newRelaySocket(fakeTls: SSLSocket): RelayBinarySocket {
        val constructor = Class.forName("com.unsupportedpastels.hermesandroid.relay.TlsRelayBinarySocket")
            .getDeclaredConstructor(SSLSocket::class.java, SecureRandom::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(fakeTls, SecureRandom()) as RelayBinarySocket
    }

    private fun emptyInput() = object : InputStream() {
        override fun read(): Int = -1
    }

    private class FakeTlsSocket(
        private val input: InputStream,
        private val output: OutputStream,
        private val onClose: () -> Unit,
    ) : SSLSocket() {
        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun close() = onClose()
        override fun getSupportedCipherSuites() = emptyArray<String>()
        override fun getEnabledCipherSuites() = emptyArray<String>()
        override fun setEnabledCipherSuites(suites: Array<out String>?) = Unit
        override fun getSupportedProtocols() = emptyArray<String>()
        override fun getEnabledProtocols() = emptyArray<String>()
        override fun setEnabledProtocols(protocols: Array<out String>?) = Unit
        override fun getSession(): SSLSession = throw UnsupportedOperationException()
        override fun addHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun removeHandshakeCompletedListener(listener: HandshakeCompletedListener?) = Unit
        override fun startHandshake() = Unit
        override fun setUseClientMode(mode: Boolean) = Unit
        override fun getUseClientMode() = true
        override fun setNeedClientAuth(need: Boolean) = Unit
        override fun getNeedClientAuth() = false
        override fun setWantClientAuth(want: Boolean) = Unit
        override fun getWantClientAuth() = false
        override fun setEnableSessionCreation(flag: Boolean) = Unit
        override fun getEnableSessionCreation() = true
    }
}
