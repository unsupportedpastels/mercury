package com.unsupportedpastels.hermesandroid.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.connection.ServerCatalog
import com.unsupportedpastels.hermesandroid.connection.ServerCatalogEntry
import com.unsupportedpastels.hermesandroid.connection.ServerOrigin
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import com.unsupportedpastels.hermesandroid.theme.HermesAndroidTheme

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ServerSettingsCatalogTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionAndInactiveRemovalUseExplicitConfirmation() {
        val first = ServerOrigin.parse("https://first.example")
        val second = ServerOrigin.parse("https://second.example")
        var selected: ServerOrigin? = null
        var removed: ServerOrigin? = null
        composeRule.setContent {
            HermesAndroidTheme {
                ServerSettingsScreen(
                    serverOrigin = first,
                    serverCatalog = ServerCatalog(
                        entries = listOf(
                            ServerCatalogEntry(first, "First"),
                            ServerCatalogEntry(second, "Second"),
                        ),
                        activeOrigin = first,
                    ),
                    snapshot = HermesGatewaySnapshot(),
                    showBack = true,
                    onBack = {},
                    onSave = { Result.success(Unit) },
                    onSelectServer = {
                        selected = it
                        Result.success(Unit)
                    },
                    onRemoveServer = {
                        removed = it
                        Result.success(Unit)
                    },
                )
            }
        }

        composeRule.onNodeWithContentDescription("Remove ${first.value}").assertIsEnabled().performClick()
        composeRule.onNodeWithText("Remove server?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Cancel")[1].performClick()
        composeRule.onNodeWithContentDescription("Select ${second.value}").performClick()
        composeRule.onNodeWithContentDescription("Remove ${second.value}").performClick()
        composeRule.onNodeWithText("Remove server?").assertIsDisplayed()
        composeRule.onAllNodesWithText("Cancel")[1].performClick()
        composeRule.runOnIdle {
            assertEquals(second, selected)
            assertEquals(null, removed)
        }

        composeRule.onNodeWithContentDescription("Remove ${second.value}").performClick()
        composeRule.onNodeWithText("Remove server?").assertIsDisplayed()
        composeRule.onNodeWithText("Remove").performClick()
        composeRule.runOnIdle { assertEquals(second, removed) }
    }
}
