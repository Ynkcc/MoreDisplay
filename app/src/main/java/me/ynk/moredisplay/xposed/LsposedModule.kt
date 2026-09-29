package me.ynk.moredisplay.xposed

import android.content.Context
import android.os.Bundle
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import me.ynk.moredisplay.daemon.DisplayEngine
import me.ynk.moredisplay.daemon.ProviderRpc
import me.ynk.moredisplay.daemon.SystemDisplayEngine
import java.lang.reflect.Executable
import java.util.concurrent.ConcurrentHashMap

/**
 * LibXposed API 102 模块入口。
 *
 * 作用域只需勾选 `android`（即 system_server 进程）：
 * 1. 在 system_server 内建起虚拟屏引擎，并 Hook `ContentProvider$Transport#call`
 *    给 App 提供 RPC 应答（见 [ProviderRpc]）；
 * 2. 放宽 `ActivityTaskSupervisor#isCallerAllowedToLaunchOnDisplay`，
 *    让应用可以把自己启动到我们托管的虚拟屏上。
 */
class LsposedModule : XposedModule() {

    companion object {
        private const val TAG = "MoreDisplay_Lsposed"
        private const val MODULE_PACKAGE = "me.ynk.moredisplay"
        private const val TRANSPORT_CLASS = "android.content.ContentProvider\$Transport"

        /** Executable → 其中 `String` 类型形参的下标，避免在高频 provider 调用上重复反射。 */
        private val stringArgIndexes = ConcurrentHashMap<Executable, IntArray>()
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        log(Log.INFO, TAG, "system_server starting, installing hooks")
        hookAmsContext(param.classLoader)
        hookProviderRpc()
        hookLaunchPermission(param.classLoader)
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        if (param.packageName == MODULE_PACKAGE) {
            LsposedBridge.markInjected()
            log(Log.INFO, TAG, "module injected into own app process")
        }
    }

    /**
     * 捕获 `ActivityManagerService.mUiContext`（Android 16 字段名为 `mUiContext`，
     * 旧版本回退 `mContext`），用于初始化引擎。
     *
     * 注意：本回调发生在 **原构造器体执行之前**，此时字段尚未赋值，
     * 因此必须在 `chain.proceed()` 之后再读取，否则永远拿到 null。
     */
    private fun hookAmsContext(classLoader: ClassLoader) {
        runCatching {
            val ams = classLoader.loadClass("com.android.server.am.ActivityManagerService")
            val targets = ams.declaredConstructors
                .filter { it.parameterTypes.firstOrNull() == Context::class.java }
            targets.forEach { ctor ->
                hook(ctor).intercept { chain ->
                    val result = chain.proceed()
                    runCatching { attachContext(chain.thisObject) }
                        .onFailure { log(Log.ERROR, TAG, "attach daemon context failed", it) }
                    result
                }
            }
            log(Log.INFO, TAG, "ams constructor hooks installed: ${targets.size}")
        }.onFailure { log(Log.ERROR, TAG, "hookAmsContext failed", it) }
    }

    private fun attachContext(ams: Any) {
        val context = readContext(ams, "mUiContext") ?: readContext(ams, "mContext")
        if (context == null) {
            log(Log.WARN, TAG, "no usable context field on ${ams.javaClass.name}")
            return
        }
        SystemDaemon.attach(context)
        log(Log.INFO, TAG, "daemon engine ready in system_server")
    }

    private fun readContext(target: Any, fieldName: String): Context? = runCatching {
        val field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.get(target) as? Context
    }.getOrNull()

    /**
     * Hook 所有进程内 provider 调用的统一入口 `ContentProvider$Transport#call`。
     *
     * 该类位于 boot classpath，因此在 system_server 中可直接加载；
     * 相比 Hook `SettingsProvider#call` 免去了“拿到 provider APK 的 ClassLoader”这一步。
     * 只有 method 等于 [ProviderRpc.METHOD] 的调用被接管，其余原样放行。
     */
    private fun hookProviderRpc() {
        runCatching {
            val transport = Class.forName(TRANSPORT_CLASS)
            val targets = transport.declaredMethods.filter {
                it.name == "call" && it.parameterTypes.lastOrNull() == Bundle::class.java
            }
            targets.forEach { method ->
                hook(method).intercept { chain ->
                    val indexes = stringArgIndexes.getOrPut(chain.executable) {
                        chain.executable.parameterTypes.mapIndexedNotNull { i, type ->
                            if (type == String::class.java) i else null
                        }.toIntArray()
                    }
                    val isRpc = indexes.any { chain.getArg(it) == ProviderRpc.METHOD }
                    if (!isRpc) {
                        chain.proceed()
                    } else {
                        val engine = SystemDaemon.engine
                        val extras = chain.getArg(chain.executable.parameterTypes.size - 1) as? Bundle
                        if (engine == null) {
                            ProviderRpc.errorBundle(-2, "daemon engine not ready in system_server")
                        } else {
                            ProviderRpc.handle(engine, extras)
                        }
                    }
                }
            }
            log(Log.INFO, TAG, "provider rpc hooks installed on ${targets.size} overload(s)")
        }.onFailure { log(Log.ERROR, TAG, "hookProviderRpc failed", it) }
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
            val method = clz.declaredMethods.firstOrNull {
                it.name == "isCallerAllowedToLaunchOnDisplay" && it.parameterCount == 4
            } ?: throw NoSuchMethodException("isCallerAllowedToLaunchOnDisplay(IIILActivityInfo;)Z")
            hook(method).intercept { chain ->
                val displayId = chain.getArg(2) as? Int
                if (displayId != null && displayId != 0 && SystemDaemon.isManagedDisplay(displayId)) {
                    true
                } else {
                    chain.proceed()
                }
            }
            log(Log.INFO, TAG, "launch permission hook installed")
        }.onFailure { log(Log.ERROR, TAG, "hookLaunchPermission failed", it) }
    }
}

/**
 * system_server 内的守护引擎持有者：进程内唯一、重复附着幂等。
 */
object SystemDaemon {
    private const val TAG = "MoreDisplay_Lsposed"

    @Volatile
    private var systemContext: Context? = null

    @Volatile
    private var engineRef: DisplayEngine? = null

    val engine: DisplayEngine?
        get() = engineRef ?: createEngine()

    fun attach(ctx: Context) {
        if (systemContext == null) systemContext = ctx
        createEngine()
    }

    private fun createEngine(): DisplayEngine? {
        engineRef?.let { return it }
        val ctx = systemContext ?: return null
        synchronized(this) {
            engineRef?.let { return it }
            return SystemDisplayEngine(ctx).also {
                engineRef = it
                Log.i(TAG, "system daemon engine created pid=${android.os.Process.myPid()}")
            }
        }
    }

    fun isManagedDisplay(displayId: Int): Boolean =
        engineRef?.listDisplays()?.any { it.displayId == displayId } == true
}
