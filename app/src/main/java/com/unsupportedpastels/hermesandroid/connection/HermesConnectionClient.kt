package com.unsupportedpastels.hermesandroid.connection

import com.unsupportedpastels.hermesandroid.app.DurableSessionId
import com.unsupportedpastels.hermesandroid.app.SessionSummary
import com.unsupportedpastels.hermesandroid.app.validHostFolderName
import com.unsupportedpastels.hermesandroid.app.validProjectWorkspacePath
import com.unsupportedpastels.hermesandroid.gateway.ChatMessage
import com.unsupportedpastels.hermesandroid.gateway.ChatMessageRole
import com.unsupportedpastels.hermesandroid.gateway.CronJob
import com.unsupportedpastels.hermesandroid.gateway.CronJobRun
import com.unsupportedpastels.hermesandroid.gateway.CronJobScope
import com.unsupportedpastels.hermesandroid.gateway.parseCronJob
import com.unsupportedpastels.hermesandroid.gateway.parseCronJobRuns
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryEntry
import com.unsupportedpastels.hermesandroid.gateway.HostDirectoryListing
import com.unsupportedpastels.hermesandroid.gateway.ModelOptions
import com.unsupportedpastels.hermesandroid.gateway.ModelProviderOption
import com.unsupportedpastels.hermesandroid.gateway.ModelSelection
import com.unsupportedpastels.hermesandroid.gateway.ModelSwitchResult
import com.unsupportedpastels.hermesandroid.gateway.CurrentModelInfo
import com.unsupportedpastels.hermesandroid.gateway.ModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.parseExplicitModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.parseModelCapabilities
import com.unsupportedpastels.hermesandroid.gateway.OperationalStatus
import com.unsupportedpastels.hermesandroid.gateway.parseOperationalStatus
import com.unsupportedpastels.hermesandroid.files.HostFileContent
import com.unsupportedpastels.hermesandroid.files.HostFileEntry
import com.unsupportedpastels.hermesandroid.files.HostFileListing
import com.unsupportedpastels.hermesandroid.files.MAX_HOST_FILE_BYTES
import com.unsupportedpastels.hermesandroid.files.MAX_HOST_FILE_ENTRIES
import com.unsupportedpastels.hermesandroid.files.validCanonicalHostFilePath
import com.unsupportedpastels.hermesandroid.files.validHostFileMimeType
import com.unsupportedpastels.hermesandroid.files.validHostFileName
import com.unsupportedpastels.hermesandroid.voice.ElevenLabsVoice
import com.unsupportedpastels.hermesandroid.voice.SpeechAudio
import com.unsupportedpastels.hermesandroid.voice.decodeAudioDataUrl
import com.unsupportedpastels.hermesandroid.voice.TranscriptionResult
import com.unsupportedpastels.hermesandroid.voice.VoiceAudioTimeouts
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilities
import com.unsupportedpastels.hermesandroid.voice.VoiceCapabilityPolicy
import com.unsupportedpastels.hermesandroid.voice.VoiceServerConfig
import com.unsupportedpastels.hermesandroid.voice.audioRequestTimeout
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.Base64

@Serializable
private data class HermesStatusResponse(
    val version: String? = null,
    @SerialName("auth_required") val authRequired: Boolean,
    @SerialName("auth_flows") val authFlows: List<String> = emptyList(),
)

@Serializable
data class HermesAuthProvider(
    val name: String,
    @SerialName("display_name") val displayName: String = name,
    @SerialName("supports_password") val supportsPassword: Boolean = false,
)

@Serializable
private data class HermesAuthProvidersResponse(
    val providers: List<HermesAuthProvider>,
)

@Serializable
private data class HermesAuthenticatedUser(
    @SerialName("user_id") val userId: String = "",
)

@Serializable
private data class HermesSessionRow(
    @SerialName("session_key") val sessionKey: String? = null,
    val id: String? = null,
    val title: String? = null,
    val preview: String? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    val model: String? = null,
    @SerialName("billing_provider") val billingProvider: String? = null,
    val provider: String? = null,
    val profile: String? = null,
    val cwd: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
)

@Serializable
private data class HermesSessionsResponse(
    val sessions: List<HermesSessionRow>,
    val total: Int? = null,
    val limit: Int? = null,
    val offset: Int? = null,
)

@Serializable
private data class HermesSessionSearchResponse(
    val results: List<HermesSessionSearchRow> = emptyList(),
)

@Serializable
private data class HermesSessionSearchRow(
    @SerialName("session_id") val sessionId: String? = null,
    val id: String? = null,
    val title: String? = null,
    val snippet: String? = null,
    val role: String? = null,
)

data class SessionSearchResult(
    val sessionId: DurableSessionId,
    val title: String,
    val snippet: String,
    val role: String? = null,
)

data class SessionPage(
    val sessions: List<SessionSummary>,
    val total: Int? = null,
    val limit: Int,
    val offset: Int,
)

@Serializable
private data class ProfilesResponse(
    val profiles: List<JsonObject> = emptyList(),
)

@Serializable
private data class DefaultModelSetResponse(
    val ok: Boolean = false,
    @SerialName("confirm_required") val confirmRequired: Boolean = false,
    @SerialName("confirm_message") val confirmMessage: String? = null,
)

@Serializable
private data class HermesManagedFileEntry(
    val name: String,
    val path: String,
    @SerialName("is_directory") val isDirectory: Boolean,
)

@Serializable
private data class HermesManagedFilesResponse(
    val path: String,
    val parent: String? = null,
    val entries: List<HermesManagedFileEntry>,
    val root: String? = null,
    @SerialName("locked_root") val lockedRoot: String? = null,
    @SerialName("can_change_path") val canChangePath: Boolean = true,
)

@Serializable
private data class HermesManagedDirectoryCreateRequest(
    val path: String,
)

@Serializable
private data class HermesManagedDirectoryCreateResponse(
    val ok: Boolean,
    val path: String,
)

@Serializable
private data class HermesTranscriptResponse(
    val messages: List<JsonObject> = emptyList(),
    val data: List<JsonObject> = emptyList(),
)

@Serializable
data class SessionUpdateResult(
    val ok: Boolean,
    val title: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
)

@Serializable
private data class SessionUpdateRequest(
    val title: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    val profile: String? = null,
)

@Serializable
private data class BulkDeleteSessionsRequest(
    val ids: List<String>,
    val profile: String? = null,
)

@Serializable
private data class BulkDeleteSessionsResponse(
    val ok: Boolean = false,
    val deleted: Int? = null,
)

data class BulkDeleteResult(
    val ok: Boolean,
    val deleted: Int,
)

enum class SessionBulkDeleteCapability {
    Unknown,
    Supported,
    Unsupported,
}

data class HermesConnectionInfo(
    val version: String?,
    val authRequired: Boolean,
    val nativeOAuthSupported: Boolean,
    val providers: List<HermesAuthProvider>,
    val sessions: List<SessionSummary> = emptyList(),
)

data class AuthenticatedHermesConnection(
    val userId: String,
    val sessions: List<SessionSummary>,
)

open class HermesConnectionException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class HermesSessionBulkDeleteUnsupportedException(
    val statusCode: Int,
) : HermesConnectionException("Bulk session deletion is not supported by this Hermes server")

/**
 * A credential-bearing protected REST request was rejected with HTTP 401, so the
 * attached credential is no longer accepted. Released Hermes returns `401` for a
 * missing or stale dashboard session token; `403` means the request authorized
 * successfully but the operation is forbidden by resource/policy rules, so it must
 * never reach this type.
 */
open class HermesAuthenticationRejectedException(
    message: String,
) : HermesConnectionException(message)

/**
 * A mid-session protected REST request returned HTTP 401. This refines
 * [HermesAuthenticationRejectedException] for paths where a live OAuth refresh
 * token can heal the rejection by reconnecting rather than forcing a fresh
 * sign-in. Loopback recovery treats it as any other credential rejection.
 */
class HermesUnauthorizedException(
    message: String = "Hermes request returned HTTP 401",
) : HermesAuthenticationRejectedException(message)

enum class CronRestEndpoint {
    Trigger,
    Runs,
}

