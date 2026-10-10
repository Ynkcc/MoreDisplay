package io.github.ynkcc.moredisplay.xposed

import android.os.Binder
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.core.VirtualDisplayFlags
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * 需求 B：录屏屏替换 —— 目标 App 录屏时实际录到的是托管虚拟屏，而不是默认屏。
 *
 * ## 落点（真机 services.jar dexdump 核实，Android 16 / SDK 36）
 * `DisplayManagerService#createVirtualDisplayInternal(...)`。
 * - AOSP（Android 14+）：7 参，末位是 callingUid —— `BinderService.createVirtualDisplay` 在
 *   `Binder.getCallingUid()` 之后把它传进来；
 * - ColorOS（Android 16 实测）：6 参，砍掉了 callingUid —— 此时在 proceed 前取
 *   `Binder.getCallingUid()`（该点在 clearCallingIdentity 之前，身份仍有效）。
 *
 * ## 为什么改 `mDisplayIdToMirror` 而不是换 Surface
 * 全链路只有一个 mirror 源真值：`VirtualDisplayConfig.mDisplayIdToMirror`（`PRIVATE FINAL int`）。实测它的两个消费者：
 * 1. `VirtualDisplayAdapter$VirtualDisplayDevice.<init>`：`mDisplayIdToMirror = config.getDisplayIdToMirror()`
 *    —— 决定显示设备真正镜像哪块屏，也是 `dumpsys display` 里打印 `mDisplayIdToMirror=` 的来源（验收证据）；
 * 2. `createVirtualDisplayInternal` 自身随后用同一个 getter 构造
 *    `ContentRecordingSession.createDisplaySession/createOverlaySession(displayIdToMirror)`
 *    → `IMediaProjectionManager.setContentRecordingSession(...)`（Android 15+ 的 ContentRecordingSession 路径）。
 * 所以只要在这个方法入口把 config 里的 mirror 源改掉，两条路都覆盖，且**不需要碰 App、不需要换 surface**。
 *
 * 客户端侧（framework.jar）确认：`DisplayManager.createVirtualDisplay(...)` 三种重载都会无条件
 * `builder.setDisplayIdToMirror(getDisplayIdToMirror())`，而该方法在单用户设备上恒返回
 * `Display.DEFAULT_DISPLAY(0)`（只有多用户可见后台用户时才返回 `getMainDisplayIdAssignedToUser()`）。
 * 也就是说「App 创建的虚拟屏」默认都记着「镜像 display 0」——这正是录屏要替换的东西。
 *
 * ## 只在真·镜像录屏上生效（避免误伤）
 * 同时满足才改写：
 * 1. 调用方是 App uid，且策略里配了 `recordDisplayId`；
 * 2. `getDisplayIdToMirror() == 0`（确实在镜像默认屏）；
 * 3. flags **不带** `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY`（那是「只显示自己内容」，不是镜像）；
 * 4. 满足下面任一条即认定为「镜像默认屏」：
 *    - flags 带 `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR`（0x10）；
 *    - 带了 MediaProjection token（`IMediaProjection` 非空 = 确实在录屏）。
 *    （放第二条是因为各 ROM/App 的 flags 组合差异大，而 `DisplayManager.getDisplayIdToMirror()`
 *    在单用户设备上恒返回 0，只有 token 才能真正区分「录屏」与「自建内容屏」；
 *    `OWN_CONTENT_ONLY` 仍会挡住「借 token 但自己画」的情况。）
 * 5. 不是 VirtualDevice（`companion/virtual`）创建的显示。
 *
 * ## 默认放行
 * 没配 `recordDisplayId` / 非 App uid / 任一条件不满足 → 原样 `proceed()`，行为与未装模块一致。
 */
private const val TAG = "MoreDisplay_Record"

/** `Display.DEFAULT_DISPLAY`。 */
private const val DEFAULT_DISPLAY_ID = 0

private val createHits = AtomicLong()
private val redirectApplied = AtomicLong()
private val redirectSkipped = AtomicLong()

@Volatile
private var mirrorField: Field? = null

@Volatile
private var flagsField: Field? = null

@Volatile
private var recordHookInstalled: String = "false"

