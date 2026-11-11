package com.unsupportedpastels.mercury.core.interaction

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ServerRequestPolicyTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun recognisesOnlyStringIdRequestFrames() {
        val request = obj("""{"jsonrpc":"2.0","id":"srq-1","method":"clarify","params":{}}""")
        assertEquals("srq-1", ServerRequestPolicy.requestId(request))
        assertEquals("clarify", ServerRequestPolicy.method(request))

        val clientResponse = obj("""{"jsonrpc":"2.0","id":7,"result":{}}""")
        assertNull(ServerRequestPolicy.requestId(clientResponse))
        assertNull(ServerRequestPolicy.method(clientResponse))

        val event = obj("""{"jsonrpc":"2.0","method":"event","params":{}}""")
        assertNull(ServerRequestPolicy.method(event))
    }

    @Test
    fun clarifySurfacesAsLegacyBatchEvent() {
        val disposition = ServerRequestPolicy.classify(
            "clarify",
            "srq-1",
            """{"session_id":"s1","questions":[
                {"qid":"q1","question":"Which?","choices":["a","b"],"multi_select":false},
                {"qid":"q2","question":"Why?","choices":[],"multi_select":false}
            ]}""",
        )
        val surface = assertIs<ServerRequestPolicy.Disposition.Surface>(disposition)
        assertEquals("clarify.request", surface.eventType)
        assertEquals("srq-1", surface.legacyRequestId)
        assertEquals(listOf("q1", "q2"), surface.questionIds)
        val payload = obj(surface.payloadJson)
        assertEquals("srq-1", payload["request_id"]!!.jsonPrimitive.content)
        assertEquals(2, payload["questions"]!!.jsonArray.size)
    }

    @Test
    fun clarifyReplayDropsQuestionsTheServerAlreadyLocked() {
        val disposition = ServerRequestPolicy.classify(
            "clarify",
            "srq-1",
            """{"questions":[{"qid":"q1","question":"A?"},{"qid":"q2","question":"B?"}],"answers":{"q1":null}}""",
        )
        assertEquals(listOf("q2"), assertIs<ServerRequestPolicy.Disposition.Surface>(disposition).questionIds)

        val allLocked = ServerRequestPolicy.classify(
            "clarify",
            "srq-1",
            """{"questions":[{"qid":"q1","question":"A?"}],"answers":{"q1":"x"}}""",
        )
        assertIs<ServerRequestPolicy.Disposition.Ignore>(allLocked)
    }

    @Test
    fun approvalKeepsTheQueueRequestIdAndDropsSessionId() {
        val disposition = ServerRequestPolicy.classify(
            "approval",
            "srq-9",
            """{"session_id":"s1","request_id":"appr-1","command":"rm -rf x","description":"danger",
                "choices":["once","session","always","deny"]}""",
        )
        val surface = assertIs<ServerRequestPolicy.Disposition.Surface>(disposition)
        assertEquals("approval.request", surface.eventType)
        assertEquals("appr-1", surface.legacyRequestId)
        val payload = obj(surface.payloadJson)
        assertEquals("appr-1", payload["request_id"]!!.jsonPrimitive.content)
        assertNull(payload["session_id"])
        assertEquals(4, payload["choices"]!!.jsonArray.size)
    }

    @Test
    fun approvalWithoutQueueIdIsDeclinedNotStalled() {
        val disposition = ServerRequestPolicy.classify("approval", "srq-9", """{"command":"x"}""")
        assertEquals(
            ServerRequestPolicy.METHOD_NOT_FOUND_CODE,
            assertIs<ServerRequestPolicy.Disposition.Decline>(disposition).code,
        )
    }

    @Test
    fun secretAndSudoUseTheSecureInputCard() {
        val secret = assertIs<ServerRequestPolicy.Disposition.Surface>(
            ServerRequestPolicy.classify("secret", "srq-2", """{"env_var":"KEY","prompt":"API key"}"""),
        )
        assertEquals("secret.request", secret.eventType)
        assertEquals("API key", obj(secret.payloadJson)["prompt"]!!.jsonPrimitive.content)

        val sudo = assertIs<ServerRequestPolicy.Disposition.Surface>(
            ServerRequestPolicy.classify("sudo", "srq-3", """{"command":"apt"}"""),
        )
        assertEquals("sudo.request", sudo.eventType)
        assertEquals("srq-3", sudo.legacyRequestId)
    }

    @Test
    fun desktopBridgesAndUnknownMethodsAreDeclinedImmediately() {
        for (method in listOf("terminal.read", "preview.read", "preview.act", "window.read", "tour", "theme.apply")) {
            assertEquals(
                ServerRequestPolicy.NOT_SHOWN_CODE,
                assertIs<ServerRequestPolicy.Disposition.Decline>(ServerRequestPolicy.classify(method, "srq", "{}")).code,
            )
        }
        assertEquals(
            ServerRequestPolicy.METHOD_NOT_FOUND_CODE,
            assertIs<ServerRequestPolicy.Disposition.Decline>(ServerRequestPolicy.classify("vault.code", "srq", "{}")).code,
        )
    }

    @Test
    fun wireShapesMatchTheHermesContracts() {
        assertEquals("""{"server_requests":true}""", ServerRequestPolicy.capabilitiesParams())

        val skipped = obj(ServerRequestPolicy.clarifyLockParams("srq-1", "q1", ""))
        assertEquals(JsonNull, skipped["answer"])
        assertEquals("a", obj(ServerRequestPolicy.clarifyLockParams("srq-1", "q1", "a"))["answer"]!!.jsonPrimitive.content)

        val answer = obj(ServerRequestPolicy.requestAnswerParams("srq-2", ServerRequestPolicy.valueResult("secret")))
        assertEquals("srq-2", answer["id"]!!.jsonPrimitive.content)
        assertEquals("secret", answer["result"]!!.jsonObject["value"]!!.jsonPrimitive.content)

        val error = obj(ServerRequestPolicy.errorFrame("srq-3", 4404, "nope"))
        assertEquals("srq-3", error["id"]!!.jsonPrimitive.content)
        assertEquals(4404L, error["error"]!!.jsonObject["code"]!!.jsonPrimitive.long)
    }

    @Test
    fun openRequestsReadsTheResumeSnapshot() {
        val requests = ServerRequestPolicy.openRequests(
            """{"session_id":"s1","open_requests":[
                {"id":"srq-1","method":"clarify","params":{"session_id":"s1","questions":[]}},
                {"id":5,"method":"bad"},
                {"id":"srq-2"}
            ]}""",
        )
        assertEquals(listOf("srq-1"), requests.map { ServerRequestPolicy.requestId(it) })
        assertEquals(emptyList(), ServerRequestPolicy.openRequests("""{"session_id":"s1"}"""))
    }

    @Test
    fun withdrawnRequestsMapToLegacyExpireEvents() {
        assertEquals("clarify.expire", ServerRequestPolicy.expireEventType("clarify"))
        assertEquals("approval.expire", ServerRequestPolicy.expireEventType("approval"))
        assertEquals("secret.expire", ServerRequestPolicy.expireEventType("secret"))
        assertEquals("sudo.expire", ServerRequestPolicy.expireEventType("sudo"))
        assertNull(ServerRequestPolicy.expireEventType("tour"))
    }
}
