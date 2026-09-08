package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.gateway.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackgroundResponseNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun staleEvidenceRemovesWaitingNoticeWithoutInventingAResponse() {
        val now = mutableLongStateOf(2_000L)
        val messages = listOf(ChatMessage(ChatMessageRole.User, "Synthetic request"))
        val tasks = BackgroundTasks(listOf(
            BackgroundTaskRow(RuntimeSessionId("runtime"), "child", "Review", null, BackgroundTaskStatus.Active, 1_000L),
        ))
        val notice = "Background work continues. The final response is not available yet."
        compose.setContent {
            MaterialTheme { BackgroundResponseNotice(messages, tasks, false, nowOverride = now.longValue) }
        }
        compose.onNodeWithText(notice).assertIsDisplayed()
        compose.runOnIdle { now.longValue = 122_000L }
        compose.onAllNodesWithText(notice).assertCountEquals(0)
        org.junit.Assert.assertEquals(1, messages.size)
        org.junit.Assert.assertEquals(ChatMessageRole.User, messages.single().role)
    }
}
