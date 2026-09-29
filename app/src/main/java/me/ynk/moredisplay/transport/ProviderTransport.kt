package me.ynk.moredisplay.transport

import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import me.ynk.moredisplay.core.RpcCodec
import me.ynk.moredisplay.core.RpcRequest
import me.ynk.moredisplay.core.RpcResponse
import me.ynk.moredisplay.core.Transport
import me.ynk.moredisplay.daemon.ProviderRpc

/**
 * App → system_server 守护进程的 RPC 通道。
 *
 * 走 `content://settings` 提供者（其进程即 system_server），
 * 由模块在 system_server 内 Hook `SettingsProvider#call` 应答。
 */
class ProviderTransport(
    private val context: Context
) : Transport {

    companion object {
        private const val TAG = "ProviderTransport"

        /** 探测守护进程是否已在 system_server 中就绪。 */
        fun ping(context: Context): Boolean = runCatching {
            val reply = call(context, RpcRequest.Ping(0))
            RpcCodec.unmarshallResponse(reply) is RpcResponse.Pong
        }.getOrElse {
            Log.d(TAG, "daemon ping failed: ${it.message}")
            false
        }

        private fun call(context: Context, request: RpcRequest): ByteArray {
            val args = Bundle().apply {
                putByteArray(ProviderRpc.EXTRA_REQUEST, RpcCodec.marshallRequest(request))
            }
            val reply = context.contentResolver.call(
                Settings.Global.CONTENT_URI, ProviderRpc.METHOD, null, args
            ) ?: throw IllegalStateException("daemon provider returned null for $request")
            return reply.getByteArray(ProviderRpc.EXTRA_RESPONSE)
                ?: throw IllegalStateException("daemon provider returned empty reply for $request")
        }
    }

    override val isConnected: Boolean = true

    override suspend fun send(request: RpcRequest): RpcResponse =
        RpcCodec.unmarshallResponse(call(context, request))

    override fun onDisconnected(handler: (() -> Unit)?) {
        // provider 调用是一次性 IPC，没有长连接，无需死亡通知。
    }

    override fun close() = Unit
}
