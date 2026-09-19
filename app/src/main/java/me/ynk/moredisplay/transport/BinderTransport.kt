package me.ynk.moredisplay.transport

import android.os.IBinder
import android.os.Parcel
import android.util.Log
import me.ynk.moredisplay.core.RpcRequest
import me.ynk.moredisplay.core.RpcResponse
import me.ynk.moredisplay.core.Transport

class BinderTransport private constructor(
    private val remote: IBinder
) : Transport {

    companion object {
        private const val TAG = "BinderTransport"
        private const val DESCRIPTOR = "me.ynk.moredisplay.daemon"
        private const val TRANSACTION_INVOKE = 1

        fun connect(): BinderTransport {
            val binder = acquireDaemonBinder()
                ?: throw IllegalStateException("lsposed daemon binder not published")
            return BinderTransport(binder)
        }

        private fun acquireDaemonBinder(): IBinder? {
            val sm = runCatching {
                Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java)
                    .invoke(null, "moredisplay.daemon") as? IBinder
            }.getOrNull()
            if (sm != null) {
                Log.i(TAG, "daemon binder found via ServiceManager")
                return sm
            }
            Log.w(TAG, "daemon binder not in ServiceManager, lsposed not active")
            return null
        }
    }

    override val isConnected: Boolean
        get() = remote.isBinderAlive

    private var deathHandler: IBinder.DeathRecipient? = null

    override suspend fun send(request: RpcRequest): RpcResponse {
        TODO("parcel codec: write RpcRequest, transact(TRANSACTION_INVOKE), read RpcResponse")
    }

    override fun onDisconnected(handler: (() -> Unit)?) {
        deathHandler?.let { runCatching { remote.unlinkToDeath(it, 0) } }
        deathHandler = null
        if (handler != null) {
            val recipient = object : IBinder.DeathRecipient {
                override fun binderDied() {
                    Log.e(TAG, "daemon binder died")
                    handler()
                }
            }
            remote.linkToDeath(recipient, 0)
            deathHandler = recipient
        }
    }

    override fun close() {
        onDisconnected(null)
    }
}
