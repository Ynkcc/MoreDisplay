package io.github.ynkcc.moredisplay.xposed

import android.content.ComponentName
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * 需求 C：无障碍屏替换 —— 目标 App 的无障碍手势被改写到托管虚拟屏。
 *
 * ## 落点（真机 services.jar dexdump 核实，Android 16 / SDK 36）
 * `AccessibilityServiceConnection.dispatchGesture(I, ParceledListSlice, I)V`
 * （继承自 `AbstractAccessibilityServiceConnection`，重写实现位于子类）。
 * 真机方法体（偏移 2f4054）核实调用链：
 * ```
 * dispatchGesture(sequence, steps, displayId)
 *   -> SystemSupport.getMotionEventInjectorForDisplayLocked(displayId)
 *   -> MotionEventInjector.injectEvents(list, client, sequence, displayId, isAccessibilityTool)
 * ```
 * 第三参就是目标 displayId，一路传到手势注入器；在此改写即可让整条注入链落到托管屏。
 *
 * 客户端侧（framework）确认：`AccessibilityService.dispatchGesture(...)` 把
 * `gesture.getDisplayId()` 原样传给 binder；`GestureDescription.Builder.mDisplayId`
 * 默认值是 `Display.DEFAULT_DISPLAY(0)`。也就是说常规自动点击/滑动脚本如果不显式
 * `setDisplayId`，手势都落在默认屏 —— 这正是要替换的对象。
 *
 * ## 为什么按「包名 + mUserId」匹配而不是 uid
 * 无障碍连接对象（`AbstractAccessibilityServiceConnection`）上没有 uid 字段，
 * 只有 `mComponentName` 和 `mUserId`（真机 dexdump 核实实例字段表）。策略条目的
 * uid 本身编码了用户（`uid / 100000` == mUserId），所以按「包名 + 服务所属用户」
 * 匹配即可精确对位，也免去 `PackageManager.getPackageUidAsUser` 的每次解析。
 * 多用户下同一包名会为每个用户各装一份（uid 不同），仅按包名匹配会有歧义，
 * 因此 mUserId 是必需维度。
 *
 * ## 只在「目标默认屏」时改写（避免误伤）
 * 同时满足才改写：
 * 1. 连接的服务包名在策略里配了 `operatedDisplayId`；
 * 2. 手势目标 displayId == 0（默认屏；服务显式指向其它屏的一律放行）；
 * 3. 目标 displayId 是本守护引擎托管的虚拟屏（`SystemDaemon.isManagedDisplay`）。
 *
 * ## 默认放行
 * 没配 `operatedDisplayId` / 包名不匹配 / 任一条件不满足 → 原样 `proceed()`，
 * 行为与未装模块一致。`performGlobalAction` 等系统级动作不在此链路，不受影响。
 */
private const val TAG = "MoreDisplay_A11y"

/** `Display.DEFAULT_DISPLAY`。 */
private const val DEFAULT_DISPLAY_ID = 0

private val gestureHits = AtomicLong()
private val redirectApplied = AtomicLong()
private val redirectSkipped = AtomicLong()

/** mComponentName 字段（声明在抽象父类 `AbstractAccessibilityServiceConnection` 上）。 */
@Volatile
private var componentField: Field? = null

/** mUserId 字段（`AccessibilityServiceConnection` 上，服务所属用户）。 */
@Volatile
private var userIdField: Field? = null

@Volatile
private var accessibilityHookInstalled: String = "false"

fun XposedModule.installAccessibilityRedirectHooks(classLoader: ClassLoader) {
    runCatching {
        val clz = classLoader.loadClass("com.android.server.accessibility.AccessibilityServiceConnection")
        val method = clz.declaredMethods.firstOrNull {
            it.name == "dispatchGesture" &&
                it.parameterCount == 3 &&
                it.parameterTypes[0] == java.lang.Integer.TYPE &&
                it.parameterTypes[1].name == "android.content.pm.ParceledListSlice" &&
                it.parameterTypes[2] == java.lang.Integer.TYPE
        } ?: throw NoSuchMethodException(
            "AccessibilityServiceConnection.dispatchGesture(I,ParceledListSlice,I)V"
        )
        val superClz = clz.superclass
        componentField = runCatching {
            clz.getDeclaredField("mComponentName").apply { isAccessible = true }
        }.recoverCatching {
            superClz.getDeclaredField("mComponentName").apply { isAccessible = true }
        }.getOrNull() ?: throw NoSuchFieldException(
            "mComponentName not found on $clz / $superClz"
        )
        userIdField = runCatching {
            clz.getDeclaredField("mUserId").apply { isAccessible = true }
        }.recoverCatching {
            superClz.getDeclaredField("mUserId").apply { isAccessible = true }
        }.getOrNull()
        hook(method).intercept { chain ->
            val displayId = chain.getArg(2) as? Int
            val handled = if (displayId != null) {
                runCatching { tryRedirectGesture(chain, displayId) }
                    .onFailure { Log.e(TAG, "gesture redirect failed", it) }
                    .getOrDefault(false)
            } else {
                false
            }
            if (!handled) chain.proceed() else null
        }
        accessibilityHookInstalled = "true"
        log(
            Log.INFO,
            TAG,
            "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()} " +
                "(componentField=${componentField != null})"
        )
    }.onFailure {
        accessibilityHookInstalled = "false"
        log(Log.ERROR, TAG, "hook AccessibilityServiceConnection.dispatchGesture failed", it)
    }
}

/**
 * 返回 true 表示已用改写后的参数完成调用（外层不得再 proceed）。
 */
private fun tryRedirectGesture(chain: XposedInterface.Chain, displayId: Int): Boolean {
    val connection = chain.thisObject ?: return false
    val field = componentField ?: return false
    val componentName = field.get(connection) as? ComponentName ?: return false
    val packageName = componentName.packageName
    // 服务所属用户：多用户下同一包名各用户各一份（uid 不同），必须带 userId 消歧。
    val userId = userIdField?.getInt(connection) ?: 0

    val target = DisplayPolicyRegistry.operatedDisplayIdForPackage(packageName, userId)
        ?: return false

    gestureHits.incrementAndGet()

    val redirectable = displayId == DEFAULT_DISPLAY_ID &&
        target != DEFAULT_DISPLAY_ID &&
        SystemDaemon.isManagedDisplay(target)

    if (!redirectable) {
        val s = redirectSkipped.incrementAndGet()
        if (s <= 5 || s % 100 == 0L) {
            Log.i(TAG, "A11Y skip pkg=$packageName displayId=$displayId target=$target (skip#=$s)")
        }
        return false
    }

    // 用改写后的参数继续原流程：injector 选择与 injectEvents 全都用这个 displayId。
    val args = chain.args.toTypedArray()
    args[2] = target
    redirectApplied.incrementAndGet()
    Log.i(
        TAG,
        "A11Y redirect pkg=$packageName user=$userId gesture $displayId -> $target " +
            "(applied#${redirectApplied.get()})"
    )
    chain.proceedWith(chain.thisObject, args)
    return true
}

/** 供验收/自查：需求 C 的 Hook 命中计数（区分「已装」与「已生效」）。 */
fun accessibilityHookStats(): String =
    "a11y(installed=$accessibilityHookInstalled call=${gestureHits.get()} " +
        "applied=${redirectApplied.get()} skip=${redirectSkipped.get()})"
