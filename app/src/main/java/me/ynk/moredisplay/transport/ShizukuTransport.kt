package me.ynk.moredisplay.transport

import android.os.IBinder
import android.util.Log
import me.ynk.moredisplay.core.RpcCodec
import me.ynk.moredisplay.core.RpcRequest
import me.ynk.moredisplay.core.RpcResponse
import me.ynk.moredisplay.core.Transport
import me.ynk.moredisplay.shizuku.IDaemonRpc

class ShizukuTransport(
    private val remote: IDaemonRpc
) : Transport {

    companion object {
        private const val TAG = "ShizukuTransport"
    }

    private val binder: IBinder get() = remote.asBinder()

    override val isConnected: Boolean
        get() = binder.isBinderAlive

    private var deathHandler: IBinder.DeathRecipient? = null

    override suspend fun send(request: RpcRequest): RpcResponse {
        val payload = RpcCodec.marshallRequest(request)
        val responseBytes = runCatching { remote.invoke(payload) }
            .getOrElse {
                Log.e(TAG, "invoke failed for request=$request", it)
                throw IllegalStateException("shizuku rpc invoke failed: ${it.message}", it)
            }
        return RpcCodec.unmarshallResponse(responseBytes)
    }

    override fun onDisconnected(handler: (() -> Unit)?) {
        deathHandler?.let { runCatching { binder.unlinkToDeath(it, 0) } }
        deathHandler = null
        if (handler != null) {
            val recipient = IBinder.DeathRecipient {
                Log.e(TAG, "shizuku daemon binder died")
                handler()
            }
            runCatching { binder.linkToDeath(recipient, 0) }
                .onFailure { Log.e(TAG, "linkToDeath failed", it) }
            deathHandler = recipient
        }
    }

    override fun close() {
        onDisconnected(null)
    }
}