/** A released server explicitly rejected an audited cron REST route. */
class HermesCronRestUnsupportedException(
    val endpoint: CronRestEndpoint,
    val statusCode: Int,
) : HermesConnectionException("Cron ${endpoint.name.lowercase()} endpoint is not supported")

/** An older client implementation has no cron REST method at all. */
class HermesCronRestLegacyUnsupportedException(
    val endpoint: CronRestEndpoint,
) : HermesConnectionException("Cron ${endpoint.name.lowercase()} endpoint is not available")

/** HTTP 409 means another scheduler owns this run; it is not a retryable transport failure. */
class HermesCronJobClaimedException(
    val jobId: String,
) : HermesConnectionException("Cron job is already running or was claimed by another scheduler")

internal class HermesResponseBodyTooLargeException :
    HermesConnectionException("Hermes response body was too large")

private const val MAX_RESPONSE_BODY_BYTES = 64 * 1024
private const val MAX_TRANSCRIPT_BODY_BYTES = 1024 * 1024
// Base64 TTS audio for a finalized message can exceed the 1 MiB transcript cap.
private const val MAX_SPEECH_RESPONSE_BODY_BYTES = 8 * 1024 * 1024
private const val MAX_CRON_RESPONSE_BODY_BYTES = 128 * 1024
private const val MAX_ELEVENLABS_VOICES = 200
private const val MAX_MODEL_OPTIONS_RESPONSE_BODY_BYTES = 1024 * 1024
private const val MAX_TRANSCRIPT_REASONING_CHARS = 1024 * 1024
private val TRANSCRIPT_PAGE_LIMITS = intArrayOf(100, 50, 25, 10, 5, 1)
private const val MAX_DURABLE_SESSIONS = 20
private const val MAX_SESSION_PAGE_SIZE = 500
private const val MAX_HOST_DIRECTORY_ENTRIES = 500
internal const val MAX_CRON_RUNS = 20
private const val MAX_MANAGED_IMAGE_BYTES = 10 * 1024 * 1024
internal const val MAX_EFFECTIVE_CONTEXT_LENGTH = 100_000_000
private const val MAX_HOST_FILE_LISTING_BODY_BYTES = 512 * 1024
private const val MAX_HOST_FILE_READ_BODY_BYTES = 1024 * 1024

internal fun HttpClientConfig<*>.configureHermesHttpClient() {
    followRedirects = false
    // Installed with no defaults so ordinary chat/REST requests keep their
    // engine-level timeout behaviour. Long-running `/api/audio/…` calls opt into
    // extended windows per-request via HttpRequestBuilder.audioRequestTimeout().
    install(HttpTimeout)
}

internal suspend fun HttpResponse.readBodyTextBounded(
    maxBytes: Int = MAX_RESPONSE_BODY_BYTES,
): String {
    require(maxBytes in 1..MAX_SPEECH_RESPONSE_BODY_BYTES)
    val channel = bodyAsChannel()
    return try {
        val source = channel.readRemaining(maxBytes + 1L)
        try {
            val bytes = ByteArray(maxBytes + 1)
            var count = 0
            while (!source.exhausted()) {
                val read = source.readAtMostTo(bytes, count, bytes.size)
                if (read <= 0) break
                count += read
                if (count > maxBytes) {
                    throw HermesResponseBodyTooLargeException()
                }
            }
            if (count > maxBytes) {
                throw HermesResponseBodyTooLargeException()
            }
            String(bytes, 0, count, Charsets.UTF_8)
        } finally {
            source.close()
        }
    } finally {
        channel.cancel(null)
    }
}

/**
 * Classifies a credential-bearing protected response. Only HTTP 401 means the
 * credential itself was rejected. HTTP 403 is an authorized-but-forbidden policy
 * denial (unreadable file, path outside a managed root, sensitive path, unwritable
 * directory, Host/Origin restriction) and must never invalidate a credential,
 * trigger a rebootstrap, or be retried automatically. Public routes are called
 * without a credential and are therefore never classified here.
 */
private fun HttpResponse.throwIfHermesCredentialRejected(credential: HermesCredential) {
    if (credential != HermesCredential.None && status.value == 401) {
        throw HermesAuthenticationRejectedException("Hermes credential was rejected with HTTP 401")
    }
}

interface HermesConnectionClient {
    suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo

    /** Public-only tunnel probe; never attempts the protected sessions route. */
    suspend fun probeExternalTunnel(serverOrigin: ServerOrigin): HermesConnectionInfo = probe(serverOrigin)

    /** Public, profile-scoped operational status; this never uses `/api/system/stats`. */
    suspend fun loadOperationalStatus(
        serverOrigin: ServerOrigin,
        profile: String,
    ): OperationalStatus = throw UnsupportedOperationException()

    /**
     * Fail-closed probe of the `/api/audio/…` route family. Returns
     * [VoiceCapabilities.NONE] when the routes are absent (older server) or the
     * probe fails, so every voice affordance hides rather than errors.
     */
    suspend fun probeVoiceCapabilities(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): VoiceCapabilities = VoiceCapabilities.NONE

    /**
     * Read the server-authoritative `voice` config section. Falls back to
     * [VoiceServerConfig.DEFAULT] on any transport or shape error.
     */
    suspend fun loadVoiceServerConfig(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): VoiceServerConfig = VoiceServerConfig.DEFAULT

    /**
     * Transcribe a recorded utterance via `POST /api/audio/transcribe`. [dataUrl]
     * must be a `data:<audio-mime>;base64,<...>` URL. A blank transcript means the
     * server detected silence — a normal result, not an error.
     */
    suspend fun transcribeAudio(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        dataUrl: String,
        mimeType: String?,
    ): TranscriptionResult = throw UnsupportedOperationException()

    /**
     * Synthesize [text] to speech via `POST /api/audio/speak`, returning decoded
     * audio bytes ready for playback. This is the REST fallback for read-aloud;
     * the streaming path is `/api/audio/speak-stream`.
     */
    suspend fun speakText(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        text: String,
    ): SpeechAudio = throw UnsupportedOperationException()

    /**
     * Deep-merge [config] into the server's profile-scoped config via
     * `PUT /api/config` (the released server merges incoming over disk, so only
     * the changed nested fields are sent). Returns true on success.
     */
    suspend fun updateServerConfig(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        config: JsonObject,
    ): Boolean = throw UnsupportedOperationException()

    /**
     * List configured ElevenLabs voices for the picker. Empty when the route
     * reports `available:false` or the response is malformed.
     */
    suspend fun listElevenLabsVoices(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): List<ElevenLabsVoice> = emptyList()

    suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): AuthenticatedHermesConnection = throw UnsupportedOperationException()

    /**
     * Reload only the selected profile's durable rows after a scoped mutation.
     * [archivedOnly] maps to the official `archived=only` query so an archived
     * filter can actually see archived rows instead of an always-excluded list.
     */
    suspend fun loadSessionsForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        archivedOnly: Boolean = false,
    ): List<SessionSummary> = authenticate(serverOrigin, credential).sessions

    suspend fun loadSessionsPageForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        limit: Int = MAX_DURABLE_SESSIONS,
        offset: Int = 0,
        archivedOnly: Boolean = false,
    ): SessionPage = SessionPage(
        sessions = if (credential != HermesCredential.None) {
            loadSessionsForProfile(
                serverOrigin = serverOrigin,
                credential = credential,
                profile = profile,
                archivedOnly = archivedOnly,
            )
        } else {
            emptyList()
        },
        total = null,
        limit = limit,
        offset = offset,
    )

    suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ): List<ChatMessage> = throw UnsupportedOperationException()

    suspend fun loadHostDirectories(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String? = null,
    ): HostDirectoryListing = throw UnsupportedOperationException()

    suspend fun loadHostFiles(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String? = null,
    ): HostFileListing = throw UnsupportedOperationException()

    suspend fun readManagedFile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): HostFileContent = throw UnsupportedOperationException()

    suspend fun downloadManagedFile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): HostFileContent = throw UnsupportedOperationException()

    suspend fun streamManagedFile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): HostFileContent = throw UnsupportedOperationException()

    suspend fun createHostDirectory(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        parentPath: String,
        name: String,
    ): HostDirectoryListing = throw UnsupportedOperationException()

    suspend fun downloadManagedImage(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): ByteArray = throw UnsupportedOperationException()

    suspend fun updateSession(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
        profile: String? = null,
        title: String? = null,
        archived: Boolean? = null,
        pinned: Boolean? = null,
    ): SessionUpdateResult = throw UnsupportedOperationException()

    suspend fun deleteSession(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
        profile: String? = null,
    ): Unit = throw UnsupportedOperationException()

    suspend fun bulkDeleteSessions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionIds: Collection<DurableSessionId>,
        profile: String? = null,
    ): BulkDeleteResult = throw UnsupportedOperationException()

    suspend fun searchSessions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        query: String,
        profile: String? = null,
    ): List<SessionSearchResult> = throw UnsupportedOperationException()

    suspend fun loadProfiles(serverOrigin: ServerOrigin, credential: HermesCredential): List<String> =
        throw UnsupportedOperationException()

    suspend fun loadDefaultModelOptions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): ModelOptions = throw UnsupportedOperationException()

    suspend fun loadCurrentModelInfo(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): CurrentModelInfo = throw UnsupportedOperationException()

    suspend fun loadProfileReasoningEffort(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        provider: String,
        model: String,
    ): String? = null

    /** Load the profile-wide reasoning default without applying a model override. */
    suspend fun loadProfileReasoningDefault(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): String? = null

    /**
     * Load every per-model reasoning override configured for [profile], resolved
     * against the models in [options] so the picker can show each model's current
     * effort. Keys are matched spelling-tolerantly (dots↔dashes, provider prefix).
     */
    suspend fun loadProfileReasoningOverrides(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        options: ModelOptions,
    ): Map<ModelSelection, String> = emptyMap()

    suspend fun setProfileReasoningEffort(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        effort: String,
    ): Unit = throw UnsupportedOperationException()

    /**
     * Set (or clear) a per-model reasoning-effort override for future chats.
     * Writes `agent.reasoning_overrides.<provider/model>` via the deep-merging
     * `PUT /api/config`, so it preserves the global default and every other
     * model's override. Passing effort "none" disables thinking for that model.
     */
    suspend fun setProfileModelReasoningOverride(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        selection: ModelSelection,
        effort: String,
    ): Unit = throw UnsupportedOperationException()

    suspend fun setDefaultModel(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        selection: ModelSelection,
        confirmExpensiveModel: Boolean = false,
    ): ModelSwitchResult = throw UnsupportedOperationException()

    suspend fun triggerCronJob(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        jobId: String,
    ): CronJob = throw HermesCronRestLegacyUnsupportedException(CronRestEndpoint.Trigger)

    suspend fun loadCronJobRuns(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        jobId: String,
        limit: Int = MAX_CRON_RUNS,
    ): List<CronJobRun> = throw HermesCronRestLegacyUnsupportedException(CronRestEndpoint.Runs)
}

internal suspend fun authenticatedConnectionConcurrently(
    loadUser: suspend () -> String,
    loadSessions: suspend () -> List<SessionSummary>,
): AuthenticatedHermesConnection = coroutineScope {
    val user = async { loadUser() }
    val sessions = async { loadSessions() }
    AuthenticatedHermesConnection(user.await(), sessions.await())
}

