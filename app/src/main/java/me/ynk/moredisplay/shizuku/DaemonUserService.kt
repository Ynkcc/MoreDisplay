package me.ynk.moredisplay.shizuku

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import me.ynk.moredisplay.core.RpcCodec
import me.ynk.moredisplay.core.RpcResponse
import me.ynk.moredisplay.daemon.ShellDisplayEngine
import me.ynk.moredisplay.daemon.handleRequest

class DaemonUserService @Keep constructor(context: Context) : IDaemonRpc.Stub() {

    companion object {
        private const val TAG = "DaemonUserService"
    }

    private val engine = ShellDisplayEngine(context)

    override fun invoke(payload: ByteArray): ByteArray {
        val request = runCatching { RpcCodec.unmarshallRequest(payload) }
            .getOrElse {
                Log.e(TAG, "unmarshall request failed, size=${payload.size}", it)
                throw it
            }
        Log.d(TAG, "rpc request=$request")
        val response = runCatching { handleRequest(engine, request) }
            .getOrElse { RpcResponse.Error(request.id, -1, "daemon handler crashed: ${it.stackTraceToString().take(2000)}") }
        return RpcCodec.marshallResponse(response)
    }

    override fun destroy() {
        Log.i(TAG, "destroy: releasing ${engine.listDisplays().size} displays")
        engine.listDisplays().forEach { info ->
            runCatching { engine.removeDisplay(info.displayId) }
                .onFailure { Log.e(TAG, "release displayId=${info.displayId} failed on destroy", it) }
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
