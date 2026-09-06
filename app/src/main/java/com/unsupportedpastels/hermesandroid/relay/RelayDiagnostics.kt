package com.unsupportedpastels.hermesandroid.relay

import android.content.Context
import android.util.Log
import java.time.Instant
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Safe, bounded metadata about the installed app build. */
data class RelayAppBuildMetadata(
    val versionName: String?,
    val versionCode: Long?,
) {
    internal fun sanitized(): RelayAppBuildMetadata = RelayAppBuildMetadata(
        versionName = versionName.safeVersionName(),
        versionCode = versionCode?.takeIf { it >= 0L },
    )

    companion object {
        val Unknown = RelayAppBuildMetadata(versionName = null, versionCode = null)

        /** Reads only package version metadata; failures intentionally become unknown. */
        fun from(context: Context): RelayAppBuildMetadata {
            val packageInfo = runCatching {
                val appContext = context.applicationContext
                appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            }.getOrNull() ?: return Unknown
            return RelayAppBuildMetadata(
                versionName = packageInfo.versionName,
                versionCode = packageInfo.longVersionCode,
            ).sanitized()
        }
    }
}

enum class RelayDiagnosticOperation {
    Connection,
    Pairing,
}

enum class RelayDiagnosticPhase {
    Attempt,
    Open,
    Handshake,
    Admission,
    Disconnect,
    Cancellation,
}

enum class RelayDiagnosticOpenStage { Dns, Tcp, Tls, Upgrade }

enum class RelayDiagnosticExceptionCategory {
    Timeout, Tls, Dns, Connection, Protocol, Authorization, Cancellation, Unknown,
}

enum class RelayDiagnosticStatus {
    Started,
    Succeeded,
    Failed,
    TimedOut,
    Cancelled,
}

enum class RelayDiagnosticReason {
    Offline,
    NotAuthorized,
    ProtocolViolation,
    MalformedQr,
    UnsupportedVersion,
    MissingRelayOrigin,
    ExpiredOffer,
    OfferRejected,
    StorageFailed,
    TargetLimitReached,
    TransportFailure,
    Cancellation,
    Timeout,
    Unknown,
}

enum class RelayDiagnosticCloseReason {
    Explicit,
    RemoteEndOfStream,
    RemoteClose,
    LocalFailure,
    ProtocolViolation,
    Cancelled,
    Timeout,
    PairingComplete,
}

/** One sanitized event retained by the process-local relay diagnostics journal. */
class RelayDiagnosticEvent internal constructor(
    val utc: String,
    val monotonicDurationMillis: Long,
    val attemptId: String,
    val operation: RelayDiagnosticOperation,
    val phase: RelayDiagnosticPhase,
    val status: RelayDiagnosticStatus,
    val reason: RelayDiagnosticReason?,
    val closeReason: RelayDiagnosticCloseReason?,
    val appVersionName: String,
    val appVersionCode: Long?,
    val openStage: RelayDiagnosticOpenStage? = null,
    val exceptionCategory: RelayDiagnosticExceptionCategory? = null,
) {
    /** The log line has only closed-vocabulary fields and bounded scalar values. */
    fun toSafeLogLine(): String = buildString {
        append("event=relay_diagnostic")
        append(" utc=").append(utc)
        append(" elapsed_ms=").append(monotonicDurationMillis)
        append(" attempt=").append(attemptId)
        append(" operation=").append(operation.logName())
        append(" phase=").append(phase.logName())
        append(" open_stage=").append(openStage?.logName() ?: "none")
        append(" exception_category=").append(exceptionCategory?.logName() ?: "none")
        append(" status=").append(status.logName())
        append(" reason=").append(reason?.logName() ?: "none")
        append(" close_reason=").append(closeReason?.logName() ?: "none")
        append(" app_version=").append(appVersionName)
        append(" app_version_code=").append(appVersionCode ?: "unknown")
    }
}

/**
 * Process-local relay journal. It intentionally does not persist, export, or accept
 * connection data. The default logger emits the same safe fields to logcat.
 */
