package me.ynk.moredisplay.core

object DaemonProtocol {
    const val DAEMON_VERSION = 8
}

sealed interface RpcRequest {
    val id: Int

    data class Ping(override val id: Int) : RpcRequest
    data class GetCapabilities(override val id: Int) : RpcRequest
    data class CreateDisplay(override val id: Int, val spec: DisplaySpec) : RpcRequest
    data class HoldDisplay(override val id: Int, val displayId: Int) : RpcRequest
    data class RemoveDisplay(override val id: Int, val displayId: Int) : RpcRequest
    data class ListDisplays(override val id: Int) : RpcRequest
}

sealed interface RpcResponse {
    val id: Int

    data class Pong(override val id: Int, val daemonPid: Int, val daemonVersion: Int) : RpcResponse
    data class Capabilities(override val id: Int, val capabilities: DaemonCapabilities) : RpcResponse
    data class DisplayResult(override val id: Int, val info: DisplayInfo) : RpcResponse
    data class DisplayList(override val id: Int, val displays: List<DisplayInfo>) : RpcResponse
    data class Ok(override val id: Int) : RpcResponse
    data class Error(override val id: Int, val code: Int, val message: String) : RpcResponse
}

interface Transport : AutoCloseable {
    val isConnected: Boolean
    suspend fun send(request: RpcRequest): RpcResponse
    fun onDisconnected(handler: (() -> Unit)?)
}
