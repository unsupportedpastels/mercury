package com.unsupportedpastels.hermesandroid.connection

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unsupportedpastels.hermesandroid.MainActivity
import com.unsupportedpastels.hermesandroid.relay.EncryptedRelayTargetStore
import com.unsupportedpastels.hermesandroid.relay.RelayPairingCoordinator
import com.unsupportedpastels.hermesandroid.relay.TlsRelayBinarySocketFactory
import com.unsupportedpastels.mercury.core.relay.RelayPairingPayload
import com.unsupportedpastels.mercury.core.relay.RelayTargetStatus
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Opt-in folder journey against one isolated, real Mercury Relay host.
 *
 * The pairing offer and CA are installed into the app sandbox by the QA
 * controller. The test performs the real Noise pairing, approves only the
 * returned device ID/fingerprint through the local dashboard API, persists the
 * approved target in the production encrypted store, and then starts the real
 * MainActivity Relay path.
 *
 * Instrumentation arguments:
 *
 *   MERCURY_RELAY_FOLDER_QA=1
 *   QA_ORIGIN=https://localhost:<isolated-qa-port>
 *   QA_FOLDER_ROOT=/tmp/<run>/managedroot
 */
@RunWith(AndroidJUnit4::class)
class RelayFoldersEndToEndTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val arguments
        get() = InstrumentationRegistry.getArguments()

    @Test
    fun browseKnownFolderCreateUniqueChildAndRegisterProject() {
        assumeTrue(
            "Set MERCURY_RELAY_FOLDER_QA=1 to run the isolated Relay folder journey",
            arguments.getString(ARG_OPT_IN) == "1",
        )
        val root = requiredArgument(ARG_FOLDER_ROOT)
        require(root.startsWith("/") && root.length <= MAX_PATH_LENGTH && !root.endsWith("/")) {
            "QA_FOLDER_ROOT must be one canonical absolute path"
        }
        val knownChildPath = "$root/$KNOWN_CHILD"
        val suffix = UUID.randomUUID().toString().take(8)
        val newFolderName = "android-$suffix"
        val canonicalChildPath = "$knownChildPath/$newFolderName"
        val projectName = "Android Relay Folder $suffix"

        waitForDescription("Create project")
        composeRule.onNodeWithContentDescription("Create project")
            .assertIsEnabled()
            .performClick()
        waitForFolder(root)

        waitForText(KNOWN_CHILD, substring = false)
        composeRule.onNodeWithText(KNOWN_CHILD, substring = false).performClick()
        waitForFolder(knownChildPath)
        capture("existing")

        composeRule.onNodeWithTag("Toggle create host folder")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.onNodeWithTag("New folder name input")
            .performScrollTo()
            .performTextInput(newFolderName)
        composeRule.onNodeWithTag("Confirm create host folder")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        // The path is read from the host response through the actual Compose
        // state, not inferred from a local fake listing.
        waitForFolder(canonicalChildPath)
        capture("created")

        composeRule.onNodeWithTag("Project name input")
            .performScrollTo()
            .performTextClearance()
        composeRule.onNodeWithTag("Project name input")
            .performTextInput(projectName)
        composeRule.onNodeWithTag("Confirm create project")
            .assertIsEnabled()
            .performClick()

        // A name/path still present in the form is not registration success.
        try {
            composeRule.waitUntil(UI_TIMEOUT_MILLIS) {
                composeRule.onAllNodesWithTag("Create project sheet").fetchSemanticsNodes().isEmpty()
            }
        } catch (failure: Throwable) {
            capture("registration-failure")
            throw failure
        }
        waitForText(projectName, substring = false)
        waitForText("Workspace: $canonicalChildPath", substring = false)
        capture("registered")
    }

    private fun requiredArgument(name: String): String {
        val value = arguments.getString(name)
        require(!value.isNullOrBlank() && value == value.trim()) {
            "$name must be supplied for Relay folder QA"
        }
        return value
    }

    private fun waitForDescription(description: String, timeoutMillis: Long = UI_TIMEOUT_MILLIS) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching {
                composeRule.onNodeWithContentDescription(description).assertIsEnabled()
                true
            }.getOrDefault(false)
        }
    }

    private fun waitForText(
        text: String,
        substring: Boolean = true,
        timeoutMillis: Long = UI_TIMEOUT_MILLIS,
    ) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching {
                composeRule.onAllNodesWithText(text, substring = substring)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun waitForFolder(path: String, timeoutMillis: Long = UI_TIMEOUT_MILLIS) {
        try {
            composeRule.waitUntil(timeoutMillis) {
                runCatching {
                    composeRule.onNodeWithTag("Toggle create host folder").assertIsEnabled()
                    composeRule.onNodeWithTag("Host folder input")
                        .fetchSemanticsNode()
                        .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText]
                        .text == path
                }.getOrDefault(false)
            }
        } catch (failure: Throwable) {
            capture("failure")
            throw AssertionError(composeRule.onAllNodes(isRoot()).printToString(), failure)
        }
    }

    private fun capture(label: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val metric = arguments.getString("metric")?.takeIf(String::isNotBlank) ?: "compact"
        val directory = requireNotNull(instrumentation.targetContext.getExternalFilesDir(null))
        val file = File(directory, "relay-folders-$metric-$label.png")
        file.outputStream().use { output ->
            check(
                instrumentation.uiAutomation
                    .takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output),
            ) { "Could not write Relay folder screenshot" }
        }
    }

    companion object {
        private const val ARG_OPT_IN = "MERCURY_RELAY_FOLDER_QA"
        private const val ARG_QA_ORIGIN = "QA_ORIGIN"
        private const val ARG_FOLDER_ROOT = "QA_FOLDER_ROOT"
        private const val KNOWN_CHILD = "existing"
        private const val MAX_PATH_LENGTH = 1_024
        private const val UI_TIMEOUT_MILLIS = 30_000L
        private const val API_TIMEOUT_MILLIS = 10_000
        private const val MANAGEMENT_PREFIX = "/api/relay"

        private var previousTlsContext: SSLContext? = null
        private var previousHttpsSocketFactory: SSLSocketFactory? = null

        @JvmStatic
        @BeforeClass
        fun pairAndApproveRelayTarget() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(
                "Set MERCURY_RELAY_FOLDER_QA=1 to run the isolated Relay folder journey",
                args.getString(ARG_OPT_IN) == "1",
            )
            grantNotificationPermission(instrumentation)

            val qaOrigin = requiredArgument(args, ARG_QA_ORIGIN)
            requireLocalHttpsOrigin(qaOrigin)
            val context = instrumentation.targetContext
            installFixtureTrust(context)

            val payloadFile = File(context.filesDir, "qa-pairing-payload.json")
            val caFile = File(context.filesDir, "qa-root-ca.crt")
            check(payloadFile.isFile) { "Missing qa-pairing-payload.json in app filesDir" }
            check(caFile.isFile) { "Missing qa-root-ca.crt in app filesDir" }
            val payloadText = payloadFile.readText(Charsets.UTF_8)
            val now = System.currentTimeMillis() / 1_000L
            val parsedPayload = RelayPairingPayload.parse(payloadText, now)
            requireLocalRelayOrigin(parsedPayload.relayOrigin)

            val store = EncryptedRelayTargetStore(context)
            val target = runBlocking {
                RelayPairingCoordinator(
                    socketFactory = TlsRelayBinarySocketFactory(),
                    targets = store,
                ).pair(payloadText)
            }

            approveExactTarget(
                qaOrigin = qaOrigin,
                deviceId = target.deviceId,
                fingerprint = target.fingerprint,
            )
            val approvedAt = System.currentTimeMillis() / 1_000L
            runBlocking { store.markApproved(target.id, approvedAt) }
            val stored = runBlocking { store.load().firstOrNull { it.id == target.id } }
                ?: error("Production Relay target store omitted the paired target")
            check(stored.status == RelayTargetStatus.Approved) {
                "Production Relay target store did not persist approval"
            }
            check(stored.deviceId == target.deviceId && stored.fingerprint == target.fingerprint) {
                "Production Relay target store changed the paired identity"
            }
        }

        @JvmStatic
        @AfterClass
        fun restoreFixtureTrust() {
            previousHttpsSocketFactory?.let { HttpsURLConnection.setDefaultSSLSocketFactory(it) }
            previousTlsContext?.let { SSLContext.setDefault(it) }
            previousHttpsSocketFactory = null
            previousTlsContext = null
        }

        private fun requiredArgument(args: android.os.Bundle, name: String): String {
            val value = args.getString(name)
            require(!value.isNullOrBlank() && value == value.trim()) {
                "$name must be supplied for Relay folder QA"
            }
            return value
        }

        private fun grantNotificationPermission(instrumentation: android.app.Instrumentation) {
            if (Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(
                    instrumentation.targetContext.packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                )
            }
        }

        private fun installFixtureTrust(context: android.content.Context) {
            val caFile = File(context.filesDir, "qa-root-ca.crt")
            check(caFile.isFile) { "Missing qa-root-ca.crt in app filesDir" }
            previousTlsContext = SSLContext.getDefault()
            previousHttpsSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
            val certificate = caFile.inputStream().use { input ->
                CertificateFactory.getInstance("X.509").generateCertificate(input)
            }
            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("qa-root", certificate)
            }
            val trustManagers = TrustManagerFactory
                .getInstance(TrustManagerFactory.getDefaultAlgorithm())
                .apply { init(keyStore) }
                .trustManagers
            SSLContext.getInstance("TLS").apply {
                init(null, trustManagers, SecureRandom())
                // TlsRelayBinarySocketFactory reads HttpsURLConnection's default
                // socket factory; leave its existing hostname verifier untouched.
                SSLContext.setDefault(this)
                HttpsURLConnection.setDefaultSSLSocketFactory(socketFactory)
            }
        }

        private fun requireLocalHttpsOrigin(origin: String) {
            val uri = URI(origin)
            require(uri.scheme == "https" && isLoopbackHost(uri.host)) {
                "QA_ORIGIN must be an HTTPS localhost/127.0.0.1 origin"
            }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "QA_ORIGIN must not contain credentials, a query, or a fragment"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "QA_ORIGIN must be a bare origin"
            }
            require(uri.port == -1 || uri.port in 1..65_535) {
                "QA_ORIGIN has an invalid port"
            }
        }

        private fun requireLocalRelayOrigin(origin: String) {
            val uri = URI(origin)
            require(uri.scheme == "https" || uri.scheme == "wss") {
                "Pairing payload must use an HTTPS/WSS loopback relay origin"
            }
            require(isLoopbackHost(uri.host)) {
                "Pairing payload relay origin must be localhost/127.0.0.1 for QA"
            }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "Pairing payload relay origin must be a bare origin"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Pairing payload relay origin must not contain a path"
            }
            require(uri.port == -1 || uri.port in 1..65_535) {
                "Pairing payload relay origin has an invalid port"
            }
        }

        private fun isLoopbackHost(host: String?): Boolean =
            host == "localhost" || host == "127.0.0.1"

        private fun approveExactTarget(
            qaOrigin: String,
            deviceId: String,
            fingerprint: String,
        ) {
            val devicePath = "$MANAGEMENT_PREFIX/devices/${Uri.encode(deviceId)}/approve"
            val body = JSONObject()
                .put("confirmed_fingerprint", fingerprint)
                .toString()
            request(
                origin = qaOrigin,
                path = devicePath,
                method = "POST",
                body = body,
            )

            val readback = JSONObject(
                request(
                    origin = qaOrigin,
                    path = "$MANAGEMENT_PREFIX/devices",
                    method = "GET",
                ),
            )
            val devices = readback.optJSONArray("devices") ?: JSONArray()
            val matched = (0 until devices.length())
                .asSequence()
                .mapNotNull { devices.optJSONObject(it) }
                .firstOrNull { it.optString("device_id") == deviceId }
                ?: error("QA management readback omitted the paired device")
            check(
                matched.optString("status") == "authorized" &&
                    matched.optString("fingerprint") == fingerprint,
            ) { "QA management readback did not authorize the exact paired device" }
        }

        private fun request(
            origin: String,
            path: String,
            method: String,
            body: String? = null,
        ): String {
            val connection = (URL(origin.trimEnd('/') + path).openConnection() as HttpsURLConnection).apply {
                requestMethod = method
                connectTimeout = API_TIMEOUT_MILLIS
                readTimeout = API_TIMEOUT_MILLIS
                instanceFollowRedirects = false
                useCaches = false
                body?.let {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            return try {
                body?.let { payload ->
                    connection.outputStream.use { output -> output.write(payload.encodeToByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                val response = stream?.use { it.readBounded() }.orEmpty()
                check(status == HttpURLConnection.HTTP_OK) {
                    "QA management API returned HTTP $status"
                }
                response
            } finally {
                connection.disconnect()
            }
        }
    }
}

private fun InputStream.readBounded(): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
        check(output.size() <= 64 * 1024) { "QA management response exceeded its bound" }
    }
    return output.toString(Charsets.UTF_8.name())
}
