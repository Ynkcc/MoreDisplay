package me.ynk.moredisplay.daemon

import android.content.Context
import android.util.Log

object DaemonEntry {

    private const val TAG = "MoreDisplay_DaemonEntry"
    private const val SOCKET_NAME = "moredisplay.daemon.rpc"

    fun main(args: Array<String>) {
        Log.i(TAG, "daemon starting, args=${args.joinToString()}, uid=${android.os.Process.myUid()}")
        runCatching {
            val context = createSystemContext()
            val engine = ShellDisplayEngine(context)
            SocketRpcServer(SOCKET_NAME).start(engine)
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { engine.listDisplays().forEach { engine.removeDisplay(it.displayId) } }
            })
        }.onFailure {
            Log.e(TAG, "daemon startup failed", it)
            android.os.Process.killProcess(android.os.Process.myPid())
        }

        synchronized(this) {
            while (true) (this as java.lang.Object).wait()
        }
    }

    private fun createSystemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
            .getMethod("systemMain").invoke(null)
        val getSystemContext = activityThread.javaClass.getMethod("getSystemContext")
        return getSystemContext.invoke(activityThread) as Context
    }
}
