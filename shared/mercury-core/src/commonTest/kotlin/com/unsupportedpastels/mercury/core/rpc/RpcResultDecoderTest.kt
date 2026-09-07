package com.unsupportedpastels.mercury.core.rpc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RpcResultDecoderTest {
    @Test
    fun modelOptionsFilterAndDeduplicateTolerantly() {
        val options = RpcResultDecoder.modelOptions(
            """{"provider":"nous","model":"m1","providers":[
              {"slug":"nous","name":"Nous","authenticated":true,"models":["m1","m1","  m2  ","bad model"],
               "capabilities":{"m1":{"fast":true,"reasoning":false,"future":1},"m2":{"reasoning":true},"m3":{"fast":"true"}}},
              {"slug":"hidden","authenticated":false,"models":["secret"]},
              {"slug":"nous","models":["duplicate-provider"]},
              {"slug":"empty","models":[]},"junk"],"future":"ignored"}""",
        )
        assertEquals(ModelSelectionSpec("nous", "m1"), options.current)
        assertEquals(1, options.providers.size)
        assertEquals(listOf("m1", "m2"), options.providers[0].models)
        assertEquals(ModelCapabilitiesSpec(fast = true, reasoning = false), options.providers[0].capabilities["m1"])
        assertEquals(ModelCapabilitiesSpec(fast = null, reasoning = null), options.providers[0].capabilities["m3"])
    }

    @Test
    fun usageAliasesClampBoundsAndContextBreakdownDeduplicates() {
        val usage = RpcResultDecoder.sessionUsage(
            """{"prompt_tokens":12,"completion_tokens":8,"total":20,"used_tokens":200,"max_tokens":1000,
               "context_percentage":120,"requests":-2,"credits_lines":["a",4,"b"],"info":{"x":1}}""",
        )
        assertEquals(12L, usage.inputTokens)
        assertEquals(8L, usage.outputTokens)
        assertEquals(100.0, usage.contextPercent)
        assertEquals(0L, usage.calls)
        assertEquals(listOf("a", "b"), usage.creditsLines)
        assertNull(usage.rawInfo)

        val breakdown = RpcResultDecoder.contextBreakdown(
            """{"breakdown":[{"category":"system","token_count":7},{"label":"tools","count":3},{"name":"system","tokens":99},4],
               "context_used":10,"context_max":100,"context_percent":10}""",
        )
        assertEquals(listOf("system", "tools"), breakdown.categories.map(ContextCategoryResult::name))
        assertEquals(listOf(7L, 3L), breakdown.categories.map(ContextCategoryResult::tokens))
        assertEquals(10L, breakdown.usedTokens)
    }

    @Test
    fun compressAndBranchKeepMessageRowsAsJsonText() {
        val compress = RpcResultDecoder.compress(
            """{"status":"aborted","messages":[{"role":"user"},"bad"],"usage":{"input":5},"info":{"future":1}}""",
        )
        assertTrue(compress.aborted)
        assertEquals(listOf("""{"role":"user"}"""), compress.messagesJson)
        assertEquals(5L, compress.usage?.inputTokens)
        assertNull(compress.info)

        val branch = RpcResultDecoder.branch(
            """{"session_id":"new-runtime","durable_session_id":"new-durable","title":"Branch","messages":[{"role":"assistant"}]}""",
        )
        assertEquals("new-runtime", branch.runtimeSessionId)
        assertEquals("new-durable", branch.durableSessionId)
        assertFailsWith<RpcResultException> { RpcResultDecoder.branch("""{"session_id":"runtime-only"}""") }
    }

    @Test
    fun slashCompletionDefaultsDisplayAndClampsReplaceFrom() {
        val decoded = RpcResultDecoder.slashCompletion(
            """{"items":[{"text":"help","display":"/help","meta":"docs"},{"text":"model"},{"display":"bad"},7],"replace_from":2}""",
            inputLength = 1,
        )
        assertEquals(
            listOf(SlashCompletionRow("help", "/help", "docs"), SlashCompletionRow("model", "/model", null)),
            decoded.items,
        )
        assertEquals(1, decoded.replaceFrom)
        assertEquals(0, RpcResultDecoder.slashCompletion("""{"items":[]}""", 5).replaceFrom)
        assertFailsWith<RpcResultException> { RpcResultDecoder.slashCompletion("""{"replace_from":-1}""", 5) }
    }

    @Test
    fun resumeRejectsDurableMismatchAndReadsInfo() {
        val resumed = RpcResultDecoder.resume(
            """{"session_id":"runtime-1","session_key":"durable-1","resumed":true,"running":false,
               "messages":[{"role":"user","text":"hi"}],"inflight":{"user":"u","streaming":true},
               "info":{"model":"m","provider":"p","reasoning_effort":"high","fast":true}}""",
            requestedDurableSessionId = "durable-1",
        )
        assertEquals("runtime-1", resumed.runtimeSessionId)
        assertEquals("durable-1", resumed.durableSessionId)
        assertTrue(resumed.resumed)
        assertEquals(1, resumed.messagesJson.size)
        assertTrue(resumed.hasInflight)
        assertTrue(resumed.inflightStreaming)
        assertEquals("high", resumed.reasoningEffort)
        assertEquals(true, resumed.fastMode)
        assertFailsWith<RpcResultException> {
            RpcResultDecoder.resume("""{"session_id":"runtime-1","session_key":"other"}""", "durable-1")
        }
        assertFailsWith<RpcResultException> { RpcResultDecoder.resume("""{"session_key":"durable-1"}""", "durable-1") }
    }

    @Test
    fun interactionResponseAcceptsStatusOrResolvedForms() {
        assertEquals(InteractionStatus.Ok, RpcResultDecoder.interactionResponse("""{"status":"OK"}"""))
        assertEquals(InteractionStatus.Expired, RpcResultDecoder.interactionResponse("""{"resolved":false}"""))
        assertEquals(InteractionStatus.Ok, RpcResultDecoder.interactionResponse("""{"resolved":2}"""))
        assertEquals(InteractionStatus.Unknown, RpcResultDecoder.interactionResponse("""{"status":"later"}"""))
        assertFailsWith<RpcResultException> { RpcResultDecoder.interactionResponse("""{"other":1}""") }
    }
}
