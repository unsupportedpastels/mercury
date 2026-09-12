package com.unsupportedpastels.mercury.core.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PushPreviewContractTest {
    private val exact = """{"capabilities":{"push_previews":{"version":1,"register_method":"relay.push.preview.register","unregister_method":"relay.push.unregister","aead":"CHACHA20-POLY1305","max_plaintext_bytes":1280,"max_title_utf8_bytes":160,"max_body_utf8_bytes":640}}}"""
    @Test fun capabilityRequiresExactContractAndBoundedLimits() {
        assertNotNull(PushPreviewContract.capability(Json.parseToJsonElement(exact).jsonObject))
        for (bad in listOf(exact.replace("\"version\":1", "\"version\":\"1\""), exact.replace("1280", "1281"), exact.replace("CHACHA20-POLY1305", "AES-GCM"), "{}"))
            assertNull(PushPreviewContract.capability(Json.parseToJsonElement(bad).jsonObject))
    }
    @Test fun aadMatchesFrozenVector() {
        val bytes = PushPreviewContract.aad("sandbox", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8", "AAECAwQFBgcICQoLDA0ODw")
        assertEquals("000000176d6572637572792e707573682d707265766965772e7631000000013100000004433230500000000773616e64626f780000002b41414543417751464267634943516f4c4441304f4478415245684d554652595847426b61477877644868380000002b494345694979516c4a69636f4b536f724c4330754c7a41784d6a4d304e5459334f446b364f7a7739506a380000001641414543417751464267634943516f4c4441304f4477", bytes.joinToString("") { "%02x".format(it) })
    }
    @Test fun routeBoundsRemainCanonicalUtf8Limits() {
        assertEquals(128, PushPreviewContract.MAX_ROUTE_SESSION_UTF8_BYTES)
        assertEquals(64, PushPreviewContract.MAX_ROUTE_PROFILE_UTF8_BYTES)
    }
    @Test fun policyCapsUtf8AndNeverQuotesAttentionPrompt() {
        val prefs = PushPreviewPolicy.Preferences(true, true, true, true)
        assertEquals(640, PushPreviewPolicy.utf8Prefix("🙂".repeat(200), 640).encodeToByteArray().size)
        assertEquals("Authorization is required to continue", PushPreviewPolicy.attention(NotificationInputKind.APPROVAL, prefs)?.body)
        assertNull(PushPreviewPolicy.completion("x", "title", prefs.copy(completion = false)))
    }
}
