package me.ynk.moredisplay.daemon

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.util.Log
import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.core.Privilege
import me.ynk.moredisplay.dispatch.CapabilityMatrix

/**
 * 运行在 system_server（LSPosed 注入）内的显示器引擎。
 *
 * 与 [ShellDisplayEngine] 的差别只在执行身份：这里以 uid 1000 / 包名 `android` 创建虚拟屏，
 * 因此可以携带 `TRUSTED` 等特权 flag，且归属 system_server，
 * 调用方 App 进程退出也不会带走已创建的屏幕。
 *
 * ## 为什么直接走 `DisplayManagerGlobal` 而不是 `DisplayManager`
 * 引擎在 AMS 构造完成时就被创建，那一刻 DisplayManagerService 还没注册，
 * `DisplayManager` 构造期捕获的 `mGlobal` 会是 null（且该实例已被 Context 缓存），
 * 之后再调用 `createVirtualDisplay` 必然 NPE。
 * `DisplayManagerGlobal.getInstance()` 是静态方法、每次都会重试取服务，因此安全。
 */
class SystemDisplayEngine(
    private val systemContext: Context
) : DisplayEngine {

    companion object {
        private const val TAG = "SystemDisplayEngine"
        private const val GLOBAL_CLASS = "android.hardware.display.DisplayManagerGlobal"
    }

    private val lock = Any()
    private val managed = LinkedHashMap<Int, DisplayInfo>()
    private val handles = LinkedHashMap<Int, VirtualDisplay>()

    override val capabilities = CapabilityMatrix.forPrivilege(Privilege.LSPOSED)

    override fun createDisplay(spec: DisplaySpec): DisplayInfo {
        if (!capabilities.supportsFlags(spec.flags)) {
            throw SecurityException("unsupported flags 0x${Integer.toHexString(spec.flags)}")
        }
        val flags = CapabilityMatrix.flagsForSdk(spec.flags)

        val global = Class.forName(GLOBAL_CLASS).getMethod("getInstance").invoke(null)
            ?: throw IllegalStateException("DisplayManagerGlobal unavailable (is the display service up?)")
        val create = global.javaClass.methods.firstOrNull {
            it.name == "createVirtualDisplay" &&
                it.parameterTypes.firstOrNull() == Context::class.java &&
                it.returnType == VirtualDisplay::class.java
        } ?: throw NoSuchMethodException("createVirtualDisplay(Context, ...): VirtualDisplay not found")

        val config = VirtualDisplayConfig.Builder(spec.name, spec.width, spec.height, spec.densityDpi)
            .setFlags(flags)
            .build()
        val vd = create.invoke(global, systemContext, null, config, null, null) as? VirtualDisplay
            ?: throw IllegalStateException("createVirtualDisplay returned null for $spec")

        val info = DisplayInfo(vd.display.displayId, spec)
        synchronized(lock) {
            managed[info.displayId] = info
            handles[info.displayId] = vd
        }
        Log.i(
            TAG,
            "created displayId=${info.displayId} ${spec.width}x${spec.height}@${spec.densityDpi} " +
                "flags=0x${Integer.toHexString(flags)} owner=${systemContext.packageName}"
        )
        return info
    }

    override fun holdDisplay(displayId: Int): DisplayInfo? = synchronized(lock) { managed[displayId] }

    override fun removeDisplay(displayId: Int) {
        val vd = synchronized(lock) {
            managed.remove(displayId) ?: throw IllegalArgumentException("display $displayId not managed")
            handles.remove(displayId)
                ?: throw IllegalStateException("display $displayId has no VirtualDisplay handle")
        }
        vd.release()
        Log.i(TAG, "released displayId=$displayId")
    }

    override fun listDisplays(): List<DisplayInfo> = synchronized(lock) { managed.values.toList() }
}