fun XposedModule.installRecordMirrorHooks(classLoader: ClassLoader) {
    runCatching {
        val configClass = classLoader.loadClass("android.hardware.display.VirtualDisplayConfig")
        mirrorField = configClass.getDeclaredField("mDisplayIdToMirror").apply { isAccessible = true }
        flagsField = configClass.getDeclaredField("mFlags").apply { isAccessible = true }
        val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService")
        // AOSP（Android 14+）：7 参，末位是 callingUid；
        // ColorOS/others：6 参（砍掉 callingUid），此时在 proceed 前取 Binder.getCallingUid()。
        val method = clz.declaredMethods.firstOrNull {
            it.name == "createVirtualDisplayInternal" &&
                it.parameterTypes[0].name == "android.hardware.display.VirtualDisplayConfig" &&
                (it.parameterCount == 7 && it.parameterTypes[6] == java.lang.Integer.TYPE ||
                    it.parameterCount == 6)
        } ?: throw NoSuchMethodException(
            "DisplayManagerService.createVirtualDisplayInternal(VirtualDisplayConfig,...)"
        )
        val uidArgIndex = if (method.parameterCount == 7) 6 else -1
        hook(method).intercept { chain ->
            val config = chain.getArg(0)
            val uid = (uidArgIndex.takeIf { it >= 0 }?.let { chain.getArg(it) } as? Int)
                ?: Binder.getCallingUid()
            // uid 经 elvis 链后必为非空 Int，此处只需判 config
            if (config != null) {
                runCatching { tryRedirectMirror(this, chain, config, uid) }
                    .onFailure { Log.e(TAG, "mirror redirect failed uid=$uid", it) }
            }
            chain.proceed()
        }
        recordHookInstalled = "true"
        log(
            Log.INFO,
            TAG,
            "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()} " +
                "(params=${method.parameterCount}, mirrorField=${mirrorField != null}, flagsField=${flagsField != null})"
        )
    }.onFailure {
        recordHookInstalled = "false"
        log(Log.ERROR, TAG, "hook createVirtualDisplayInternal failed", it)
    }
}

private fun tryRedirectMirror(
    module: XposedModule,
    chain: XposedInterface.Chain,
    config: Any,
    uid: Int
) {
    val target = DisplayPolicyRegistry.recordDisplayIdFor(uid) ?: return
    createHits.incrementAndGet()

    val field = mirrorField ?: throw IllegalStateException("mDisplayIdToMirror field not resolved")
    val flags = (flagsField?.getInt(config)) ?: 0
    val current = field.getInt(config)
    val projection = chain.getArg(2)
    val virtualDevice = chain.getArg(3)

    val mirroringDefault = current == DEFAULT_DISPLAY_ID
    val autoMirror = flags and VirtualDisplayFlags.AUTO_MIRROR != 0
    val ownContentOnly = flags and VirtualDisplayFlags.OWN_CONTENT_ONLY != 0

    // 认定「这是在镜像默认屏」的两条等价路径：
    //   1. 显式带 AUTO_MIRROR；
    //   2. 带了 MediaProjection token（录屏），且没声明 OWN_CONTENT_ONLY。
    // 两条都要求 mirror 源确实是 display 0 —— 只改「镜像默认屏」这一种，别的一律不碰。
    val mirroringDefaultDisplay = mirroringDefault && !ownContentOnly &&
        (autoMirror || projection != null)

    if (!mirroringDefaultDisplay || virtualDevice != null) {
        val s = redirectSkipped.incrementAndGet()
        Log.i(
            TAG,
            "RECORD skip uid=$uid mirror=$current flags=0x${Integer.toHexString(flags)} " +
                "autoMirror=$autoMirror ownContentOnly=$ownContentOnly " +
                "vdev=${virtualDevice != null} projection=${projection != null} (skip#=$s)"
        )
        return
    }

    field.setInt(config, target)
    val a = redirectApplied.incrementAndGet()
    Log.i(
        TAG,
        "RECORD redirect uid=$uid mirror $DEFAULT_DISPLAY_ID -> $target " +
            "flags=0x${Integer.toHexString(flags)} projection=${projection != null} (applied#=$a)"
    )
}

/** 供验收/自查：需求 B 的 Hook 命中计数（区分「已装」与「已生效」）。 */
fun recordMirrorHookStats(): String =
    "record(installed=$recordHookInstalled call=${createHits.get()} " +
        "applied=${redirectApplied.get()} skip=${redirectSkipped.get()})"
