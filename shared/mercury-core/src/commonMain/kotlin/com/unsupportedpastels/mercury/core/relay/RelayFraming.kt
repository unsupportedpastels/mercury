package com.unsupportedpastels.mercury.core.relay

internal class RelayFramingException(
    val reason: String,
) : Exception("Mercury Relay frame validation failed")

class RelayFrame(
    val channelId: ByteArray,
    val messageId: ByteArray,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val logicalLength: Int,
    val payload: ByteArray,
)

object RelayFraming {
    val magic = byteArrayOf(0x4d, 0x52)
    const val protocolVersion = 1
    const val kindHermesBytes = 1
    const val channelIdSize = 16
    const val messageIdSize = 16
    const val headerSize = 50
    const val maxNoisePlaintextBytes = 65_519
    const val maxPayloadBytes = maxNoisePlaintextBytes - headerSize
    const val maxLogicalMessageBytes = 16 shl 20
    const val maxFragmentCount = (maxLogicalMessageBytes + maxPayloadBytes - 1) / maxPayloadBytes

    fun canonicalFragmentCount(logicalLength: Int): Int =
        maxOf(1, (logicalLength + maxPayloadBytes - 1) / maxPayloadBytes)

    @Throws(RelayFramingException::class)
    fun encodeMessage(channelId: ByteArray, messageId: ByteArray, payload: ByteArray): List<ByteArray> {
        if (channelId.size != channelIdSize || messageId.size != messageIdSize) fail("invalid identifier length")
        if (payload.size > maxLogicalMessageBytes) fail("logical length exceeds v1 limit")
        val count = canonicalFragmentCount(payload.size)
        return List(count) { index ->
            val start = index * maxPayloadBytes
            val length = canonicalPayloadLength(payload.size, index)
            ByteArray(headerSize + length).also { record ->
                record[0] = magic[0]
                record[1] = magic[1]
                record[2] = protocolVersion.toByte()
                record[3] = kindHermesBytes.toByte()
                record[4] = 0
                record[5] = 0
                writeUInt16(record, 6, index)
                writeUInt16(record, 8, count)
                writeUInt32(record, 10, payload.size)
                writeUInt32(record, 14, length)
                channelId.copyInto(record, 18)
                messageId.copyInto(record, 34)
                payload.copyInto(record, headerSize, start, start + length)
            }
        }
    }

    @Throws(RelayFramingException::class)
    fun decodeRecord(record: ByteArray): RelayFrame {
        if (record.size < headerSize) fail("truncated record header")
        if (record.size > maxNoisePlaintextBytes) fail("record exceeds Noise plaintext limit")
        if (record[0] != magic[0] || record[1] != magic[1]) fail("invalid magic")
        if (record[2].toInt() and 0xff != protocolVersion) fail("unknown version")
        if (record[3].toInt() and 0xff != kindHermesBytes) fail("unknown kind")
        if (record[4].toInt() != 0) fail("unknown flags")
        if (record[5].toInt() != 0) fail("reserved value is nonzero")
        val fragmentIndex = readUInt16(record, 6)
        val fragmentCount = readUInt16(record, 8)
        val logicalLength = readUInt32(record, 10)
        val payloadLength = readUInt32(record, 14)
        if (fragmentCount !in 1..maxFragmentCount) fail("fragment count is outside the v1 range")
        if (logicalLength !in 0..maxLogicalMessageBytes) fail("logical length exceeds v1 limit")
        val expectedCount = canonicalFragmentCount(logicalLength)
        if (fragmentIndex !in 0 until expectedCount) fail("fragment index or length is not canonical")
        if (fragmentCount != expectedCount) fail("fragment count is not canonical")
        val expectedLength = canonicalPayloadLength(logicalLength, fragmentIndex)
        if (payloadLength != expectedLength) fail("payload length is not canonical")
        if (record.size != headerSize + payloadLength) fail("record length does not match payload length")
        return RelayFrame(
            channelId = record.copyOfRange(18, 34),
            messageId = record.copyOfRange(34, 50),
            fragmentIndex = fragmentIndex,
            fragmentCount = fragmentCount,
            logicalLength = logicalLength,
            payload = record.copyOfRange(headerSize, record.size),
        )
    }

    fun decodeFailureReason(record: ByteArray): String? =
        try {
            decodeRecord(record)
            null
        } catch (error: RelayFramingException) {
            error.reason
        }

    private fun canonicalPayloadLength(logicalLength: Int, fragmentIndex: Int): Int =
        minOf(maxPayloadBytes, logicalLength - fragmentIndex * maxPayloadBytes)

    private fun writeUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeUInt32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff shl 24) or
            (bytes[offset + 1].toInt() and 0xff shl 16) or
            (bytes[offset + 2].toInt() and 0xff shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun fail(reason: String): Nothing = throw RelayFramingException(reason)
}

class RelayFrameReassembler(
    private val expectedChannelId: ByteArray? = null,
) {
    private var messageId: ByteArray? = null
    private var activeChannelId: ByteArray? = null
    private var fragmentCount = 0
    private var logicalLength = 0
    private var nextIndex = 0
    private var receivedLength = 0
    private var parts = mutableListOf<ByteArray>()

    val inProgress: Boolean get() = messageId != null

    fun reset() {
        messageId = null
        activeChannelId = null
        fragmentCount = 0
        logicalLength = 0
        nextIndex = 0
        receivedLength = 0
        parts = mutableListOf()
    }

    @Throws(RelayFramingException::class)
    fun push(record: ByteArray): ByteArray? {
        try {
            val frame = RelayFraming.decodeRecord(record)
            if (expectedChannelId != null && !frame.channelId.contentEquals(expectedChannelId)) {
                fail("metadata channel mismatch")
            }
            if (messageId == null) {
                if (frame.fragmentIndex != 0) fail("order requires fragment zero first")
                activeChannelId = frame.channelId
                messageId = frame.messageId
                fragmentCount = frame.fragmentCount
                logicalLength = frame.logicalLength
            } else if (!frame.channelId.contentEquals(activeChannelId!!) ||
                !frame.messageId.contentEquals(messageId!!) || frame.fragmentCount != fragmentCount ||
                frame.logicalLength != logicalLength
            ) {
                fail("metadata channel, message, or length mismatch")
            }
            if (frame.fragmentIndex != nextIndex) fail("order is not contiguous")
            if (receivedLength + frame.payload.size > logicalLength) fail("received length exceeds logical length")
            parts += frame.payload
            receivedLength += frame.payload.size
            nextIndex += 1
            if (nextIndex != fragmentCount) return null
            if (receivedLength != logicalLength) fail("received length is incomplete")
            val result = ByteArray(receivedLength)
            var offset = 0
            parts.forEach { part ->
                part.copyInto(result, offset)
                offset += part.size
            }
            reset()
            return result
        } catch (error: Throwable) {
            reset()
            throw error
        }
    }

    private fun fail(reason: String): Nothing = throw RelayFramingException(reason)
}
