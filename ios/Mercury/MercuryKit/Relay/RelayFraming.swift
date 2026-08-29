import Foundation

/// Compact v1 plaintext records for bounded Noise application data.
///
/// Behavioral mirror of the plugin's `framing.py` and the frozen schema in
/// `protocol/schemas/framing-envelope-v1.json`: a fixed 50-byte header
/// (network byte order) followed by payload bytes, a 65,519-byte record cap,
/// a 1 MiB logical-message cap, canonical minimum fragmentation, and strictly
/// contiguous metadata-stable reassembly that resets on every rejection.
enum RelayFraming {
    static let magic: [UInt8] = [0x4D, 0x52] // "MR"
    static let protocolVersion: UInt8 = 1
    static let kindHermesBytes: UInt8 = 1
    static let channelIDSize = 16
    static let messageIDSize = 16
    static let headerSize = 50
    static let maxNoisePlaintextBytes = 65_519
    static let maxPayloadBytes = maxNoisePlaintextBytes - headerSize // 65,469
    /// Raised from the initial 1 MiB so attachments comparable to direct
    /// mode fit through the relay; kept under the router's 25 MB / 10 s
    /// per-socket byte budget so one maximal message cannot trip it. Must
    /// match the plugin's `framing.MAX_LOGICAL_MESSAGE_BYTES`.
    static let maxLogicalMessageBytes = 16 << 20
    static let maxFragmentCount = (maxLogicalMessageBytes + maxPayloadBytes - 1) / maxPayloadBytes

    struct DecodeError: Error, Equatable {
        let reason: String
    }

    struct Frame: Equatable {
        let channelID: Data
        let messageID: Data
        let fragmentIndex: Int
        let fragmentCount: Int
        let logicalLength: Int
        let payload: Data
    }

    static func canonicalFragmentCount(logicalLength: Int) -> Int {
        max(1, (logicalLength + maxPayloadBytes - 1) / maxPayloadBytes)
    }

    private static func canonicalPayloadLength(logicalLength: Int, fragmentIndex: Int) -> Int {
        min(maxPayloadBytes, logicalLength - fragmentIndex * maxPayloadBytes)
    }

    /// Encode one bounded logical byte string into canonical records.
    static func encodeMessage(
        channelID: Data,
        messageID: Data,
        payload: Data
    ) throws -> [Data] {
        guard channelID.count == channelIDSize, messageID.count == messageIDSize else {
            throw DecodeError(reason: "invalid identifier length")
        }
        guard payload.count <= maxLogicalMessageBytes else {
            throw DecodeError(reason: "logical length exceeds v1 limit")
        }
        let count = canonicalFragmentCount(logicalLength: payload.count)
        var records: [Data] = []
        records.reserveCapacity(count)
        for index in 0..<count {
            let start = payload.index(payload.startIndex, offsetBy: index * maxPayloadBytes)
            let length = canonicalPayloadLength(
                logicalLength: payload.count, fragmentIndex: index
            )
            let end = payload.index(start, offsetBy: length)
            var record = Data(capacity: headerSize + length)
            record.append(contentsOf: magic)
            record.append(protocolVersion)
            record.append(kindHermesBytes)
            record.append(0) // flags
            record.append(0) // reserved
            appendUInt16(&record, UInt16(index))
            appendUInt16(&record, UInt16(count))
            appendUInt32(&record, UInt32(payload.count))
            appendUInt32(&record, UInt32(length))
            record.append(channelID)
            record.append(messageID)
            record.append(payload[start..<end])
            records.append(record)
        }
        return records
    }

