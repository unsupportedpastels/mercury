package com.unsupportedpastels.hermesandroid.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeHermesGateway(
    initialSnapshot: HermesGatewaySnapshot = HermesGatewaySnapshot(),
) : HermesGateway {
    private val mutableSnapshots = MutableStateFlow(initialSnapshot)

    override val snapshots: StateFlow<HermesGatewaySnapshot> = mutableSnapshots.asStateFlow()

    override suspend fun refresh(): HermesGatewaySnapshot = mutableSnapshots.value

    fun replaceSnapshot(snapshot: HermesGatewaySnapshot) {
        mutableSnapshots.value = snapshot
    }
}
