package com.unsupportedpastels.mercury.core.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PushPreviewCorpusTest {
    @Test fun canonicalPolicyCorpus() {
        val text = checkNotNull(javaClass.classLoader!!.getResourceAsStream("push-preview/policy-corpus.json")).bufferedReader().use { it.readText() }
        val rows = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        for (rowElement in rows) {
            val row = rowElement.jsonObject
            fun string(name: String) = row[name]?.jsonPrimitive?.content
            fun bool(name: String) = row[name]?.jsonPrimitive?.booleanOrNull ?: false
            val preferences = PushPreviewPolicy.Preferences(true, true, bool("include_title"), bool("include_excerpt"))
            val result = when (string("kind")) {
                "completion" -> PushPreviewPolicy.completion(string("text")!!, string("title"), preferences)
                "approval" -> PushPreviewPolicy.attention(NotificationInputKind.APPROVAL, preferences)
                "clarification" -> PushPreviewPolicy.attention(NotificationInputKind.CLARIFICATION, preferences)
                else -> PushPreviewPolicy.attention(NotificationInputKind.SECURE_INPUT, preferences)
            }!!
            assertEquals(string("expected_title"), result.title, string("name"))
            assertEquals(string("expected_body"), result.body, string("name"))
            if (string("kind") != "completion") assertFalse((result.title.orEmpty() + result.body.orEmpty()).contains(string("text")!!))
        }
    }
}