    /// Validate and decode one complete canonical plaintext record.
    static func decodeRecord(_ record: Data) throws -> Frame {
        guard record.count >= headerSize else {
            throw DecodeError(reason: "truncated record header")
        }
        guard record.count <= maxNoisePlaintextBytes else {
            throw DecodeError(reason: "record exceeds Noise plaintext limit")
        }
        let bytes = [UInt8](record)
        guard bytes[0] == magic[0], bytes[1] == magic[1] else {
            throw DecodeError(reason: "invalid magic")
        }
        guard bytes[2] == protocolVersion else { throw DecodeError(reason: "unknown version") }
        guard bytes[3] == kindHermesBytes else { throw DecodeError(reason: "unknown kind") }
        guard bytes[4] == 0 else { throw DecodeError(reason: "unknown flags") }
        guard bytes[5] == 0 else { throw DecodeError(reason: "reserved value is nonzero") }
        let fragmentIndex = Int(readUInt16(bytes, 6))
        let fragmentCount = Int(readUInt16(bytes, 8))
        let logicalLength = Int(readUInt32(bytes, 10))
        let payloadLength = Int(readUInt32(bytes, 14))
        guard fragmentCount >= 1, fragmentCount <= maxFragmentCount else {
            throw DecodeError(reason: "fragment count is outside the v1 range")
        }
        guard logicalLength <= maxLogicalMessageBytes else {
            throw DecodeError(reason: "logical length exceeds v1 limit")
        }
        let expectedCount = canonicalFragmentCount(logicalLength: logicalLength)
        guard fragmentIndex >= 0, fragmentIndex < expectedCount else {
            throw DecodeError(reason: "fragment index or length is not canonical")
        }
        guard fragmentCount == expectedCount else {
            throw DecodeError(reason: "fragment count is not canonical")
        }
        let expectedPayloadLength = canonicalPayloadLength(
            logicalLength: logicalLength, fragmentIndex: fragmentIndex
        )
        guard payloadLength == expectedPayloadLength else {
            throw DecodeError(reason: "payload length is not canonical")
        }
        guard record.count == headerSize + payloadLength else {
            throw DecodeError(reason: "record length does not match payload length")
        }
        return Frame(
            channelID: Data(bytes[18..<34]),
            messageID: Data(bytes[34..<50]),
            fragmentIndex: fragmentIndex,
            fragmentCount: fragmentCount,
            logicalLength: logicalLength,
            payload: Data(bytes[headerSize...])
        )
    }

    private static func appendUInt16(_ data: inout Data, _ value: UInt16) {
        data.append(UInt8(value >> 8))
        data.append(UInt8(value & 0xFF))
    }

    private static func appendUInt32(_ data: inout Data, _ value: UInt32) {
        data.append(UInt8((value >> 24) & 0xFF))
        data.append(UInt8((value >> 16) & 0xFF))
        data.append(UInt8((value >> 8) & 0xFF))
        data.append(UInt8(value & 0xFF))
    }

    private static func readUInt16(_ bytes: [UInt8], _ offset: Int) -> UInt16 {
        UInt16(bytes[offset]) << 8 | UInt16(bytes[offset + 1])
    }

    private static func readUInt32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) << 24 | UInt32(bytes[offset + 1]) << 16
            | UInt32(bytes[offset + 2]) << 8 | UInt32(bytes[offset + 3])
    }
}

/// Reassemble one logical message from strictly ordered, matching records.
/// Any rejection resets the in-progress message before rethrowing.
final class RelayFrameReassembler {
    private let expectedChannelID: Data?
    private var messageID: Data?
    private var activeChannelID: Data?
    private var fragmentCount = 0
    private var logicalLength = 0
    private var nextIndex = 0
    private var receivedLength = 0
    private var parts: [Data] = []

    init(channelID: Data? = nil) {
        expectedChannelID = channelID
    }

    var inProgress: Bool { messageID != nil }

    func reset() {
        messageID = nil
        activeChannelID = nil
        fragmentCount = 0
        logicalLength = 0
        nextIndex = 0
        receivedLength = 0
        parts = []
    }

    /// Consume one record; returns the complete logical message when the
    /// final canonical fragment arrives, nil while in progress.
    func push(_ record: Data) throws -> Data? {
        do {
            let frame = try RelayFraming.decodeRecord(record)
            if let expectedChannelID, frame.channelID != expectedChannelID {
                throw RelayFraming.DecodeError(reason: "metadata channel mismatch")
            }
            if messageID == nil {
                guard frame.fragmentIndex == 0 else {
                    throw RelayFraming.DecodeError(reason: "order requires fragment zero first")
                }
                activeChannelID = frame.channelID
                messageID = frame.messageID
                fragmentCount = frame.fragmentCount
                logicalLength = frame.logicalLength
            } else if frame.channelID != activeChannelID
                || frame.messageID != messageID
                || frame.fragmentCount != fragmentCount
                || frame.logicalLength != logicalLength {
                throw RelayFraming.DecodeError(
                    reason: "metadata channel, message, or length mismatch"
                )
            }
            guard frame.fragmentIndex == nextIndex else {
                throw RelayFraming.DecodeError(reason: "order is not contiguous")
            }
            guard receivedLength + frame.payload.count <= logicalLength else {
                throw RelayFraming.DecodeError(reason: "received length exceeds logical length")
            }
            parts.append(frame.payload)
            receivedLength += frame.payload.count
            nextIndex += 1
            guard nextIndex == fragmentCount else { return nil }
            guard receivedLength == logicalLength else {
                throw RelayFraming.DecodeError(reason: "received length is incomplete")
            }
            var message = Data(capacity: receivedLength)
            for part in parts { message.append(part) }
            reset()
            return message
        } catch {
            reset()
            throw error
        }
    }
}
