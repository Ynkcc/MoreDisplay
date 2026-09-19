package me.ynk.moredisplay.daemon

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log

class SystemRpcServer(
    private val engine: DisplayEngine
) {

    companion object {
        private const val TAG = "SystemRpcServer"
        private const val SERVICE_NAME = "moredisplay.daemon"
        private const val DESCRIPTOR = "me.ynk.moredisplay.daemon"
        private const val TRANSACTION_INVOKE = 1
    }

    fun start() {
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code != TRANSACTION_INVOKE || reply == null) {
                    return super.onTransact(code, data, reply, flags)
                }
                return runCatching {
                    val request = RequestCodec.read(data)
                    val response = handleRequest(engine, request)
                    ResponseCodec.write(reply, response)
                    true
                }.getOrElse {
                    Log.e(TAG, "transact failed", it)
                    (it as? Exception)?.let(reply::writeException)
                        ?: reply.writeException(RuntimeException(it))
                    true
                }
            }
        }
        val addService = Class.forName("android.os.ServiceManager").getMethod(
            "addService", String::class.java, IBinder::class.java
        )
        addService.invoke(null, SERVICE_NAME, binder)
        Log.i(TAG, "daemon binder published as $SERVICE_NAME")
    }
}

object RequestCodec {
    fun read(data: Parcel): me.ynk.moredisplay.core.RpcRequest {
        TODO("parcel codec for RpcRequest")
    }
}

object ResponseCodec {
    fun write(reply: Parcel, response: me.ynk.moredisplay.core.RpcResponse) {
        TODO("parcel codec for RpcResponse")
    }
}
