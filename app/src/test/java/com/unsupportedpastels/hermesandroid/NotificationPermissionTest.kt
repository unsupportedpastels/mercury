package com.unsupportedpastels.hermesandroid

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsupportedpastels.hermesandroid.gateway.AuthenticationState
import com.unsupportedpastels.hermesandroid.gateway.ConnectionState
import com.unsupportedpastels.hermesandroid.gateway.HermesGatewaySnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NotificationPermissionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun connectedPublicServerRequestsOnlyOnceAcrossRecreation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(composeRule)
        var requests = 0
        restoration.setContent {
            NotificationPermissionEffect(
                HermesGatewaySnapshot(
                    connectionState = ConnectionState.Connected,
                    authenticationState = AuthenticationState.NotRequired,
                ),
            ) { requests++ }
        }
        composeRule.runOnIdle { assertEquals(1, requests) }
        restoration.emulateSavedInstanceStateRestore()
        composeRule.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun freshLaunchWaitsForConnectedAuthenticationAndDoesNotRepeat() {
        val snapshot = mutableStateOf(HermesGatewaySnapshot())
        var requests = 0
        composeRule.setContent {
            NotificationPermissionEffect(snapshot.value) { requests++ }
        }
        composeRule.runOnIdle { assertEquals(0, requests) }
        composeRule.runOnIdle {
            snapshot.value = snapshot.value.copy(authenticationState = AuthenticationState.Authenticated)
        }
        composeRule.runOnIdle { assertEquals(0, requests) }
        composeRule.runOnIdle {
            snapshot.value = snapshot.value.copy(connectionState = ConnectionState.Connected)
        }
        composeRule.runOnIdle { assertEquals(1, requests) }
        composeRule.runOnIdle {
            snapshot.value = snapshot.value.copy(connectionState = ConnectionState.Recovering)
        }
        composeRule.runOnIdle {
            snapshot.value = snapshot.value.copy(connectionState = ConnectionState.Connected)
        }
        composeRule.runOnIdle { assertEquals(1, requests) }
    }
}
