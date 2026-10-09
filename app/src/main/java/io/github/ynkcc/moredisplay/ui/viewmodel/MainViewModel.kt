package io.github.ynkcc.moredisplay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.ynkcc.moredisplay.core.ConnectionStatus
import io.github.ynkcc.moredisplay.core.IDisplayRepository
import io.github.ynkcc.moredisplay.core.Privilege
import io.github.ynkcc.moredisplay.core.WorkModeInfo
import io.github.ynkcc.moredisplay.dispatch.DispatchCenter

class MainViewModel(
    private val repo: IDisplayRepository
) : ViewModel() {

    val status: StateFlow<ConnectionStatus> = repo.connectionStatus
    val error: StateFlow<String?> = repo.connectionError

    /** 当前主通道（最高优先级已连接通道）。 */
    val workMode: StateFlow<WorkModeInfo> = repo.workMode

    /** 所有已连接通道（多通道并行：Shizuku 与 LSPosed 可同时工作）。 */
    val workModes: StateFlow<Map<Privilege, WorkModeInfo>> = repo.workModes

    /** 当前可用（尚未 necessarily 连接）的接入方式。 */
    private val _availablePrivileges = MutableStateFlow<List<Privilege>>(emptyList())
    val availablePrivileges: StateFlow<List<Privilege>> = _availablePrivileges

    init {
        refreshProviders()
    }

    /** 连接所有可用通道（已连接的跳过，渐进接入）。 */
    fun connect() = viewModelScope.launch {
        repo.connect()
        repo.refreshDisplays()
    }

    fun disconnect() = viewModelScope.launch { repo.disconnect() }

    fun refreshProviders() {
        _availablePrivileges.value = DispatchCenter.activeProviders().map { it.privilege }
    }
}