class RelayDiagnostics(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val utcMillis: () -> Long = { System.currentTimeMillis() },
    private val monotonicNanos: () -> Long = { System.nanoTime() },
    private val attemptIdFactory: () -> String = { UUID.randomUUID().toString().replace("-", "").take(12) },
    appBuildMetadata: RelayAppBuildMetadata = RelayAppBuildMetadata.Unknown,
    private val logger: (String) -> Unit = { line -> Log.i(LOG_TAG, line) },
) {
    init {
        require(capacity in 1..MAX_CAPACITY) { "Relay diagnostics capacity must be between 1 and $MAX_CAPACITY" }
    }

    private val journalLock = Any()
    private val events = ArrayDeque<RelayDiagnosticEvent>()
    private val metadata = AtomicReference(appBuildMetadata.sanitized())

    /** Starts an ephemeral attempt and immediately records its start event. */
    fun beginAttempt(operation: RelayDiagnosticOperation): RelayDiagnosticAttempt {
        val attemptId = safeAttemptId(runCatching { attemptIdFactory() }.getOrNull() ?: "invalid")
        val startedAtNanos = runCatching { monotonicNanos() }.getOrDefault(0L)
        val attempt = RelayDiagnosticAttempt(this, attemptId, operation, startedAtNanos)
        append(
            attempt = attempt,
            phase = RelayDiagnosticPhase.Attempt,
            status = RelayDiagnosticStatus.Started,
            reason = null,
            closeReason = null,
        )
        return attempt
    }

    /** Returns a copy in chronological ring-buffer order. */
    fun snapshot(): List<RelayDiagnosticEvent> = synchronized(journalLock) { events.toList() }

    fun clear() {
        synchronized(journalLock) { events.clear() }
    }

    /** Refreshes version metadata without retaining a Context or package identifier. */
    fun updateBuildMetadata(value: RelayAppBuildMetadata) {
        metadata.set(value.sanitized())
    }

    fun updateBuildMetadata(context: Context) {
        updateBuildMetadata(RelayAppBuildMetadata.from(context))
    }

    internal fun append(
        attempt: RelayDiagnosticAttempt,
        phase: RelayDiagnosticPhase,
        status: RelayDiagnosticStatus,
        reason: RelayDiagnosticReason?,
        closeReason: RelayDiagnosticCloseReason?,
        exceptionCategory: RelayDiagnosticExceptionCategory? = null,
    ) {
        val now = runCatching { monotonicNanos() }.getOrDefault(attempt.startedAtNanos)
        val elapsed = if (now >= attempt.startedAtNanos) {
            (now - attempt.startedAtNanos) / NANOS_PER_MILLISECOND
        } else {
            0L
        }
        val build = metadata.get()
        val event = RelayDiagnosticEvent(
            utc = runCatching { Instant.ofEpochMilli(utcMillis()).toString() }.getOrDefault("unknown"),
            monotonicDurationMillis = elapsed.coerceAtLeast(0L),
            attemptId = attempt.id,
            operation = attempt.operation,
            phase = phase,
            status = status,
            reason = reason,
            closeReason = closeReason,
            appVersionName = build.versionName ?: UNKNOWN_VALUE,
            appVersionCode = build.versionCode,
            openStage = if (phase == RelayDiagnosticPhase.Open || phase == RelayDiagnosticPhase.Cancellation) attempt.openStage else null,
            exceptionCategory = exceptionCategory,
        )
        synchronized(journalLock) {
            if (events.size == capacity) events.removeFirst()
            events.addLast(event)
        }
        runCatching { logger(event.toSafeLogLine()) }
    }

    companion object {
        const val DEFAULT_CAPACITY = 32
        const val MAX_CAPACITY = 64
        val shared: RelayDiagnostics = RelayDiagnostics()
        private const val LOG_TAG = "MercuryRelay"
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val UNKNOWN_VALUE = "unknown"
    }
}

