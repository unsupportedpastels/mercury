import CryptoKit
import Foundation

actor OfflineCacheStore {
    private let backend: OfflineCacheBacking
    private let cipher: OfflineCacheCrypting
    private let clock: @Sendable () -> Int64
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder
    private(set) var transcriptCachingEnabled: Bool

    init(
        backend: OfflineCacheBacking = ProtectedFileOfflineCacheBackend(),
        cipher: OfflineCacheCrypting = AESGCMOfflineCacheCipher(),
        clock: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970) }
    ) {
        self.backend = backend
        self.cipher = cipher
        self.clock = clock
        encoder = JSONEncoder()
        decoder = JSONDecoder()
        transcriptCachingEnabled = backend.readTranscriptCachingEnabled()
    }

    func read(scope: OfflineCacheScope, now: Int64) throws -> OfflineCacheSnapshot {
        let rows = try readRows(now: now)
            .filter { $0.originFingerprint == Self.originFingerprint(scope.origin) && $0.profile == scope.profile }
            .sorted {
                $0.updatedAtEpochSeconds == $1.updatedAtEpochSeconds
                    ? $0.sessionID < $1.sessionID
                    : $0.updatedAtEpochSeconds > $1.updatedAtEpochSeconds
            }
        return OfflineCacheSnapshot(sessions: rows.map { row in
            OfflineCachedSession(
                summary: row.summary.sessionRow(id: row.sessionID, profile: row.profile),
                messages: transcriptCachingEnabled ? row.messages : [],
                updatedAtEpochSeconds: row.updatedAtEpochSeconds
            )
        })
    }

    func writeMetadata(scope: OfflineCacheScope, sessions: [SessionRow], now: Int64) throws {
        let rows = try readRows(now: now)
        let fingerprint = Self.originFingerprint(scope.origin)
        let safeSessions = sessions.prefix(OfflineCachePolicy.maxSessionCount)
            .filter { !$0.id.isEmpty }
        let currentIDs = Set(safeSessions.map(\.id))

        for row in rows where row.originFingerprint == fingerprint && row.profile == scope.profile
            && !currentIDs.contains(row.sessionID) {
            try backend.deleteRow(key: row.rowKey)
        }

        let existing = Dictionary(uniqueKeysWithValues: rows.map { ($0.rowKey, $0) })
        for session in safeSessions {
            let key = Self.rowKey(scope: scope, sessionID: session.id)
            let messages = transcriptCachingEnabled ? existing[key]?.messages ?? [] : []
            let row = try storedRow(
                scope: scope,
                summary: session,
                messages: messages,
                now: now
            )
            try write(row)
        }
        try prune(now: now)
    }

    func writeTranscript(
        scope: OfflineCacheScope,
        summary: SessionRow,
        messages: [OfflineCachedMessage],
        now: Int64
    ) throws {
        guard transcriptCachingEnabled, !summary.id.isEmpty else { return }
        let row = try storedRow(
            scope: scope,
            summary: summary,
            messages: Self.boundMessages(messages),
            now: now
        )
        try write(row)
        try prune(now: now)
    }

    func deleteSession(scope: OfflineCacheScope, durableSessionID: String) throws {
        guard !durableSessionID.isEmpty else { throw OfflineCacheError.invalidSessionID }
        try backend.deleteRow(key: Self.rowKey(scope: scope, sessionID: durableSessionID))
    }

    func clearTranscriptTails(scope: OfflineCacheScope? = nil, now: Int64? = nil) throws {
        let rows = try readRows(now: now ?? clock())
        for row in rows where (scope == nil || Self.matches(row, scope!)) && !row.messages.isEmpty {
            try write(row.replacingMessages([]))
        }
    }

    func clearTranscriptTails(origin: String, now: Int64? = nil) throws {
        guard let normalized = ServerOrigin.normalize(origin) else { throw OfflineCacheError.invalidScope }
        let fingerprint = Self.originFingerprint(normalized)
        let rows = try readRows(now: now ?? clock())
        for row in rows where row.originFingerprint == fingerprint && !row.messages.isEmpty {
            try write(row.replacingMessages([]))
        }
    }

    func clear(scope: OfflineCacheScope? = nil, now: Int64? = nil) throws {
        let rows = try readRows(now: now ?? clock())
        for row in rows where scope == nil || Self.matches(row, scope!) {
            try backend.deleteRow(key: row.rowKey)
        }
    }

    /// Integration seam for sign-out. All metadata and transcript bodies for
    /// the normalized origin are deleted across profiles; other origins remain.
    func clearForLogout(origin: String, now: Int64? = nil) throws {
        guard let normalized = ServerOrigin.normalize(origin) else { throw OfflineCacheError.invalidScope }
        let fingerprint = Self.originFingerprint(normalized)
        let rows = try readRows(now: now ?? clock())
        for row in rows where row.originFingerprint == fingerprint {
            try backend.deleteRow(key: row.rowKey)
        }
    }

    func setTranscriptCachingEnabled(_ enabled: Bool) throws {
        try backend.writeTranscriptCachingEnabled(enabled)
        transcriptCachingEnabled = enabled
        if !enabled { try clearTranscriptTails() }
    }

    func isTranscriptCachingEnabled() -> Bool { transcriptCachingEnabled }

    private func storedRow(
        scope: OfflineCacheScope,
        summary: SessionRow,
        messages: [OfflineCachedMessage],
        now: Int64
    ) throws -> StoredOfflineCacheRow {
        let safeID = Self.bound(summary.id, bytes: OfflineCachePolicy.maxIDBytes)
        guard !safeID.isEmpty else { throw OfflineCacheError.invalidSessionID }
        let key = Self.rowKey(scope: scope, sessionID: safeID)
        return StoredOfflineCacheRow(
            rowKey: key,
            originFingerprint: Self.originFingerprint(scope.origin),
            profile: scope.profile,
            sessionID: safeID,
            updatedAtEpochSeconds: max(0, now),
            summary: StoredSessionSummary(
                title: Self.bound(summary.title, bytes: OfflineCachePolicy.maxTextBytes),
                preview: Self.bound(summary.preview, bytes: OfflineCachePolicy.maxTextBytes),
                lastActive: summary.lastActive,
                messageCount: max(0, summary.messageCount),
                model: summary.model.map { Self.bound($0, bytes: OfflineCachePolicy.maxTextBytes) },
                profile: scope.profile
            ),
            messages: transcriptCachingEnabled ? messages : []
        )
    }

    private func write(_ row: StoredOfflineCacheRow) throws {
        let plaintext = try encoder.encode(row)
        let aad = Data(row.rowKey.utf8)
        let ciphertext = try cipher.seal(plaintext, authenticating: aad)
        guard ciphertext.count <= OfflineCachePolicy.maxEncryptedRowBytes else {
            throw OfflineCacheError.persistenceFailed
        }
        try backend.writeRow(ciphertext, key: row.rowKey)
    }

    private func readRows(now: Int64) throws -> [StoredOfflineCacheRow] {
        let keys = try backend.listRowKeys(limit: OfflineCachePolicy.maxCandidateRows)
        var rows: [StoredOfflineCacheRow] = []
        for key in keys {
            guard key.hasPrefix("row-") else {
                try? backend.deleteRow(key: key)
                continue
            }
            do {
                guard let ciphertext = try backend.readRow(key: key),
                      ciphertext.count <= OfflineCachePolicy.maxEncryptedRowBytes else {
                    try? backend.deleteRow(key: key)
                    continue
                }
                let plaintext = try cipher.open(ciphertext, authenticating: Data(key.utf8))
                let row = try decoder.decode(StoredOfflineCacheRow.self, from: plaintext)
                guard row.rowKey == key,
                      row.originFingerprint.count == 64,
                      !row.profile.isEmpty,
                      row.profile.utf8.count <= OfflineCachePolicy.maxProfileBytes,
                      !row.sessionID.isEmpty,
                      row.sessionID.utf8.count <= OfflineCachePolicy.maxIDBytes,
                      row.updatedAtEpochSeconds >= 0,
                      row.updatedAtEpochSeconds <= Int64.max - OfflineCachePolicy.retentionSeconds,
                      row.updatedAtEpochSeconds + OfflineCachePolicy.retentionSeconds >= now
                else { throw OfflineCacheError.corruptRow }
                rows.append(row)
            } catch {
                // Corruption, authentication failure, expiry, and unknown
                // payload shape fail closed per-row without exposing content.
                try? backend.deleteRow(key: key)
            }
        }
        return rows
    }

    private func prune(now: Int64) throws {
        let rows = try readRows(now: now).sorted {
            $0.updatedAtEpochSeconds == $1.updatedAtEpochSeconds
                ? $0.rowKey < $1.rowKey
                : $0.updatedAtEpochSeconds > $1.updatedAtEpochSeconds
        }
        var keptBytes = 0
        for (index, row) in rows.enumerated() {
            guard let data = try backend.readRow(key: row.rowKey) else { continue }
            if index >= OfflineCachePolicy.maxSessionCount
                || keptBytes + data.count > OfflineCachePolicy.maxTotalBytes {
                try backend.deleteRow(key: row.rowKey)
            } else {
                keptBytes += data.count
            }
        }
    }

    private static func matches(_ row: StoredOfflineCacheRow, _ scope: OfflineCacheScope) -> Bool {
        row.originFingerprint == originFingerprint(scope.origin) && row.profile == scope.profile
    }

    private static func rowKey(scope: OfflineCacheScope, sessionID: String) -> String {
        "row-\(sha256("\(scope.origin)\u{0}\(scope.profile)\u{0}\(sessionID)")).cache"
    }

    private static func originFingerprint(_ origin: String) -> String { sha256(origin) }

    private static func sha256(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    private static func bound(_ value: String, bytes: Int) -> String {
        guard value.utf8.count > bytes else { return value }
        var result = value
        while result.utf8.count > bytes, !result.isEmpty { result.removeLast() }
        return result
    }

    private static func boundMessages(_ messages: [OfflineCachedMessage]) -> [OfflineCachedMessage] {
        var selected: [OfflineCachedMessage] = []
        var bytes = 0
        let budget = OfflineCachePolicy.maxTotalBytes / 2
        for message in messages.suffix(OfflineCachePolicy.maxMessagesPerSession).reversed() {
            let bounded = OfflineCachedMessage(
                role: message.role,
                text: bound(message.text, bytes: OfflineCachePolicy.maxBodyBytes),
                reasoningText: bound(message.reasoningText, bytes: OfflineCachePolicy.maxBodyBytes)
            )
            let size = bounded.text.utf8.count + bounded.reasoningText.utf8.count
            if selected.isEmpty || bytes + size <= budget {
                selected.insert(bounded, at: 0)
                bytes += size
            }
        }
        return selected
    }
}

private struct StoredOfflineCacheRow: Codable {
    let rowKey: String
    let originFingerprint: String
    let profile: String
    let sessionID: String
    let updatedAtEpochSeconds: Int64
    let summary: StoredSessionSummary
    let messages: [OfflineCachedMessage]

    func replacingMessages(_ messages: [OfflineCachedMessage]) -> Self {
        Self(
            rowKey: rowKey,
            originFingerprint: originFingerprint,
            profile: profile,
            sessionID: sessionID,
            updatedAtEpochSeconds: updatedAtEpochSeconds,
            summary: summary,
            messages: messages
        )
    }
}

private struct StoredSessionSummary: Codable {
    let title: String
    let preview: String
    let lastActive: Date?
    let messageCount: Int
    let model: String?
    let profile: String

    func sessionRow(id: String, profile: String) -> SessionRow {
        SessionRow(
            id: id,
            title: title,
            preview: preview,
            lastActive: lastActive,
            messageCount: messageCount,
            model: model,
            profile: profile
        )
    }
}
