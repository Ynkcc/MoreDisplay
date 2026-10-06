package me.ynk.moredisplay.provider

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.ynk.moredisplay.App
import me.ynk.moredisplay.core.Privilege
import me.ynk.moredisplay.core.Transport
import me.ynk.moredisplay.dispatch.DaemonProvider
import me.ynk.moredisplay.shizuku.DaemonUserService
import me.ynk.moredisplay.shizuku.IDaemonRpc
import me.ynk.moredisplay.transport.ShizukuTransport
import rikka.shizuku.Shizuku

class LsposedDaemonProvider : DaemonProvider {
    override val privilege = Privilege.LSPOSED
    override val priority = 100

    override fun isAvailable(): Boolean =
        me.ynk.moredisplay.xposed.LsposedBridge.isInjected

    override suspend fun startDaemon(): Result<Unit> = Result.success(Unit)

    override suspend fun stopDaemon(): Result<Unit> = Result.success(Unit)

    override suspend fun connectTransport(): Result<Transport> = runCatching {
        me.ynk.moredisplay.transport.ProviderTransport(App.context)
    }
}

class ShizukuDaemonProvider : DaemonProvider {
    override val privilege = Privilege.SHELL_SHIZUKU
    override val priority = 50

    companion object {
        private const val TAG = "ShizukuProvider"
        private const val BIND_TIMEOUT_MS = 15_000L
    }

    private var userServiceArgs: Shizuku.UserServiceArgs? = null
    private var serviceConnection: ServiceConnection? = null
    private var remote: IDaemonRpc? = null

    override fun isAvailable(): Boolean {
        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrElse {
            Log.d(TAG, "shizuku binder unavailable: ${it.message}")
            false
        }
        if (!binderAlive) return false
        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrElse {
            Log.d(TAG, "shizuku permission check failed: ${it.message}")
            false
        }
        if (!granted) Log.d(TAG, "shizuku binder alive but permission not granted")
        return granted
    }

    override suspend fun startDaemon(): Result<Unit> = runCatching {
        if (remote != null) {
            Log.d(TAG, "shizuku daemon already bound")
            return@runCatching
        }
        val context: Context = App.context
        val args = Shizuku.UserServiceArgs(
            ComponentName(context.packageName, DaemonUserService::class.java.name)
        )
            .daemon(true)
            .processNameSuffix("daemon")
            .version(2)
        userServiceArgs = args

        fun newConnection(): Pair<CompletableDeferred<IDaemonRpc>, ServiceConnection> {
            val connected = CompletableDeferred<IDaemonRpc>()
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    if (binder == null) {
                        connected.completeExceptionally(IllegalStateException("shizuku returned null binder for $name"))
                    } else {
                        connected.complete(IDaemonRpc.Stub.asInterface(binder))
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    Log.w(TAG, "shizuku daemon service disconnected: $name")
                    remote = null
                }
            }
            return connected to connection
        }

        suspend fun bind(): IDaemonRpc {
            val (connected, connection) = newConnection()
            serviceConnection = connection
            withContext(Dispatchers.Main) {
                Shizuku.bindUserService(args, connection)
            }
            return withTimeout(BIND_TIMEOUT_MS) { connected.await() }
        }

        remote = bind()
        if (!verifyDaemonVersion()) {
            Log.w(TAG, "daemon version mismatch, destroying stale daemon and rebinding")
            remote?.destroy()
            remote = null
            remote = bind()
            verifyDaemonVersion()
        }
        Log.i(TAG, "shizuku daemon bound, binder alive=${remote?.asBinder()?.isBinderAlive}")
    }

    private fun verifyDaemonVersion(): Boolean {
        val stub = remote ?: return false
        val response = runCatching {
            me.ynk.moredisplay.core.RpcCodec.unmarshallResponse(
                stub.invoke(me.ynk.moredisplay.core.RpcCodec.marshallRequest(
                    me.ynk.moredisplay.core.RpcRequest.Ping(0)
                ))
            )
        }.getOrElse {
            Log.e(TAG, "version probe failed", it)
            return false
        }
        val pong = response as? me.ynk.moredisplay.core.RpcResponse.Pong
        val actual = pong?.daemonVersion ?: -1
        val expected = me.ynk.moredisplay.core.DaemonProtocol.DAEMON_VERSION
        if (actual != expected) {
            Log.w(TAG, "daemon version mismatch: expected=$expected actual=$actual pid=${pong?.daemonPid}")
            return false
        }
        Log.i(TAG, "daemon version ok: v$actual pid=${pong?.daemonPid}")
        return true
    }

    override suspend fun stopDaemon(): Result<Unit> = runCatching {
        val args = userServiceArgs ?: return@runCatching
        runCatching { remote?.destroy() }
            .onFailure { Log.w(TAG, "destroy daemon service failed: ${it.message}") }
        withContext(Dispatchers.Main) {
            runCatching { Shizuku.unbindUserService(args, serviceConnection, false) }
                .onFailure { Log.w(TAG, "unbindUserService failed: ${it.message}") }
        }
        remote = null
        serviceConnection = null
        userServiceArgs = null
    }

    override suspend fun connectTransport(): Result<Transport> = runCatching {
        val binder = remote ?: error("shizuku daemon not started, call startDaemon first")
        ShizukuTransport(binder)
    }
}

class RootDaemonProvider : DaemonProvider {
    override val privilege = Privilege.ROOT
    override val priority = 10

    override fun isAvailable(): Boolean = false

    override suspend fun startDaemon(): Result<Unit> = TODO("adb/root: app_process launch kotlin daemon jar")

    override suspend fun stopDaemon(): Result<Unit> = TODO("kill root daemon process")

    override suspend fun connectTransport(): Result<Transport> = TODO("connect local abstract socket of root daemon")
}
