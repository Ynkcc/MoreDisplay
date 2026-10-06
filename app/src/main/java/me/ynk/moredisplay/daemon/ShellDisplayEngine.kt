package me.ynk.moredisplay.daemon

import android.hardware.display.DisplayManager
import android.util.Log
import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.core.VirtualDisplayFlags
import me.ynk.moredisplay.dispatch.CapabilityMatrix
import me.ynk.moredisplay.dispatch.CapabilityMatrix.TRUSTED_GOVERNED_FLAGS
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

    // 特权 flag（TRUSTED/OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED/SHOULD_SHOW_SYSTEM_DECORATIONS）
    // 由 DMS 按调用方权限逐项校验。uid 0 全部放行；uid 2000 取决于 ROM 是否给
    // com.android.shell 授予 ADD_TRUSTED_DISPLAY 等权限，因此启动时实测一次。
    private val hasPrivilege: (String) -> Boolean = { permission ->
        android.os.Process.myUid() == 0 || runCatching {
            context.checkPermission(
                permission, android.os.Process.myPid(), android.os.Process.myUid()
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    override val capabilities =
        CapabilityMatrix.shellPrivilegeCapabilities(hasPrivilege)

    override fun createDisplay(request: DisplaySpec): DisplayInfo {
        var flags = CapabilityMatrix.normalizeFlagCombination(
            CapabilityMatrix.flagsForSdk(request.flags)
        )
        // 与 DMS 行为对齐：非 TRUSTED 的屏 sys-decor 会被静默剔除，直接上报真实值。
        if (flags and VirtualDisplayFlags.TRUSTED == 0) {
            flags = flags and VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS.inv()
        }
        // 探测已覆盖绝大多数场景；SecurityException 兜底做逐级降级，避免整次 create 失败。
        var vd: android.hardware.display.VirtualDisplay? = null
        repeat(4) { attempt ->
            if (vd != null) return@repeat
            try {
                vd = checkNotNull(manager) { "DisplayManager unavailable" }.createVirtualDisplay(
                    request.name, request.width, request.height, request.densityDpi, null, flags
                )
            } catch (e: SecurityException) {
                val downgraded = downgradePrivilegedFlags(flags, e)
                if (downgraded == flags || attempt == 3) throw e
                Log.w(
                    TAG, "flags 0x${Integer.toHexString(flags)} rejected by DMS (${e.message}), " +
                        "retry with 0x${Integer.toHexString(downgraded)}"
                )
                flags = downgraded
            }
        }
        val display = checkNotNull(vd) { "createVirtualDisplay returned null for $request" }
        val spec = request.copy(flags = flags)
        val info = DisplayInfo(display.display.displayId, spec)
        managed[info.displayId] = info
        virtualDisplays[info.displayId] = display
        Log.i(TAG, "created displayId=${info.displayId} ${spec.width}x${spec.height}@${spec.densityDpi} flags=0x${Integer.toHexString(flags)}")
        return info
    }

    /** 按异常信息剔除被拒绝的特权 flag；消息无法判读时一次性剔除全部特权 flag。 */
    private fun downgradePrivilegedFlags(flags: Int, e: SecurityException): Int {
        val msg = e.message ?: return flags and TRUSTED_GOVERNED_FLAGS.inv()
        var next = flags
        when {
            msg.contains("ADD_TRUSTED_DISPLAY") ->
                next = next and (VirtualDisplayFlags.TRUSTED or VirtualDisplayFlags.OWN_DISPLAY_GROUP).inv()
            msg.contains("ADD_ALWAYS_UNLOCKED_DISPLAY") ->
                next = next and VirtualDisplayFlags.ALWAYS_UNLOCKED.inv()
            msg.contains("INTERNAL_SYSTEM_WINDOW") ->
                next = next and VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS.inv()
            else -> next = flags and TRUSTED_GOVERNED_FLAGS.inv()
        }
        // TRUSTED 被剔除后 sys-decor 必然失效，一并剔除。
        if (next and VirtualDisplayFlags.TRUSTED == 0) {
            next = next and VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS.inv()
        }
        return next
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
