package me.ynk.moredisplay.xposed

import android.content.Context
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import me.ynk.moredisplay.daemon.SystemDisplayEngine
import me.ynk.moredisplay.daemon.SystemRpcServer

class LsposedModule : XposedModule() {

    companion object {
        private const val TAG = "MoreDisplay_Lsposed"
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "system_server starting, installing hooks")
        hookAmsContext(param.classLoader)
        hookLaunchPermission(param.classLoader)
    }

    private fun hookAmsContext(classLoader: ClassLoader) {
        runCatching {
            val ams = classLoader.loadClass("com.android.server.am.ActivityManagerService")
            ams.declaredConstructors
                .filter { it.parameterTypes.firstOrNull() == Context::class.java }
                .forEach { ctor ->
                    hook(ctor).intercept { chain ->
                        val thisObject = chain.thisObject
                        runCatching {
                            val field = thisObject.javaClass.getDeclaredField("mUiContext")
                            field.isAccessible = true
                            (field.get(thisObject) as? Context)?.let { AmsContextHandlers.startDaemon(it) }
                        }.onFailure { log(Log.WARN, TAG, "capture mUiContext failed: ${it.message}") }
                        chain.proceed()
                    }
                }
            log(Log.INFO, TAG, "ams constructor hooks installed")
        }.onFailure { log(Log.ERROR, TAG, "hookAmsContext failed", it) }
    }

    private fun hookLaunchPermission(classLoader: ClassLoader) {
        val supervisor = when {
            android.os.Build.VERSION.SDK_INT >= 31 -> "com.android.server.wm.ActivityTaskSupervisor"
            else -> null
        } ?: run {
            log(Log.INFO, TAG, "launch permission hook skipped for sdk=${android.os.Build.VERSION.SDK_INT}")
            return
        }
        runCatching {
            val clz = classLoader.loadClass(supervisor)
            clz.declaredMethods
                .firstOrNull { it.name == "isCallerAllowedToLaunchOnDisplay" && it.parameterCount == 4 }
                ?.let { method ->
                    hook(method).intercept { chain ->
                        val displayId = chain.getArg(2) as? Int
                        if (displayId != null && displayId != 0 &&
                            AmsContextHandlers.isManagedDisplay(displayId)
                        ) {
                            true
                        } else {
                            chain.proceed()
                        }
                    }
                    log(Log.INFO, TAG, "launch permission hook installed")
                }
        }.onFailure { log(Log.ERROR, TAG, "hookLaunchPermission failed", it) }
    }
}

object AmsContextHandlers {
    private const val TAG = "MoreDisplay_Lsposed"

    @Volatile
    private var engine: me.ynk.moredisplay.daemon.DisplayEngine? = null

    fun startDaemon(context: Context) {
        if (engine != null) return
        synchronized(this) {
            if (engine != null) return
            runCatching {
                LsposedBridge.markInjected()
                val e = SystemDisplayEngine(context)
                SystemRpcServer(e).start()
                engine = e
                Log.i(TAG, "system daemon started in system_server")
            }.onFailure { Log.e(TAG, "system daemon start failed", it) }
        }
    }

    fun isManagedDisplay(displayId: Int): Boolean =
        engine?.listDisplays()?.any { it.displayId == displayId } == true
}