/** Lifecycle handle shared by connector, socket factory, and chat socket. */
class RelayDiagnosticAttempt internal constructor(
    private val owner: RelayDiagnostics,
    val id: String,
    val operation: RelayDiagnosticOperation,
    internal val startedAtNanos: Long,
) {
    private val stateLock = Any()
    private var terminal = false
    private var disconnectRecorded = false
    internal var openStage: RelayDiagnosticOpenStage? = null
        private set
    private var openStageSucceeded = false

    /** At most one start/success pair per stage; late IO callbacks cannot revive a terminal attempt. */
    internal fun recordOpenStageStarted(stage: RelayDiagnosticOpenStage) = synchronized(stateLock) {
        val expected = openStage?.let { if (openStageSucceeded) it.ordinal + 1 else it.ordinal } ?: 0
        if (terminal || stage == openStage || stage.ordinal != expected) return@synchronized
        openStage = stage
        openStageSucceeded = false
        owner.append(this, RelayDiagnosticPhase.Open, RelayDiagnosticStatus.Started, null, null)
    }

    internal fun recordOpenStageSucceeded(stage: RelayDiagnosticOpenStage) = synchronized(stateLock) {
        if (terminal || openStage != stage || openStageSucceeded) return@synchronized
        openStageSucceeded = true
        owner.append(this, RelayDiagnosticPhase.Open, RelayDiagnosticStatus.Succeeded, null, null)
    }

    internal fun recordOpenFailure(error: Exception) {
        val category = error.toOpenDiagnosticCategory()
        if (category == RelayDiagnosticExceptionCategory.Timeout) {
            recordTimeout(RelayDiagnosticPhase.Open)
        } else {
            recordFailure(
                RelayDiagnosticPhase.Open,
                (error as? RelayConnectionException)?.failure?.toDiagnosticReason() ?: RelayDiagnosticReason.Offline,
                category,
            )
        }
    }

    fun recordSuccess(phase: RelayDiagnosticPhase) {
        require(phase == RelayDiagnosticPhase.Open ||
            phase == RelayDiagnosticPhase.Handshake ||
            phase == RelayDiagnosticPhase.Admission
        )
        synchronized(stateLock) {
            if (!terminal) owner.append(this, phase, RelayDiagnosticStatus.Succeeded, null, null)
        }
    }

    fun recordFailure(
        phase: RelayDiagnosticPhase,
        reason: RelayDiagnosticReason,
        exceptionCategory: RelayDiagnosticExceptionCategory? = null,
    ) {
        require(phase == RelayDiagnosticPhase.Attempt ||
            phase == RelayDiagnosticPhase.Open ||
            phase == RelayDiagnosticPhase.Handshake ||
            phase == RelayDiagnosticPhase.Admission ||
            phase == RelayDiagnosticPhase.Disconnect
        )
        synchronized(stateLock) {
            if (terminal) return
            terminal = true
            owner.append(this, phase, RelayDiagnosticStatus.Failed, reason, null, exceptionCategory)
        }
    }

    fun recordTimeout(phase: RelayDiagnosticPhase) {
        require(phase == RelayDiagnosticPhase.Open ||
            phase == RelayDiagnosticPhase.Handshake ||
            phase == RelayDiagnosticPhase.Admission
        )
        synchronized(stateLock) {
            if (terminal) return
            terminal = true
            owner.append(this, phase, RelayDiagnosticStatus.TimedOut, RelayDiagnosticReason.Timeout, null, RelayDiagnosticExceptionCategory.Timeout)
        }
    }

    fun recordCancellation() {
        synchronized(stateLock) {
            if (terminal) return
            terminal = true
            owner.append(
                this,
                RelayDiagnosticPhase.Cancellation,
                RelayDiagnosticStatus.Cancelled,
                RelayDiagnosticReason.Cancellation,
                null,
                RelayDiagnosticExceptionCategory.Cancellation,
            )
        }
    }

    fun recordDisconnect(reason: RelayDiagnosticCloseReason) {
        synchronized(stateLock) {
            if (disconnectRecorded) return
            disconnectRecorded = true
            terminal = true
            owner.append(
                this,
                RelayDiagnosticPhase.Disconnect,
                RelayDiagnosticStatus.Succeeded,
                null,
                reason,
            )
        }
    }
}

