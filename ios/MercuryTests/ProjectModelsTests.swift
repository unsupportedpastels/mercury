import Foundation
import XCTest
@testable import Mercury

final class ProjectModelsTests: XCTestCase {
    func testTreeParsesOfficialShapeAliasesAndHomeBucketWithoutNormalizingServerValues() throws {
        let data = Data(#"""
        {
          "projects": [
            {
              "id": "p_server",
              "label": "App",
              "path": "/srv/Canonical App",
              "isAuto": true,
              "sessionCount": 4,
              "previewSessions": [{"id":"stored-1","title":"First","cwd":"/srv/Canonical App"}],
              "repos": [{
                "id":"repo-1", "label":"main", "path":"/srv/Canonical App",
                "groups":[{"id":"lane-1","label":"Main","isMain":true,"isKanban":false,"sessions":[]}]
              }],
              "future": {"additive": true}
            },
            {"id":"__no_project__","label":"Home","path":"/must/not/be/used","isNoProject":false}
          ],
          "active_id": "p_server",
          "scoped_session_ids": ["stored-1", {"id":"stored-2"}],
          "future": true
        }
        """#.utf8)

        let tree = try ProjectModelsParser.parseTree(data)

        XCTAssertEqual(tree.activeProjectID, ProjectID("p_server"))
        XCTAssertEqual(tree.scopedSessionIDs, Set(["stored-1", "stored-2"]))
        XCTAssertEqual(tree.projects[0].id.rawValue, "p_server")
        XCTAssertEqual(tree.projects[0].path, "/srv/Canonical App")
        XCTAssertTrue(tree.projects[0].isAuto)
        XCTAssertEqual(tree.projects[0].previewSessions.single?.projectID, ProjectID("p_server"))
        XCTAssertEqual(tree.projects[0].repositories.single?.groups.single?.id, "lane-1")
        XCTAssertTrue(tree.projects[1].isNoProject)
        XCTAssertNil(tree.projects[1].path)
    }

    func testTreeSkipsMalformedRowsAndBoundsProjectsPreviewsAndScopedIDs() throws {
        let projects: [[String: Any]] = (0..<105).map { index in
            [
                "id": "p-\(index)",
                "label": String(repeating: "L", count: 200),
                "previewSessions": (0..<5).map { ["id": "s-\(index)-\($0)", "title": "Preview"] },
            ]
        }
        let malformedProjects: [Any] = [["label": "missing id"], "not an object"]
        let scopedIDs: [Any] = (0..<2_005).map { "scoped-\($0)" as Any } + [17, ""]
        var payload: [String: Any] = [
            "projects": malformedProjects + projects.map { $0 as Any },
            "scoped_session_ids": scopedIDs,
        ]
        payload["additive"] = ["ignored": true]

        let tree = try ProjectModelsParser.parseTree(JSONSerialization.data(withJSONObject: payload))

        XCTAssertEqual(tree.projects.count, ProjectModelBounds.maxProjects)
        XCTAssertEqual(tree.projects.first?.previewSessions.count, ProjectModelBounds.maxPreviewSessions)
        XCTAssertEqual(tree.projects.first?.label.count, ProjectModelBounds.maxLabelCharacters)
        XCTAssertEqual(tree.scopedSessionIDs.count, ProjectModelBounds.maxScopedSessionIDs)
    }

    func testTreeRejectsRelativeAndTraversalProjectPaths() throws {
        let tree = try ProjectModelsParser.parseTree(Data(#"""
        {"projects":[
          {"id":"relative","label":"Relative","path":"workspace/app"},
          {"id":"traversal","label":"Traversal","path":"/srv/../secret"},
          {"id":"canonical","label":"Canonical","path":"/srv/app"}
        ]}
        """#.utf8))

        XCTAssertNil(tree.projects[0].path)
        XCTAssertNil(tree.projects[1].path)
        XCTAssertEqual(tree.projects[2].path, "/srv/app")
    }

    func testProjectSessionsFlattenNestedReposAndGroupsDeduplicateAndNeverUseRuntimeID() throws {
        let data = Data(#"""
        {"project":{
          "id":"p1","label":"App","path":"/workspace/app","sessionCount":4,
          "repos":[
            {"id":"repo-a","groups":[{"id":"main","sessions":[
              {"id":"stored-1","session_key":"runtime-wrong","title":"First","cwd":"/workspace/app"},
              {"id":"stored-1","title":"Duplicate"},
              {"session_key":"runtime-only","title":"Must be skipped"},
              {"id":7,"title":"Malformed"}
            ]}]},
            {"id":"repo-b","groups":[{"id":"work","sessions":[
              {"durable_id":"stored-2","name":"Second","workspace_path":"/workspace/app/sub"}
            ]}]}
          ]
        },"future":"ignored"}
        """#.utf8)

        let result = try ProjectModelsParser.parseProjectSessions(data, requestedProjectID: ProjectID("p1"))

        XCTAssertEqual(result.sessions.map(\.id), ["stored-1", "stored-2"])
        XCTAssertEqual(result.sessions[0].workspacePath, "/workspace/app")
        XCTAssertEqual(result.sessions[0].projectID, ProjectID("p1"))
        XCTAssertEqual(result.project.repos.count, 2)
    }

    func testProjectSessionsAreBoundedAtOneHundredLoadedRowsAcrossLanes() throws {
        let rows = (0..<125).map { ["id": "stored-\($0)", "title": "Session \($0)"] }
        let payload: [String: Any] = [
            "project": [
                "id": "p1",
                "repos": [["id": "repo", "groups": [["id": "main", "sessions": rows]]]],
            ],
        ]

        let result = try ProjectModelsParser.parseProjectSessions(
            JSONSerialization.data(withJSONObject: payload),
            requestedProjectID: ProjectID("p1")
        )

        XCTAssertEqual(result.sessions.count, ProjectModelBounds.maxLoadedSessions)
        XCTAssertEqual(result.sessions.last?.id, "stored-99")
        XCTAssertEqual(result.project.repos.single?.groups.single?.sessions.count, ProjectModelBounds.maxLoadedSessions)
    }

    func testMalformedContainersDegradeToEmptyFallbackInsteadOfFailingWholePayload() throws {
        let tree = try ProjectModelsParser.parseTree(Data(#"{"projects":"wrong","active_id":3,"scoped_session_ids":{}}"#.utf8))
        XCTAssertTrue(tree.projects.isEmpty)
        XCTAssertNil(tree.activeProjectID)
        XCTAssertTrue(tree.scopedSessionIDs.isEmpty)

        let requested = ProjectID("requested")
        let sessions = try ProjectModelsParser.parseProjectSessions(
            Data(#"{"project":{"label":9,"repos":"wrong"},"sessions":"wrong"}"#.utf8),
            requestedProjectID: requested
        )
        XCTAssertEqual(sessions.project.id, requested)
        XCTAssertEqual(sessions.project.label, "requested")
        XCTAssertTrue(sessions.sessions.isEmpty)
    }

    func testCreateResultRequiresProjectObjectWithAuthoritativeOpaqueID() throws {
        let result = try ProjectModelsParser.parseCreateResult(Data(#"""
        {"project":{"id":"/srv/opaque project id","name":"Created","primary_path":"/srv/App"},"future":true}
        """#.utf8))

        XCTAssertEqual(result.project.id, ProjectID("/srv/opaque project id"))
        XCTAssertEqual(result.project.label, "Created")
        XCTAssertEqual(result.project.path, "/srv/App")

        for malformed in [
            #"{}"#,
            #"{"project":null}"#,
            #"{"project":{"name":"Missing ID"}}"#,
            #"{"project":{"project_id":"alias-is-not-create-id"}}"#,
            #"{"project":{"id":""}}"#,
            #"{"project":{"id":"   "}}"#,
            #"{"project":{"id":7}}"#,
        ] {
            XCTAssertThrowsError(try ProjectModelsParser.parseCreateResult(Data(malformed.utf8)))
        }
    }

    func testActiveResultRequiresExactBoundedIDOrExplicitNull() throws {
        XCTAssertEqual(
            try ProjectModelsParser.parseActiveProjectID(Data(#"{"active_id":"p:opaque/value","extra":1}"#.utf8)),
            ProjectID("p:opaque/value")
        )
        XCTAssertNil(try ProjectModelsParser.parseActiveProjectID(Data(#"{"active_id":null}"#.utf8)))

        for malformed in [#"{}"#, #"{"active_id":7}"#, #"{"active_id":""}"#, #"{"active_id":"   "}"#] {
            XCTAssertThrowsError(try ProjectModelsParser.parseActiveProjectID(Data(malformed.utf8)))
        }
    }

    func testReconciliationJoinsOnlyDurableIDPrefersRESTMetadataAndAssignsProjectWorkspace() {
        let project = ProjectSummary(
            id: ProjectID("p1"),
            label: "App",
            path: "/workspace/app",
            sessionCount: 1
        )
        let rpc = ProjectSession(
            id: "stored-1",
            title: "RPC title",
            preview: "RPC preview",
            lastActive: Date(timeIntervalSince1970: 10),
            messageCount: 1,
            model: "rpc-model",
            provider: "rpc-provider",
            profile: "rpc-profile",
            workspacePath: "/workspace/app/sub",
            projectID: project.id
        )
        let runtimeCollision = SessionRow(id: "runtime-wrong", title: "Wrong runtime match")
        let rest = SessionRow(
            id: "stored-1",
            title: "REST title",
            preview: "REST preview",
            lastActive: Date(timeIntervalSince1970: 20),
            messageCount: 8,
            model: "rest-model",
            profile: "rest-profile"
        )

        let reconciled = ProjectSessionReconciler.reconcile(
            projectSessions: [rpc],
            restSessions: [runtimeCollision, rest],
            project: project
        )

        XCTAssertEqual(reconciled.single?.id, "stored-1")
        XCTAssertEqual(reconciled.single?.row.title, "REST title")
        XCTAssertEqual(reconciled.single?.row.preview, "REST preview")
        XCTAssertEqual(reconciled.single?.row.messageCount, 8)
        XCTAssertEqual(reconciled.single?.projectID, ProjectID("p1"))
        XCTAssertEqual(reconciled.single?.workspacePath, "/workspace/app/sub")
        XCTAssertEqual(reconciled.single?.provider, "rpc-provider")
    }
}

private extension Array {
    var single: Element? { count == 1 ? first : nil }
}
