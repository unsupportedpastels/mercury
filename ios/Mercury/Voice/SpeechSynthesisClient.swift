import Foundation

struct SpeechSynthesisRequest: Codable, Equatable, Sendable {
    let text: String
}

struct SpeechSynthesisResponse: Codable, Equatable, Sendable {
    let ok: Bool?
    let dataURL: String
    let mimeType: String?

    enum CodingKeys: String, CodingKey {
        case ok
        case dataURL = "data_url"
        case mimeType = "mime_type"
    }
}

struct SpeechAudio: Equatable, Sendable {
    let data: Data
    let mimeType: String
}

enum SpeechSynthesisError: Error, Equatable, Sendable {
    case invalidOrigin
    case authenticationMissing
    case emptyText
    case authenticationRejected
    case unsupported
    case requestRejected(statusCode: Int)
    case transient(statusCode: Int)
    case unexpectedStatus(statusCode: Int)
    case responseTooLarge
    case audioTooLarge
    case invalidResponse
    case transport

    static func classify(statusCode: Int) -> SpeechSynthesisError {
        switch statusCode {
        case 401, 403: return .authenticationRejected
        case 404, 405: return .unsupported
        case 400, 409, 413, 415, 422: return .requestRejected(statusCode: statusCode)
        case 408, 425, 429, 500...599: return .transient(statusCode: statusCode)
        default: return .unexpectedStatus(statusCode: statusCode)
        }
    }
}

/// Contract audited against the official Hermes `POST /api/audio/speak`
/// handler. `/api/audio/speak-stream` is a WebSocket PCM conversation route,
/// not REST, and is intentionally outside this bounded single-message module.
enum SpeechSynthesisRequestPolicy {
    static let route = "/api/audio/speak"
    static let maxProfileCharacters = 64
    static let maxTextCharacters = 32_768
    static let maxResponseBytes = 8 * 1024 * 1024
    static let maxDecodedAudioBytes = 6 * 1024 * 1024

    static func makeRequest(
        origin: URL,
        accessToken: String,
        profile: String,
        text: String
    ) throws -> URLRequest {
        guard let scheme = origin.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              origin.host != nil else { throw SpeechSynthesisError.invalidOrigin }
        guard !accessToken.isEmpty else { throw SpeechSynthesisError.authenticationMissing }
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { throw SpeechSynthesisError.emptyText }

        var components = URLComponents()
        components.scheme = scheme
        components.host = origin.host
        components.port = origin.port
        components.path = route
        let boundedProfile = String(profile.prefix(maxProfileCharacters))
        if !boundedProfile.isEmpty {
            components.queryItems = [URLQueryItem(name: "profile", value: boundedProfile)]
        }
        guard let url = components.url else { throw SpeechSynthesisError.invalidOrigin }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.timeoutInterval = 120
        request.httpBody = try JSONEncoder().encode(SpeechSynthesisRequest(
            text: String(trimmed.prefix(maxTextCharacters))
        ))
        return request
    }

    static func decodeResponse(data: Data) throws -> SpeechAudio {
        guard data.count <= maxResponseBytes else { throw SpeechSynthesisError.responseTooLarge }
        guard let response = try? JSONDecoder().decode(SpeechSynthesisResponse.self, from: data) else {
            throw SpeechSynthesisError.invalidResponse
        }
        return try decodeAudioDataURL(response.dataURL)
    }

    private static func decodeAudioDataURL(_ value: String) throws -> SpeechAudio {
        guard value.hasPrefix("data:"), let comma = value.firstIndex(of: ",") else {
            throw SpeechSynthesisError.invalidResponse
        }
        let header = String(value[value.index(value.startIndex, offsetBy: 5)..<comma])
        let parts = header.split(separator: ";", omittingEmptySubsequences: false)
        guard parts.dropFirst().contains(where: { $0.lowercased() == "base64" }) else {
            throw SpeechSynthesisError.invalidResponse
        }
        let mime = parts.first.map(String.init).flatMap { $0.isEmpty ? nil : $0 } ?? "audio/mpeg"
        guard mime.lowercased().hasPrefix("audio/") else { throw SpeechSynthesisError.invalidResponse }
        let encoded = value[value.index(after: comma)...]
        let maximumEncodedCharacters = ((maxDecodedAudioBytes + 2) / 3) * 4 + 4
        guard encoded.utf8.count <= maximumEncodedCharacters else { throw SpeechSynthesisError.audioTooLarge }
        let compactEncoded = String(encoded.filter { !$0.isWhitespace })
        guard let audio = Data(base64Encoded: compactEncoded, options: []), !audio.isEmpty else {
            throw SpeechSynthesisError.invalidResponse
        }
        guard audio.count <= maxDecodedAudioBytes else { throw SpeechSynthesisError.audioTooLarge }
        return SpeechAudio(data: audio, mimeType: mime)
    }
}

protocol SpeechSynthesizing: Sendable {
    func synthesize(text: String) async throws -> SpeechAudio
}

struct RESTSpeechSynthesizer: SpeechSynthesizing {
    typealias Transport = @Sendable (URLRequest) async throws -> (Data, HTTPURLResponse)

    let origin: URL
    let accessToken: String
    let profile: String
    let transport: Transport

    init(origin: URL, accessToken: String, profile: String, transport: @escaping Transport) {
        self.origin = origin
        self.accessToken = accessToken
        self.profile = profile
        self.transport = transport
    }

    init(origin: URL, accessToken: String, profile: String, session: URLSession = .shared) {
        self.init(origin: origin, accessToken: accessToken, profile: profile) { request in
            do {
                let (data, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse else { throw SpeechSynthesisError.transport }
                return (data, http)
            } catch let error as SpeechSynthesisError {
                throw error
            } catch {
                throw SpeechSynthesisError.transport
            }
        }
    }

    func synthesize(text: String) async throws -> SpeechAudio {
        let request = try SpeechSynthesisRequestPolicy.makeRequest(
            origin: origin,
            accessToken: accessToken,
            profile: profile,
            text: text
        )
        let (data, response): (Data, HTTPURLResponse)
        do {
            (data, response) = try await transport(request)
        } catch let error as SpeechSynthesisError {
            throw error
        } catch {
            throw SpeechSynthesisError.transport
        }
        guard (200..<300).contains(response.statusCode) else {
            throw SpeechSynthesisError.classify(statusCode: response.statusCode)
        }
        return try SpeechSynthesisRequestPolicy.decodeResponse(data: data)
    }
}
