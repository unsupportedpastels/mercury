package com.unsupportedpastels.mercury.core.relay

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RelayAdmissionEnvelopeTest {
    private val deviceId = RelayBase64.urlSafeEncode(ByteArray(16) { it.toByte() })

    @Test
    fun recoveryIsExplicitAndPreservesLegacyBytes() {
        for (cursor in listOf(null, 0L, 42L, Long.MAX_VALUE)) {
            val cursorField = cursor?.let { ",\"resume_cursor\":$it" }.orEmpty()
            val legacy = "{\"device_id\":\"$deviceId\",\"profile\":\"default\"$cursorField,\"type\":\"controller.open\"}"
            assertEquals(legacy, RelayAdmissionEnvelope.controllerOpen(deviceId, "default", cursor).decodeToString())
            assertContentEquals(
                legacy.encodeToByteArray(),
                RelayAdmissionEnvelope.controllerOpen(deviceId, "default", cursor, recoveryVersion = null),
            )
            assertEquals(
                legacy.dropLast(1) + ",\"recovery_version\":1}",
                RelayAdmissionEnvelope.controllerOpen(deviceId, "default", cursor, recoveryVersion = 1).decodeToString(),
            )
        }
    }

    @Test
    fun unsupportedRecoveryVersionsFailClosed() {
        for (version in listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE)) {
            val error = assertFailsWith<RelayProtocolException> {
                RelayAdmissionEnvelope.controllerOpen(deviceId, "default", null, version)
            }
            assertEquals(RelayProtocolFailure.InvalidEnvelope, error.failure)
        }
    }

    @Test
    fun versionedAdmissionRetainsInputValidation() {
        for ((id, profile, cursor) in listOf(
            Triple("invalid", "default", 0L),
            Triple(deviceId, "bad profile", 0L),
            Triple(deviceId, "default", -1L),
            Triple(deviceId, "x".repeat(RelayProtocolPolicy.maxProfileCharacters + 1), 0L),
        )) {
            assertFailsWith<RelayProtocolException> {
                RelayAdmissionEnvelope.controllerOpen(id, profile, cursor, 1)
            }
        }
    }
}
