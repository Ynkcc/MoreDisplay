package me.ynk.moredisplay.core

object DaemonProtocol {
    const val DAEMON_VERSION = 12
}

sealed interface RpcRequest {
    val id: Int

    data class Ping(override val id: Int) : RpcRequest
    data class GetCapabilities(override val id: Int) : RpcRequest
    data class CreateDisplay(override val id: Int, val spec: DisplaySpec) : RpcRequest
    data class HoldDisplay(override val id: Int, val displayId: Int) : RpcRequest
    data class RemoveDisplay(override val id: Int, val displayId: Int) : RpcRequest
    data class ListDisplays(override val id: Int) : RpcRequest

    /** 需求 A：下发/覆盖某个 uid 的屏幕可见性策略（立即生效，无需重启 system_server）。 */
    data class SetDisplayPolicy(override val id: Int, val policy: DisplayPolicy) : RpcRequest

    /** 查询某个 uid 的策略，无策略时返回 null。 */
    data class GetDisplayPolicy(override val id: Int, val uid: Int) : RpcRequest

    /** 删除某个 uid 的策略（回到默认放行）。 */
    data class RemoveDisplayPolicy(override val id: Int, val uid: Int) : RpcRequest

    data class ListDisplayPolicies(override val id: Int) : RpcRequest

    /** 以 daemon 身份枚举「某个 uid 实际可见」的 displayId（用于策略验收/自查）。 */
    data class DisplaysForUid(override val id: Int, val uid: Int) : RpcRequest
}

sealed interface RpcResponse {
    val id: Int

    data class Pong(override val id: Int, val daemonPid: Int, val daemonVersion: Int) : RpcResponse
    data class Capabilities(override val id: Int, val capabilities: DaemonCapabilities) : RpcResponse
    data class DisplayResult(override val id: Int, val info: DisplayInfo) : RpcResponse
    data class DisplayList(override val id: Int, val displays: List<DisplayInfo>) : RpcResponse
    data class PolicyResult(override val id: Int, val policy: DisplayPolicy?) : RpcResponse
    data class PolicyList(override val id: Int, val policies: List<DisplayPolicy>) : RpcResponse
    data class IdList(override val id: Int, val ids: List<Int>) : RpcResponse
    data class Ok(override val id: Int) : RpcResponse
    data class Error(override val id: Int, val code: Int, val message: String) : RpcResponse
}

interface Transport : AutoCloseable {
    val isConnected: Boolean
    suspend fun send(request: RpcRequest): RpcResponse
    fun onDisconnected(handler: (() -> Unit)?)
}