/** Optional internal seam for passing one attempt into native socket setup. */
internal interface RelayDiagnosticSocketFactory {
    suspend fun connectWithDiagnostics(
        url: String,
        routingToken: String?,
        attempt: RelayDiagnosticAttempt,
    ): RelayBinarySocket
}

internal suspend fun RelayBinarySocketFactory.connectRelaySocket(
    url: String,
    routingToken: String?,
    attempt: RelayDiagnosticAttempt,
): RelayBinarySocket = if (this is RelayDiagnosticSocketFactory) {
    connectWithDiagnostics(url, routingToken, attempt)
} else {
    connect(url, routingToken)
}

internal fun RelayConnectionFailure.toDiagnosticReason(): RelayDiagnosticReason = when (this) {
    RelayConnectionFailure.Offline -> RelayDiagnosticReason.Offline
    RelayConnectionFailure.NotAuthorized -> RelayDiagnosticReason.NotAuthorized
    RelayConnectionFailure.ProtocolViolation -> RelayDiagnosticReason.ProtocolViolation
}

internal fun RelayPairingFailure.toDiagnosticReason(): RelayDiagnosticReason = when (this) {
    RelayPairingFailure.MalformedQr -> RelayDiagnosticReason.MalformedQr
    RelayPairingFailure.UnsupportedVersion -> RelayDiagnosticReason.UnsupportedVersion
    RelayPairingFailure.MissingRelayOrigin -> RelayDiagnosticReason.MissingRelayOrigin
    RelayPairingFailure.ExpiredOffer -> RelayDiagnosticReason.ExpiredOffer
    RelayPairingFailure.OfferRejected -> RelayDiagnosticReason.OfferRejected
    RelayPairingFailure.StorageFailed -> RelayDiagnosticReason.StorageFailed
    RelayPairingFailure.TargetLimitReached -> RelayDiagnosticReason.TargetLimitReached
    RelayPairingFailure.ProtocolViolation -> RelayDiagnosticReason.ProtocolViolation
    RelayPairingFailure.Offline -> RelayDiagnosticReason.Offline
}

/** Only type checks; never reads messages, names, addresses, or provider text. Bounded even for cyclic causes. */
internal fun Exception.toOpenDiagnosticCategory(): RelayDiagnosticExceptionCategory {
    var cause: Throwable? = this
    repeat(8) {
        if (cause is java.net.SocketTimeoutException) return RelayDiagnosticExceptionCategory.Timeout
        cause = cause?.cause
    }
    return when (this) {
        is kotlinx.coroutines.CancellationException -> RelayDiagnosticExceptionCategory.Cancellation
        is javax.net.ssl.SSLException -> RelayDiagnosticExceptionCategory.Tls
        is java.net.UnknownHostException -> RelayDiagnosticExceptionCategory.Dns
        is RelayConnectionException -> when (failure) {
            RelayConnectionFailure.NotAuthorized -> RelayDiagnosticExceptionCategory.Authorization
            RelayConnectionFailure.ProtocolViolation -> RelayDiagnosticExceptionCategory.Protocol
            RelayConnectionFailure.Offline -> RelayDiagnosticExceptionCategory.Connection
        }
        is java.io.IOException -> RelayDiagnosticExceptionCategory.Connection
        else -> RelayDiagnosticExceptionCategory.Unknown
    }
}

private fun String?.safeVersionName(): String = this
    ?.trim()
    ?.takeIf { it.length in 1..32 && it.all(::isSafeMetadataChar) }
    ?: "unknown"

private fun safeAttemptId(value: String): String = value
    .take(24)
    .takeIf { it.isNotEmpty() && it.all { char -> isSafeAsciiAlphaNumeric(char) || char == '-' || char == '_' } }
    ?: "invalid"

private fun isSafeMetadataChar(char: Char): Boolean =
    isSafeAsciiAlphaNumeric(char) || char in ".-_+"

private fun isSafeAsciiAlphaNumeric(char: Char): Boolean =
    char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'

private fun Enum<*>.logName(): String = name
    .replace(Regex("([a-z])([A-Z])"), "$1_$2")
    .lowercase(Locale.US)
