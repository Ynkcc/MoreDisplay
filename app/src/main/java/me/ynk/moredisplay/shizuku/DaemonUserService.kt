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

    init {
        // 必须先于 engine 属性初始化执行（属性按声明序初始化），
        // 否则引擎会以 root 身份探测权限/选择候选包名。
        demoteToShellIfNeeded()
        Log.i(TAG, "daemon started, uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()}")
    }

    private val engine = ShellDisplayEngine(context)

    /**
     * root (uid 0) 启动时降权到 shell (2000)。DMS `validatePackageName`
     * 要求包名属于调用 uid，而 uid 0 通常不属于任何包——但各 ROM 对 root
     * 的处理不一致（实测部分 ROM 放行、部分直接拒绝），无法预先判定。
     * uid 2000 + com.android.shell 则在所有 ROM 上均合法，故统一降权。
     * 必须在构造期（binder 线程启动前）执行，保证后续线程继承降权后的身份。
     */
    private fun demoteToShellIfNeeded() {
        if (android.os.Process.myUid() != 0) return
        runCatching {
            android.system.Os.setgid(2000)
            android.system.Os.setuid(2000)
        }.onSuccess {
            Log.i(TAG, "root daemon demoted to shell (uid 2000) for DMS compatibility")
        }.onFailure {
            Log.w(TAG, "demote to shell failed, continue as root: ${it.message}")
        }
    }

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
