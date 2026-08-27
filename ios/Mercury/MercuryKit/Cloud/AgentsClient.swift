import Foundation

// MARK: - Portal agent discovery (GET /api/agents)
//
// Wire contract verified against the live Portal:
// - GET https://portal.nousresearch.com/api/agents
//   header `Authorization: Bearer <token>`
//   → 200 {agents:[{id,name,status,dashboardUrl,dashboardGatewayState}], org:{...}}
// - 409 {error:"org_selection_required", orgs:[...]} → re-call ?org=<slug>
// - An account without a subscription gets a verification_uri pointing at the
//   subscribe page — expected account state, not an error.

/// One discovered cloud agent row.
struct AgentRow: Equatable, Sendable {
    var id: String
    var name: String?
    var status: String?
    var dashboardURL: String?
    var dashboardGatewayState: String?
}

struct OrgInfo: Equatable, Sendable {
    var name: String?
    var slug: String?
}

struct AgentsResult: Equatable, Sendable {
    var agents: [AgentRow]
    var org: OrgInfo?
}

/// One selectable organization from a 409 org_selection_required response.
struct OrgOption: Equatable, Sendable {
    var slug: String
    var name: String?
}

enum AgentsError: Error, Equatable {
    case invalidToken
    case orgSelectionRequired([OrgOption])
    case failed(String)
}

struct AgentsClient {

    /// Hard cap on response body (64 KiB), matching the rest of MercuryKit.
    static let maxBodyBytes = 65_536

    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    /// Fetches the account's cloud agents. Pass `org` to disambiguate after a
    /// 409 org-selection response.
    func agents(
        origin: String,
        accessToken: String,
        org: String? = nil
    ) async throws -> AgentsResult {
        guard !accessToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw AgentsError.invalidToken
        }
        guard let normalized = URL(string: origin) else {
            throw AgentsError.failed("Hermes Cloud origin was invalid")
        }
        var components = URLComponents(url: normalized.appendingPathComponent("api/agents"), resolvingAgainstBaseURL: false)
        if let org, !org.isEmpty {
            components?.queryItems = [URLQueryItem(name: "org", value: org)]
        }
        guard let url = components?.url else {
            throw AgentsError.failed("Hermes Cloud agents URL was invalid")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "GET"
        request.setValue("Bearer \(accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        let data: Data
        let httpResponse: HTTPURLResponse
        do {
            let (body, response) = try await session.data(for: request)
            data = body
            httpResponse = response as? HTTPURLResponse ?? HTTPURLResponse(
                url: url, statusCode: 502, httpVersion: nil, headerFields: nil)!
        } catch {
            throw AgentsError.failed("Could not reach Hermes Cloud")
        }

        if data.count > Self.maxBodyBytes {
            throw AgentsError.failed("Hermes Cloud agents response too large")
        }

        switch httpResponse.statusCode {
        case 200...299:
            break
        case 409:
            // {error:"org_selection_required", orgs:[...]} — surface choices.
            guard Self.isOrgSelectionRequired(data) else {
                throw AgentsError.failed("Hermes Cloud returned HTTP 409")
            }
            let options = Self.parseOrgOptions(data)
            throw AgentsError.orgSelectionRequired(options)
        case 401, 403:
            throw AgentsError.invalidToken
        default:
            throw AgentsError.failed("Hermes Cloud returned HTTP \(httpResponse.statusCode)")
        }

        return try Self.parse(data)
    }

    // MARK: - Parsing

    static func parse(_ data: Data) throws -> AgentsResult {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any] else {
            throw AgentsError.failed("Hermes Cloud agents response was not valid JSON")
        }

        var rows: [AgentRow] = []
        if let list = root["agents"] as? [[String: Any]] {
            for item in list {
                // id is required; everything else tolerantly optional.
                guard let id = bounded(item["id"], maxChars: 256), !id.isEmpty else { continue }
                rows.append(AgentRow(
                    id: id,
                    name: bounded(item["name"], maxChars: 256),
                    status: bounded(item["status"], maxChars: 64),
                    dashboardURL: bounded(item["dashboardUrl"], maxChars: 2048)
                        ?? bounded(item["dashboard_url"], maxChars: 2048),
                    dashboardGatewayState: bounded(item["dashboardGatewayState"], maxChars: 64)
                        ?? bounded(item["dashboard_gateway_state"], maxChars: 64)
                ))
            }
        }

        var info: OrgInfo?
        if let orgObject = root["org"] as? [String: Any] {
            info = OrgInfo(
                name: bounded(orgObject["name"], maxChars: 256),
                slug: bounded(orgObject["slug"], maxChars: 256)
            )
        }
        return AgentsResult(agents: rows, org: info)
    }

    static func parseOrgOptions(_ data: Data) -> [OrgOption] {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any],
              let list = root["orgs"] as? [[String: Any]] else { return [] }
        var seen = Set<String>()
        var options: [OrgOption] = []
        for item in list {
            guard let slug = bounded(item["slug"], maxChars: 256) ?? bounded(item["id"], maxChars: 256),
                  !slug.isEmpty,
                  seen.insert(slug).inserted else { continue }
            options.append(OrgOption(slug: slug, name: bounded(item["name"], maxChars: 256)))
        }
        return options
    }

    private static func isOrgSelectionRequired(_ data: Data) -> Bool {
        guard let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any],
              root["orgs"] is [Any] else { return false }
        return bounded(root["error"], maxChars: 64) == "org_selection_required"
    }

    private static func bounded(_ value: Any?, maxChars: Int) -> String? {
        guard let string = value as? String else { return nil }
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        return String(trimmed.prefix(maxChars))
    }
}
