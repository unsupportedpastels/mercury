package com.unsupportedpastels.hermesandroid.connection

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class ServerSettingsViewModel(
    private val repository: ServerSettingsRepository,
) : ViewModel() {
    val states: StateFlow<ServerSettingsState> = repository.states.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ServerSettingsState.Loading,
    )

    fun save(serverOrigin: ServerOrigin): Deferred<Result<Unit>> = viewModelScope.async {
        runCatching { repository.save(serverOrigin) }
    }

    fun save(entry: ServerCatalogEntry): Deferred<Result<Unit>> = viewModelScope.async {
        runCatching { repository.save(entry) }
    }

    fun select(serverOrigin: ServerOrigin): Deferred<Result<Unit>> = viewModelScope.async {
        runCatching { repository.select(serverOrigin) }
    }

    fun updateLabel(entry: ServerCatalogEntry): Deferred<Result<Unit>> = viewModelScope.async {
        runCatching { repository.updateLabel(entry) }
    }

    fun remove(serverOrigin: ServerOrigin): Deferred<Result<Unit>> = viewModelScope.async {
        runCatching {
            repository.remove(serverOrigin)
            Unit
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val applicationContext = context.applicationContext

        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ServerSettingsViewModel::class.java))
            return ServerSettingsViewModel(
                DataStoreServerSettingsRepository(applicationContext),
            ) as T
        }
    }
}
