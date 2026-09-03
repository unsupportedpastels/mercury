package com.unsupportedpastels.hermesandroid.relay

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.unsupportedpastels.mercury.core.relay.RelayPairedTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface RelayPairingPhase {
    data object Idle : RelayPairingPhase
    data object Pairing : RelayPairingPhase
    data class AwaitingApproval(val targetId: String, val fingerprint: String) : RelayPairingPhase
    data class Approved(val targetId: String) : RelayPairingPhase
    data class Failed(val message: String) : RelayPairingPhase
}

data class RelayUiState(
    val targets: List<RelayPairedTarget> = emptyList(),
    val phase: RelayPairingPhase = RelayPairingPhase.Idle,
    val targetsError: String? = null,
)

class RelayViewModel(
    private val targets: RelayTargetRepository,
    private val coordinator: RelayPairingCoordinator,
    private val closeResources: () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(RelayUiState())
    val state: StateFlow<RelayUiState> = mutableState.asStateFlow()
    private var approvalJob: Job? = null

    init {
        loadTargets()
    }

    fun loadTargets(): Job = viewModelScope.launch {
        try {
            mutableState.value = mutableState.value.copy(targets = targets.load(), targetsError = null)
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                targets = emptyList(),
                targetsError = "Saved relay pairings could not be read.",
            )
        }
    }

    fun beginPairing(scannedText: String): Job {
        if (mutableState.value.phase !is RelayPairingPhase.Idle &&
            mutableState.value.phase !is RelayPairingPhase.Failed
        ) {
            return viewModelScope.launch { }
        }
        mutableState.value = mutableState.value.copy(phase = RelayPairingPhase.Pairing)
        return viewModelScope.launch {
            try {
                val target = coordinator.pair(scannedText)
                mutableState.value = mutableState.value.copy(
                    targets = targets.load(),
                    phase = RelayPairingPhase.AwaitingApproval(target.id, target.fingerprint),
                )
                startApprovalPolling(target)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = mutableState.value.copy(
                    phase = RelayPairingPhase.Failed(pairingMessage(error)),
                )
            }
        }
    }

    fun cancelPairing() {
        approvalJob?.cancel()
        approvalJob = null
        mutableState.value = mutableState.value.copy(phase = RelayPairingPhase.Idle)
    }

    fun resetFailure() {
        if (mutableState.value.phase is RelayPairingPhase.Failed) cancelPairing()
    }

    fun scannerUnavailable() {
        mutableState.value = mutableState.value.copy(
            phase = RelayPairingPhase.Failed("QR scanning is unavailable. Paste the pairing code instead."),
        )
    }

    fun removeTarget(target: RelayPairedTarget): Job = viewModelScope.launch {
        runCatching { targets.remove(target.id) }
        loadTargets().join()
    }

    fun renameTarget(target: RelayPairedTarget, label: String): Job = viewModelScope.launch {
        runCatching { targets.updateLabel(target.id, label) }
        loadTargets().join()
    }

    private fun startApprovalPolling(target: RelayPairedTarget) {
        approvalJob?.cancel()
        approvalJob = viewModelScope.launch {
            while (true) {
                if (coordinator.probeApproval(target, "default")) {
                    val loaded = runCatching { targets.load() }.getOrDefault(mutableState.value.targets)
                    mutableState.value = mutableState.value.copy(
                        targets = loaded,
                        phase = RelayPairingPhase.Approved(target.id),
                    )
                    return@launch
                }
                delay(3_000)
            }
        }
    }

    private fun pairingMessage(error: Exception): String = when ((error as? RelayPairingException)?.failure) {
        RelayPairingFailure.MalformedQr -> "That code isn't a Mercury Relay pairing QR."
        RelayPairingFailure.UnsupportedVersion -> "This pairing code needs a newer version of Mercury."
        RelayPairingFailure.MissingRelayOrigin -> "The host has no relay address configured. Create a new code after configuring it."
        RelayPairingFailure.ExpiredOffer -> "That pairing code has expired. Create a fresh one on the host."
        RelayPairingFailure.OfferRejected -> "The host refused this pairing code. It may already be used."
        RelayPairingFailure.Offline -> "The relay could not be reached. Check that the host is online."
        RelayPairingFailure.ProtocolViolation -> "Pairing failed a security check and was stopped."
        RelayPairingFailure.StorageFailed -> "The pairing could not be saved securely on this device."
        RelayPairingFailure.TargetLimitReached -> "You've reached the limit of saved relay pairings. Remove one first."
        null -> "Pairing failed. Try again."
    }

    override fun onCleared() {
        approvalJob?.cancel()
        closeResources()
        super.onCleared()
    }

    class ProductionFactory(
        private val context: Context,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RelayViewModel::class.java))
            val store = EncryptedRelayTargetStore(context)
            val coordinator = RelayPairingCoordinator(
                socketFactory = TlsRelayBinarySocketFactory(),
                targets = store,
            )
            return RelayViewModel(store, coordinator) as T
        }
    }
}
