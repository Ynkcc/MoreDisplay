package me.ynk.moredisplay.daemon

import android.hardware.display.DisplayManager
import android.util.Log
import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.dispatch.CapabilityMatrix
import me.ynk.moredisplay.dispatch.AndroidVersions

class ShellDisplayEngine(
    context: android.content.Context
) : DisplayEngine {

    companion object {
        private const val TAG = "ShellDisplayEngine"
        private const val SHELL_PACKAGE = "com.android.shell"
    }

    // daemon 以 shell uid 运行时，App 包名不属于该 uid，DMS 校验
    // "packageName must match the owner uid" 会拒绝；改用 uid 所属包名 com.android.shell。
    // root uid 免此校验，直接用原 context。
    private val validatedContext: android.content.Context = run {
        val appUid = runCatching {
            context.packageManager.getPackageUid(context.packageName, 0)
        }.getOrDefault(-1)
        if (android.os.Process.myUid() == appUid || android.os.Process.myUid() == 0) context
        else object : android.content.ContextWrapper(context) {
            override fun getPackageName(): String = SHELL_PACKAGE
            override fun getOpPackageName(): String = SHELL_PACKAGE
            override fun getAttributionSource(): android.content.AttributionSource = shellAttributionSource
            override fun getSystemService(name: String): Any? {
                if (name == android.content.Context.DISPLAY_SERVICE) {
                    // ContextWrapper.getSystemService 会委托回 base context，
                    // 必须反射 DisplayManager(Context) 构造器，让 mPackageName
                    // 取到本 wrapper 重写后的 opPackageName。
                    return runCatching {
                        DisplayManager::class.java.declaredConstructors
                            .first { it.parameterTypes.size == 1 && it.parameterTypes[0] == android.content.Context::class.java }
                            .apply { isAccessible = true }
                            .newInstance(this) as DisplayManager
                    }.getOrElse {
                        Log.e(TAG, "reflective DisplayManager construction failed", it)
                        super.getSystemService(name)
                    }
                }
                return super.getSystemService(name)
            }
        }
    }

    private val manager =
        validatedContext.getSystemService(DisplayManager::class.java)

    private val shellAttributionSource: android.content.AttributionSource by lazy {
        val uid = android.os.Process.myUid()
        val cls = android.content.AttributionSource::class.java
        val ctor = cls.declaredConstructors.firstOrNull {
            it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java
        } ?: error("AttributionSource(uid, packageName, ...) constructor not found")
        ctor.isAccessible = true
        val args = ctor.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 -> uid
                i == 1 -> SHELL_PACKAGE
                t == Int::class.javaPrimitiveType -> 0
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        ctor.newInstance(*args) as android.content.AttributionSource
    }
    private val managed = linkedMapOf<Int, DisplayInfo>()
    private val virtualDisplays = linkedMapOf<Int, android.hardware.display.VirtualDisplay>()

    override val capabilities = CapabilityMatrix.forPrivilege(me.ynk.moredisplay.core.Privilege.SHELL_SHIZUKU)

    override fun createDisplay(spec: DisplaySpec): DisplayInfo {
        val flags = CapabilityMatrix.flagsForSdk(spec.flags)
        val vd = checkNotNull(manager) { "DisplayManager unavailable" }.createVirtualDisplay(
            spec.name, spec.width, spec.height, spec.densityDpi, null, flags
        ) ?: throw IllegalStateException("createVirtualDisplay returned null for $spec")
        val info = DisplayInfo(vd.display.displayId, spec)
        managed[info.displayId] = info
        virtualDisplays[info.displayId] = vd
        Log.i(TAG, "created displayId=${info.displayId} ${spec.width}x${spec.height}@${spec.densityDpi} flags=0x${Integer.toHexString(flags)}")
        return info
    }

    override fun holdDisplay(displayId: Int): DisplayInfo? {
        managed[displayId]?.let { return it }
        val display = manager?.getDisplay(displayId) ?: return null
        val dpi = validatedContext.resources.displayMetrics.densityDpi
        return DisplayInfo(displayId, DisplaySpec(display.width, display.height, dpi)).also { managed[displayId] = it }
    }

    override fun removeDisplay(displayId: Int) {
        val info = managed.remove(displayId) ?: throw IllegalArgumentException("display $displayId not managed")
        val vd = virtualDisplays.remove(displayId) ?: throw IllegalStateException("display $displayId has no VirtualDisplay handle")
        runCatching { vd.release() }.onFailure {
            Log.e(TAG, "release displayId=$displayId failed", it)
            throw it
        }
        Log.i(TAG, "removed displayId=$displayId")
    }

    override fun listDisplays(): List<DisplayInfo> = managed.values.toList()
}
