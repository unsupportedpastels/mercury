package com.unsupportedpastels.hermesandroid.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.LinkAnnotation
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.app.ComposerAttachment
import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.DelegatedSubagent
import com.unsupportedpastels.hermesandroid.app.DelegationStatus
import com.unsupportedpastels.hermesandroid.app.ProjectId
import com.unsupportedpastels.hermesandroid.app.ProjectLoadState
import com.unsupportedpastels.hermesandroid.app.ApprovalInteraction
import com.unsupportedpastels.hermesandroid.app.ClarificationInteraction
import com.unsupportedpastels.hermesandroid.app.ProjectSessionLoadState
import com.unsupportedpastels.hermesandroid.app.ProjectSummary
import com.unsupportedpastels.hermesandroid.app.RunEventState
import com.unsupportedpastels.hermesandroid.app.RunInteractionLifecycle
import com.unsupportedpastels.hermesandroid.app.RunStatus
import com.unsupportedpastels.hermesandroid.app.RunToolRow
import com.unsupportedpastels.hermesandroid.app.RunToolState
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.UnsupportedBlockingInteraction
import com.unsupportedpastels.hermesandroid.connection.HermesAuthProvider
import com.unsupportedpastels.hermesandroid.connection.ModelPickerState
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.connection.ServerSettingsState
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.voice.MessageReadAloud
import com.unsupportedpastels.hermesandroid.voice.SpeechAudio
import com.unsupportedpastels.hermesandroid.gateway.ChatBillingNotice
import com.unsupportedpastels.hermesandroid.gateway.ChatSessionSnapshot
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryEntry
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.CurrentModelInfo
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.gateway.RecentSessionsState
import com.unsupportedpastels.hermesandroid.gateway.RuntimeSessionId
import com.unsupportedpastels.hermesandroid.gateway.RuntimeAccess
import com.unsupportedpastels.hermesandroid.gateway.ActiveRuntimeSession
import com.unsupportedpastels.hermesandroid.gateway.UnsupportedBlockingKind
import com.unsupportedpastels.hermesandroid.files.HostFileEntry
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme
import com.unsupportedpastels.hermesandroid.navigation.ProjectRoute
import com.unsupportedpastels.hermesandroid.navigation.SessionDetailRoute
import com.unsupportedpastels.hermesandroid.share.SharePayload
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HermesAppTest {
    @Test
    fun sharedContentPickerStagesIntoSelectedSessionWithoutSending() {
        val sessionId = sessions.first().id
        val attachment = ComposerAttachment(
            id = "content://provider/report",
            uri = "content://provider/report",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
        )
        var staged: Pair<DurableSessionId, List<ComposerAttachment>>? = null
        var sent: Pair<DurableSessionId, String>? = null
        var consumed = false

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot,
                    sharePayload = SharePayload(1, "Review this report", listOf(attachment)),
                    onSharePayloadConsumed = { consumed = true },
                    onAddAttachments = { id, values ->
                        staged = id to values
                        emptyList()
                    },
                    onSendMessage = { id, text -> sent = id to text },
                )
            }
        }

        composeRule.onNodeWithText("Send to chat").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Share with First session").performClick()

        assertEquals(sessionId to listOf(attachment), staged)
        assertTrue(consumed)
        assertEquals(null, sent)
        composeRule.onNodeWithText("Review this report").assertIsDisplayed()
    }

    @get:Rule
    val composeRule = createComposeRule()

    private val sessions = listOf(
        SessionSummary(DurableSessionId("stored-1"), "First session"),
        SessionSummary(DurableSessionId("stored-2"), "Second session"),
    )
    private val connectedSnapshot = HermesGatewaySnapshot(
        connectionState = ConnectionState.Connected,
        durableSessions = sessions,
    )

    @Test
    fun sessionFilesStageCanonicalReferenceWithoutArtifactBrowserInAttachmentMenu() {
        val session = sessions.first()
        var loadedPath: String? = "not-called"
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(
                session.id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            ChatMessageRole.Assistant,
                            "Generated output\nMEDIA: /tmp/report.pdf\nMEDIA: /tmp/preview.png\nMEDIA: /tmp/voice.mp3",
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(session.id),
                    onLoadHostFiles = { path ->
                        loadedPath = path
                        Result.success(
                            HostFileListing(
                                path = "/srv",
                                parentPath = null,
                                entries = listOf(
                                    HostFileEntry(
                                        name = "notes.txt",
                                        path = "/srv/notes.txt",
                                        isDirectory = false,
                                        size = 12,
                                        mimeType = "text/plain",
                                    ),
                                ),
                            ),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Attach files").performClick()
        composeRule.onNodeWithText("Host files").performClick()
        composeRule.waitUntil { loadedPath == null }
        composeRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeRule.onNodeWithText("Attach").performClick()
        val stagedDraft = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.InputText),
        ).fetchSemanticsNode().config[SemanticsProperties.InputText].text
        assertEquals("", stagedDraft)
        composeRule.onNodeWithContentDescription("Remove host reference @file:/srv/notes.txt")
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Attach files").performClick()
        composeRule.onNodeWithText("Host files").performClick()
        composeRule.onNodeWithText("notes.txt").assertIsDisplayed()
        composeRule.onNodeWithText("Attach").performClick()
        composeRule.onAllNodesWithContentDescription("Remove host reference @file:/srv/notes.txt")
            .assertCountEquals(1)

        composeRule.onNodeWithContentDescription("Attach files").performClick()
        composeRule.onAllNodesWithText("Artifacts").assertCountEquals(0)
    }

    @Test
    fun consecutiveTranscriptToolMessagesCollapseIntoOneExpandableGroup() {
        val session = sessions.first()
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(
                session.id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(ChatMessageRole.User, "deploy the site"),
                        ChatMessage(ChatMessageRole.Tool, "web_extract · https://example.com/"),
                        ChatMessage(ChatMessageRole.Tool, "patch · /home/mark/site/index.html"),
                        ChatMessage(ChatMessageRole.Tool, "terminal · curl -fsSL https://example.com"),
                        ChatMessage(ChatMessageRole.Assistant, "Deployed successfully."),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(session.id),
                )
            }
        }

        // Collapsed: one group header, no raw per-tool rows visible yet.
        composeRule.onNodeWithContentDescription("3 actions, completed, collapsed")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Tool").assertCountEquals(0)

        // Expand the group; the individual tool rows appear underneath.
        composeRule.onNodeWithContentDescription("3 actions, completed, collapsed").performClick()
        composeRule.onNodeWithContentDescription("3 actions, completed, expanded")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("Tool").assertCountEquals(3)
    }

    @Test
    fun assistantResponsesHaveNoPerMessageReadAloudSpeakerButton() {
        val session = sessions.first()
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(
                session.id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(ChatMessageRole.User, "what device is linked?"),
                        ChatMessage(ChatMessageRole.Assistant, "Your Garmin Venu X1 is linked."),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(session.id),
                    // TTS is fully available, yet the per-message speaker button
                    // must not appear beneath responses.
                    readAloud = MessageReadAloud { Result.success(SpeechAudio(byteArrayOf(1), "audio/mpeg")) },
                )
            }
        }

        composeRule.onNodeWithText("Your Garmin Venu X1 is linked.").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Read message aloud").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Stop reading aloud").assertCountEquals(0)
    }

    @Test
    fun sessionDetailsSummarizeArtifactsAndOpenArtifactBrowser() {
        val session = sessions.first()
        val previewBytes = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(
                session.id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            ChatMessageRole.Assistant,
                            "Generated output\nMEDIA: /tmp/report.pdf\nMEDIA: /tmp/preview.png\nMEDIA: /tmp/voice.mp3",
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(session.id),
                    onLoadManagedImage = { Result.success(previewBytes) },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open session details").performClick()
        composeRule.onAllNodesWithText("Artifacts").assertCountEquals(1)
        composeRule.onAllNodesWithText("3 artifacts").assertCountEquals(1)
        composeRule.onAllNodesWithText("report.pdf").assertCountEquals(1)
        composeRule.onAllNodesWithText("preview.png").assertCountEquals(1)
        composeRule.onAllNodesWithText("voice.mp3").assertCountEquals(1)
        composeRule.onNodeWithText("View all artifacts").performScrollTo().performClick()
        composeRule.onNodeWithTag("Artifact search").assertIsDisplayed()
        composeRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeRule.onAllNodesWithText("Share")[0].performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Save")[0].performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Filter artifacts: Audio").performClick()
        composeRule.onNodeWithText("voice.mp3").assertIsDisplayed()
        composeRule.onNodeWithText("Play").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Filter artifacts: Image").performClick()
        composeRule.onNodeWithContentDescription("Zoom image preview.png").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Close").assertExists()
    }

    @Test
    fun completedSwipeOpensDeleteConfirmationAfterPointerRelease() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = connectedSnapshot)
            }
        }

        composeRule.onNodeWithText("First session").performTouchInput {
            swipeRight(durationMillis = 300)
        }

        composeRule.onNodeWithText("Delete session?").assertIsDisplayed()
    }

    @Test
    fun homeDoesNotExposeMultiSessionSelectionAction() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = connectedSnapshot)
            }
        }

        composeRule.onAllNodesWithContentDescription("Select sessions").assertCountEquals(0)
        composeRule.onAllNodesWithText("Select").assertCountEquals(0)
    }

    @Test
    fun homeShowsOnlyAuthoritativeRunningSubagents() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        delegationStatus = DelegationStatus(
                            active = listOf(
                                DelegatedSubagent(
                                    subagentId = "child-1",
                                    goal = "Inspect lifecycle races",
                                    status = "running",
                                    parentSubagentId = "parent-1",
                                ),
                            ),
                        ),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Running subagents").assertIsDisplayed()
        composeRule.onNodeWithText("Inspect lifecycle races").assertIsDisplayed()
        composeRule.onNodeWithText("running · child of parent-1").assertIsDisplayed()
    }

    @Test
    fun compactComposerKeepsAttachmentInputAndSendInsideOneSurface() {
        val sessionId = sessions.first().id
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                )
            }
        }

        val composerAncestor = hasAnyAncestor(hasTestTag("Message composer"))
        composeRule.onNodeWithContentDescription("Attach files").assert(composerAncestor)
        composeRule.onNode(hasSetTextAction().and(composerAncestor)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Send message").assert(composerAncestor)
    }

    @Test
    fun respondingComposerReplacesSendWithStopInsideSameSurface() {
        val sessionId = sessions.first().id
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        activeRuntimes = listOf(
                            ActiveRuntimeSession(
                                RuntimeSessionId("runtime-1"),
                                sessionId,
                                "First session",
                                RuntimeAccess.Controller,
                            ),
                        ),
                        chatSessions = mapOf(sessionId to ChatSessionSnapshot(isSending = true)),
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                )
            }
        }

        val composerAncestor = hasAnyAncestor(hasTestTag("Message composer"))
        composeRule.onNodeWithContentDescription("Stop Hermes response").assert(composerAncestor)
        composeRule.onNodeWithContentDescription("Send message").assertDoesNotExist()
    }

    @Test
    fun homePullToRefreshInvokesRefreshCallback() {
        var refreshCalls = 0
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                        projectState = ProjectLoadState.Loaded(
                            projects = listOf(
                                ProjectSummary(
                                    ProjectId("project-1"),
                                    "App",
                                    "/workspace/app",
                                    sessionCount = 2,
                                    previewSessions = emptyList(),
                                ),
                            ),
                            activeProjectId = null,
                            scopedSessionIds = emptySet(),
                        ),
                    ),
                    serverSettingsState = ServerSettingsState.Ready(
                        ServerOrigin.parse("https://hermes.example"),
                    ),
                    isHomeRefreshing = false,
                    onRefreshHome = { refreshCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithTag("Home pull to refresh").performTouchInput {
            down(center)
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, 40f), delayMillis = 16) }
            up()
        }

        composeRule.runOnIdle { assertTrue(refreshCalls > 0) }
    }

    @Test
    fun newDraftShowsAdvertisedReasoningLevelWithoutLiveRuntime() {
        val draft = SessionSummary(
            id = DurableSessionId("draft-preview"),
            title = "New chat",
            isLocalDraft = true,
        )
        var selectedReasoning: Pair<DurableSessionId, String>? = null
        var selectedFast: Pair<DurableSessionId, Boolean>? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                        durableSessions = listOf(draft),
                        chatSessions = mapOf(
                            draft.id to ChatSessionSnapshot(
                                model = "openai/gpt-5.6-sol",
                                provider = "openai-codex",
                                modelCapabilities = ModelCapabilities(fast = true, reasoning = true),
                                reasoningEffort = "medium",
                                draftDefaultsLoaded = true,
                            ),
                        ),
                    ),
                    initialRoute = SessionDetailRoute(draft.id),
                    onReasoningSelected = { id, effort -> selectedReasoning = id to effort },
                    onFastSelected = { id, fast -> selectedFast = id to fast },
                )
            }
        }

        composeRule.onNodeWithText("gpt-5.6-sol").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Change reasoning effort").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("high").performClick()
        assertEquals(draft.id to "high", selectedReasoning)
        composeRule.onNodeWithContentDescription("Change fast mode").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Fast").performClick()
        assertEquals(draft.id to true, selectedFast)
    }

    @Test
    fun observedSessionKeepsReportedReasoningLevelVisibleWhenCapabilitiesAreUnavailable() {
        val sessionId = sessions.first().id
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        chatSessions = mapOf(
                            sessionId to ChatSessionSnapshot(
                                model = "openai/gpt-5.6-terra",
                                provider = "openai-codex",
                                reasoningEffort = "high",
                            ),
                        ),
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                )
            }
        }

        composeRule.onNodeWithText("high").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Reported reasoning effort")
            .assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription("Change reasoning effort").assertCountEquals(0)
    }

    @Test
    fun durableSessionWithAdvertisedReasoningShowsEditableReasoningWithoutLiveRuntime() {
        val sessionId = sessions.first().id
        var selectedReasoning: Pair<DurableSessionId, String>? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        chatSessions = mapOf(
                            sessionId to ChatSessionSnapshot(
                                model = "openai/gpt-5.6-sol",
                                provider = "openai-codex",
                                modelCapabilities = ModelCapabilities(reasoning = true),
                                reasoningEffort = "medium",
                            ),
                        ),
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                    onReasoningSelected = { id, effort -> selectedReasoning = id to effort },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Change reasoning effort").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("high").performClick()
        assertEquals(sessionId to "high", selectedReasoning)
    }

    @Test
    fun durableSessionWithAdvertisedFastShowsSpeedometerWithoutLiveRuntime() {
        val sessionId = sessions.first().id
        var selectedFast: Pair<DurableSessionId, Boolean>? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        chatSessions = mapOf(
                            sessionId to ChatSessionSnapshot(
                                model = "openai/gpt-5.6-sol",
                                provider = "openai-codex",
                                modelCapabilities = ModelCapabilities(fast = true, reasoning = true),
                                fastMode = "normal",
                                reasoningEffort = "medium",
                            ),
                        ),
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                    onFastSelected = { id, fast -> selectedFast = id to fast },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Change fast mode").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Fast").performClick()
        assertEquals(sessionId to true, selectedFast)
    }

    @Test
    fun exactModelCommandOpensNativePickerWithoutSendingChatText() {
        val sessionId = sessions.first().id
        var opened: DurableSessionId? = null
        var sent: Pair<DurableSessionId, String>? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                    onOpenModelPicker = { opened = it },
                    onSendMessage = { id, text -> sent = id to text },
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("/model")
        composeRule.onNodeWithContentDescription("Send message").performClick()

        assertEquals(sessionId, opened)
        assertEquals(null, sent)
        val inputText = composeRule.onNode(hasSetTextAction())
            .fetchSemanticsNode().config[SemanticsProperties.InputText].text
        assertTrue(inputText.isEmpty())
    }

    @Test
    fun exactReasoningCommandUpdatesRuntimeWithoutSendingChatText() {
        val sessionId = sessions.first().id
        var selected: Pair<DurableSessionId, String>? = null
        var sent: Pair<DurableSessionId, String>? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                    ),
                    initialRoute = SessionDetailRoute(sessionId),
                    onReasoningSelected = { id, effort -> selected = id to effort },
                    onSendMessage = { id, text -> sent = id to text },
                )
            }
        }

        composeRule.onNode(hasSetTextAction()).performTextInput("/reasoning medium")
        composeRule.onNodeWithContentDescription("Send message").performClick()

        assertEquals(sessionId to "medium", selected)
        assertEquals(null, sent)
        val inputText = composeRule.onNode(hasSetTextAction())
            .fetchSemanticsNode().config[SemanticsProperties.InputText].text
        assertTrue(inputText.isEmpty())
    }

    @Test
    fun chatPageUsesSpeedometerForFastModeAndKeepsModelAndReasoningVisible() {
        val sessionId = sessions.first().id
        var selectedFast: Pair<DurableSessionId, Boolean>? = null
        var snapshot by mutableStateOf(
            connectedSnapshot.copy(
                activeRuntimes = listOf(
                    ActiveRuntimeSession(
                        runtimeSessionId = RuntimeSessionId("runtime-visible"),
                        durableSessionId = sessionId,
                        title = "First session",
                        access = RuntimeAccess.Controller,
                    ),
                ),
                chatSessions = mapOf(
                    sessionId to ChatSessionSnapshot(
                        model = "gpt-5.6-sol",
                        provider = "openai-codex",
                        modelCapabilities = ModelCapabilities(fast = true, reasoning = true),
                        fastMode = "fast",
                        reasoningEffort = "medium",
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(sessionId),
                    onFastSelected = { id, fast -> selectedFast = id to fast },
                )
            }
        }

        composeRule.onNodeWithText("gpt-5.6-sol").assertIsDisplayed()
        composeRule.onNodeWithText("medium").assertIsDisplayed()
        composeRule.onAllNodesWithText("Fast").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Change fast mode")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    "Fast mode enabled",
                ),
            )
            .performClick()
        composeRule.onNodeWithText("Normal").performClick()
        assertEquals(sessionId to false, selectedFast)

        composeRule.runOnIdle {
            snapshot = snapshot.copy(
                chatSessions = mapOf(
                    sessionId to snapshot.chatSessions.getValue(sessionId).copy(
                        messages = listOf(ChatMessage(ChatMessageRole.Assistant, "Done")),
                    ),
                ),
            )
        }

        composeRule.onNodeWithText("gpt-5.6-sol").assertIsDisplayed()
        composeRule.onNodeWithText("medium").assertIsDisplayed()
    }

    @Test
    fun modelPickerFiltersByProviderAndReturnsCanonicalSelection() {
        val sessionId = sessions.first().id
        var selection: ModelSelection? = null
        val state = ModelPickerState.Ready(
            durableSessionId = sessionId,
            options = ModelOptions(
                current = ModelSelection("nous", "current-model"),
                providers = listOf(
                    ModelProviderOption("nous", "Nous Research", listOf("current-model", "luna")),
                    ModelProviderOption(
                        "openrouter",
                        "OpenRouter",
                        listOf("anthropic/claude-sonnet-4.6"),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot,
                    modelPickerState = state,
                    onModelSelected = { selection = it },
                )
            }
        }

        composeRule.onNodeWithText("Choose model").assertIsDisplayed()
        composeRule.onNodeWithText("OpenRouter").performClick()
        composeRule.onNodeWithText("anthropic/claude-sonnet-4.6").performClick()

        assertEquals(ModelSelection("openrouter", "anthropic/claude-sonnet-4.6"), selection)
    }

    @Test
    fun modelPickerErrorOffersRetryAndDoesNotBecomeAnEmptySpinner() {
        var retries = 0
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot,
                    modelPickerState = ModelPickerState.Error(
                        durableSessionId = sessions.first().id,
                        message = "Could not load models",
                    ),
                    onRetryModelPicker = { retries += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Could not load models").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun homeUsesConfiguredServerHostnameAndConnectionContext() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot,
                    serverSettingsState = ServerSettingsState.Ready(
                        ServerOrigin.parse("https://ham.sdhost.cc:8443"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("ham.sdhost.cc").assertIsDisplayed()
        composeRule.onNodeWithText("Hermes").assertDoesNotExist()
        composeRule.onNodeWithText("Agent workspace").assertIsDisplayed()
        composeRule.onNodeWithText("Connected").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search projects and sessions").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hybrid C navigation").assertDoesNotExist()
    }

    @Test
    fun homeFallsBackToHermesIdentityWithoutConfiguredServer() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = connectedSnapshot)
            }
        }

        composeRule.onNodeWithText("Hermes").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w820dp-h700dp")
    fun unfoldedDockOmitsIdentityWhileHomeUsesConfiguredServerHostname() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot,
                    serverSettingsState = ServerSettingsState.Ready(
                        ServerOrigin.parse("https://ham.sdhost.cc"),
                    ),
                )
            }
        }

        composeRule.onAllNodesWithText("ham.sdhost.cc").assertCountEquals(1)
        composeRule.onAllNodesWithText("Hermes").assertCountEquals(0)
    }

    @Test
    fun hybridCHomeUsesTaskAndSettingsHierarchy() {
        val project = ProjectSummary(
            id = ProjectId("project-hybrid-c"),
            label = "Hermes Android",
            primaryPath = "/workspace/hermes-android",
            sessionCount = 1,
            previewSessions = sessions.take(1),
        )
        var taskStarts = 0
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                        projects = listOf(project),
                        projectState = ProjectLoadState.Loaded(listOf(project)),
                    ),
                    onCreateSession = {
                        taskStarts += 1
                        null
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("New task").performClick()
        composeRule.onNodeWithText("New chat").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, taskStarts)
        composeRule.onNodeWithContentDescription("Open Servers settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Servers settings").performClick()
        composeRule.onNodeWithContentDescription("Server origin input").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w400dp-h900dp")
    fun compactHomeKeepsOperationalOverviewInSettingsAndUsesSquareNewTaskFab() {
        var taskStarts = 0
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                    ),
                    onCreateSession = {
                        taskStarts += 1
                        null
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Operational overview").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("New task").assertIsDisplayed().performClick()
        assertEquals(1, taskStarts)
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithContentDescription("Open Connection & profile settings").performClick()
        composeRule.onNodeWithContentDescription("Operational overview").assertIsDisplayed()
    }

    @Test
    fun createProjectBrowsesHostFolderAndOpensServerCreatedProject() {
        val createdProject = ProjectSummary(
            ProjectId("p-created"),
            "Demo",
            "/srv/apps",
            0,
            emptyList(),
        )
        val loadedPaths = mutableListOf<String?>()
        var submitted: Pair<String, String>? = null
        var opened: ProjectId? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                        projectState = ProjectLoadState.Loaded(emptyList()),
                    ),
                    onLoadHostDirectories = { path ->
                        loadedPaths += path
                        Result.success(
                            if (path == null) {
                                HostDirectoryListing(
                                    path = "/srv",
                                    directories = listOf(HostDirectoryEntry("apps", "/srv/apps")),
                                )
                            } else {
                                HostDirectoryListing(path = path, directories = emptyList())
                            },
                        )
                    },
                    onCreateProject = { name, path ->
                        submitted = name to path
                        Result.success(createdProject)
                    },
                    onOpenProject = { opened = it },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Create project").performClick()
        composeRule.waitUntil { loadedPaths == listOf(null) }
        val appsAction = composeRule.onNodeWithText("apps")
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
        composeRule.runOnIdle { appsAction.action?.invoke() }
        composeRule.waitUntil { loadedPaths == listOf(null, "/srv/apps") }
        composeRule.onNodeWithTag("Project name input").performTextInput("Demo")
        val createAction = composeRule.onNodeWithTag("Confirm create project")
            .assertIsEnabled()
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
        composeRule.runOnIdle { createAction.action?.invoke() }
        composeRule.waitUntil { submitted != null }

        assertEquals("Demo" to "/srv/apps", submitted)
        assertEquals(ProjectId("p-created"), opened)
        composeRule.onNodeWithTag("Create project sheet").assertDoesNotExist()
    }

    @Test
    fun createProjectCanCreateAndEnterNewHostFolder() {
        var initialLoaded = false
        var folderRequest: Pair<String, String>? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                        projectState = ProjectLoadState.Loaded(emptyList()),
                    ),
                    onLoadHostDirectories = {
                        initialLoaded = true
                        Result.success(HostDirectoryListing("/srv", emptyList()))
                    },
                    onCreateHostDirectory = { parent, name ->
                        folderRequest = parent to name
                        Result.success(HostDirectoryListing("/srv/$name", emptyList()))
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Create project").performClick()
        composeRule.waitUntil { initialLoaded }
        val toggleFolderAction = composeRule.onNodeWithTag("Toggle create host folder")
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
        composeRule.runOnIdle { toggleFolderAction.action?.invoke() }
        composeRule.onNodeWithTag("New folder name input").performTextInput("Demo")
        val createFolderAction = composeRule.onNodeWithTag("Confirm create host folder")
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
        composeRule.runOnIdle { createFolderAction.action?.invoke() }
        composeRule.waitUntil { folderRequest != null }

        assertEquals("/srv" to "Demo", folderRequest)
        composeRule.onNodeWithTag("Project name input")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.InputText,
                    androidx.compose.ui.text.AnnotatedString("Demo"),
                ),
            )
        composeRule.onNodeWithTag("Host folder input")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.InputText,
                    androidx.compose.ui.text.AnnotatedString("/srv/Demo"),
                ),
            )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w884dp-h707dp-land")
    fun createProjectFooterRemainsVisibleWithScrollableHostFoldersOnFoldLandscape() {
        val listing = HostDirectoryListing(
            path = "/home/mark",
            directories = (1..12).map { index ->
                HostDirectoryEntry("folder-$index", "/home/mark/folder-$index")
            },
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                        projectState = ProjectLoadState.Loaded(emptyList()),
                    ),
                    initialProjectCreatorOpen = true,
                    initialProjectCreatorListing = listing,
                )
            }
        }

        composeRule.onNodeWithTag("Host directory list").assertIsDisplayed()
        composeRule.onNodeWithTag("Toggle create host folder").assertIsDisplayed()
        composeRule.onNodeWithTag("Confirm create project").assertIsDisplayed()
    }

    @Test
    fun openProjectSearchIsHostedByAnOpaqueSurface() {
        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = connectedSnapshot) }
        }

        composeRule.onNodeWithContentDescription("Search projects and sessions").performClick()

        composeRule.onNode(
            hasSetTextAction().and(hasAnyAncestor(hasTestTag("Opaque project search"))),
        ).assertIsDisplayed()
    }

    @Test
    fun hybridCHomeSearchFiltersProjectAndSessionNavigation() {
        val androidProject = ProjectSummary(
            ProjectId("project-android"),
            "Hermes Android",
            "/workspace/android",
            1,
            emptyList(),
        )
        val docsProject = ProjectSummary(
            ProjectId("project-docs"),
            "Hermes Docs",
            "/workspace/docs",
            0,
            emptyList(),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        projects = listOf(androidProject, docsProject),
                        projectState = ProjectLoadState.Loaded(listOf(androidProject, docsProject)),
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Search projects and sessions").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Docs")

        composeRule.onNodeWithText("Hermes Docs").assertIsDisplayed()
        composeRule.onNodeWithText("Hermes Android").assertDoesNotExist()
        composeRule.onNodeWithText("First session").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Close search").performClick()
        composeRule.onNodeWithText("Hermes Android").assertIsDisplayed()
    }

    @Test
    fun longTranscriptAutoScrollsToNewestMessage() {
        val history = (1..30).map { index ->
            ChatMessage(ChatMessageRole.User, "old message $index")
        } + ChatMessage(ChatMessageRole.Assistant, "newest streamed answer")
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessions.first().id to ChatSessionSnapshot(messages = history),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("newest streamed answer").assertIsDisplayed()
    }

    @Test
    fun projectDrillInShowsWorkspaceCountStateAndGroundedActions() {
        val projectId = ProjectId("project-1")
        val openSession = SessionSummary(
            DurableSessionId("open-session"),
            "Open session",
            projectId = projectId,
        )
        val draftSession = SessionSummary(
            DurableSessionId("draft-session"),
            "Draft session",
            projectId = projectId,
            isLocalDraft = true,
        )
        val project = ProjectSummary(projectId, "Project Alpha", null, 2, emptyList())
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(openSession, draftSession),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessions = mapOf(projectId to listOf(openSession, draftSession)),
            projectSessionStates = mapOf(
                projectId to ProjectSessionLoadState.Loaded(listOf(openSession, draftSession)),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithTag("Project home row:Project Alpha").performClick()
        composeRule.onNodeWithContentDescription("Sessions inbox, 2 sessions").assertIsDisplayed()
        composeRule.onNodeWithText("Sessions", substring = false).assertIsDisplayed()
        composeRule.onNodeWithText("Workspace: No workspace").assertIsDisplayed()
        composeRule.onAllNodesWithText("State: Loaded").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Session Open session, No workspace")
            .assertIsDisplayed()
        composeRule.onNodeWithText("New task").assertIsDisplayed()
        composeRule.onAllNodesWithText("Open", substring = false).assertCountEquals(0)
        composeRule.onAllNodesWithText("Resume", substring = false).assertCountEquals(0)
    }

    @Test
    fun inboxSessionRowShowsServeBackedPreviewModelCountProfileAndRecency() {
        val projectId = ProjectId("project-rich-inbox")
        val session = SessionSummary(
            id = DurableSessionId("rich-inbox-session"),
            title = "Revitalize inbox style session sidebar",
            projectId = projectId,
            workspacePath = "/workspace/hermes-agent",
            preview = "I think I might have some worktree or draft problems",
            lastActiveEpochSeconds = 1_700_000_000.0,
            messageCount = 679,
            model = "Fable 5",
            provider = "nous",
            profile = "hermes-agent",
        )
        val project = ProjectSummary(
            projectId,
            "Hermes Agent",
            "/workspace/hermes-agent",
            1,
            listOf(session),
        )
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(session),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessions = mapOf(projectId to listOf(session)),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loaded(listOf(session))),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithTag("Project home row:Hermes Agent").performClick()
        composeRule.onNodeWithText("hermes-agent").assertIsDisplayed()
        composeRule.onNodeWithText("Revitalize inbox style session sidebar").assertIsDisplayed()
        composeRule.onNodeWithText("I think I might have some worktree or draft problems")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Fable 5 · 679 messages").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Last active time available").assertIsDisplayed()
    }

    @Test
    fun projectSessionStatusShowsWorkingThenUnreadCompletionUntilSessionIsOpened() {
        val projectId = ProjectId("project-status")
        val session = SessionSummary(
            DurableSessionId("status-session"),
            "Status session",
            projectId = projectId,
            workspacePath = "/workspace/status",
        )
        val project = ProjectSummary(projectId, "Status project", "/workspace/status", 1, emptyList())
        var snapshot by mutableStateOf(
            connectedSnapshot.copy(
                durableSessions = listOf(session),
                projects = listOf(project),
                projectState = ProjectLoadState.Loaded(listOf(project)),
                projectSessions = mapOf(projectId to listOf(session)),
                projectSessionStates = mapOf(
                    projectId to ProjectSessionLoadState.Loaded(listOf(session)),
                ),
                chatSessions = mapOf(session.id to ChatSessionSnapshot(isSending = true)),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithTag("Project home row:Status project").performClick()
        composeRule.onNodeWithContentDescription("Status session is running").assertIsDisplayed()

        composeRule.runOnIdle {
            snapshot = snapshot.copy(
                chatSessions = mapOf(session.id to ChatSessionSnapshot(isSending = false)),
            )
        }
        composeRule.onNodeWithContentDescription("Status session completed; unread")
            .assertIsDisplayed()

        composeRule.onNodeWithText("Status session").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onAllNodesWithContentDescription("Status session completed; unread")
            .assertCountEquals(0)
    }

    @Test
    fun runningProjectSessionIndicatorPulses() {
        val projectId = ProjectId("project-pulse")
        val session = SessionSummary(
            DurableSessionId("pulse-session"),
            "Pulse session",
            projectId = projectId,
            workspacePath = "/workspace/pulse",
        )
        val project = ProjectSummary(projectId, "Pulse project", "/workspace/pulse", 1, emptyList())
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(session),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessions = mapOf(projectId to listOf(session)),
            projectSessionStates = mapOf(
                projectId to ProjectSessionLoadState.Loaded(listOf(session)),
            ),
            chatSessions = mapOf(session.id to ChatSessionSnapshot(isSending = true)),
        )
        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithTag("Project home row:Pulse project").performClick()
        composeRule.onNodeWithContentDescription("Pulse session is running").assertIsDisplayed()

        val startAlpha = sessionStatusPulseAlphaAt(playTimeMillis = 0)
        val midPulseAlpha = sessionStatusPulseAlphaAt(playTimeMillis = 450)
        val completedCycleAlpha = sessionStatusPulseAlphaAt(playTimeMillis = 1_800)

        assertTrue("Expected amber indicator alpha to fade during the pulse", midPulseAlpha < startAlpha)
        assertEquals(startAlpha, completedCycleAlpha, 0.001f)
    }

    @Test
    fun backgroundRunningProjectSessionStaysAmberWhileAnotherSessionIsOpen() {
        val projectId = ProjectId("project-background-running")
        val running = SessionSummary(
            DurableSessionId("running-background"),
            "Running background",
            projectId = projectId,
            workspacePath = "/workspace/background",
        )
        val viewed = SessionSummary(
            DurableSessionId("viewed-background"),
            "Viewed session",
            projectId = projectId,
            workspacePath = "/workspace/background",
        )
        val project = ProjectSummary(
            projectId,
            "Background project",
            "/workspace/background",
            2,
            emptyList(),
        )
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(running, viewed),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessions = mapOf(projectId to listOf(running, viewed)),
            projectSessionStates = mapOf(
                projectId to ProjectSessionLoadState.Loaded(listOf(running, viewed)),
            ),
            activeRuntimes = listOf(
                ActiveRuntimeSession(
                    RuntimeSessionId("runtime-background"),
                    running.id,
                    running.title,
                    RuntimeAccess.Controller,
                ),
            ),
            chatSessions = mapOf(running.id to ChatSessionSnapshot(isSending = true)),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot, initialRoute = ProjectRoute(projectId))
            }
        }

        composeRule.onNodeWithContentDescription("Running background is running").assertIsDisplayed()
        composeRule.onNodeWithText("Viewed session").performClick()
        composeRule.onNodeWithText("Viewed session").assertIsDisplayed()
    }

    @Test
    fun idleAttachedControllerDoesNotMarkProjectSessionAsRunning() {
        val projectId = ProjectId("project-idle-controller")
        val session = SessionSummary(
            DurableSessionId("idle-controller"),
            "Idle controller",
            projectId = projectId,
            workspacePath = "/workspace/idle",
        )
        val project = ProjectSummary(projectId, "Idle project", "/workspace/idle", 1, emptyList())
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(session),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessions = mapOf(projectId to listOf(session)),
            projectSessionStates = mapOf(
                projectId to ProjectSessionLoadState.Loaded(listOf(session)),
            ),
            activeRuntimes = listOf(
                ActiveRuntimeSession(
                    RuntimeSessionId("runtime-idle"),
                    session.id,
                    session.title,
                    RuntimeAccess.Controller,
                ),
            ),
            chatSessions = mapOf(session.id to ChatSessionSnapshot(isSending = false)),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot, initialRoute = ProjectRoute(projectId))
            }
        }

        composeRule.onAllNodesWithContentDescription("Idle controller is running").assertCountEquals(0)
    }

    @Test
    fun onlyProjectWithRunningSessionExposesSelectedSemantics() {
        val idleProjectId = ProjectId("project-idle")
        val workingProjectId = ProjectId("project-working")
        val idleProject = ProjectSummary(idleProjectId, "Idle project", "/workspace/idle", 0, emptyList())
        val workingProject = ProjectSummary(
            workingProjectId,
            "Working project",
            "/workspace/working",
            1,
            emptyList(),
        )
        val workingSession = SessionSummary(
            DurableSessionId("working-session"),
            "Running task",
            projectId = workingProjectId,
        )
        val snapshot = connectedSnapshot.copy(
            durableSessions = sessions + workingSession,
            projects = listOf(idleProject, workingProject),
            projectState = ProjectLoadState.Loaded(
                projects = listOf(idleProject, workingProject),
                activeProjectId = idleProjectId,
            ),
            activeProjectId = idleProjectId,
            chatSessions = mapOf(
                workingSession.id to ChatSessionSnapshot(isSending = true),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        assertFalse(
            composeRule.onNodeWithText("Idle project")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Selected],
        )
        assertTrue(
            composeRule.onNodeWithText("Working project")
                .fetchSemanticsNode()
                .config[SemanticsProperties.Selected],
        )
        composeRule.onNodeWithContentDescription(
            "Project Working project, active session running, 1 session",
        ).assertIsDisplayed()
    }

    @Test
    fun hybridCProjectRowSummarizesCountAndLatestSession() {
        val projectId = ProjectId("project-hybrid-summary")
        val latest = SessionSummary(DurableSessionId("latest"), "OAuth callback repair")
        val project = ProjectSummary(
            projectId,
            "Hermes Android",
            "/workspace/hermes-android",
            3,
            listOf(latest),
        )
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(
                projects = listOf(project),
                activeProjectId = projectId,
            ),
            activeProjectId = projectId,
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription(
            "Project Hermes Android, 3 sessions, latest OAuth callback repair",
        ).assertIsDisplayed()
    }

    @Test
    fun runActivityExposesSemanticStateDescriptionsAndStopDescription() {
        val sessionId = sessions.first().id
        val snapshot = connectedSnapshot.copy(
            activeRuntimes = listOf(
                ActiveRuntimeSession(
                    RuntimeSessionId("runtime-1"),
                    sessionId,
                    "First session",
                    RuntimeAccess.Controller,
                ),
            ),
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    isSending = true,
                    runState = RunEventState(
                        status = RunStatus(kind = "working", text = "Gathering context"),
                        tools = listOf(
                            RunToolRow(
                                toolId = "tool-running",
                                name = "read_file",
                                context = "src/main.kt",
                                state = RunToolState.Running,
                            ),
                            RunToolRow(
                                toolId = "tool-complete",
                                name = "shell",
                                summary = "Listed project files",
                                state = RunToolState.Completed,
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Current status: working — Gathering context")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2 actions, running, collapsed").performClick()
        composeRule.onNodeWithContentDescription("Running tool read_file: src/main.kt")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Completed tool shell: Listed project files")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stop Hermes response").assertIsDisplayed()
    }

    @Test
    fun pendingApprovalUsesCautionSemantics() {
        val sessionId = sessions.first().id
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    runState = RunEventState(
                        approval = ApprovalInteraction(
                            runtimeSessionId = RuntimeSessionId("runtime-1"),
                            requestId = "approval-1",
                            commandPreview = null,
                            descriptionPreview = "Allow this action?",
                            choices = listOf("once"),
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Approval pending").assertIsDisplayed()
    }

    @Test
    fun selectedWorkspaceRendersTypedRunStateWithoutRawToolFields() {
        val sessionId = sessions.first().id
        val runtimeId = RuntimeSessionId("runtime-1")
        val runState = RunEventState(
            status = RunStatus(kind = "working", text = "Gathering context"),
            tools = listOf(
                RunToolRow(
                    toolId = "tool-running",
                    name = "read_file",
                    context = "src/main.kt",
                    state = RunToolState.Running,
                ),
                RunToolRow(
                    toolId = "tool-complete",
                    name = "shell",
                    summary = "Listed project files",
                    state = RunToolState.Completed,
                ),
            ),
        )
        val snapshot = connectedSnapshot.copy(
            activeRuntimes = listOf(
                ActiveRuntimeSession(runtimeId, sessionId, "First session", RuntimeAccess.Controller),
            ),
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    isSending = true,
                    runState = runState,
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("working").assertIsDisplayed()
        composeRule.onNodeWithText("Gathering context").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("2 actions, running, collapsed").performClick()
        composeRule.onNodeWithText("read_file").assertIsDisplayed()
        composeRule.onNodeWithText("src/main.kt").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Running").assertIsDisplayed()
        composeRule.onNodeWithText("shell").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Listed project files").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Completed").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("arguments").assertCountEquals(0)
        composeRule.onAllNodesWithText("results").assertCountEquals(0)
        composeRule.onAllNodesWithText("duration").assertCountEquals(0)
        composeRule.onAllNodesWithText("tokens").assertCountEquals(0)
        composeRule.onAllNodesWithText("diff").assertCountEquals(0)
        composeRule.onAllNodesWithText("terminal").assertCountEquals(0)
    }

    @Test
    fun completedToolHistoryIsCollapsedUntilTheUserExpandsIt() {
        val sessionId = sessions.first().id
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            role = ChatMessageRole.Assistant,
                            text = "The answer remains the primary content.",
                        ),
                    ),
                    runState = RunEventState(
                        tools = listOf(
                            RunToolRow(
                                toolId = "tool-search",
                                name = "search_files",
                                summary = "Found the relevant plan section",
                                state = RunToolState.Completed,
                            ),
                            RunToolRow(
                                toolId = "tool-read",
                                name = "read_file",
                                summary = "Read the tracked backlog",
                                state = RunToolState.Completed,
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(sessionId),
                )
            }
        }

        composeRule.onNodeWithContentDescription("2 actions, completed, collapsed")
            .assertIsDisplayed()
        composeRule.onAllNodesWithText("search_files").assertCountEquals(0)
        composeRule.onAllNodesWithText("read_file").assertCountEquals(0)

        composeRule.onNodeWithContentDescription("2 actions, completed, collapsed").performClick()

        composeRule.onNodeWithContentDescription("2 actions, completed, expanded")
            .assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Completed tool search_files: Found the relevant plan section",
        ).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(
            "Completed tool read_file: Read the tracked backlog",
        ).assertIsDisplayed()
    }

    @Test
    fun completedToolSummaryAppearsAfterTranscriptContent() {
        val sessionId = sessions.first().id
        val transcriptText = "The answer remains above completed background activity."
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            role = ChatMessageRole.Assistant,
                            text = transcriptText,
                        ),
                    ),
                    runState = RunEventState(
                        tools = listOf(
                            RunToolRow(
                                toolId = "tool-read",
                                name = "read_file",
                                state = RunToolState.Completed,
                            ),
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(sessionId),
                )
            }
        }

        val transcriptTop = composeRule.onNodeWithText(transcriptText)
            .fetchSemanticsNode()
            .boundsInRoot
            .top
        val completedSummaryTop = composeRule.onNodeWithContentDescription("1 action, completed, collapsed")
            .fetchSemanticsNode()
            .boundsInRoot
            .top

        assertTrue(transcriptTop < completedSummaryTop)
        composeRule.onNodeWithContentDescription("1 action, completed, collapsed")
            .assert(hasAnyAncestor(hasTestTag("Session timeline")))
    }

    @Test
    fun completedToolsReturnCollapsedAfterStateRestoration() {
        val sessionId = sessions.first().id
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    messages = listOf(ChatMessage(ChatMessageRole.Assistant, "Done")),
                    runState = RunEventState(
                        tools = listOf(
                            RunToolRow(
                                toolId = "tool-read",
                                name = "read_file",
                                summary = "Read the file",
                                state = RunToolState.Completed,
                            ),
                        ),
                    ),
                ),
            ),
        )
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot, initialRoute = SessionDetailRoute(sessionId))
            }
        }

        composeRule.onNodeWithContentDescription("1 action, completed, collapsed").performClick()
        composeRule.onNodeWithContentDescription("Completed tool read_file: Read the file")
            .assertIsDisplayed()

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithContentDescription("1 action, completed, collapsed").assertIsDisplayed()
        composeRule.onAllNodesWithText("Read the file").assertCountEquals(0)
    }

    @Test
    fun pendingClarificationChoiceUsesExactCallbackAndTerminalStateHasNoControls() {
        val sessionId = sessions.first().id
        val requestId = "clarify-1"
        var response: Triple<DurableSessionId, String, String>? = null
        val pending = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    runState = RunEventState(
                        clarification = ClarificationInteraction(
                            runtimeSessionId = RuntimeSessionId("runtime-1"),
                            requestId = requestId,
                            question = "Which environment should I use?",
                            choices = listOf("Staging", "Production"),
                            multiSelect = false,
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = pending,
                    onClarificationResponse = { id, request, answer ->
                        response = Triple(id, request, answer)
                    },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        // The card renders at the bottom-pinned tail of the transcript; a tall
        // clarify card can push the question above the fold, so assert it exists
        // while the interactive controls (choices + Continue) stay on screen.
        composeRule.onNodeWithText("Which environment should I use?").assertExists()
        // Desktop parity: picking a choice stages it; the user confirms with Continue.
        composeRule.onNodeWithText("Production").performClick()
        composeRule.onNodeWithText("Continue").performClick()
        assertEquals(Triple(sessionId, requestId, "Production"), response)
    }

    @Test
    fun clarificationSkipSendsEmptyAnswer() {
        val sessionId = sessions.first().id
        val requestId = "clarify-skip"
        var response: Triple<DurableSessionId, String, String>? = null
        val pending = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    runState = RunEventState(
                        clarification = ClarificationInteraction(
                            runtimeSessionId = RuntimeSessionId("runtime-1"),
                            requestId = requestId,
                            question = "Which environment should I use?",
                            choices = listOf("Staging", "Production"),
                            multiSelect = false,
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = pending,
                    onClarificationResponse = { id, request, answer ->
                        response = Triple(id, request, answer)
                    },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Skip").performClick()
        assertEquals(Triple(sessionId, requestId, ""), response)
    }

    @Test
    fun expiredClarificationShowsTimedOutAndHasNoChoiceControls() {
        val sessionId = sessions.first().id
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    runState = RunEventState(
                        clarification = ClarificationInteraction(
                            runtimeSessionId = RuntimeSessionId("runtime-1"),
                            requestId = "clarify-1",
                            question = "Which environment should I use?",
                            choices = listOf("Staging", "Production"),
                            multiSelect = false,
                            lifecycle = RunInteractionLifecycle.Expired,
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }
        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Which environment should I use?").assertExists()
        // A settled (timed-out) card drops all interactive controls.
        composeRule.onNodeWithText("Timed out").assertExists()
        composeRule.onAllNodesWithText("Continue").assertCountEquals(0)
        composeRule.onAllNodesWithText("Skip").assertCountEquals(0)
    }

    @Test
    fun multiSelectClarificationSendsAdvertisedChoicesInServerOrder() {
        val sessionId = sessions.first().id
        var response: Triple<DurableSessionId, String, String>? = null
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    runState = RunEventState(
                        clarification = ClarificationInteraction(
                            runtimeSessionId = RuntimeSessionId("runtime-1"),
                            requestId = "clarify-multi",
                            question = "Choose checks",
                            choices = listOf("Beta", "Alpha", "Gamma"),
                            multiSelect = true,
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onClarificationResponse = { id, request, answer ->
                        response = Triple(id, request, answer)
                    },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Gamma").performClick()
        composeRule.onNodeWithText("Beta").performClick()
        composeRule.onNodeWithText("Continue").performClick()

        assertEquals(Triple(sessionId, "clarify-multi", "Beta, Gamma"), response)
    }

    @Test
    fun pendingApprovalSendsOnlyTheAdvertisedChoice() {
        val sessionId = sessions.first().id
        var response: Triple<DurableSessionId, String, Boolean>? = null
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    runState = RunEventState(
                        approval = ApprovalInteraction(
                            runtimeSessionId = RuntimeSessionId("runtime-1"),
                            requestId = null,
                            commandPreview = "redacted command",
                            descriptionPreview = "Allow this action?",
                            choices = listOf("once", "deny"),
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onApprovalResponse = { id, choice, all ->
                        response = Triple(id, choice, all)
                    },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Allow this action?").assertIsDisplayed()
        composeRule.onNodeWithText("deny").performClick()

        assertEquals(Triple(sessionId, "deny", false), response)
    }

    @Test
    fun sudoRequestUsesMaskedOneShotInputAndCorrelatesTheExactRequest() {
        val sessionId = sessions.first().id
        var response: List<Any>? = null
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    runState = RunEventState(
                        unsupportedBlocking = UnsupportedBlockingInteraction(
                            runtimeSessionId = RuntimeSessionId("runtime-1"),
                            kind = UnsupportedBlockingKind.Sudo,
                            requestId = "sudo-1",
                            prompt = "Sensitive prompt must not render",
                            lifecycle = RunInteractionLifecycle.Pending,
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onBlockingResponse = { id, kind, requestId, value ->
                        response = listOf(id, kind, requestId, value)
                    },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Sudo password").performTextInput("opaque-password")
        composeRule.onNodeWithText("Send password").performClick()
        assertEquals(
            listOf(sessionId, UnsupportedBlockingKind.Sudo, "sudo-1", "opaque-password"),
            response,
        )
        composeRule.onAllNodesWithText("Sensitive prompt must not render").assertCountEquals(0)
    }

    @Test
    fun stopTargetsSelectedControllerAndStoppingStateDisablesRepeatedAction() {
        val sessionId = sessions.first().id
        val runtimeId = RuntimeSessionId("runtime-1")
        var stopped: DurableSessionId? = null
        var stopping by mutableStateOf(false)

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        activeRuntimes = listOf(
                            ActiveRuntimeSession(runtimeId, sessionId, "First session", RuntimeAccess.Controller),
                        ),
                        chatSessions = mapOf(
                            sessionId to ChatSessionSnapshot(isSending = true, isStopping = stopping),
                        ),
                    ),
                    onStopSession = { stopped = it },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Stop Hermes response").performClick()
        assertEquals(sessionId, stopped)

        stopping = true
        composeRule.onNodeWithContentDescription("Stop Hermes response").assertIsNotEnabled()
    }

    @Test
    fun assistantMarkdownRendersWithoutLiteralFormattingMarkers() {
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessions.first().id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            ChatMessageRole.Assistant,
                            """
                            - **Container:** removed; no `service` remains.
                            ```text
                            example/image:latest
                            ```
                            """.trimIndent(),
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Container: removed; no service remains.").assertIsDisplayed()
        composeRule.onNodeWithText("example/image:latest").assertIsDisplayed()
    }

    @Test
    fun userMarkdownUsesTheSameRichTextRendererAsAssistantMessages() {
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessions.first().id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            ChatMessageRole.User,
                            "- **Status:** open `Settings`.",
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithText("Status: open Settings.").assertIsDisplayed()
    }

    @Test
    fun markdownTableRendersAsAccessibleHorizontallyScrollableGrid() {
        val markdown = """
            | Category | Preferred source |
            |---|---|
            | Body Battery, stress, Garmin recovery | CIRQA |
            | Golf UX and round activity | Apple Watch + 18Birdies |
        """.trimIndent()

        composeRule.setContent {
            HermesAndroidTheme { MarkdownMessage(markdown) }
        }

        val table = composeRule.onNodeWithContentDescription("Markdown table, 2 columns, 2 rows")
            .assertIsDisplayed()
            .fetchSemanticsNode()
        assertTrue(table.config.contains(SemanticsProperties.HorizontalScrollAxisRange))
        composeRule.onNodeWithText("Category").assertIsDisplayed()
        composeRule.onNodeWithText("Apple Watch + 18Birdies").assertIsDisplayed()
        composeRule.onAllNodesWithText("|---|---|").assertCountEquals(0)
    }

    @Test
    fun bareMarkdownUrlExposesClickableLinkAnnotation() {
        val url = "https://example.com/pr/1"

        composeRule.setContent {
            HermesAndroidTheme { MarkdownMessage(url) }
        }

        val annotated = composeRule.onNodeWithText(url)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .single()
        val link = annotated.getLinkAnnotations(0, annotated.length).single().item

        assertEquals(url, (link as LinkAnnotation.Url).url)
    }

    @Test
    fun oversizedMarkdownRevealsOnlyOneBoundedChunkAtATime() {
        val chunkSize = 4_000
        val message =
            "a".repeat(chunkSize) +
                "b".repeat(chunkSize) +
                "c".repeat(32)

        composeRule.setContent {
            HermesAndroidTheme {
                MarkdownMessage(message)
            }
        }

        composeRule.onNodeWithText("aaa", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("bbb", substring = true).assertCountEquals(0)
        composeRule.onNodeWithText("Show more").performClick()
        composeRule.onNodeWithText("bbb", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("ccc", substring = true).assertCountEquals(0)
    }

    @Test
    fun streamingAssistantMessageRendersRawTextNotPartialMarkdown() {
        val sessionId = sessions.first().id
        val partial =
            "```python\nprint('partial markdown')\n**unclosed bold | stray pipe"
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            role = ChatMessageRole.Assistant,
                            text = partial,
                            isStreaming = true,
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(sessionId),
                )
            }
        }

        // Streaming text must render as raw plain text: parsing partial markdown
        // (unclosed code fences, stray bold markers, half-built tables) produces
        // garbled output until message.complete finalizes the text.
        composeRule.onNodeWithTag("Streaming assistant text")
            .assertIsDisplayed()
            .assertTextEquals(partial)
    }

    @Test
    fun completedAssistantMessageRendersFullMarkdown() {
        val sessionId = sessions.first().id
        val markdown =
            "| Category | Preferred source |\n" +
                "|---|---|\n" +
                "| GPU | RTX 5090 |"
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(
                            role = ChatMessageRole.Assistant,
                            text = markdown,
                            isStreaming = false,
                        ),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(sessionId),
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Markdown table, 2 columns, 1 rows")
            .assertIsDisplayed()
        composeRule.onAllNodesWithTag("Streaming assistant text").assertCountEquals(0)
    }

    @Test
    fun reasoningDisclosureStaysOpenWhileStreamingTextChanges() {
        val sessionId = sessions.first().id
        var snapshot by mutableStateOf(
            connectedSnapshot.copy(
                chatSessions = mapOf(
                    sessionId to ChatSessionSnapshot(
                        messages = listOf(
                            ChatMessage(
                                role = ChatMessageRole.Assistant,
                                text = "Partial",
                                isStreaming = true,
                                reasoningText = "Reasoning details",
                            ),
                        ),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot, initialRoute = SessionDetailRoute(sessionId))
            }
        }

        composeRule.onNodeWithContentDescription("Show thinking").performClick()
        composeRule.onNodeWithText("Reasoning details").assertIsDisplayed()
        composeRule.runOnIdle {
            snapshot = snapshot.copy(
                chatSessions = mapOf(
                    sessionId to snapshot.chatSessions.getValue(sessionId).copy(
                        messages = listOf(
                            ChatMessage(
                                role = ChatMessageRole.Assistant,
                                text = "Partial answer grew",
                                isStreaming = true,
                                reasoningText = "Reasoning details grew",
                            ),
                        ),
                    ),
                ),
            )
        }

        composeRule.onNodeWithContentDescription("Hide thinking").assertIsDisplayed()
        composeRule.onNodeWithText("Reasoning details grew").assertIsDisplayed()
    }

    @Test
    fun selectingSessionLoadsTranscriptAndSendsComposerText() {
        var opened: DurableSessionId? = null
        var sent: Pair<DurableSessionId, String>? = null
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            chatSessions = mapOf(
                sessions.first().id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(ChatMessageRole.User, "Earlier question"),
                        ChatMessage(ChatMessageRole.Assistant, "Earlier answer"),
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onOpenSession = { opened = it },
                    onSendMessage = { sessionId, text -> sent = sessionId to text },
                )
            }
        }

        composeRule.onNodeWithText("Agent workspace").assertIsDisplayed()
        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Back").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier question").assertIsDisplayed()
        composeRule.onNodeWithText("Earlier answer").assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(sessions.first().id, opened)
        composeRule.onNode(hasSetTextAction()).performTextInput("New question")
        composeRule.onNodeWithContentDescription("Send message").performClick()
        assertEquals(sessions.first().id to "New question", sent)
    }

    @Test
    fun billingDescriptorRendersDistinctRecoveryCard() {
        val sessionId = sessions.first().id
        val snapshot = connectedSnapshot.copy(
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    messages = listOf(ChatMessage(ChatMessageRole.Assistant, "Usage limit reached.")),
                    billingNotice = ChatBillingNotice(
                        provider = "nous",
                        billingUrl = "https://portal.nousresearch.com/billing",
                        isNous = true,
                        message = "Add credits to continue.",
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot, initialRoute = SessionDetailRoute(sessionId))
            }
        }

        composeRule.onNodeWithContentDescription("Billing action required").assertIsDisplayed()
        composeRule.onNodeWithText("Add credits to continue.").assertIsDisplayed()
        composeRule.onNodeWithText("Provider: nous").assertIsDisplayed()
        composeRule.onNodeWithText("Open Nous billing").assertIsDisplayed()
    }

    @Test
    fun acceptedOptimisticUserMessageClearsTheMatchingComposerDraft() {
        val sessionId = sessions.first().id
        var snapshot by mutableStateOf(
            connectedSnapshot.copy(
                authenticationState = AuthenticationState.Authenticated,
                chatSessions = mapOf(sessionId to ChatSessionSnapshot()),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onSendMessage = { id, text ->
                        snapshot = snapshot.copy(
                            chatSessions = snapshot.chatSessions +
                                (id to ChatSessionSnapshot(
                                    messages = listOf(
                                        ChatMessage(ChatMessageRole.User, text),
                                        ChatMessage(
                                            role = ChatMessageRole.Assistant,
                                            text = "",
                                            isStreaming = true,
                                        ),
                                    ),
                                    isSending = true,
                                )),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Accepted question")
        composeRule.onNodeWithContentDescription("Send message").performClick()

        val inputText = composeRule.onNode(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.InputText),
        )
            .fetchSemanticsNode().config[SemanticsProperties.InputText].text
        assertEquals("", inputText)
    }

    @Test
    fun initialSessionRouteShowsExactWorkspaceAndOpensExactDurableId() {
        val session = SessionSummary(
            id = DurableSessionId("initial-session"),
            title = "Initial workspace",
            projectId = ProjectId("project-1"),
            workspacePath = "/workspace/alpha",
        )
        var opened: DurableSessionId? = null
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(session),
            chatSessions = mapOf(
                session.id to ChatSessionSnapshot(
                    messages = listOf(
                        ChatMessage(ChatMessageRole.Assistant, "The selected workspace is ready."),
                    ),
                ),
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(session.id),
                    onOpenSession = { opened = it },
                )
            }
        }

        composeRule.onNodeWithText("Initial workspace").assertIsDisplayed()
        composeRule.onNodeWithText("The selected workspace is ready.").assertIsDisplayed()
        composeRule.waitForIdle()
        assertEquals(session.id, opened)
        composeRule.onNodeWithContentDescription("Open session details").performClick()
        composeRule.onNodeWithText("Workspace: /workspace/alpha").assertIsDisplayed()
    }

    @Test
    fun selectedAttachmentsRenderAsRemovableInputChips() {
        var removed: Pair<DurableSessionId, String>? = null
        val sessionId = sessions.first().id
        val attachment = ComposerAttachment(
            id = "attachment-1",
            uri = "content://provider/report",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 42,
        )
        val snapshot = connectedSnapshot.copy(authenticationState = AuthenticationState.Authenticated)

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    attachments = mapOf(sessionId to listOf(attachment)),
                    onRemoveAttachment = { id, attachmentId -> removed = id to attachmentId },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Attach files").assertIsDisplayed()
        composeRule.onNodeWithText("report.pdf").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Remove report.pdf").performClick()
        assertEquals(sessionId to "attachment-1", removed)
    }

    @Test
    fun attachmentOnlyDraftCanBeSentAndComposerControlsDisableWhileSending() {
        var sent: Pair<DurableSessionId, String>? = null
        val sessionId = sessions.first().id
        val attachment = ComposerAttachment(
            id = "attachment-1",
            uri = "content://provider/report",
            displayName = "report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 42,
        )
        var isSending by mutableStateOf(false)
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot.copy(
                        chatSessions = mapOf(sessionId to ChatSessionSnapshot(isSending = isSending)),
                    ),
                    attachments = mapOf(sessionId to listOf(attachment)),
                    onSendMessage = { id, text -> sent = id to text },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Send message").performClick()
        assertEquals(sessionId to "", sent)

        composeRule.runOnIdle { isSending = true }
        composeRule.onNodeWithContentDescription("Attach files").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test
    fun failedSubmissionKeepsTypedDraftEditable() {
        val sessionId = sessions.first().id
        var snapshot by mutableStateOf(
            connectedSnapshot.copy(
                authenticationState = AuthenticationState.Authenticated,
                chatSessions = mapOf(sessionId to ChatSessionSnapshot()),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onSendMessage = { _, text ->
                        snapshot = snapshot.copy(
                            chatSessions = mapOf(
                                sessionId to ChatSessionSnapshot(
                                    messages = listOf(ChatMessage(ChatMessageRole.User, text)),
                                    error = "prompt rejected",
                                ),
                            ),
                        )
                    },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Keep this draft")
        composeRule.onNodeWithContentDescription("Send message").performClick()
        composeRule.onNodeWithText("prompt rejected").assertIsDisplayed()
        val inputText = composeRule.onNode(hasSetTextAction())
            .fetchSemanticsNode().config[SemanticsProperties.InputText].text
        assertEquals("Keep this draft", inputText)
    }

    @Test
    fun modelChipPreservesUnsentDraft() {
        val sessionId = sessions.first().id
        var pickerOpened: DurableSessionId? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.Authenticated,
                        chatSessions = mapOf(sessionId to ChatSessionSnapshot()),
                    ),
                    onOpenModelPicker = { pickerOpened = it },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Keep this draft")
        composeRule.onNodeWithContentDescription("Change session model").performClick()

        assertEquals(sessionId, pickerOpened)
        val inputText = composeRule.onNode(hasSetTextAction())
            .fetchSemanticsNode().config[SemanticsProperties.InputText].text
        assertEquals("Keep this draft", inputText)
    }

    @Test
    fun removingLastAttachmentRemovesItsChip() {
        var attachments by mutableStateOf(
            mapOf(
                sessions.first().id to listOf(
                    ComposerAttachment(
                        id = "attachment-1",
                        uri = "content://provider/report",
                        displayName = "report.pdf",
                        mimeType = "application/pdf",
                        sizeBytes = 42,
                    ),
                ),
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(authenticationState = AuthenticationState.Authenticated),
                    attachments = attachments,
                    onRemoveAttachment = { sessionId, _ -> attachments = attachments - sessionId },
                )
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNodeWithContentDescription("Remove report.pdf").performClick()
        composeRule.onNodeWithText("report.pdf").assertDoesNotExist()
    }

    @Test
    fun authenticationFreeSessionDoesNotAdvertiseUnsupportedNativeSend() {
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.NotRequired,
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Question")
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test
    fun composerDraftSurvivesSavedStateRestoration() {
        val restorationTester = StateRestorationTester(composeRule)
        restorationTester.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = connectedSnapshot)
            }
        }

        composeRule.onNodeWithText("First session").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Restored draft")

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("First session").assertIsDisplayed()
        composeRule.onNodeWithText("Restored draft").assertIsDisplayed()
    }

    @Test
    fun connectedServerWithoutDurableSessionsIsNotShownAsUnconfigured() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                    ),
                )
            }
        }

        composeRule.onNodeWithText("No saved sessions").assertIsDisplayed()
    }

    @Test
    fun reachableGatedServerOffersNativeNousSignIn() {
        var signInRequested = false
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Connected,
                        authenticationState = AuthenticationState.SignInRequired,
                        serverVersion = "0.20.0",
                        nativeOAuthSupported = true,
                        authProviders = listOf(HermesAuthProvider("nous", "Nous Research")),
                    ),
                    serverSettingsState = ServerSettingsState.Ready(
                        ServerOrigin.parse("https://hermes.example"),
                    ),
                    onSignIn = { signInRequested = true },
                )
            }
        }

        composeRule.onNodeWithText("Server reachable").assertIsDisplayed()
        composeRule.onNodeWithText("Hermes 0.20.0 · Sign in required").assertIsDisplayed()
        composeRule.onNodeWithText("Sign in with Nous").performClick()

        assertTrue(signInRequested)
    }

    @Test
    fun connectionFailureIsVisibleInsteadOfOnlyShowingSavedOrigin() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(
                        connectionState = ConnectionState.Disconnected,
                        connectionError = "Could not reach Hermes Serve",
                    ),
                    serverSettingsState = ServerSettingsState.Ready(
                        ServerOrigin.parse("https://hermes.example"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Could not reach Hermes Serve").assertIsDisplayed()
    }

    @Test
    fun unconfiguredScreenSavesCanonicalHttpsServerOrigin() {
        var savedOrigin: ServerOrigin? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onSaveServerOrigin = { origin ->
                        savedOrigin = origin
                        Result.success(Unit)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Configure server").performClick()
        composeRule.onNodeWithContentDescription("Open Servers settings").performClick()
        composeRule.onNodeWithText("Server origin").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Server origin input")
            .performTextInput("HTTPS://Example.COM/")
        composeRule.onNodeWithText("Save").performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals("https://example.com", savedOrigin?.value)
    }

    @Test
    fun serverDialogRejectsCleartextOriginForLanServers() {
        var savedOrigin: ServerOrigin? = null
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Ready(null),
                    onSaveServerOrigin = { origin ->
                        savedOrigin = origin
                        Result.success(Unit)
                    },
                )
            }
        }

        composeRule.onNodeWithText("Configure server").performClick()
        composeRule.onNodeWithContentDescription("Open Servers settings").performClick()
        composeRule.onNodeWithContentDescription("Server origin input")
            .performTextInput("http://10.0.1.2")

        composeRule.onNodeWithText("Non-loopback origins require HTTPS", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Save").assertIsNotEnabled()
        composeRule.waitForIdle()

        assertNull(savedOrigin)
    }

    @Test
    fun settingsShowsCurrentProfileContextAndKeepsFutureReasoningDefaultSeparate() {
        val snapshot = HermesGatewaySnapshot(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.Authenticated,
            profiles = listOf("default", "work"),
            selectedProfile = "default",
            defaultModelOptions = ModelOptions(
                current = ModelSelection("nous", "Hermes-4-405B"),
                providers = emptyList(),
                profile = "default",
            ),
            currentModelInfo = CurrentModelInfo(
                profile = "default",
                provider = "nous",
                model = "Hermes-4-405B",
                effectiveContextLength = 131072,
                capabilities = ModelCapabilities(reasoning = true, fast = true),
            ),
            profileReasoningEffort = "medium",
            profileReasoningDefault = "medium",
        )
        composeRule.setContent {
            HermesAndroidTheme {
                ServerSettingsScreen(
                    serverOrigin = ServerOrigin.parse("https://hermes.example"),
                    snapshot = snapshot,
                    showBack = false,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                )
            }
        }

        composeRule.onNodeWithText("Current profile model").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Effective context: 131072 tokens").performScrollTo().assertIsDisplayed()
        // Reasoning is now set per-model inside the picker, not via a separate
        // profile-wide dropdown, so the Change model entry point is what shows.
        composeRule.onNodeWithContentDescription("Change default model").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Reasoning default for future chats").assertCountEquals(0)
    }

    @Test
    fun loadingSettingsCannotBeMistakenForUnconfigured() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Loading,
                )
            }
        }

        composeRule.onNodeWithText("Loading server settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").assertIsNotEnabled()
    }

    @Test
    fun unavailableSettingsCanBeReplaced() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = HermesGatewaySnapshot(),
                    serverSettingsState = ServerSettingsState.Unavailable,
                )
            }
        }

        composeRule.onNodeWithText("Server settings unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Open Server to replace the saved origin.").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithContentDescription("Open Servers settings").performClick()
        composeRule.onNodeWithContentDescription("Server origin input").assertIsDisplayed()
    }

    @Test
    fun settingsHubListsSectionsAndOpensModelSectionForAuthenticatedServer() {
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            currentModelInfo = CurrentModelInfo(
                profile = "default",
                provider = "nous",
                model = "Hermes-4-405B",
                effectiveContextLength = 131072,
                capabilities = ModelCapabilities(reasoning = true, fast = true),
            ),
            defaultModelOptions = ModelOptions(
                current = ModelSelection("nous", "Hermes-4-405B"),
                providers = emptyList(),
                profile = "default",
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription("Settings").performClick()
        // Hub shows a concise list of sections, not the full form.
        composeRule.onNodeWithContentDescription("Open Servers settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Files settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Default model settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Offline & privacy settings")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithContentDescription("Open Default model settings").performClick()
        composeRule.onNodeWithText("Default model for new chats").assertIsDisplayed()
        // Servers form is not on the model section.
        composeRule.onNodeWithContentDescription("Server origin input").assertDoesNotExist()
    }

    @Test
    fun unauthenticatedSettingsHubOnlyOffersServers() {
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = HermesGatewaySnapshot(connectionState = ConnectionState.Connected))
            }
        }

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithContentDescription("Open Servers settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Files settings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open Default model settings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open Account settings").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Open Offline & privacy settings").assertDoesNotExist()
    }

    @Test
    fun settingsHubPlacesFilesAfterServersAndOpensDisabledPreviewToggle() {
        val snapshot = connectedSnapshot.copy(authenticationState = AuthenticationState.Authenticated)
        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithContentDescription("Open Files settings").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open Files settings").performClick()
        // Do NOT assert onNodeWithText("Files"): the section renders "Files" twice
        // (TopAppBar `title = section.title` and the Offline-style body header), so
        // a single-node matcher throws "found 2 nodes". Assert unique copy instead.
        composeRule.onNodeWithText(
            "Files the agent puts in chat open in another app on this phone. In-app preview is coming soon.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("How files from chat open on this phone").assertDoesNotExist()
        composeRule.onNodeWithText("In-app file preview").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("In-app file preview").assertIsNotEnabled()
    }

    @Test
    fun modelPickerGroupsByProviderSearchesAndSetsDefault() {
        var chosen: ModelSelection? = null
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            defaultModelOptions = ModelOptions(
                current = ModelSelection("nous", "Hermes-4-405B"),
                providers = listOf(
                    ModelProviderOption(
                        slug = "nous",
                        name = "Nous",
                        models = listOf("Hermes-4-405B", "Hermes-4-70B"),
                        capabilities = mapOf(
                            "Hermes-4-405B" to ModelCapabilities(reasoning = true),
                        ),
                    ),
                    ModelProviderOption(
                        slug = "openai",
                        name = "OpenAI",
                        models = listOf("gpt-5.6-sol"),
                    ),
                ),
                profile = "default",
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onSetProfileDefaultModel = { selection, _ ->
                        chosen = selection
                        ModelSwitchResult(accepted = true)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithContentDescription("Open Default model settings").performClick()
        // Current-model card, not a wall of chips.
        composeRule.onNodeWithContentDescription("Change default model").performClick()

        // Grouped by provider inside the sheet — the Nous models render.
        composeRule.onNodeWithContentDescription("Select Nous Hermes-4-405B").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Select Nous Hermes-4-70B").assertIsDisplayed()

        // Search narrows to a single provider's model.
        composeRule.onNodeWithContentDescription("Search models").performTextInput("70b")
        composeRule.onNodeWithContentDescription("Select Nous Hermes-4-70B").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Select Nous Hermes-4-405B").assertDoesNotExist()

        // Clearing the query and filtering by Reasoning drops the fast-only 70B model.
        composeRule.onNodeWithContentDescription("Search models").performTextClearance()
        composeRule.onNodeWithContentDescription("Filter by Reasoning capability").performClick()
        composeRule.onNodeWithContentDescription("Select Nous Hermes-4-405B").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Select Nous Hermes-4-70B").assertDoesNotExist()
        // Turn the filter back off before selecting.
        composeRule.onNodeWithContentDescription("Filter by Reasoning capability").performClick()

        composeRule.onNodeWithContentDescription("Select Nous Hermes-4-70B").performClick()
        composeRule.runOnIdle {
            assertEquals(ModelSelection("nous", "Hermes-4-70B"), chosen)
        }
    }

    @Test
    fun modelPickerExpandsReasoningModelAndSetsPerModelEffort() {
        var reasoningCall: Pair<ModelSelection, String>? = null
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            profileReasoningEffort = "medium",
            profileReasoningDefault = "medium",
            defaultModelOptions = ModelOptions(
                current = ModelSelection("nous", "Hermes-4-405B"),
                providers = listOf(
                    ModelProviderOption(
                        slug = "nous",
                        name = "Nous",
                        models = listOf("Hermes-4-405B", "Hermes-4-70B"),
                        capabilities = mapOf(
                            "Hermes-4-405B" to ModelCapabilities(reasoning = true),
                            "Hermes-4-70B" to ModelCapabilities(fast = true),
                        ),
                    ),
                ),
                profile = "default",
            ),
        )
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onSetModelReasoningOverride = { selection, effort ->
                        reasoningCall = selection to effort
                        Result.success(Unit)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Settings").performClick()
        composeRule.onNodeWithContentDescription("Open Default model settings").performClick()
        composeRule.onNodeWithContentDescription("Change default model").performClick()

        // The fast-only 70B model has no reasoning expander.
        composeRule.onNodeWithContentDescription("Show options for Hermes-4-70B").assertDoesNotExist()

        // Expand the reasoning-capable model to reveal Thinking + effort scale.
        composeRule.onNodeWithContentDescription("Show options for Hermes-4-405B").performClick()
        composeRule.onNodeWithContentDescription("Thinking for Hermes-4-405B").assertIsDisplayed()

        // Setting an effort level writes a per-model override, not a global one.
        composeRule.onNodeWithContentDescription("Set Hermes-4-405B reasoning to high")
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) {
            reasoningCall != null
        }
        assertEquals(ModelSelection("nous", "Hermes-4-405B") to "high", reasoningCall)
    }

    @Test
    fun projectHomeSeparatesProjectsFromUnscopedRecentSessions() {
        val projectId = ProjectId("project-1")
        val scopedSession = sessions.first().copy(projectId = projectId)
        val unscopedSession = SessionSummary(DurableSessionId("recent-1"), "Unscoped recent")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(scopedSession, unscopedSession),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(
                projects = listOf(project),
                activeProjectId = projectId,
                scopedSessionIds = setOf(scopedSession.id),
            ),
            activeProjectId = projectId,
            scopedSessionIds = setOf(scopedSession.id),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithText("Projects").assertIsDisplayed()
        composeRule.onNodeWithTag("Project home row:Project Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Recent Sessions").assertIsDisplayed()
        composeRule.onNodeWithText("Unscoped recent").assertIsDisplayed()
        composeRule.onNodeWithText(scopedSession.title).assertIsDisplayed()
    }

    @Test
    fun recentSessionWorkspaceJoinsAuthoritativeProjectIdentity() {
        val projectId = ProjectId("project-workspace")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        val session = SessionSummary(
            id = DurableSessionId("workspace-session"),
            title = "Workspace session",
            workspacePath = "/workspace/alpha",
        )
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            durableSessions = listOf(session),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(
                projects = listOf(project),
                scopedSessionIds = setOf(session.id),
            ),
            scopedSessionIds = setOf(session.id),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onAllNodesWithText("Project Alpha").assertCountEquals(2)
        composeRule.onAllNodesWithText("Durable session").assertCountEquals(0)
    }

    @Test
    fun homeRecentSessionsShowsPreviewAndViewAllOpensPagedList() {
        val previewSessions = (1..10).map { index ->
            SessionSummary(
                id = DurableSessionId("recent-$index"),
                title = "Recent $index",
                lastActiveEpochSeconds = index.toDouble(),
            )
        }
        val allSessions = previewSessions + SessionSummary(
            id = DurableSessionId("recent-11"),
            title = "Recent 11",
            lastActiveEpochSeconds = 0.0,
        )
        var loadCalls = 0
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            durableSessions = previewSessions,
            recentSessions = RecentSessionsState(
                sessions = allSessions,
                total = 11,
                nextOffset = 11,
                hasMore = false,
            ),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onLoadRecentSessions = { loadCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithText("Recent 10").assertIsDisplayed()
        composeRule.onNodeWithText("Recent 11").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("View all recent sessions").performClick()
        composeRule.onNodeWithTag("Recent sessions full list").performScrollToIndex(10)
        composeRule.onNodeWithText("Recent 11").assertIsDisplayed()
        assertEquals(1, loadCalls)
    }

    @Test
    fun authenticationFreeRecentSessionsStillTriggersLoad() {
        var loadCalls = 0
        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        authenticationState = AuthenticationState.NotRequired,
                    ),
                    onLoadRecentSessions = { loadCalls += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("View all recent sessions").performClick()
        composeRule.waitForIdle()

        assertEquals(1, loadCalls)
    }

    @Test
    fun unsupportedProjectsFallBackToAllDurableRecentSessions() {
        val snapshot = connectedSnapshot.copy(
            durableSessions = sessions,
            projectState = ProjectLoadState.Unsupported,
            projects = emptyList(),
            scopedSessionIds = setOf(sessions.first().id),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithText("Recent Sessions").assertIsDisplayed()
        sessions.forEach { session -> composeRule.onNodeWithText(session.title).assertIsDisplayed() }
    }

    @Test
    fun unscopedLocalDraftWithNullWorkspaceRetainsSendBehavior() {
        val draft = SessionSummary(
            id = DurableSessionId("draft-unscoped"),
            title = "Unscoped draft",
            projectId = null,
            workspacePath = null,
            isLocalDraft = true,
        )
        var created = false
        var sent: Pair<DurableSessionId, String>? = null
        val snapshot = HermesGatewaySnapshot(
            connectionState = ConnectionState.Connected,
            authenticationState = AuthenticationState.Authenticated,
            durableSessions = listOf(draft),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onCreateSession = {
                        created = true
                        draft.id
                    },
                    onSendMessage = { sessionId, text -> sent = sessionId to text },
                )
            }
        }

        composeRule.onNodeWithContentDescription("New task").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Unscoped task")
        composeRule.onNodeWithContentDescription("Send message").performClick()

        assertTrue(created)
        assertEquals(draft.id to "Unscoped task", sent)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun handledNotificationDoesNotReopenOldSessionWhenNewTaskChangesSessionList() {
        val requestedSession = SessionSummary(
            DurableSessionId("notification-session"),
            "Notification session",
        )
        val newDraft = SessionSummary(
            DurableSessionId("new-draft"),
            "New task draft",
            isLocalDraft = true,
        )
        var snapshot by mutableStateOf(
            connectedSnapshot.copy(
                authenticationState = AuthenticationState.Authenticated,
                durableSessions = listOf(requestedSession),
            ),
        )
        var openedSession: DurableSessionId? = null

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    requestedSessionId = requestedSession.id,
                    requestedSessionRequestId = 1L,
                    onCreateSession = {
                        snapshot = snapshot.copy(durableSessions = listOf(newDraft, requestedSession))
                        newDraft.id
                    },
                    onOpenSession = { openedSession = it },
                )
            }
        }

        composeRule.onNodeWithText("New task").performClick()

        composeRule.runOnIdle { assertEquals(newDraft.id, openedSession) }
    }

    @Test
    fun validProjectDraftShowsAuthoritativeWorkspaceAndCanSend() {
        val projectId = ProjectId("project-1")
        val draft = SessionSummary(
            id = DurableSessionId("draft-project-1"),
            title = "Valid task",
            projectId = projectId,
            workspacePath = "/workspace/alpha",
            isLocalDraft = true,
        )
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        var sent: Pair<DurableSessionId, String>? = null
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            durableSessions = listOf(draft),
            projectSessions = mapOf(projectId to listOf(draft)),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loaded(listOf(draft))),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onSendMessage = { sessionId, text -> sent = sessionId to text },
                )
            }
        }

        composeRule.onNodeWithTag("Project home row:Project Alpha").performClick()
        composeRule.onNodeWithText("Valid task").performClick()
        composeRule.onNode(hasSetTextAction()).performTextInput("Start valid task")
        composeRule.onNodeWithContentDescription("Send message").performClick()

        assertEquals(draft.id to "Start valid task", sent)
        composeRule.onNodeWithContentDescription("Open session details").performClick()
        composeRule.onNodeWithText("Workspace: /workspace/alpha").assertIsDisplayed()
    }

    @Test
    fun projectDraftWithoutWorkspaceShowsWarningKeepsComposerEditableAndDisablesSend() {
        val projectId = ProjectId("project-1")
        val draft = SessionSummary(
            id = DurableSessionId("draft-project-1"),
            title = "Needs workspace",
            projectId = projectId,
            workspacePath = null,
            isLocalDraft = true,
        )
        val project = ProjectSummary(projectId, "Project Alpha", null, 1, emptyList())
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            durableSessions = listOf(draft),
            projectSessions = mapOf(projectId to listOf(draft)),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loaded(listOf(draft))),
            chatSessions = mapOf(draft.id to ChatSessionSnapshot(error = "No workspace")),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithTag("Project home row:Project Alpha").performClick()
        composeRule.onNodeWithText("Needs workspace").performClick()
        composeRule.onNodeWithText("No workspace").assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performTextInput("Keep editing")
        composeRule.onNodeWithContentDescription("Send message").assertIsNotEnabled()
    }

    @Test
    fun loadedProjectNewTaskInvokesCallbackAndNavigatesToReturnedDraft() {
        val projectId = ProjectId("project-1")
        val draftId = DurableSessionId("draft-project-1")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        val draft = SessionSummary(
            id = draftId,
            title = "Returned draft",
            projectId = projectId,
            workspacePath = "/workspace/alpha",
            isLocalDraft = true,
        )
        var createdForProject: ProjectId? = null
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            durableSessions = listOf(draft),
            projectSessions = mapOf(projectId to listOf(draft)),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loaded(listOf(draft))),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onCreateProjectSession = {
                        createdForProject = it
                        draftId
                    },
                )
            }
        }

        composeRule.onNodeWithTag("Project home row:Project Alpha").performClick()
        composeRule.onNodeWithText("New task").performClick()
        composeRule.onNodeWithText("Returned draft").assertIsDisplayed()

        assertEquals(projectId, createdForProject)
    }

    @Test
    fun selectingProjectNavigatesToDrillInAndOnlyRequestsMetadata() {
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 0, emptyList())
        var openedProject: ProjectId? = null
        var openedSession: DurableSessionId? = null
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loading),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onOpenProject = { openedProject = it },
                    onOpenSession = { openedSession = it },
                )
            }
        }

        composeRule.onNodeWithTag("Project home row:Project Alpha").performClick()
        composeRule.onNodeWithText("Loading project sessions").assertIsDisplayed()
        assertEquals(projectId, openedProject)
        assertEquals(null, openedSession)
    }

    @Test
    fun projectDrillInRendersTransientUnsupportedAndLoadedEmptyStates() {
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 0, emptyList())
        var drillState by mutableStateOf<ProjectSessionLoadState>(
            ProjectSessionLoadState.TransientError("temporary metadata outage"),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = connectedSnapshot.copy(
                        projects = listOf(project),
                        projectState = ProjectLoadState.Loaded(listOf(project)),
                        projectSessionStates = mapOf(projectId to drillState),
                    ),
                )
            }
        }

        composeRule.onNodeWithTag("Project home row:Project Alpha").performClick()
        composeRule.onNodeWithText("temporary metadata outage").assertIsDisplayed()
        composeRule.runOnIdle { drillState = ProjectSessionLoadState.Unsupported }
        composeRule.onNodeWithText("Project sessions unavailable").assertIsDisplayed()
        composeRule.runOnIdle { drillState = ProjectSessionLoadState.Loaded(emptyList()) }
        composeRule.onNodeWithText("No sessions in this project").assertIsDisplayed()
    }

    @Test
    fun selectingLoadedProjectSessionOpensWorkspaceExplicitly() {
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        val projectSession = SessionSummary(
            DurableSessionId("project-session"),
            "Project session",
            projectId = projectId,
        )
        var openedSession: DurableSessionId? = null
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(projectSession),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loaded(listOf(projectSession))),
            projectSessions = mapOf(projectId to listOf(projectSession)),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot, onOpenSession = { openedSession = it })
            }
        }

        composeRule.onNodeWithTag("Project home row:Project Alpha").performClick()
        composeRule.onNodeWithText("Project session").performClick()
        composeRule.waitForIdle()

        assertEquals(projectSession.id, openedSession)
        composeRule.onNodeWithText("Project session").assertIsDisplayed()
    }

    @Test
    fun compactBackReturnsFromSessionToProjectAndHome() {
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        val projectSession = SessionSummary(DurableSessionId("project-session"), "Project session", projectId)
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(projectSession),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loaded(listOf(projectSession))),
            projectSessions = mapOf(projectId to listOf(projectSession)),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithTag("Project home row:Project Alpha").performClick()
        composeRule.onNodeWithText("Project session").performClick()
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.onAllNodesWithText("Project Alpha").assertCountEquals(2)
        composeRule.onNodeWithText("Back").performClick()
        composeRule.onNodeWithText("Recent Sessions").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w820dp-h700dp")
    fun unfoldedFoldWidthShowsProjectDock() {
        val project = ProjectSummary(
            ProjectId("project-alpha"),
            "Project Alpha",
            "/workspace/alpha",
            0,
            emptyList(),
        )
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription("Project dock, expanded").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open project Project Alpha").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun unfoldedProjectDockShowsTheHomeProjectWithoutDuplicateGlobalHome() {
        val homeProject = ProjectSummary(
            ProjectId("project-home"),
            "Home",
            "/home/mark",
            0,
            emptyList(),
        )
        val snapshot = connectedSnapshot.copy(
            projects = listOf(homeProject),
            projectState = ProjectLoadState.Loaded(listOf(homeProject)),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription("Project dock, expanded").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Home navigation").assertDoesNotExist()
        composeRule.onAllNodesWithContentDescription("Open project Home").assertCountEquals(1)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun selectedProjectCanChooseAndApplyAnIconFromTheDock() {
        val projectId = ProjectId("project-alpha")
        val project = ProjectSummary(
            projectId,
            "Project Alpha",
            "/workspace/alpha",
            0,
            emptyList(),
        )
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
        )
        var selectedIcon by mutableStateOf<ProjectIconId?>(null)

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = ProjectRoute(projectId),
                    projectIcons = selectedIcon?.let { mapOf(projectId to it) }.orEmpty(),
                    onSaveProjectIcon = { savedProjectId, iconId ->
                        assertEquals(projectId, savedProjectId)
                        selectedIcon = iconId
                        Result.success(Unit)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Choose icon for Project Alpha").performClick()
        composeRule.onNodeWithText("Choose project icon").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Search project icons").performTextInput("rocket")
        composeRule.onNodeWithContentDescription("Project icon Rocket").performClick()

        composeRule.waitForIdle()
        assertEquals(ProjectIconId.Rocket, selectedIcon)
        composeRule.onNodeWithText("Choose project icon").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Project Alpha icon Rocket").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun wideProjectDockAccordionsWithoutLosingProjectActions() {
        val project = ProjectSummary(
            ProjectId("project-alpha"),
            "Project Alpha",
            "/workspace/alpha",
            0,
            emptyList(),
        )
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription("Project dock, expanded").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open project Project Alpha").assertIsDisplayed()
        composeRule.onAllNodesWithText("New task").assertCountEquals(1)
        composeRule.onAllNodesWithText("Settings").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Collapse project dock").performClick()
        composeRule.onNodeWithContentDescription("Project dock, collapsed").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open project Project Alpha").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hide project dock").performClick()
        composeRule.onNodeWithContentDescription("Show project dock").assertIsDisplayed().performClick()
        composeRule.onNodeWithContentDescription("Project dock, collapsed").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Expand project dock").performClick()
        composeRule.onNodeWithContentDescription("Project dock, expanded").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun collapsedProjectDockOmitsServerInitial() {
        val project = ProjectSummary(
            ProjectId("project-alpha"),
            "Project Alpha",
            "/workspace/alpha",
            0,
            emptyList(),
        )
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription("Collapse project dock").performClick()
        composeRule.onNodeWithContentDescription("Project dock, collapsed").assertIsDisplayed()
        composeRule.onAllNodesWithText("H").assertCountEquals(0)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun expandedProjectDockOmitsServerIdentity() {
        val project = ProjectSummary(
            ProjectId("project-alpha"),
            "Project Alpha",
            "/workspace/alpha",
            0,
            emptyList(),
        )
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = ProjectRoute(project.id),
                    serverSettingsState = ServerSettingsState.Ready(
                        ServerOrigin.parse("https://ham.sdhost.cc"),
                    ),
                )
            }
        }

        composeRule.onNodeWithContentDescription("Project dock, expanded").assertIsDisplayed()
        composeRule.onAllNodesWithText("H").assertCountEquals(0)
        composeRule.onAllNodesWithText("ham.sdhost.cc").assertCountEquals(0)
        composeRule.onNodeWithContentDescription("Collapse project dock").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun collapsedProjectDockCentersActionContent() {
        val project = ProjectSummary(
            ProjectId("project-alpha"),
            "Project Alpha",
            "/workspace/alpha",
            0,
            emptyList(),
        )
        val snapshot = connectedSnapshot.copy(
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription("Collapse project dock").performClick()
        val projectActionCenterX = composeRule
            .onNodeWithContentDescription("Open project Project Alpha")
            .fetchSemanticsNode()
            .boundsInRoot
            .center
            .x
        val projectIconCenterX = composeRule
            .onNodeWithContentDescription("Project Alpha icon Folder", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .center
            .x
        val newTaskActionCenterX = composeRule
            .onNodeWithContentDescription("New task")
            .fetchSemanticsNode()
            .boundsInRoot
            .center
            .x
        val newTaskGlyphCenterX = composeRule
            .onNodeWithText("+", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .center
            .x

        assertEquals(projectActionCenterX, projectIconCenterX, 0.5f)
        assertEquals(newTaskActionCenterX, newTaskGlyphCenterX, 0.5f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun wideProjectDockKeepsProjectSessionsAsMasterBesideSelectedSession() {
        val projectId = ProjectId("project-alpha")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        val projectSession = SessionSummary(
            DurableSessionId("project-session"),
            "Project session",
            projectId = projectId,
        )
        var createdForProject: ProjectId? = null
        val snapshot = connectedSnapshot.copy(
            authenticationState = AuthenticationState.Authenticated,
            durableSessions = listOf(projectSession),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessions = mapOf(projectId to listOf(projectSession)),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loaded(listOf(projectSession))),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    onCreateProjectSession = {
                        createdForProject = it
                        null
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Open project Project Alpha").performClick()
        composeRule.onNodeWithText("Select a session").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("New task in Project Alpha").performClick()
        assertEquals(projectId, createdForProject)
        composeRule.onNodeWithText("Project session").performClick()
        composeRule.onNodeWithContentDescription("Project sessions for Project Alpha").assertIsDisplayed()
        composeRule.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w816dp-h700dp")
    fun unfoldedWorkspaceGivesDetailMoreWidthAndExposesPaneResizeHandle() {
        val projectId = ProjectId("project-alpha")
        val sessionId = DurableSessionId("project-session")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        val projectSession = SessionSummary(
            sessionId,
            "Project session",
            projectId = projectId,
            workspacePath = "/workspace/alpha",
        )
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(projectSession),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(listOf(project)),
            projectSessions = mapOf(projectId to listOf(projectSession)),
            projectSessionStates = mapOf(
                projectId to ProjectSessionLoadState.Loaded(listOf(projectSession)),
            ),
            chatSessions = mapOf(
                sessionId to ChatSessionSnapshot(
                    messages = listOf(ChatMessage(ChatMessageRole.Assistant, "Session detail")),
                ),
            ),
        )
        var savedPaneProportion: Float? = null

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(
                    snapshot = snapshot,
                    initialRoute = SessionDetailRoute(sessionId),
                    onProjectSessionPaneProportionChanged = { savedPaneProportion = it },
                )
            }
        }

        val masterBounds = composeRule
            .onNodeWithContentDescription("Project sessions for Project Alpha")
            .fetchSemanticsNode()
            .boundsInRoot
        val detailBounds = composeRule.onNodeWithTag("Session timeline")
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue("master=$masterBounds detail=$detailBounds", detailBounds.width > masterBounds.width)
        composeRule.onNodeWithContentDescription("Collapse project sessions").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Show project sessions").assertDoesNotExist()
        val resizeHandle = composeRule.onNodeWithTag("Project session pane resize handle")
        resizeHandle.assertIsDisplayed()
        resizeHandle.performTouchInput {
            down(center)
            repeat(10) {
                moveBy(androidx.compose.ui.geometry.Offset(-8f, 0f), delayMillis = 16)
            }
            up()
        }
        composeRule.waitForIdle()
        val resizedDetailBounds = composeRule.onNodeWithTag("Session timeline")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "before=$detailBounds after=$resizedDetailBounds",
            resizedDetailBounds.width > detailBounds.width,
        )
        composeRule.waitUntil(timeoutMillis = 2_000) { savedPaneProportion != null }
        assertTrue(savedPaneProportion!! < DEFAULT_PROJECT_SESSION_PANE_PROPORTION)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1200dp-h800dp")
    fun wideListDetailDoesNotRenderUnnecessaryBackControls() {
        val projectId = ProjectId("project-1")
        val project = ProjectSummary(projectId, "Project Alpha", "/workspace/alpha", 1, emptyList())
        val projectSession = SessionSummary(DurableSessionId("project-session"), "Project session", projectId)
        val snapshot = connectedSnapshot.copy(
            durableSessions = listOf(projectSession),
            projects = listOf(project),
            projectState = ProjectLoadState.Loaded(
                projects = listOf(project),
                scopedSessionIds = setOf(projectSession.id),
            ),
            scopedSessionIds = setOf(projectSession.id),
            projectSessionStates = mapOf(projectId to ProjectSessionLoadState.Loaded(listOf(projectSession))),
            projectSessions = mapOf(projectId to listOf(projectSession)),
        )

        composeRule.setContent {
            HermesAndroidTheme { HermesApp(snapshot = snapshot) }
        }

        composeRule.onNodeWithContentDescription("Project dock, expanded").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Home navigation").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("Settings navigation").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open project Project Alpha").performClick()
        composeRule.onNodeWithText("Back").assertDoesNotExist()
        composeRule.onNodeWithText("Project session").performClick()
        composeRule.onNodeWithText("Back").assertDoesNotExist()
    }

    @Test
    fun duplicateProjectLabelsStillInvokeTheClickedProjectId() {
        val firstId = ProjectId("project-1")
        val secondId = ProjectId("project-2")
        val first = ProjectSummary(firstId, "Duplicate project", "/workspace/one", 0, emptyList())
        val second = ProjectSummary(secondId, "Duplicate project", "/workspace/two", 0, emptyList())
        var openedProject: ProjectId? = null
        val snapshot = connectedSnapshot.copy(
            projects = listOf(first, second),
            projectState = ProjectLoadState.Loaded(listOf(first, second)),
        )

        composeRule.setContent {
            HermesAndroidTheme {
                HermesApp(snapshot = snapshot, onOpenProject = { openedProject = it })
            }
        }

        composeRule.onAllNodesWithText("Duplicate project").assertCountEquals(2)[1].performClick()

        assertEquals(secondId, openedProject)
    }
}
