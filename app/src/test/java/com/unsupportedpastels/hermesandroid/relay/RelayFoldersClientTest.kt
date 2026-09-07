package com.unsupportedpastels.hermesandroid.relay

import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayFoldersClientTest {
    private val supportedStatus = Json.parseToJsonElement(
        """{"capabilities":{"folders":{"version":1,"list_method":"relay.folders.list","create_method":"relay.folders.create"}}}""",
    ).jsonObject

    @Test
    fun oldHostsGetUpgradeGuidanceWithoutFolderRpc() = runTest {
        val calls = mutableListOf<String>()
        val error = runCatching {
            RelayFoldersClient().list("default", null) { method, _ ->
                calls += method
                JsonObject(emptyMap())
            }
        }.exceptionOrNull()

        assertTrue(error is RelayFoldersUnsupportedException)
        assertTrue(error?.message?.contains("Update the Mercury Relay host plugin") == true)
        assertEquals(listOf("relay.status"), calls)
    }

    @Test
    fun listUsesVersionedCapabilityAndMapsManagedFilesDirectoryShape() = runTest {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val listing = RelayFoldersClient().list("default", "/workspace") { method, params ->
            calls += method to params
            when (method) {
                "relay.status" -> supportedStatus
                "relay.folders.list" -> Json.parseToJsonElement(
                    """
                    {
                      "path":"/workspace",
                      "parent":"/",
                      "root":"/",
                      "locked_root":"/",
                      "can_change_path":true,
                      "entries":[
                        {"name":"src","path":"/workspace/src","is_dir":true},
                        {"name":"README.md","path":"/workspace/README.md","is_directory":false}
                      ]
                    }
                    """.trimIndent(),
                ).jsonObject
                else -> error("unexpected method $method")
            }
        }

        assertEquals(
            HostDirectoryListing(
                path = "/workspace",
                directories = listOf(com.unsupportedpastels.hermesandroid.gateway.HostDirectoryEntry("src", "/workspace/src")),
                parentPath = "/",
                root = "/",
                lockedRoot = "/",
                canChangePath = true,
            ),
            listing,
        )
        assertEquals(listOf("relay.status", "relay.folders.list"), calls.map { it.first })
        assertEquals("default", calls[1].second["profile"]!!.jsonPrimitive.content)
        assertEquals("/workspace", calls[1].second["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun invalidInputsDoNotDispatchAndCreateIsNotRetried() = runTest {
        val invalidCalls = mutableListOf<String>()
        val invalid = runCatching {
            RelayFoldersClient().list("default", "relative") { method, _ ->
                invalidCalls += method
                supportedStatus
            }
        }.exceptionOrNull()
        assertTrue(invalid is RelayFoldersInvalidInputException)
        assertTrue(invalidCalls.isEmpty())

        var createCalls = 0
        val listing = RelayFoldersClient().create("default", "/workspace", "child") { method, _ ->
            when (method) {
                "relay.status" -> supportedStatus
                "relay.folders.create" -> {
                    createCalls += 1
                    Json.parseToJsonElement(
                        """{"path":"/workspace/child","entries":[]}""",
                    ).jsonObject
                }
                else -> error("unexpected method $method")
            }
        }
        assertEquals("/workspace/child", listing.path)
        assertEquals(1, createCalls)
    }
}
