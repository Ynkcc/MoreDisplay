package io.github.ynkcc.moredisplay.core

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

    /**
     * 按通道分组的托管屏。key 为创建/托管它的通道 privilege
     * （未知归属归入 [Privilege.NONE]），供 UI 分通道管理。
     */
    val displaysByChannel: StateFlow<Map<Privilege, List<DisplayInfo>>>

    /** 当前激活的最高优先级工作模式（多通道并行时的主通道）。 */
    val workMode: StateFlow<WorkModeInfo>
    /** 所有已连接通道的工作模式，key 为通道 privilege。 */
    val workModes: StateFlow<Map<Privilege, WorkModeInfo>>

    suspend fun connect(): Result<Unit>
    suspend fun disconnect(): Result<Unit>

    /**
     * 创建虚拟屏。[privilege] 为 null 时路由到当前已连接的最高优先级通道
     * （LSPosed > ROOT > SHELL_SHIZUKU）；显式指定时路由到对应通道，
     * 使 Shizuku 与 LSPosed 可同时各自创建/管理自己的屏幕。
     */
    suspend fun createDisplay(spec: DisplaySpec, privilege: Privilege? = null): Result<DisplayInfo>
    suspend fun holdDisplay(displayId: Int): Result<DisplayInfo>
    suspend fun removeDisplay(displayId: Int): Result<Unit>
    suspend fun listDisplays(): Result<List<DisplayInfo>>

    /** 该 displayId 由哪个通道创建/托管（未知返回 null）。 */
    fun displayOrigin(displayId: Int): Privilege?

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