class HttpHermesConnectionClient(
    private val client: HttpClient,
) : HermesConnectionClient {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun probeVoiceCapabilities(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): VoiceCapabilities = try {
        val response = client.get("${serverOrigin.value}/api/audio/elevenlabs/voices") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            VoiceCapabilityPolicy.fromVoicesProbe(response.status.value, elevenLabsAvailable = false)
        } else {
            val available = (json.parseToJsonElement(body) as? JsonObject)
                ?.get("available")?.jsonPrimitive?.booleanOrNull ?: false
            VoiceCapabilityPolicy.fromVoicesProbe(response.status.value, elevenLabsAvailable = available)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: HermesAuthenticationRejectedException) {
        // Fail-closed fallbacks must not hide a rejected credential: the caller
        // needs the rejection type to refresh the loopback session and retry.
        throw rejected
    } catch (_: Exception) {
        VoiceCapabilities.NONE
    }

    override suspend fun loadVoiceServerConfig(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): VoiceServerConfig = try {
        val response = client.get("${serverOrigin.value}/api/config") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            VoiceServerConfig.DEFAULT
        } else {
            VoiceServerConfig.fromConfigRoot(json.parseToJsonElement(body) as? JsonObject)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (rejected: HermesAuthenticationRejectedException) {
        throw rejected
    } catch (_: Exception) {
        VoiceServerConfig.DEFAULT
    }

    override suspend fun transcribeAudio(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        dataUrl: String,
        mimeType: String?,
    ): TranscriptionResult {
        val response = client.post("${serverOrigin.value}/api/audio/transcribe") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("data_url", dataUrl)
                    mimeType?.takeIf { it.isNotBlank() }?.let { put("mime_type", it) }
                }.toString(),
            )
            audioRequestTimeout(VoiceAudioTimeouts.TRANSCRIBE_REQUEST_MILLIS)
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException(
                "Hermes transcription returned HTTP ${response.status.value}",
            )
        }
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Hermes transcription response was invalid")
        return TranscriptionResult(
            transcript = root["transcript"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
            provider = root["provider"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun speakText(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        text: String,
    ): SpeechAudio {
        val response = client.post("${serverOrigin.value}/api/audio/speak") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("text", text) }.toString())
            audioRequestTimeout(VoiceAudioTimeouts.SPEAK_REQUEST_MILLIS)
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded(MAX_SPEECH_RESPONSE_BODY_BYTES)
        if (!response.status.isSuccess()) {
            throw HermesConnectionException(
                "Hermes speech synthesis returned HTTP ${response.status.value}",
            )
        }
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Hermes speech response was invalid")
        val dataUrl = root["data_url"]?.jsonPrimitive?.contentOrNull
            ?: throw HermesConnectionException("Hermes speech response had no audio")
        return decodeAudioDataUrl(dataUrl)
            ?: throw HermesConnectionException("Hermes speech audio was not decodable")
    }

    override suspend fun updateServerConfig(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        config: JsonObject,
    ): Boolean {
        val response = client.put("${serverOrigin.value}/api/config") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("config", config) }.toString())
        }
        response.throwIfHermesCredentialRejected(credential)
        if (!response.status.isSuccess()) return false
        val body = response.readBodyTextBounded()
        return (json.parseToJsonElement(body) as? JsonObject)
            ?.get("ok")?.jsonPrimitive?.booleanOrNull == true
    }

    override suspend fun listElevenLabsVoices(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): List<ElevenLabsVoice> {
        val response = client.get("${serverOrigin.value}/api/audio/elevenlabs/voices") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
        }
        response.throwIfHermesCredentialRejected(credential)
        if (!response.status.isSuccess()) return emptyList()
        val body = response.readBodyTextBounded(MAX_TRANSCRIPT_BODY_BYTES)
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        if (root["available"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
        val voices = root["voices"] as? JsonArray ?: return emptyList()
        return voices.mapNotNull { element ->
            val voice = element as? JsonObject ?: return@mapNotNull null
            val id = voice["voice_id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf(String::isNotBlank)?.take(128) ?: return@mapNotNull null
            ElevenLabsVoice(
                voiceId = id,
                name = voice["name"]?.jsonPrimitive?.contentOrNull.orEmpty().take(128),
                label = voice["label"]?.jsonPrimitive?.contentOrNull.orEmpty().take(256),
            )
        }.take(MAX_ELEVENLABS_VOICES)
    }

    override suspend fun updateSession(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
        profile: String?,
        title: String?,
        archived: Boolean?,
        pinned: Boolean?,
    ): SessionUpdateResult {
        require(title != null || archived != null || pinned != null) { "Session update is empty" }
        val response = client.patch(
            "${serverOrigin.value}/api/sessions/${durableSessionId.value.encodeURLPathPart()}",
        ) {
            applyHermesCredential(credential, serverOrigin)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SessionUpdateRequest(
                        title = title?.take(512),
                        archived = archived,
                        pinned = pinned,
                        profile = profile?.takeIf { it != "default" },
                    ),
                ),
            )
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes session update returned HTTP ${response.status.value}")
        }
        return json.decodeFromString<SessionUpdateResult>(body)
    }

    override suspend fun deleteSession(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
        profile: String?,
    ) {
        val response = client.delete(
            "${serverOrigin.value}/api/sessions/${durableSessionId.value.encodeURLPathPart()}",
        ) {
            applyHermesCredential(credential, serverOrigin)
            profile?.takeIf { it != "default" }?.let { parameter("profile", it) }
        }
        response.readBodyTextBounded()
        response.throwIfHermesCredentialRejected(credential)
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes session deletion returned HTTP ${response.status.value}")
        }
    }

    override suspend fun bulkDeleteSessions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionIds: Collection<DurableSessionId>,
        profile: String?,
    ): BulkDeleteResult = try {
        val ids = durableSessionIds.map { sessionId ->
            sessionId.value.takeIf { it.isNotBlank() && it.length <= 256 }
                ?: throw IllegalArgumentException("Session ID is invalid")
        }.distinct()
        require(ids.isNotEmpty()) { "Bulk session deletion requires at least one ID" }
        require(ids.size <= 500) { "Bulk session deletion is limited to 500 sessions" }
        val boundedProfile = profile?.trim()?.takeIf { it.isNotEmpty() && it.length <= 64 }
        if (profile != null && boundedProfile == null) {
            throw IllegalArgumentException("Session profile is invalid")
        }
        val response = client.post("${serverOrigin.value}/api/sessions/bulk-delete") {
            applyHermesCredential(credential, serverOrigin)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    BulkDeleteSessionsRequest(
                        ids = ids,
                        profile = boundedProfile?.takeIf { it != "default" },
                    ),
                ),
            )
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (response.status.value == 404 || response.status.value == 405) {
            throw HermesSessionBulkDeleteUnsupportedException(response.status.value)
        }
        if (!response.status.isSuccess()) {
            throw HermesConnectionException(
                "Hermes bulk session deletion returned HTTP ${response.status.value}",
            )
        }
        parseBulkDeleteResponse(body, ids.size)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not bulk delete sessions", error)
    }

    override suspend fun searchSessions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        query: String,
        profile: String?,
    ): List<SessionSearchResult> {
        val boundedQuery = query.trim().take(256)
        if (boundedQuery.isEmpty()) return emptyList()
        val response = client.get("${serverOrigin.value}/api/sessions/search") {
            applyHermesCredential(credential, serverOrigin)
            parameter("q", boundedQuery)
            parameter("limit", 20)
            profile?.let { parameter("profile", it) }
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes session search returned HTTP ${response.status.value}")
        }
        return json.decodeFromString<HermesSessionSearchResponse>(body).results
            .mapNotNull { row ->
                val id = row.sessionId?.takeIf(String::isNotBlank)
                    ?: row.id?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                SessionSearchResult(
                    sessionId = DurableSessionId(id.take(256)),
                    title = row.title?.takeIf(String::isNotBlank)?.take(512) ?: "Untitled session",
                    snippet = row.snippet.orEmpty().take(1_000),
                    role = row.role?.takeIf(String::isNotBlank)?.take(32),
                )
            }
            .distinctBy(SessionSearchResult::sessionId)
            .take(20)
    }

    override suspend fun loadProfiles(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): List<String> {
        val response = client.get("${serverOrigin.value}/api/profiles") {
            applyHermesCredential(credential, serverOrigin)
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes profiles returned HTTP ${response.status.value}")
        }
        return json.decodeFromString<ProfilesResponse>(body).profiles
            .mapNotNull { row ->
                row["name"]?.jsonPrimitive?.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it.length <= 64 }
            }
            .distinct()
            .take(32)
    }

    override suspend fun loadDefaultModelOptions(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): ModelOptions {
        val boundedProfile = profile.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw HermesConnectionException("Hermes model options profile is invalid")
        val response = client.get("${serverOrigin.value}/api/model/options") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", boundedProfile)
            parameter("explicit_only", 1)
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded(MAX_MODEL_OPTIONS_RESPONSE_BODY_BYTES)
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes model options returned HTTP ${response.status.value}")
        }
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Hermes model options response was invalid")
        val provider = root["provider"]?.jsonPrimitive?.contentOrNull
        val model = root["model"]?.jsonPrimitive?.contentOrNull
        val providers = (root["providers"] as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val slug = row["slug"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            if (row["authenticated"]?.jsonPrimitive?.contentOrNull == "false" && slug != provider) {
                return@mapNotNull null
            }
            val name = row["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) ?: slug
            val models = (row["models"] as? JsonArray).orEmpty().mapNotNull { model ->
                model.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)?.take(256)
            }.distinct().take(200)
            if (models.isEmpty()) null else ModelProviderOption(
                slug = slug.take(64),
                name = name.take(128),
                models = models,
                capabilities = parseModelCapabilities(row["capabilities"]),
            )
        }.take(32)
        return ModelOptions(
            current = if (!provider.isNullOrBlank() && !model.isNullOrBlank()) {
                ModelSelection(provider.take(64), model.take(256))
            } else null,
            providers = providers,
            profile = boundedProfile,
        )
    }

    override suspend fun loadCurrentModelInfo(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): CurrentModelInfo {
        val boundedProfile = profile.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw HermesConnectionException("Hermes model info profile is invalid")
        val response = client.get("${serverOrigin.value}/api/model/info") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", boundedProfile)
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes model info returned HTTP ${response.status.value}")
        }
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Hermes model info response was invalid")
        val responseProfile = root["profile"]?.jsonPrimitive?.contentOrNull
            ?.trim()?.takeIf(String::isNotEmpty)
        if (responseProfile != null && responseProfile != boundedProfile) {
            throw HermesConnectionException("Hermes model info returned the wrong profile")
        }
        val provider = boundedModelInfoField(root["provider"], 128)
        val model = boundedModelInfoField(root["model"], 512)
        val contextLength = root["effective_context_length"]?.jsonPrimitive?.longOrNull
            ?.takeIf { it in 1..MAX_EFFECTIVE_CONTEXT_LENGTH }
            ?.toInt()
        return CurrentModelInfo(
            profile = boundedProfile,
            model = model,
            provider = provider,
            effectiveContextLength = contextLength,
            capabilities = parseExplicitModelCapabilities(root["capabilities"]),
        )
    }

    override suspend fun loadProfileReasoningEffort(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        provider: String,
        model: String,
    ): String? {
        val response = client.get("${serverOrigin.value}/api/config") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes config returned HTTP ${response.status.value}")
        }
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Hermes config response was invalid")
        val agent = root["agent"] as? JsonObject ?: return null
        val overrides = agent["reasoning_overrides"] as? JsonObject
        val bareModelVariants = reasoningBareModelVariants(model)
        val normalizedProvider = provider.trim().lowercase()
        val qualifiedVariants = bareModelVariants.mapTo(mutableSetOf()) {
            "$normalizedProvider/$it"
        }
        val normalizedModel = model.trim().lowercase()
        if (normalizedModel.startsWith("$normalizedProvider/")) {
            qualifiedVariants += normalizedModel
        }
        fun matchingOverride(keys: Set<String>): String? = overrides
            ?.entries
            ?.firstOrNull { (key, _) -> key.trim().lowercase() in keys }
            ?.value
            ?.jsonPrimitive
            ?.contentOrNull
        val override = matchingOverride(qualifiedVariants)
            ?: matchingOverride(bareModelVariants)
        return canonicalProfileReasoningEffort(override)
            ?: canonicalProfileReasoningEffort(
                agent["reasoning_effort"]?.jsonPrimitive?.contentOrNull,
            )
    }

    override suspend fun loadProfileReasoningDefault(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
    ): String? {
        val response = client.get("${serverOrigin.value}/api/config") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes config returned HTTP ${response.status.value}")
        }
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Hermes config response was invalid")
        val agent = root["agent"] as? JsonObject ?: return null
        return canonicalProfileReasoningEffort(
            agent["reasoning_effort"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override suspend fun loadProfileReasoningOverrides(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        options: ModelOptions,
    ): Map<ModelSelection, String> {
        val response = client.get("${serverOrigin.value}/api/config") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes config returned HTTP ${response.status.value}")
        }
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Hermes config response was invalid")
        val agent = root["agent"] as? JsonObject ?: return emptyMap()
        val overrides = agent["reasoning_overrides"] as? JsonObject ?: return emptyMap()
        val overrideKeys = overrides.entries.associate { (key, value) ->
            key.trim().lowercase() to value.jsonPrimitive.contentOrNull
        }
        val result = LinkedHashMap<ModelSelection, String>()
        options.providers.forEach { provider ->
            provider.models.forEach { model ->
                // The server keys overrides off the model string itself (which
                // already carries its own provider prefix, e.g.
                // "anthropic/claude-opus-5"). For bare catalog IDs, also accept
                // the provider-qualified spelling used by existing configs.
                val normalizedModel = model.trim().lowercase()
                val bareVariants = reasoningBareModelVariants(model)
                val providerQualifiedVariants = bareVariants.map { bare ->
                    "${provider.slug.trim().lowercase()}/$bare"
                }
                val candidateKeys = listOf(normalizedModel) + providerQualifiedVariants + bareVariants
                val raw = candidateKeys.distinct().firstNotNullOfOrNull { key -> overrideKeys[key] }
                val canonical = canonicalProfileReasoningEffort(raw)
                if (canonical != null) {
                    result[ModelSelection(provider.slug, model)] = canonical
                }
            }
        }
        return result
    }

    override suspend fun setProfileReasoningEffort(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        effort: String,
    ) {
        val boundedProfile = profile.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw HermesConnectionException("Hermes reasoning profile is invalid")
        val canonicalEffort = canonicalProfileReasoningEffort(effort)
            ?: throw HermesConnectionException("Hermes reasoning effort is invalid")
        val response = client.put("${serverOrigin.value}/api/config") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", boundedProfile)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("config", buildJsonObject {
                    put("agent", buildJsonObject {
                        put("reasoning_effort", canonicalEffort)
                    })
                })
            }.toString())
        }
        response.readBodyTextBounded()
        response.throwIfHermesCredentialRejected(credential)
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes reasoning default update returned HTTP ${response.status.value}")
        }
    }

    override suspend fun setProfileModelReasoningOverride(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        selection: ModelSelection,
        effort: String,
    ) {
        val boundedProfile = profile.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw HermesConnectionException("Hermes reasoning profile is invalid")
        val canonicalEffort = canonicalProfileReasoningEffort(effort)
            ?: throw HermesConnectionException("Hermes reasoning effort is invalid")
        val model = selection.model.trim().takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: throw HermesConnectionException("Hermes reasoning model is invalid")
        // The server keys reasoning_overrides off the MODEL string itself
        // (resolve_per_model_reasoning_effort / _canonical_model_variants), not
        // provider/model — the model already carries its own provider prefix
        // (e.g. "anthropic/claude-opus-5"); the selection's provider is the
        // portal slug (e.g. "nous") and must not be prepended.
        val overrideKey = model
        val response = client.put("${serverOrigin.value}/api/config") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", boundedProfile)
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("config", buildJsonObject {
                    put("agent", buildJsonObject {
                        put("reasoning_overrides", buildJsonObject {
                            put(overrideKey, canonicalEffort)
                        })
                    })
                })
            }.toString())
        }
        response.readBodyTextBounded()
        response.throwIfHermesCredentialRejected(credential)
        if (!response.status.isSuccess()) {
            throw HermesConnectionException(
                "Hermes reasoning override update returned HTTP ${response.status.value}",
            )
        }
    }

    override suspend fun setDefaultModel(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        selection: ModelSelection,
        confirmExpensiveModel: Boolean,
    ): ModelSwitchResult {
        val response = client.post("${serverOrigin.value}/api/model/set") {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", profile.take(64))
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("scope", "main")
                put("provider", selection.provider.take(64))
                put("model", selection.model.take(256))
                put("confirm_expensive_model", confirmExpensiveModel)
            }.toString())
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException("Hermes default model update returned HTTP ${response.status.value}")
        }
        val result = json.decodeFromString<DefaultModelSetResponse>(body)
        return ModelSwitchResult(
            accepted = result.ok,
            confirmationRequired = result.confirmRequired,
            confirmationMessage = result.confirmMessage?.take(1_000),
        )
    }

    override suspend fun triggerCronJob(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        jobId: String,
    ): CronJob = try {
        val boundedProfile = boundedCronProfile(profile)
        val boundedJobId = boundedCronJobId(jobId)
        val response = client.post(
            "${serverOrigin.value}/api/cron/jobs/${boundedJobId.encodeURLPathPart()}/trigger",
        ) {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", boundedProfile)
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded(MAX_CRON_RESPONSE_BODY_BYTES)
        ensureCronRestSuccess(response, CronRestEndpoint.Trigger, boundedJobId)
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Cron trigger response was invalid")
        parseCronJob(root)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not trigger cron job", error)
    }

    override suspend fun loadCronJobRuns(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        jobId: String,
        limit: Int,
    ): List<CronJobRun> = try {
        val boundedProfile = boundedCronProfile(profile)
        val boundedJobId = boundedCronJobId(jobId)
        val boundedLimit = limit.coerceIn(1, MAX_CRON_RUNS)
        val response = client.get(
            "${serverOrigin.value}/api/cron/jobs/${boundedJobId.encodeURLPathPart()}/runs",
        ) {
            applyHermesCredential(credential, serverOrigin)
            parameter("profile", boundedProfile)
            parameter("limit", boundedLimit)
        }
        response.throwIfHermesCredentialRejected(credential)
        val body = response.readBodyTextBounded(MAX_CRON_RESPONSE_BODY_BYTES)
        ensureCronRestSuccess(response, CronRestEndpoint.Runs, boundedJobId)
        val root = json.parseToJsonElement(body) as? JsonObject
            ?: throw HermesConnectionException("Cron runs response was invalid")
        parseCronJobRuns(
            result = root,
            scope = CronJobScope(serverOrigin.value, boundedProfile, boundedJobId),
            limit = boundedLimit,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not load cron job runs", error)
    }

    private fun ensureCronRestSuccess(
        response: HttpResponse,
        endpoint: CronRestEndpoint,
        jobId: String,
    ) {
        if (response.status.isSuccess()) return
        when (response.status.value) {
            404, 405 -> throw HermesCronRestUnsupportedException(endpoint, response.status.value)
            409 -> if (endpoint == CronRestEndpoint.Trigger) {
                throw HermesCronJobClaimedException(jobId)
            } else {
                throw HermesConnectionException("Cron runs request was rejected")
            }
            else -> throw HermesConnectionException(
                "Cron ${endpoint.name.lowercase()} request returned HTTP ${response.status.value}",
            )
        }
    }

    private fun boundedCronProfile(profile: String): String =
        profile.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw HermesConnectionException("Cron profile is invalid")

    private fun boundedCronJobId(jobId: String): String =
        jobId.trim().takeIf { it.isNotEmpty() && it.length <= 256 }
            ?: throw HermesConnectionException("Cron job ID is invalid")

    override suspend fun loadOperationalStatus(
        serverOrigin: ServerOrigin,
        profile: String,
    ): OperationalStatus = try {
        val boundedProfile = profile.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw HermesConnectionException("Operational status profile is invalid")
        val response = client.get("${serverOrigin.value}/api/status") {
            parameter("profile", boundedProfile)
        }
        val body = response.readBodyTextBounded()
        if (!response.status.isSuccess()) {
            throw HermesConnectionException(
                "Hermes operational status returned HTTP ${response.status.value}",
            )
        }
        parseOperationalStatus(
            Json.parseToJsonElement(body).jsonObject,
            boundedProfile,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not load Hermes operational status", error)
    }

    override suspend fun probe(serverOrigin: ServerOrigin): HermesConnectionInfo = try {
        val statusResponse = client.get("${serverOrigin.value}/api/status")
        if (!statusResponse.status.isSuccess()) {
            statusResponse.readBodyTextBounded()
            throw HermesConnectionException(
                "Hermes status returned HTTP ${statusResponse.status.value}",
            )
        }
        val status = json.decodeFromString<HermesStatusResponse>(
            statusResponse.readBodyTextBounded(),
        )
        val providers = if (status.authRequired) {
            val providersResponse = client.get("${serverOrigin.value}/api/auth/providers")
            if (!providersResponse.status.isSuccess()) {
                providersResponse.readBodyTextBounded()
                throw HermesConnectionException(
                    "Hermes provider discovery returned HTTP ${providersResponse.status.value}",
                )
            }
            json.decodeFromString<HermesAuthProvidersResponse>(
                providersResponse.readBodyTextBounded(),
            ).providers
        } else {
            emptyList()
        }
        val sessions = if (status.authRequired) {
            emptyList()
        } else {
            loadSessionsPage(serverOrigin, HermesCredential.None).sessions
        }
        HermesConnectionInfo(
            version = status.version,
            authRequired = status.authRequired,
            nativeOAuthSupported = "native_pkce" in status.authFlows,
            providers = providers,
            sessions = sessions,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not connect to Hermes Serve", error)
    }

    override suspend fun probeExternalTunnel(serverOrigin: ServerOrigin): HermesConnectionInfo = try {
        val response = client.get("${serverOrigin.value}/api/status")
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            throw HermesConnectionException("Hermes status returned HTTP ${response.status.value}")
        }
        val status = json.decodeFromString<HermesStatusResponse>(response.readBodyTextBounded())
        HermesConnectionInfo(
            version = status.version,
            authRequired = status.authRequired,
            nativeOAuthSupported = "native_pkce" in status.authFlows,
            providers = emptyList(),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not connect to Hermes Serve", error)
    }

    override suspend fun authenticate(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): AuthenticatedHermesConnection = try {
        authenticatedConnectionConcurrently(
            loadUser = { loadAuthenticatedUser(serverOrigin, credential) },
            loadSessions = { loadSessionsPage(serverOrigin, credential).sessions },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException(
            "Hermes authentication failed (${error.javaClass.simpleName})",
            error,
        )
    }

    override suspend fun loadSessionsForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        archivedOnly: Boolean,
    ): List<SessionSummary> = loadSessionsPageForProfile(
        serverOrigin = serverOrigin,
        credential = credential,
        profile = profile,
        archivedOnly = archivedOnly,
    ).sessions

    override suspend fun loadSessionsPageForProfile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String,
        limit: Int,
        offset: Int,
        archivedOnly: Boolean,
    ): SessionPage = loadSessionsPage(
        serverOrigin = serverOrigin,
        credential = credential,
        profile = profile,
        limit = limit,
        offset = offset,
        archivedOnly = archivedOnly,
    )

    private suspend fun loadAuthenticatedUser(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
    ): String {
        val response = client.get("${serverOrigin.value}/api/auth/me") {
            applyHermesCredential(credential, serverOrigin)
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            val message = "Hermes authentication returned HTTP ${response.status.value}"
            if (response.status.value == 401) {
                throw HermesAuthenticationRejectedException(message)
            }
            throw HermesConnectionException(message)
        }
        val user = json.decodeFromString<HermesAuthenticatedUser>(
            response.readBodyTextBounded(),
        )
        if (user.userId.isBlank()) {
            throw HermesConnectionException("Hermes authentication response was incomplete")
        }
        return user.userId
    }

    override suspend fun loadTranscript(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
    ): List<ChatMessage> = try {
        TRANSCRIPT_PAGE_LIMITS.forEachIndexed { index, pageLimit ->
            try {
                return loadTranscriptPage(
                    serverOrigin = serverOrigin,
                    credential = credential,
                    durableSessionId = durableSessionId,
                    pageLimit = pageLimit,
                )
            } catch (error: HermesResponseBodyTooLargeException) {
                if (index == TRANSCRIPT_PAGE_LIMITS.lastIndex) throw error
            }
        }
        throw HermesConnectionException("Could not load Hermes transcript")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not load Hermes transcript", error)
    }

    private suspend fun loadTranscriptPage(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        durableSessionId: DurableSessionId,
        pageLimit: Int,
    ): List<ChatMessage> {
        val encodedId = durableSessionId.value.encodeURLPathPart()
        val response = client.get("${serverOrigin.value}/api/sessions/$encodedId/messages") {
            applyHermesCredential(credential, serverOrigin)
            parameter("limit", pageLimit)
            parameter("order", "latest")
            parameter("profile", "default")
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            // HermesUnauthorizedException is a HermesAuthenticationRejectedException,
            // so loopback recovery treats this as any other credential rejection
            // while the OAuth reconnect path keeps its narrower handling.
            if (response.status.value == 401) {
                throw HermesUnauthorizedException(
                    "Hermes transcript returned HTTP 401",
                )
            }
            throw HermesConnectionException(
                "Hermes transcript returned HTTP ${response.status.value}",
            )
        }
        val decoded = json.decodeFromString<HermesTranscriptResponse>(
            response.readBodyTextBounded(MAX_TRANSCRIPT_BODY_BYTES),
        )
        return (decoded.messages.ifEmpty { decoded.data }).mapNotNull { row ->
            val role = when (row["role"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "user" -> ChatMessageRole.User
                "assistant" -> ChatMessageRole.Assistant
                "system" -> ChatMessageRole.System
                "tool" -> ChatMessageRole.Tool
                else -> return@mapNotNull null
            }
            // Tool rows from the server's transcript projection carry
            // {role, name, context, args?} with no text/content field; combine
            // the name and context preview so tool activity survives reloads
            // instead of being silently dropped.
            val text = when (role) {
                ChatMessageRole.Tool -> row.transcriptToolText()
                else -> row["content"]?.jsonPrimitive?.contentOrNull
                    ?: row["text"]?.jsonPrimitive?.contentOrNull
            }
            val reasoning = if (role == ChatMessageRole.Assistant) {
                row.assistantReasoningText()
            } else {
                null
            }
            if (text == null && reasoning == null) return@mapNotNull null
            ChatMessage(
                role = role,
                text = text.orEmpty(),
                reasoningText = reasoning.orEmpty(),
            )
        }
    }

    override suspend fun loadHostFiles(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String?,
    ): HostFileListing = try {
        val requestedPath = path?.let {
            validCanonicalHostFilePath(it)
                ?: throw HermesConnectionException("Host file path is invalid")
        }
        val response = client.get("${serverOrigin.value}/api/files") {
            applyHermesCredential(credential, serverOrigin)
            requestedPath?.let { parameter("path", it) }
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            response.throwIfHermesCredentialRejected(credential)
            throw HermesConnectionException(
                "Hermes host file listing returned HTTP ${response.status.value}",
            )
        }
        parseHostFileListing(response.readBodyTextBounded(MAX_HOST_FILE_LISTING_BODY_BYTES))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not load host files", error)
    }

    override suspend fun readManagedFile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): HostFileContent = try {
        val canonicalPath = validCanonicalHostFilePath(path)
            ?: throw HermesConnectionException("Host file path is invalid")
        val response = client.get("${serverOrigin.value}/api/files/read") {
            applyHermesCredential(credential, serverOrigin)
            parameter("path", canonicalPath)
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            response.throwIfHermesCredentialRejected(credential)
            throw HermesConnectionException("Hermes host file read returned HTTP ${response.status.value}")
        }
        parseHostFileContent(response.readBodyTextBounded(MAX_HOST_FILE_READ_BODY_BYTES))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not read host file", error)
    }

    override suspend fun downloadManagedFile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): HostFileContent = downloadManagedFileFrom(
        serverOrigin = serverOrigin,
        credential = credential,
        path = path,
        endpoint = "/api/files/download",
    )

    override suspend fun streamManagedFile(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): HostFileContent = downloadManagedFileFrom(
        serverOrigin = serverOrigin,
        credential = credential,
        path = path,
        endpoint = "/api/files/stream",
    )

    private suspend fun downloadManagedFileFrom(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
        endpoint: String,
    ): HostFileContent = try {
        val canonicalPath = validCanonicalHostFilePath(path)
            ?: throw HermesConnectionException("Host file path is invalid")
        val response = client.get("${serverOrigin.value}$endpoint") {
            applyHermesCredential(credential, serverOrigin)
            parameter("path", canonicalPath)
        }
        if (!response.status.isSuccess()) {
            response.bodyAsChannel().cancel(null)
            response.throwIfHermesCredentialRejected(credential)
            throw HermesConnectionException("Hermes host file download returned HTTP ${response.status.value}")
        }
        val mimeType = validHostFileMimeType(
            response.headers[io.ktor.http.HttpHeaders.ContentType]?.substringBefore(';'),
        ) ?: run {
            response.bodyAsChannel().cancel(null)
            throw HermesConnectionException("Hermes host file MIME type was invalid")
        }
        val declaredLength = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.let { value ->
            value.toLongOrNull() ?: throw HermesConnectionException("Hermes host file size was invalid")
        }
        if (declaredLength != null && declaredLength !in 0..MAX_HOST_FILE_BYTES.toLong()) {
            response.bodyAsChannel().cancel(null)
            throw HermesConnectionException("Hermes host file was too large")
        }
        val bytes = response.readBytesBounded(MAX_HOST_FILE_BYTES)
        HostFileContent(
            name = canonicalPath.substringAfterLast('/', canonicalPath).substringAfterLast('\\'),
            path = canonicalPath,
            mimeType = mimeType,
            bytes = bytes,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not download host file", error)
    }

    override suspend fun loadHostDirectories(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String?,
    ): HostDirectoryListing = try {
        val requestedPath = path?.let {
            validProjectWorkspacePath(it)
                ?: throw HermesConnectionException("Host folder path is invalid")
        }
        val response = client.get("${serverOrigin.value}/api/files") {
            applyHermesCredential(credential, serverOrigin)
            requestedPath?.let { parameter("path", it) }
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            response.throwIfHermesCredentialRejected(credential)
            throw HermesConnectionException(
                "Hermes host folder listing returned HTTP ${response.status.value}",
            )
        }
        val decoded = json.decodeFromString<HermesManagedFilesResponse>(
            response.readBodyTextBounded(),
        )
        val canonicalPath = validProjectWorkspacePath(decoded.path)
            ?: throw HermesConnectionException("Hermes host folder response was incomplete")
        HostDirectoryListing(
            path = canonicalPath,
            directories = decoded.entries.asSequence()
                .filter(HermesManagedFileEntry::isDirectory)
                .mapNotNull { entry ->
                    val name = validManagedDirectoryEntryName(entry.name) ?: return@mapNotNull null
                    val entryPath = validProjectWorkspacePath(entry.path) ?: return@mapNotNull null
                    HostDirectoryEntry(name = name, path = entryPath)
                }
                .distinctBy(HostDirectoryEntry::path)
                .take(MAX_HOST_DIRECTORY_ENTRIES)
                .toList(),
            parentPath = decoded.parent?.let(::validProjectWorkspacePath),
            lockedRoot = decoded.lockedRoot?.let(::validProjectWorkspacePath),
            canChangePath = decoded.canChangePath,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not load host folders", error)
    }

    override suspend fun createHostDirectory(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        parentPath: String,
        name: String,
    ): HostDirectoryListing = try {
        val validParent = validProjectWorkspacePath(parentPath)
            ?: throw HermesConnectionException("Host parent folder is invalid")
        val validName = validHostFolderName(name)
            ?: throw HermesConnectionException("Host folder name is invalid")
        val requestedPath = joinManagedHostPath(validParent, validName)
        val response = client.post("${serverOrigin.value}/api/files/mkdir") {
            applyHermesCredential(credential, serverOrigin)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(HermesManagedDirectoryCreateRequest(requestedPath)))
        }
        if (!response.status.isSuccess()) {
            response.readBodyTextBounded()
            response.throwIfHermesCredentialRejected(credential)
            throw HermesConnectionException(
                "Hermes host folder creation returned HTTP ${response.status.value}",
            )
        }
        val created = json.decodeFromString<HermesManagedDirectoryCreateResponse>(
            response.readBodyTextBounded(),
        )
        if (!created.ok) {
            throw HermesConnectionException("Hermes did not create the host folder")
        }
        val canonicalPath = validProjectWorkspacePath(created.path)
            ?: throw HermesConnectionException("Hermes host folder response was incomplete")
        loadHostDirectories(serverOrigin, credential, canonicalPath)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not create host folder", error)
    }

    override suspend fun downloadManagedImage(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        path: String,
    ): ByteArray = try {
        require(path.startsWith('/')) { "Managed image path must be absolute" }
        val response = client.get("${serverOrigin.value}/api/files/download") {
            applyHermesCredential(credential, serverOrigin)
            parameter("path", path)
        }
        if (!response.status.isSuccess()) {
            response.bodyAsChannel().cancel(null)
            response.throwIfHermesCredentialRejected(credential)
            throw HermesConnectionException(
                "Hermes managed image returned HTTP ${response.status.value}",
            )
        }
        val contentType = response.headers[io.ktor.http.HttpHeaders.ContentType]
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
        if (contentType?.startsWith("image/") != true) {
            response.bodyAsChannel().cancel(null)
            throw HermesConnectionException("Hermes managed file was not an image")
        }
        val declaredLength = response.headers[io.ktor.http.HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_MANAGED_IMAGE_BYTES) {
            response.bodyAsChannel().cancel(null)
            throw HermesConnectionException("Hermes managed image was too large")
        }
        val channel = response.bodyAsChannel()
        try {
            val source = channel.readRemaining(MAX_MANAGED_IMAGE_BYTES + 1L)
            try {
                val bytes = ByteArray(MAX_MANAGED_IMAGE_BYTES + 1)
                var count = 0
                while (!source.exhausted()) {
                    val read = source.readAtMostTo(bytes, count, bytes.size)
                    if (read <= 0) break
                    count += read
                    if (count > MAX_MANAGED_IMAGE_BYTES) {
                        throw HermesConnectionException("Hermes managed image was too large")
                    }
                }
                bytes.copyOf(count)
            } finally {
                source.close()
            }
        } finally {
            channel.cancel(null)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: HermesConnectionException) {
        throw error
    } catch (error: Exception) {
        throw HermesConnectionException("Could not download Hermes managed image", error)
    }

    private suspend fun loadSessionsPage(
        serverOrigin: ServerOrigin,
        credential: HermesCredential,
        profile: String = "default",
        limit: Int = MAX_DURABLE_SESSIONS,
        offset: Int = 0,
        archivedOnly: Boolean = false,
    ): SessionPage {
        val boundedProfile = profile.trim().takeIf { it.isNotEmpty() && it.length <= 64 }
            ?: throw HermesConnectionException("Hermes session profile is invalid")
        val boundedLimit = limit.coerceIn(1, MAX_SESSION_PAGE_SIZE)
        val boundedOffset = offset.coerceAtLeast(0)
        val sessionsResponse = client.get("${serverOrigin.value}/api/profiles/sessions") {
            applyHermesCredential(credential, serverOrigin)
            parameter("limit", boundedLimit)
            parameter("offset", boundedOffset)
            parameter("order", "recent")
            parameter("archived", if (archivedOnly) "only" else "exclude")
            parameter("profile", boundedProfile)
        }
        if (!sessionsResponse.status.isSuccess()) {
            sessionsResponse.readBodyTextBounded()
            val message = "Hermes session listing returned HTTP ${sessionsResponse.status.value}"
            if (credential != HermesCredential.None && sessionsResponse.status.value == 401) {
                throw HermesAuthenticationRejectedException(message)
            }
            throw HermesConnectionException(message)
        }
        val decoded = json.decodeFromString<HermesSessionsResponse>(
            sessionsResponse.readBodyTextBounded(),
        )
        val sessions = decoded.sessions.mapNotNull { row ->
            val id = row.id?.takeIf(String::isNotBlank)
                ?: row.sessionKey?.takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            SessionSummary(
                id = DurableSessionId(id),
                title = row.title?.takeIf(String::isNotBlank) ?: "Untitled session",
                workspacePath = row.cwd?.takeIf(String::isNotBlank),
                preview = row.preview?.takeIf(String::isNotBlank),
                lastActiveEpochSeconds = row.lastActive,
                messageCount = row.messageCount?.coerceAtLeast(0),
                model = row.model?.takeIf(String::isNotBlank),
                provider = (row.provider ?: row.billingProvider)?.takeIf(String::isNotBlank),
                profile = row.profile?.takeIf(String::isNotBlank),
                pinned = row.pinned,
                archived = row.archived,
            )
        }.distinctBy { it.id }
            .take(boundedLimit)
        return SessionPage(
            sessions = sessions,
            total = decoded.total,
            limit = decoded.limit?.coerceIn(1, MAX_SESSION_PAGE_SIZE) ?: boundedLimit,
            offset = decoded.offset?.coerceAtLeast(0) ?: boundedOffset,
        )
    }
}

internal fun parseBulkDeleteResponse(
    body: String,
    requestedCount: Int,
): BulkDeleteResult {
    require(requestedCount in 1..500) { "Bulk delete request count is invalid" }
    val response = Json { ignoreUnknownKeys = true }
        .decodeFromString<BulkDeleteSessionsResponse>(body)
    val deleted = response.deleted
        ?.takeIf { it in 0..requestedCount }
        ?: throw HermesConnectionException("Hermes bulk session deletion response was incomplete")
    if (!response.ok) {
        throw HermesConnectionException("Hermes bulk session deletion was not accepted")
    }
    return BulkDeleteResult(ok = true, deleted = deleted)
}

private fun boundedModelInfoField(element: JsonElement?, maxChars: Int): String? =
    (element as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf {
        it.isNotEmpty() && it.length <= maxChars && it.none(Char::isISOControl)
    }

private val profileReasoningEfforts = setOf(
    "minimal",
    "low",
    "medium",
    "high",
    "xhigh",
    "max",
    "ultra",
)

private fun canonicalProfileReasoningEffort(value: String?): String? {
    val normalized = value?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return null
    return when (normalized) {
        "none", "false", "disabled", "off", "no" -> "none"
        in profileReasoningEfforts -> normalized
        else -> null
    }
}

private fun reasoningBareModelVariants(value: String): Set<String> {
    val bare = value.trim().lowercase().substringAfterLast('/')
    if (bare.isEmpty()) return emptySet()
    return setOf(
        bare,
        bare.replace('.', '-'),
        bare.replace('-', '.'),
    )
}

private fun validManagedDirectoryEntryName(name: String): String? {
    if (name.isEmpty() || name.length > 255 || name in setOf(".", "..")) return null
    if (name.any(Char::isISOControl) || '/' in name || '\\' in name) return null
    return name
}

private fun parseHostFileListing(body: String): HostFileListing {
    val root = Json.parseToJsonElement(body) as? JsonObject
        ?: throw HermesConnectionException("Hermes host file response was invalid")
    val path = validCanonicalHostFilePath(root.managedText("path"))
        ?: throw HermesConnectionException("Hermes host file response was incomplete")
    val entries = (root["entries"] as? JsonArray)
        ?.take(MAX_HOST_FILE_ENTRIES)
        ?.mapNotNull(::parseHostFileEntry)
        .orEmpty()
    return HostFileListing(
        path = path,
        entries = entries,
        parentPath = root.managedText("parent")?.let(::validCanonicalHostFilePath),
        root = root.managedText("root")?.let(::validCanonicalHostFilePath),
        lockedRoot = root.managedText("locked_root")?.let(::validCanonicalHostFilePath),
        canChangePath = root["can_change_path"]?.jsonPrimitive?.booleanOrNull ?: true,
    )
}

private fun parseHostFileEntry(element: kotlinx.serialization.json.JsonElement): HostFileEntry? {
    val row = element as? JsonObject ?: return null
    val name = validHostFileName(row.managedText("name")) ?: return null
    val path = validCanonicalHostFilePath(row.managedText("path")) ?: return null
    val isDirectory = row["is_directory"]?.jsonPrimitive?.booleanOrNull ?: return null
    val declaredType = row.managedText("type")?.lowercase()
    if (declaredType != null && declaredType !in setOf("file", "directory", "dir")) return null
    if (declaredType == "file" && isDirectory || declaredType in setOf("directory", "dir") && !isDirectory) return null
    val size = row["size"]?.jsonPrimitive?.longOrNull
    if (size != null && size !in 0..MAX_HOST_FILE_BYTES.toLong()) return null
    val declaredMimeType = row.managedText("mime_type")
    val mimeType = declaredMimeType?.let { validHostFileMimeType(it) }
    if (declaredMimeType != null && mimeType == null) return null
    val modified = row["mtime"]?.jsonPrimitive?.doubleOrNull
    if (modified != null && !modified.isFinite()) return null
    return HostFileEntry(
        name = name,
        path = path,
        isDirectory = isDirectory,
        size = if (isDirectory) null else size,
        mimeType = if (isDirectory) null else mimeType,
        modifiedEpochSeconds = modified,
    )
}

private fun parseHostFileContent(body: String): HostFileContent {
    val root = Json.parseToJsonElement(body) as? JsonObject
        ?: throw HermesConnectionException("Hermes host file read response was invalid")
    val name = validHostFileName(root.managedText("name"))
        ?: throw HermesConnectionException("Hermes host file read response was incomplete")
    val path = validCanonicalHostFilePath(root.managedText("path"))
        ?: throw HermesConnectionException("Hermes host file read response was incomplete")
    val mimeType = validHostFileMimeType(root.managedText("mime_type"))
        ?: throw HermesConnectionException("Hermes host file MIME type was invalid")
    val declaredSize = root["size"]?.jsonPrimitive?.longOrNull
        ?: throw HermesConnectionException("Hermes host file size was invalid")
    if (declaredSize !in 0..MAX_HOST_FILE_BYTES.toLong()) {
        throw HermesConnectionException("Hermes host file was too large")
    }
    val dataUrl = root.managedText("data_url")
        ?: throw HermesConnectionException("Hermes host file data was incomplete")
    val comma = dataUrl.indexOf(',')
    if (!dataUrl.startsWith("data:") || comma <= 5 || !dataUrl.substring(0, comma).contains(";base64")) {
        throw HermesConnectionException("Hermes host file data was invalid")
    }
    val dataMime = validHostFileMimeType(dataUrl.substring(5, comma).substringBefore(';'))
        ?: throw HermesConnectionException("Hermes host file MIME type was invalid")
    if (dataMime != mimeType) throw HermesConnectionException("Hermes host file MIME type did not match")
    val encoded = dataUrl.substring(comma + 1)
    val bytes = try {
        Base64.getDecoder().decode(encoded)
    } catch (_: IllegalArgumentException) {
        throw HermesConnectionException("Hermes host file data was invalid")
    }
    if (bytes.size.toLong() != declaredSize || bytes.size > MAX_HOST_FILE_BYTES) {
        throw HermesConnectionException("Hermes host file size did not match")
    }
    return HostFileContent(name, path, mimeType, bytes)
}

private fun JsonObject.managedText(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull

private suspend fun HttpResponse.readBytesBounded(maxBytes: Int): ByteArray {
    require(maxBytes in 1..MAX_HOST_FILE_BYTES)
    val channel = bodyAsChannel()
    return try {
        val source = channel.readRemaining(maxBytes + 1L)
        try {
            val bytes = ByteArray(maxBytes + 1)
            var count = 0
            while (!source.exhausted()) {
                val read = source.readAtMostTo(bytes, count, bytes.size)
                if (read <= 0) break
                count += read
                if (count > maxBytes) throw HermesConnectionException("Hermes host file was too large")
            }
            bytes.copyOf(count)
        } finally {
            source.close()
        }
    } finally {
        channel.cancel(null)
    }
}

private fun joinManagedHostPath(parent: String, child: String): String {
    val windows = parent.length >= 2 && parent[1] == ':'
    val separator = if (windows) '\\' else '/'
    return parent.trimEnd('/', '\\') + separator + child
}

private fun JsonObject.assistantReasoningText(): String? =
    sequenceOf("reasoning", "reasoning_content", "reasoning_details")
        .mapNotNull { key -> (this[key] as? JsonPrimitive)?.contentOrNull }
        .firstOrNull(String::isNotBlank)
        ?.take(MAX_TRANSCRIPT_REASONING_CHARS)

private fun JsonObject.transcriptToolText(): String? {
    val explicitText = (this["content"] as? JsonPrimitive)?.contentOrNull
        ?: (this["text"] as? JsonPrimitive)?.contentOrNull
    if (!explicitText.isNullOrBlank()) return explicitText
    val name = (this["name"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    val context = (this["context"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    return listOfNotNull(name, context?.takeUnless { it == name })
        .joinToString(" · ")
        .takeIf(String::isNotEmpty)
}
