package com.unsupportedpastels.mercury.core.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayFoldersContractTest {
    @Test
    fun supportsOnlyTheVersionedFolderCapability() {
        val supported = """
            {"capabilities":{"folders":{"version":1,"list_method":"relay.folders.list","create_method":"relay.folders.create"}}}
        """.trimIndent()

        assertTrue(RelayFoldersContract.supports(supported))
        assertFalse(RelayFoldersContract.supports("{}"))
        assertFalse(
            RelayFoldersContract.supports(
                """{"capabilities":{"folders":{"version":"1","list_method":"relay.folders.list","create_method":"relay.folders.create"}}}""",
            ),
        )
        assertFalse(
            RelayFoldersContract.supports(
                """{"capabilities":{"folders":{"version":1,"list_method":"relay.folders.list","create_method":"wrong"}}}""",
            ),
        )
        assertFalse(RelayFoldersContract.supports("not json"))
    }

    @Test
    fun safeErrorMessageUsesOnlyTheAllowlistedFixedReasons() {
        val reasons = listOf(
            "invalid_params",
            "folders_unavailable",
            "folder_not_available",
            "folder_exists",
            "response_too_large",
            "rate_limited",
            "folder_create_failed",
        )

        reasons.forEach { reason ->
            val message = RelayFoldersContract.safeErrorMessage(reason)
            assertNotNull(message)
            assertFalse(message.contains(reason))
        }
        assertNull(RelayFoldersContract.safeErrorMessage("provider-controlled detail"))
        assertNull(RelayFoldersContract.safeErrorMessage(null))
    }

    @Test
    fun requestBuildersEmitOnlyTheSharedWireFields() {
        assertEquals(
            """{"profile":"default"}""",
            RelayFoldersContract.listParams("default", null),
        )
        assertEquals(
            """{"profile":"default","path":"C:\\work\\src"}""",
            RelayFoldersContract.listParams("default", " C:\\work\\src "),
        )
        assertEquals(
            """{"profile":"default","parent_path":"/workspace","name":"new folder"}""",
            RelayFoldersContract.createParams("default", "/workspace", "new folder"),
        )
        assertNull(RelayFoldersContract.listParams("", null))
        assertNull(RelayFoldersContract.listParams("..", null))
        assertNull(RelayFoldersContract.listParams("Work", null))
        assertNull(RelayFoldersContract.listParams("default", "relative/path"))
        assertNull(RelayFoldersContract.createParams("default", "/workspace", "../escape"))
    }

    @Test
    fun pathAndNameValidationPreserveSupportedAbsoluteForms() {
        assertEquals("/workspace", RelayFoldersContract.validPath(" /workspace "))
        assertEquals("C:\\workspace\\src", RelayFoldersContract.validPath("C:\\workspace\\src"))
        assertEquals("D:/workspace/src", RelayFoldersContract.validPath("D:/workspace/src"))
        assertNull(RelayFoldersContract.validPath("workspace/src"))
        assertNull(RelayFoldersContract.validPath("/workspace/../escape"))
        assertNull(RelayFoldersContract.validPath("C:\\workspace\\..\\escape"))
        assertNull(RelayFoldersContract.validPath("/" + "x".repeat(RelayFoldersContract.maxPathLength)))

        assertEquals("new folder", RelayFoldersContract.validName("new folder"))
        assertNull(RelayFoldersContract.validName(""))
        assertNull(RelayFoldersContract.validName("."))
        assertNull(RelayFoldersContract.validName(".."))
        assertNull(RelayFoldersContract.validName("nested/name"))
        assertNull(RelayFoldersContract.validName("nested\\name"))
        assertNull(RelayFoldersContract.validName("x".repeat(RelayFoldersContract.maxNameLength + 1)))
    }

    @Test
    fun relayCreateUsesUtf8NameBoundWithoutNarrowingDirectWindowsNames() {
        val tooManyBytes = "😀".repeat(64)
        assertNotNull(RelayFoldersContract.validName(tooManyBytes))
        assertNull(RelayFoldersContract.createParams("default", "/workspace", tooManyBytes))
        assertNotNull(RelayFoldersContract.createParams("default", "/workspace", "😀".repeat(63) + "abc"))
    }

    @Test
    fun decoderReadsDirectoryShapeAndSkipsNonDirectoriesAndDuplicates() {
        val listing = RelayFoldersContract.decodeListing(
            """
            {
              "path":"/workspace",
              "parent":null,
              "root":"/workspace",
              "locked_root":"/workspace",
              "can_change_path":false,
              "entries":[
                {"name":"src","path":"/workspace/src","is_dir":true},
                {"name":"README.md","path":"/workspace/README.md","is_directory":false},
                {"name":"src duplicate","path":"/workspace/src","is_directory":true},
                {"name":"bad/entry","path":"/workspace/bad","is_dir":true}
              ]
            }
            """.trimIndent(),
        )

        assertNotNull(listing)
        assertEquals("/workspace", listing.path)
        assertNull(listing.parentPath)
        assertEquals("/workspace", listing.root)
        assertEquals("/workspace", listing.lockedRoot)
        assertFalse(listing.canChangePath)
        assertEquals(listOf(HostFolderEntry("src", "/workspace/src")), listing.entries)
    }

    @Test
    fun decoderRejectsAnOversizedEntryArrayInsteadOfTruncatingIt() {
        val entries = (0..RelayFoldersContract.maxEntries).joinToString(",") { index ->
            "{\"name\":\"folder-$index\",\"path\":\"/workspace/folder-$index\",\"is_dir\":true}"
        }
        val listing = RelayFoldersContract.decodeListing(
            "{\"path\":\"/workspace\",\"entries\":[$entries]}",
        )

        assertNull(listing)
    }

    @Test
    fun decoderRejectsPresentEntriesWithTheWrongJsonType() {
        assertNull(
            RelayFoldersContract.decodeListing(
                "{\"path\":\"/workspace\",\"entries\":{\"name\":\"src\"}}",
            ),
        )
    }

    @Test
    fun decoderRejectsPartialListingsAndInvalidNavigationMetadata() {
        assertNull(RelayFoldersContract.decodeListing("""{"path":"/workspace"}"""))
        for ((key, value) in listOf(
            "can_change_path" to "\"false\"",
            "parent" to "\"relative\"",
            "root" to "123",
            "locked_root" to "false",
        )) {
            assertNull(RelayFoldersContract.decodeListing("""{"path":"/workspace","entries":[],"$key":$value}"""))
        }
        val minimal = assertNotNull(RelayFoldersContract.decodeListing("""{"path":"/workspace","entries":[]}"""))
        assertFalse(minimal.canChangePath)
    }

    @Test
    fun decoderRejectsAnOversizedJsonInputBeforeParsing() {
        val oversized = "{\"path\":\"/workspace\",\"padding\":\"" +
            "x".repeat(RelayFoldersContract.maxListingJsonBytes) +
            "\"}"

        assertNull(RelayFoldersContract.decodeListing(oversized))
    }

    @Test
    fun decoderAcceptsTheEntryBoundAndRejectsInvalidRoot() {
        val entries = (0 until RelayFoldersContract.maxEntries).joinToString(",") { index ->
            "{\"name\":\"folder-$index\",\"path\":\"/workspace/folder-$index\",\"is_dir\":true}"
        }
        val listing = RelayFoldersContract.decodeListing(
            "{\"path\":\"/workspace\",\"entries\":[$entries]}",
        )

        assertNotNull(listing)
        assertEquals(RelayFoldersContract.maxEntries, listing.entries.size)
        assertNull(RelayFoldersContract.decodeListing("{\"path\":\"relative\",\"entries\":[]}"))
    }
}
