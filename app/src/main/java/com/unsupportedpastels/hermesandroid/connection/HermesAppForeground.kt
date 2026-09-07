package com.unsupportedpastels.hermesandroid.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object HermesAppForeground {
    private val mutableStates = MutableStateFlow(false)
    val states: StateFlow<Boolean> = mutableStates.asStateFlow()

    fun publish(foreground: Boolean) {
        mutableStates.value = foreground
    }
}
