package io.github.ynkcc.moredisplay.daemon

import android.hardware.display.DisplayManager
import android.util.Log
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplaySpec
import io.github.ynkcc.moredisplay.core.VirtualDisplayFlags
import io.github.ynkcc.moredisplay.dispatch.CapabilityMatrix
import io.github.ynkcc.moredisplay.dispatch.CapabilityMatrix.TRUSTED_GOVERNED_FLAGS
import io.github.ynkcc.moredisplay.dispatch.AndroidVersions

class ShellDisplayEngine(
    private val context: android.content.Context
) : DisplayEngine {

    companion object {
        private const val TAG = "ShellDisplayEngine"
        private const val SHELL_PACKAGE = "com.android.shell"
    }

    private val myUid = android.os.Process.myUid()
    private val appUid = runCatching {
        context.packageManager.getPackageUid(context.packageName, 0)
    }.getOrDefault(-1)

    /**
     * DMS 的 `validatePackageName` 要求包名必须属于调用 uid。daemon 与 app 不同
     * uid 时（shell=2000 / root=0）不能直接用 app 包名：
     * - uid 2000：getPackagesForUid → [com.android.shell]，天然合法；
     * - uid 0：各 ROM 行为不一（实测有放行也有拒绝），依次尝试候选并在
     *   "packageName must match" 时降级重试。
     */
    private val candidatePackageNames: List<String> = buildList {
        if (myUid == appUid) {
            add(context.packageName)
            return@buildList
        }
        runCatching { context.packageManager.getPackagesForUid(myUid) }
            .getOrNull()
            ?.filterTo(this) { it.isNotBlank() }
        if (!contains(SHELL_PACKAGE)) add(SHELL_PACKAGE)
        if (!contains(context.packageName)) add(context.packageName)
    }

    /** 首次 create 成功后定格的 context，供 holdDisplay 等复用。 */
    @Volatile
    private var resolvedContext: android.content.Context? = null

    private val wrapperCache = mutableMapOf<String, android.content.Context>()

    private fun contextFor(packageName: String): android.content.Context {
        if (packageName == context.packageName && myUid == appUid) return context
        return wrapperCache.getOrPut(packageName) {
            object : android.content.ContextWrapper(context) {
                override fun getPackageName(): String = packageName
                override fun getOpPackageName(): String = packageName
                override fun getAttributionSource(): android.content.AttributionSource =
                    attributionSourceFor(packageName)

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
    }

    private fun attributionSourceFor(packageName: String): android.content.AttributionSource {
        val cls = android.content.AttributionSource::class.java
        val ctor = cls.declaredConstructors.firstOrNull {
            it.parameterTypes.size >= 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == String::class.java
        } ?: error("AttributionSource(uid, packageName, ...) constructor not found")
        ctor.isAccessible = true
        val args = ctor.parameterTypes.mapIndexed { i, t ->
            when {
                i == 0 -> myUid
                i == 1 -> packageName
                t == Int::class.javaPrimitiveType -> 0
                t == Boolean::class.javaPrimitiveType -> false
                else -> null
            }
        }.toTypedArray()
        return ctor.newInstance(*args) as android.content.AttributionSource
    }

    private fun displayManagerOf(ctx: android.content.Context): DisplayManager =
        checkNotNull(ctx.getSystemService(DisplayManager::class.java)) { "DisplayManager unavailable" }

    private val managed = linkedMapOf<Int, DisplayInfo>()
    private val virtualDisplays = linkedMapOf<Int, android.hardware.display.VirtualDisplay>()

    // 特权 flag（TRUSTED/OWN_DISPLAY_GROUP/ALWAYS_UNLOCKED/SHOULD_SHOW_SYSTEM_DECORATIONS）
    // 由 DMS 按调用方权限逐项校验。uid 0 全部放行；uid 2000 取决于 ROM 是否给
    // com.android.shell 授予 ADD_TRUSTED_DISPLAY 等权限，因此启动时实测一次。
    private val hasPrivilege: (String) -> Boolean = { permission ->
        myUid == 0 || runCatching {
            context.checkPermission(
                permission, android.os.Process.myPid(), myUid
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
        val candidates = buildList {
            resolvedContext?.let { add(it.packageName) }
            addAll(candidatePackageNames)
        }.distinct()
        var lastError: SecurityException? = null
        for (name in candidates) {
            val ctx = contextFor(name)
            try {
                val (vd, effectiveFlags) = createWithFlagDowngrade(ctx, request, flags)
                flags = effectiveFlags
                resolvedContext = ctx
                val spec = request.copy(flags = flags)
                val info = DisplayInfo(vd.display.displayId, spec)
                managed[info.displayId] = info
                virtualDisplays[info.displayId] = vd
                Log.i(TAG, "created displayId=${info.displayId} ${spec.width}x${spec.height}@${spec.densityDpi} " +
                    "flags=0x${Integer.toHexString(flags)} as $name (uid=$myUid)")
                return info
            } catch (e: SecurityException) {
                if (e.message?.contains("packageName must match") == true) {
                    Log.w(TAG, "package '$name' rejected by DMS (uid=$myUid), try next candidate")
                    lastError = e
                    continue
                }
                throw e
            }
        }
        throw lastError ?: IllegalStateException(
            "no usable package name for uid=$myUid, candidates=$candidates"
        )
    }

    private fun createWithFlagDowngrade(
        ctx: android.content.Context,
        request: DisplaySpec,
        initialFlags: Int
    ): Pair<android.hardware.display.VirtualDisplay, Int> {
        var flags = initialFlags
        var vd: android.hardware.display.VirtualDisplay? = null
        repeat(4) { attempt ->
            if (vd != null) return@repeat
            try {
                vd = displayManagerOf(ctx).createVirtualDisplay(
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
        return display to flags
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
            // 无投屏权限时：镜像（AUTO_MIRROR，含 PUBLIC 隐含的）不可用，
            // 降级为 OWN_CONTENT_ONLY 自绘屏；SECURE 屏需要 CAPTURE_SECURE_VIDEO_OUTPUT。
            // 注意 AUTO_MIRROR 的报错消息同时列出两个权限名，须用唯一短语区分。
            msg.contains("screen sharing virtual display") ->
                next = (next and VirtualDisplayFlags.AUTO_MIRROR.inv()) or VirtualDisplayFlags.OWN_CONTENT_ONLY
            msg.contains("secure virtual display") ->
                next = next and VirtualDisplayFlags.SECURE.inv()
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
        val ctx = resolvedContext ?: context
        val display = displayManagerOf(ctx).getDisplay(displayId) ?: return null
        val dpi = ctx.resources.displayMetrics.densityDpi
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
