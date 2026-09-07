package com.unsupportedpastels.hermesandroid.app

import com.unsupportedpastels.hermesandroid.gateway.HermesChatEvent
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RunEventModelsTest {
    private val runtime = RuntimeSessionId("runtime-1")

    @Test
    fun toolCompletionReplacesMatchingStartInPlaceWithoutDuplicate() {
        val started = RunEventState().reduce(
            HermesChatEvent.ToolStart(
                sessionId = runtime,
                toolId = "tool-1",
                name = "terminal",
                context = "working directory",
            ),
        )
        val startedWithAnother = started.reduce(
            HermesChatEvent.ToolStart(runtime, "tool-2", "search", "query"),
        )

        val completed = startedWithAnother.reduce(
            HermesChatEvent.ToolComplete(
                sessionId = runtime,
                toolId = "tool-1",
                name = "terminal",
                summary = "command completed",
            ),
        )

        assertEquals(listOf("tool-1", "tool-2"), completed.tools.map { it.toolId })
        assertEquals(RunToolState.Completed, completed.tools[0].state)
        assertEquals("command completed", completed.tools[0].summary)
        assertEquals("working directory", completed.tools[0].context)
        assertEquals(RunToolState.Running, completed.tools[1].state)
        assertSame(startedWithAnother.tools[1], completed.tools[1])
    }

    @Test
    fun terminalMessageFinalizesRunningToolsWithoutChangingCompletedHistory() {
        val terminal = RunEventState(
            tools = listOf(
                RunToolRow("tool-running", "web_search", context = "query", state = RunToolState.Running),
                RunToolRow(
                    "tool-complete",
                    "web_extract",
                    summary = "Extracted pages",
                    state = RunToolState.Completed,
                ),
            ),
        ).reduce(HermesChatEvent.MessageComplete(runtime, "answer", "done"))

        assertEquals(listOf(RunToolState.Completed, RunToolState.Completed), terminal.tools.map { it.state })
        assertEquals("query", terminal.tools[0].context)
        assertEquals("Extracted pages", terminal.tools[1].summary)
    }

    @Test
    fun terminalErrorFinalizesRunningTools() {
        val terminal = RunEventState(
            tools = listOf(
                RunToolRow("tool-running", "terminal", state = RunToolState.Running),
            ),
        ).reduce(HermesChatEvent.Error(runtime, "connection failed"))

        assertEquals(RunToolState.Completed, terminal.tools.single().state)
    }

    @Test
    fun toolRowsKeepTheNewestBoundedWindowInEventOrder() {
        val state = (0 until MAX_RUN_TOOL_ROWS + 2).fold(RunEventState()) { current, index ->
            current.reduce(HermesChatEvent.ToolStart(runtime, "tool-$index", "tool", "context"))
        }

        assertEquals(2, state.tools.first().toolId.removePrefix("tool-").toInt())
        assertEquals(51, state.tools.last().toolId.removePrefix("tool-").toInt())
        assertEquals(MAX_RUN_TOOL_ROWS, state.tools.size)
    }

    @Test
    fun runEventFieldsAreBoundedAtTheReducerBoundary() {
        val longText = "x".repeat(10_000)
        val state = RunEventState().reduce(
            HermesChatEvent.ApprovalRequest(
                runtime,
                requestId = longText,
                command = longText,
                description = longText,
                choices = List(40) { longText },
            ),
        )

        assertEquals(256, state.approval?.requestId?.length)
        assertEquals(4_096, state.approval?.commandPreview?.length)
        assertEquals(4_096, state.approval?.descriptionPreview?.length)
        assertEquals(32, state.approval?.choices?.size)
        assertEquals(256, state.approval?.choices?.first()?.length)
    }

    @Test
    fun todoToolPayloadIsBoundedAndReplacesTheLivePlan() {
        val first = RunEventState().reduce(
            HermesChatEvent.ToolStart(
                sessionId = runtime,
                toolId = "todo-tool",
                name = "todo",
                context = null,
                todos = listOf(
                    RunTodoItem("one", "Inspect the gateway", RunTodoStatus.InProgress),
                    RunTodoItem("two", "Render the activity stack", RunTodoStatus.Pending),
                ),
            ),
        )

        val replaced = first.reduce(
            HermesChatEvent.ToolComplete(
                sessionId = runtime,
                toolId = "todo-tool",
                name = "todo",
                summary = "Plan updated",
                todos = listOf(
                    RunTodoItem("one", "Inspect the gateway", RunTodoStatus.Completed),
                    RunTodoItem("two", "Render the activity stack", RunTodoStatus.InProgress),
                ),
            ),
        )

        assertEquals(
            listOf(RunTodoStatus.Completed, RunTodoStatus.InProgress),
            replaced.todos.map(RunTodoItem::status),
        )
        assertEquals("Render the activity stack", replaced.todos[1].content)
    }

    @Test
    fun malformedTodoUpdateDoesNotEraseTheLastKnownPlan() {
        val state = RunEventState(
            todos = listOf(RunTodoItem("one", "Keep this plan", RunTodoStatus.Pending)),
        )
        val unchanged = state.reduce(
            HermesChatEvent.ToolStart(
                sessionId = runtime,
                toolId = "todo-tool",
                name = "todo",
                context = null,
                todos = null,
            ),
        )

        assertEquals(state.todos, unchanged.todos)
    }

    @Test
    fun chatSessionSnapshotDefaultsToEmptyRunState() {
        assertEquals(RunEventState(), ChatSessionSnapshot().runState)
    }

    @Test
    fun completionWithoutStartCreatesCompletedRowAndStaleStartCannotRegressIt() {
        val completed = RunEventState().reduce(
            HermesChatEvent.ToolComplete(runtime, "tool-1", "terminal", "done"),
        )

        val afterStaleStart = completed.reduce(
            HermesChatEvent.ToolStart(runtime, "tool-1", "terminal", "stale"),
        )

        assertEquals(1, afterStaleStart.tools.size)
        assertEquals(RunToolState.Completed, afterStaleStart.tools.single().state)
        assertEquals("done", afterStaleStart.tools.single().summary)
    }

    @Test
    fun clarificationRequestIsPendingAndOnlyMatchingExpiryChangesIt() {
        val pending = RunEventState().reduce(
            HermesChatEvent.ClarifyRequest(
                runtime,
                "clarify-1",
                "Choose a mode",
                listOf("fast", "safe"),
                multiSelect = true,
            ),
        )

        assertEquals(runtime, pending.clarification?.runtimeSessionId)
        assertEquals("clarify-1", pending.clarification?.requestId)
        assertEquals("Choose a mode", pending.clarification?.question)
        assertEquals(listOf("fast", "safe"), pending.clarification?.choices)
        assertEquals(true, pending.clarification?.multiSelect)
        assertEquals(RunInteractionLifecycle.Pending, pending.clarification?.lifecycle)

        val afterStaleExpiry = pending.reduce(
            HermesChatEvent.ClarifyExpire(runtime, "clarify-stale"),
        )
        assertSame(pending.clarification, afterStaleExpiry.clarification)

        val expired = afterStaleExpiry.reduce(
            HermesChatEvent.ClarifyExpire(runtime, "clarify-1"),
        )
        assertEquals(RunInteractionLifecycle.Expired, expired.clarification?.lifecycle)
    }

    @Test
    fun clarificationExpiryForAnotherRuntimeDoesNotChangeCurrentRequest() {
        val pending = RunEventState().reduce(
            HermesChatEvent.ClarifyRequest(runtime, "clarify-1", "Question", listOf("yes"), false),
        )

        val afterStaleExpiry = pending.reduce(
            HermesChatEvent.ClarifyExpire(RuntimeSessionId("runtime-2"), "clarify-1"),
        )

        assertSame(pending.clarification, afterStaleExpiry.clarification)
        assertEquals(RunInteractionLifecycle.Pending, afterStaleExpiry.clarification?.lifecycle)
    }

    @Test
    fun approvalRequestReplacesCurrentApprovalAndIdentifiedExpiryIsScoped() {
        val first = RunEventState().reduce(
            HermesChatEvent.ApprovalRequest(
                runtime,
                requestId = null,
                command = "redacted command",
                description = "Allow the command?",
                choices = listOf("once", "deny"),
            ),
        )
        val pending = first.reduce(
            HermesChatEvent.ApprovalRequest(
                runtime,
                requestId = "approval-1",
                command = "next command",
                description = "Allow the next command?",
                choices = listOf("allow", "deny"),
            ),
        )

        assertEquals("approval-1", pending.approval?.requestId)
        assertEquals("next command", pending.approval?.commandPreview)
        assertEquals("Allow the next command?", pending.approval?.descriptionPreview)
        assertEquals(listOf("allow", "deny"), pending.approval?.choices)
        assertEquals(RunInteractionLifecycle.Pending, pending.approval?.lifecycle)

        val afterStaleExpiry = pending.reduce(
            HermesChatEvent.ApprovalExpire(runtime, "approval-stale"),
        )
        assertSame(pending.approval, afterStaleExpiry.approval)

        val expired = afterStaleExpiry.reduce(
            HermesChatEvent.ApprovalExpire(runtime, "approval-1"),
        )
        assertEquals(RunInteractionLifecycle.Expired, expired.approval?.lifecycle)
    }

    @Test
    fun approvalWithoutRequestIdRemainsPendingWhenAnExpiryIsAdvertised() {
        val pending = RunEventState().reduce(
            HermesChatEvent.ApprovalRequest(
                runtime,
                requestId = null,
                command = "redacted command",
                description = "Allow it?",
                choices = listOf("once"),
            ),
        )

        val afterExpiry = pending.reduce(HermesChatEvent.ApprovalExpire(runtime, "approval-1"))

        assertSame(pending.approval, afterExpiry.approval)
        assertEquals(RunInteractionLifecycle.Pending, afterExpiry.approval?.lifecycle)
    }

    @Test
    fun unsupportedBlockingRequestStoresSafePromptAndMatchingExpiry() {
        val pending = RunEventState().reduce(
            HermesChatEvent.UnsupportedBlockingRequest(
                runtime,
                com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind.Secret,
                "secret-1",
                "Enter a value",
            ),
        )

        assertEquals(runtime, pending.unsupportedBlocking?.runtimeSessionId)
        assertEquals(
            com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind.Secret,
            pending.unsupportedBlocking?.kind,
        )
        assertEquals("secret-1", pending.unsupportedBlocking?.requestId)
        assertEquals("Enter a value", pending.unsupportedBlocking?.prompt)
        assertEquals(RunInteractionLifecycle.Pending, pending.unsupportedBlocking?.lifecycle)

        val afterStaleExpiry = pending.reduce(
            HermesChatEvent.UnsupportedBlockingExpire(runtime, com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind.Secret, "secret-stale"),
        )
        assertSame(pending.unsupportedBlocking, afterStaleExpiry.unsupportedBlocking)

        val expired = afterStaleExpiry.reduce(
            HermesChatEvent.UnsupportedBlockingExpire(runtime, com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind.Secret, "secret-1"),
        )
        assertEquals(RunInteractionLifecycle.Expired, expired.unsupportedBlocking?.lifecycle)
    }

    @Test
    fun clarificationLifecycleCanProgressWithoutAllowingStaleUpdates() {
        val pending = RunEventState().reduce(
            HermesChatEvent.ClarifyRequest(runtime, "clarify-1", "Question", listOf("yes"), false),
        )

        val responding = pending.transitionClarificationLifecycle(
            requestId = "clarify-1",
            lifecycle = RunInteractionLifecycle.Responding,
        )
        val resolved = responding.transitionClarificationLifecycle(
            requestId = "clarify-1",
            lifecycle = RunInteractionLifecycle.Resolved,
        )
        val afterStaleUpdate = resolved.transitionClarificationLifecycle(
            requestId = "clarify-stale",
            lifecycle = RunInteractionLifecycle.Failed,
        )

        assertEquals(RunInteractionLifecycle.Responding, responding.clarification?.lifecycle)
        assertEquals(RunInteractionLifecycle.Resolved, resolved.clarification?.lifecycle)
        assertSame(resolved.clarification, afterStaleUpdate.clarification)
    }

    @Test
    fun statusUpdateReplacesTheCurrentStatusRow() {
        val first = RunEventState().reduce(
            HermesChatEvent.StatusUpdate(runtime, "working", "Doing work"),
        )
        val second = first.reduce(
            HermesChatEvent.StatusUpdate(runtime, "waiting", "Waiting for input"),
        )

        assertEquals(RunStatus("waiting", "Waiting for input"), second.status)
    }

    @Test
    fun messageAndErrorEventsLeaveRunStateUnchanged() {
        val state = RunEventState(
            status = RunStatus("working", "Doing work"),
        )

        assertSame(state, state.reduce(HermesChatEvent.MessageDelta(runtime, "partial")))
        assertSame(state, state.reduce(HermesChatEvent.Error(runtime, "failure")))
    }

    @Test
    fun toolGeneratingSurfacesTransientStatusPill() {
        val state = RunEventState().reduce(
            HermesChatEvent.ToolGenerating(runtime, "terminal"),
        )

        assertEquals(
            RunStatus("tool.generating", "Generating terminal arguments…"),
            state.status,
        )

        val started = state.reduce(
            HermesChatEvent.ToolStart(runtime, "tool-1", "terminal", "pwd"),
        )
        assertEquals(null, started.status)

        val terminal = state.reduce(
            HermesChatEvent.MessageComplete(runtime, "done", "complete"),
        )
        assertEquals(null, terminal.status)
    }

    @Test
    fun batchClarificationPresentsOneQuestionAtATimeUntilAllAreAnswered() {
        val questions = listOf(
            com.unsupportedpastels.mercury.core.transcript.ClarifyQuestion("q0", "Target?", listOf("staging", "prod"), false),
            com.unsupportedpastels.mercury.core.transcript.ClarifyQuestion("q1", "Regions?", listOf("eu", "us"), true),
        )
        val state = RunEventState().reduce(
            HermesChatEvent.ClarifyRequest(RuntimeSessionId("rt"), "rid", "Target?", listOf("staging", "prod"), false, questions),
        )
        val clarification = checkNotNull(state.clarification)
        assertEquals(true, clarification.isBatch)
        assertEquals("q0", clarification.currentQuestion?.qid)
        assertEquals("Target?", clarification.displayQuestion)

        val afterFirst = state.markClarificationQuestionAnswered("rid", "q0")
        val remaining = checkNotNull(afterFirst.clarification)
        assertEquals("q1", remaining.currentQuestion?.qid)
        assertEquals(listOf("eu", "us"), remaining.displayChoices)
        assertEquals(true, remaining.displayMultiSelect)
        assertEquals(RunInteractionLifecycle.Pending, remaining.lifecycle)
        assertEquals(null, afterFirst.markClarificationQuestionAnswered("rid", "q1").clarification?.currentQuestion)
    }
}
