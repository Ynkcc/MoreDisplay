package me.ynk.moredisplay.daemon

import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.core.RpcRequest
import me.ynk.moredisplay.core.RpcResponse

interface DisplayEngine {
    val capabilities: me.ynk.moredisplay.core.DaemonCapabilities

    fun createDisplay(spec: DisplaySpec): DisplayInfo
    fun holdDisplay(displayId: Int): DisplayInfo?
    fun removeDisplay(displayId: Int)
    fun listDisplays(): List<DisplayInfo>
}

interface RpcServer {
    fun start(engine: DisplayEngine)
    fun stop()
}

fun handleRequest(engine: DisplayEngine, request: RpcRequest): RpcResponse = when (request) {
    is RpcRequest.Ping -> RpcResponse.Pong(
        request.id,
        android.os.Process.myPid(),
        me.ynk.moredisplay.core.DaemonProtocol.DAEMON_VERSION
    )
    is RpcRequest.GetCapabilities -> RpcResponse.Capabilities(request.id, engine.capabilities)
    is RpcRequest.CreateDisplay -> runCatching { engine.createDisplay(request.spec) }
        .fold(
            { RpcResponse.DisplayResult(request.id, it) },
            { RpcResponse.Error(request.id, 1, "createDisplay failed: ${it.message}") }
        )
    is RpcRequest.HoldDisplay -> engine.holdDisplay(request.displayId)
        ?.let { RpcResponse.DisplayResult(request.id, it) }
        ?: RpcResponse.Error(request.id, 2, "display ${request.displayId} not held by daemon")
    is RpcRequest.RemoveDisplay -> runCatching { engine.removeDisplay(request.displayId) }
        .fold(
            { RpcResponse.Ok(request.id) },
            { RpcResponse.Error(request.id, 3, "removeDisplay failed: ${it.message}") }
        )
    is RpcRequest.ListDisplays -> RpcResponse.DisplayList(request.id, engine.listDisplays())
}
