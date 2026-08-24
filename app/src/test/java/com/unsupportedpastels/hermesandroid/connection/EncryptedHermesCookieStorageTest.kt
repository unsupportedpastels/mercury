package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.http.renderCookieHeader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedHermesCookieStorageTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferencesName = "hermes_cookie_store_tests"
    private val url = Url("https://hermes.example/")

    // A real Hermes dashboard session token is base64 (RFC 4648) and therefore
    // contains '=', '/', and '+'. The server sets it verbatim; the client must
    // replay it verbatim. URL-encoding these bytes corrupts the token and the
    // server rejects the session (observed as HTTP 503 on /api/auth/me).
    private val base64Token =
        "eyJzdWIiOiJhZG1pbiIsImtpbmQiOiJhY2Nlc3MifQ==.sig+with/slash+plus=="

    @Before
    fun clearCookiePreferences() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences("$preferencesName.keyset", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun base64SessionTokenRoundTripsVerbatim() = runTest {
        val storage = EncryptedHermesCookieStorage(context, preferencesName)
        storage.addCookie(
            url,
            io.ktor.http.Cookie(
                name = "hermes_session_at",
                value = base64Token,
                encoding = CookieEncoding.RAW,
            ),
        )

        val restored = storage.get(url).single { it.name == "hermes_session_at" }

        // The stored value must come back byte-for-byte identical.
        assertEquals(base64Token, restored.value)
        // And it must be tagged RAW so the request pipeline sends it verbatim
        // instead of percent-encoding '=' '/' '+'. This is the actual on-wire
        // guarantee: the rendered Cookie header contains the untouched token.
        assertEquals(CookieEncoding.RAW, restored.encoding)
        assertEquals(
            "hermes_session_at=$base64Token",
            renderCookieHeader(restored),
        )
    }

    @Test
    fun persistedTokenSurvivesNewStorageInstanceVerbatim() = runTest {
        EncryptedHermesCookieStorage(context, preferencesName).addCookie(
            url,
            io.ktor.http.Cookie(
                name = "hermes_session_at",
                value = base64Token,
                encoding = CookieEncoding.RAW,
            ),
        )

        // A fresh instance (process restart) must decrypt and replay verbatim.
        val restored = EncryptedHermesCookieStorage(context, preferencesName)
            .get(url)
            .single { it.name == "hermes_session_at" }

        assertEquals(base64Token, restored.value)
        assertEquals(CookieEncoding.RAW, restored.encoding)
        assertEquals(
            "hermes_session_at=$base64Token",
            renderCookieHeader(restored),
        )
    }
}
