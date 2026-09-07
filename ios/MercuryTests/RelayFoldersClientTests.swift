import XCTest
@testable import Mercury

@MainActor
final class RelayFoldersClientTests: XCTestCase {
    private let status: [String: Any] = ["capabilities": ["folders": [
        "version": 1, "list_method": "relay.folders.list", "create_method": "relay.folders.create"
    ]]]
    private let listing: [String: Any] = [
        "path": "/workspace", "parent": NSNull(), "root": "/workspace",
        "locked_root": "/workspace", "can_change_path": false,
        "entries": [["name": "existing", "path": "/workspace/existing", "is_dir": true]]
    ]

    func testRPCBoundaryAllowsOnlyKnownFolderErrorReasons() async throws {
        for reason in ["folder_exists", "private server detail"] {
            let socket = ConnectionTestSocket(frames: [])
            socket.autoRespond = { sent in
                guard let request = try? JSONSerialization.jsonObject(with: Data(sent.utf8)) as? [String: Any],
                      let id = request["id"],
                      let data = try? JSONSerialization.data(withJSONObject: [
                        "jsonrpc": "2.0", "id": id,
                        "error": ["code": -32000, "message": reason]
                      ]) else { return nil }
                return String(data: data, encoding: .utf8)
            }
            let connection = try ChatConnection(socket: socket)
            _ = connection.start()
            do {
                _ = try await connection.relayRequest("relay.folders.create", params: [:])
                XCTFail("Expected a folder error")
            } catch let error as RelayFoldersError {
                XCTAssertEqual(reason, "folder_exists")
                XCTAssertEqual(error.localizedDescription, "A folder with that name already exists.")
            } catch let error as ChatError {
                XCTAssertEqual(reason, "private server detail")
                XCTAssertEqual(error, .protocolError("Hermes RPC request failed (-32000)"))
            }
            await connection.close()
        }
    }

    func testAdvertisedHostListsDirectoriesThroughSharedContract() async throws {
        var methods: [String] = []
        let client = RelayFoldersClient(profile: "default") { method, params in
            methods.append(method)
            if method == "relay.status" { return self.status }
            XCTAssertEqual(params["profile"] as? String, "default")
            XCTAssertNil(params["path"])
            return self.listing
        }
        let result = try await client.list(path: nil)
        XCTAssertEqual(methods, ["relay.status", "relay.folders.list"])
        XCTAssertEqual(result.path, "/workspace")
        XCTAssertEqual(result.entries.map(\.path), ["/workspace/existing"])
        XCTAssertFalse(result.canChangePath)
    }

    func testCreateUsesParentAndNameAndReturnsCanonicalListing() async throws {
        var methods: [String] = []
        let client = RelayFoldersClient(profile: "default") { method, params in
            methods.append(method)
            if method == "relay.status" { return self.status }
            XCTAssertEqual(params["parent_path"] as? String, "/workspace")
            XCTAssertEqual(params["name"] as? String, "new folder")
            return ["path": "/workspace/new folder", "parent": "/workspace", "entries": []]
        }
        let created = try await client.createDirectory(parentPath: "/workspace", name: "new folder")
        XCTAssertEqual(created.path, "/workspace/new folder")
        XCTAssertEqual(methods, ["relay.status", "relay.folders.create"])
    }

    func testOlderHostGetsUpgradeGuidanceWithoutMutation() async throws {
        let client = RelayFoldersClient(profile: "default") { method, _ in
            XCTAssertEqual(method, "relay.status")
            return [:]
        }
        do {
            _ = try await client.createDirectory(parentPath: "/workspace", name: "new")
            XCTFail("Unsupported host must not receive a mutation")
        } catch let error as RelayFoldersError {
            XCTAssertEqual(error, .unsupported)
            XCTAssertTrue(error.localizedDescription.contains("Update"))
        }
    }

    func testInvalidNameDoesNotDispatch() async throws {
        let client = RelayFoldersClient(profile: "default") { _, _ in
            XCTFail("Invalid input must not dispatch")
            return [:]
        }
        do {
            _ = try await client.createDirectory(parentPath: "/workspace", name: "../escape")
            XCTFail("Traversal must be rejected")
        } catch let error as RelayFoldersError {
            XCTAssertEqual(error, .invalidInput)
        }
    }

    func testCancellationAfterStatusNeverDispatchesCreate() async throws {
        let began = expectation(description: "status started")
        var resume: CheckedContinuation<Void, Never>?
        let client = RelayFoldersClient(profile: "default") { method, _ in
            XCTAssertEqual(method, "relay.status")
            await withCheckedContinuation { resume = $0; began.fulfill() }
            return self.status
        }
        let task = Task { try await client.createDirectory(parentPath: "/workspace", name: "new") }
        await fulfillment(of: [began], timeout: 3)
        task.cancel()
        resume?.resume()
        do { _ = try await task.value; XCTFail("Cancelled mutation must not dispatch") }
        catch is CancellationError { }
    }
}
