package me.ynk.moredisplay.core

import kotlinx.coroutines.flow.StateFlow

data class DisplaySpec(
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    val name: String = "MoreDisplay",
    val flags: Int = 0
)

data class DisplayInfo(
    val displayId: Int,
    val spec: DisplaySpec,
    val owner: DisplayOwner? = null
)

data class DisplayOwner(
    val packageName: String? = null,
    val uid: Int = 0
)

enum class ConnectionStatus {
    IDLE,
    BINDING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
    RECONNECTING
}

interface IDisplayRepository {
    val connectionStatus: StateFlow<ConnectionStatus>
    val connectionError: StateFlow<String?>
    val managedDisplays: StateFlow<Map<Int, DisplayInfo>>
    val workMode: StateFlow<WorkModeInfo>

    suspend fun connect(): Result<Unit>
    suspend fun disconnect(): Result<Unit>

    suspend fun createDisplay(spec: DisplaySpec): Result<DisplayInfo>
    suspend fun holdDisplay(displayId: Int): Result<DisplayInfo>
    suspend fun removeDisplay(displayId: Int): Result<Unit>
    suspend fun listDisplays(): Result<List<DisplayInfo>>

    suspend fun setDisplayPolicy(policy: DisplayPolicy): Result<DisplayPolicy>
    suspend fun listDisplayPolicies(): Result<List<DisplayPolicy>>
    suspend fun removeDisplayPolicy(uid: Int): Result<Unit>
    suspend fun displaysForUid(uid: Int): Result<List<Int>>

    fun refreshDisplays()
}

data class WorkModeInfo(
    val privilege: Privilege,
    val daemonLocation: DaemonLocation,
    val capabilities: DaemonCapabilities
)

enum class Privilege { LSPOSED, ROOT, SHELL_SHIZUKU, NONE }

enum class DaemonLocation { SYSTEM_SERVER, STANDALONE_PROCESS, IN_APP }
