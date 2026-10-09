package io.github.ynkcc.moredisplay.daemon

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.github.ynkcc.moredisplay.core.RpcRequest
import io.github.ynkcc.moredisplay.core.RpcResponse
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class SocketRpcServer(
    private val socketName: String
) : RpcServer {

    companion object {
        private const val TAG = "SocketRpcServer"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var serverSocket: LocalServerSocket? = null
    private lateinit var engine: DisplayEngine

    override fun start(engine: DisplayEngine) {
        this.engine = engine
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            runCatching {
                serverSocket = LocalServerSocket(socketName)
                Log.i(TAG, "rpc server listening on $socketName pid=${android.os.Process.myPid()}")
                while (running.get()) {
                    val client = serverSocket?.accept() ?: break
                    launch { serve(client) }
                }
            }.onFailure { Log.e(TAG, "rpc server loop failed", it) }
        }
    }

    private fun serve(client: LocalSocket) {
        runCatching {
            client.use { socket ->
                val input = socket.inputStream
                val output = socket.outputStream
                while (running.get()) {
                    val request = readRequest(input) ?: break
                    val response = runCatching { handleRequest(engine, request) }
                        .getOrElse { RpcResponse.Error(request.id, -1, "handler crashed: ${it.message}") }
                    writeResponse(output, response)
                }
            }
        }.onFailure { Log.w(TAG, "client connection ended: ${it.message}") }
    }

    private fun readRequest(input: InputStream): RpcRequest? = TODO("codec: length-prefixed serialization")

    private fun writeResponse(output: OutputStream, response: RpcResponse) {
        TODO("codec: length-prefixed serialization")
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
            .onFailure { Log.e(TAG, "close server socket failed", it) }
        serverSocket = null
        Log.i(TAG, "rpc server stopped")
    }
}
