package io.github.ynkcc.moredisplay.xposed

import android.os.Binder
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.ynkcc.moredisplay.daemon.DisplayPolicyRegistry
import java.lang.reflect.Field
import java.util.concurrent.atomic.AtomicLong

/**
 * 需求 A：按调用方 uid 过滤屏幕可见性（黑名单 / 白名单）。
 *
 * ## 为什么全部落在 system_server 侧
 * 模块作用域只有 `android`。所有 `IDisplayManager` 的 binder 请求与所有
 * `DisplayListener` 事件都在 system_server 内产生，所以在这里裁剪即可覆盖全部 App，
 * **完全不需要 Hook 目标 App 进程**。
 *
 * ## 挂载点（签名已用 dexdump 在真机 services.jar 上核实，Android 16 / SDK 36）
 * 1. `DisplayManagerService$BinderService#getDisplayIds(Z)[I`
 *    —— App 拿列表的 binder 入口（`DisplayManager.getDisplays()` 也走它）。
 *    实测：`Binder.getCallingUid()` → `clearCallingIdentity()` → `LogicalDisplayMapper.getDisplayIdsLocked(uid, includeDisabled)`。
 *    所以必须在 `chain.proceed()` **之前**取 `Binder.getCallingUid()`，再对返回的 `int[]` 裁剪。
 * 2. `DisplayManagerService#getDisplayInfoInternal(II)Landroid/view/DisplayInfo;`
 *    —— 点名入口，第二个参数就是 `callingUid`。
 *    实测调用链：`BinderService.getDisplayInfo(I)` → `getDisplayInfoInternal(displayId, callingUid)`；
 *    系统自身走 `LocalService.getDisplayInfo(I)` → `getDisplayInfoInternal(displayId, Process.myUid())`。
 *    命中策略即返回 `null`（“猜 id 也拿不到”）。
 * 3. 事件投递（per-callback 汇聚点，两层保险）
 *    - `DisplayManagerService$CallbackRecord#notifyDisplayEventAsync(II)Z`
 *      —— `DisplayManagerService.deliverDisplayEvent(...)` 对每个回调调用的入口（真机 dexdump 核实）。
 *      `this` 是 `CallbackRecord`，`mUid` 字段即接收方 uid；命中策略时返回 `false`（不投递）。
 *    - `DisplayManagerService$CallbackRecord#transmitDisplayEvent(II)V`
 *      —— 最后一跳 `mCallback.onDisplayEvent(displayId, event)`；
 *      `dispatchPending()`（缓存事件补投）与 `notifyDisplayEventAsync()` 都汇聚到这里，作为兜底。
 *    两个 Hook 合起来覆盖 `onDisplayAdded/onDisplayRemoved/onDisplayChanged`，
 *    也包括「回调未就绪被缓存、稍后 dispatchPending 补投」的情况。
 *
 * ## 默认放行
 * 没有策略条目、或调用方不是 App uid（uid<10000：system/systemui/shell/root…）时，
 * 所有 Hook 都走原路径，行为与未装模块完全一致。
 */
private const val TAG = "MoreDisplay_Visibility"

private val listHits = AtomicLong()
private val listTrimmed = AtomicLong()
private val pointHits = AtomicLong()
private val pointBlocked = AtomicLong()
private val notifyHits = AtomicLong()
private val notifySuppressed = AtomicLong()
private val transmitHits = AtomicLong()
private val transmitSuppressed = AtomicLong()
private val uidReadFailures = AtomicLong()

/** 已装状态：区分「已装」与「已生效」。 */
private val installed = LinkedHashMap<String, Boolean>()

/** 安装完成后的快照，供其它线程无锁读取。 */
@Volatile
private var installedSummary: String = "<not installed>"

@Volatile
private var callbackUidField: Field? = null

fun XposedModule.installDisplayVisibilityHooks(classLoader: ClassLoader) {
    hookBinderGetDisplayIds(classLoader)
    hookGetDisplayInfoInternal(classLoader)
    hookCallbackNotifyDisplayEvent(classLoader)
    hookCallbackTransmitDisplayEvents(classLoader)
    hookDeliverDisplayEventDiag(classLoader)
    installedSummary = installed.entries.joinToString(prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
    log(Log.INFO, TAG, "visibility hooks installed: $installedSummary")
}

private fun XposedModule.hookBinderGetDisplayIds(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService\$BinderService")
    val method = clz.declaredMethods.firstOrNull {
        it.name == "getDisplayIds" &&
            it.parameterCount == 1 &&
            it.parameterTypes[0] == java.lang.Boolean.TYPE &&
            it.returnType == IntArray::class.java
    } ?: throw NoSuchMethodException("BinderService.getDisplayIds(Z)[I")
    hook(method).intercept { chain ->
        // 必须在 proceed 之前取：方法内部会 clearCallingIdentity()。
        val callingUid = Binder.getCallingUid()
        val result = chain.proceed()
        val ids = result as? IntArray
        if (ids == null || DisplayPolicyRegistry.get(callingUid) == null) {
            result
        } else {
            val filtered = DisplayPolicyRegistry.filter(callingUid, ids)
            val n = listHits.incrementAndGet()
            if (filtered.size != ids.size) {
                val t = listTrimmed.incrementAndGet()
                Log.i(
                    TAG,
                    "LIST trim uid=$callingUid ${ids.toList()} -> ${filtered.toList()} (call#=$n trim#=$t)"
                )
            } else if (n <= 3 || n % 200 == 0L) {
                Log.i(TAG, "LIST pass uid=$callingUid ${ids.toList()} (call#=$n)")
            }
            filtered
        }
    }
    installed["BinderService.getDisplayIds"] = true
    log(Log.INFO, TAG, "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()} -> ${method.returnType.name}")
}.onFailure {
    installed["BinderService.getDisplayIds"] = false
    log(Log.ERROR, TAG, "hook BinderService.getDisplayIds failed", it)
}

private fun XposedModule.hookGetDisplayInfoInternal(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService")
    val method = clz.declaredMethods.firstOrNull {
        it.name == "getDisplayInfoInternal" &&
            it.parameterCount == 2 &&
            it.parameterTypes[0] == java.lang.Integer.TYPE &&
            it.parameterTypes[1] == java.lang.Integer.TYPE
    } ?: throw NoSuchMethodException("DisplayManagerService.getDisplayInfoInternal(II)")
    hook(method).intercept { chain ->
        val displayId = chain.getArg(0) as? Int
        val uid = chain.getArg(1) as? Int
        val result = chain.proceed()
        if (result == null || displayId == null || uid == null) {
            result
        } else {
            pointHits.incrementAndGet()
            if (!DisplayPolicyRegistry.isVisible(uid, displayId)) {
                val b = pointBlocked.incrementAndGet()
                Log.i(TAG, "POINT block uid=$uid displayId=$displayId -> null (block#=$b)")
                null
            } else {
                result
            }
        }
    }
    installed["DisplayManagerService.getDisplayInfoInternal"] = true
    log(Log.INFO, TAG, "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}")
}.onFailure {
    installed["DisplayManagerService.getDisplayInfoInternal"] = false
    log(Log.ERROR, TAG, "hook getDisplayInfoInternal failed", it)
}

private fun XposedModule.hookCallbackTransmitDisplayEvents(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService\$CallbackRecord")
    val method = clz.declaredMethods.firstOrNull {
        it.name == "transmitDisplayEvent" &&
            it.parameterCount == 2 &&
            it.returnType == Void.TYPE
    } ?: throw NoSuchMethodException("CallbackRecord.transmitDisplayEvent(II)V")
    callbackUidField = runCatching {
        clz.getDeclaredField("mUid").apply { isAccessible = true }
    }.getOrNull()
    hook(method).intercept { chain ->
        val displayId = chain.getArg(0) as? Int
        val record = chain.thisObject
        val uid = record?.let { readCallbackUid(it) }
        if (displayId == null || uid == null) {
            chain.proceed()
        } else {
            transmitHits.incrementAndGet()
            if (!DisplayPolicyRegistry.isVisible(uid, displayId)) {
                val s = transmitSuppressed.incrementAndGet()
                Log.i(TAG, "EVENT transmit-suppress uid=$uid displayId=$displayId (suppress#=$s)")
                null
            } else {
                chain.proceed()
            }
        }
    }
    installed["CallbackRecord.transmitDisplayEvent"] = true
    log(Log.INFO, TAG, "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}")
}.onFailure {
    installed["CallbackRecord.transmitDisplayEvent"] = false
    log(Log.ERROR, TAG, "hook transmitDisplayEvent failed", it)
}

/**
 * 事件投递的 per-callback 入口：`deliverDisplayEvent` 对每个回调调用它。
 * 在此直接吞掉被隐藏 displayId 的事件，粒度最精确。
 */
private fun XposedModule.hookCallbackNotifyDisplayEvent(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService\$CallbackRecord")
    val method = clz.declaredMethods.firstOrNull {
        it.name == "notifyDisplayEventAsync" &&
            it.parameterCount == 2 &&
            it.parameterTypes[0] == java.lang.Integer.TYPE &&
            it.parameterTypes[1] == java.lang.Integer.TYPE
    } ?: throw NoSuchMethodException("CallbackRecord.notifyDisplayEventAsync(II)Z")
    hook(method).intercept { chain ->
        val displayId = chain.getArg(0) as? Int
        val record = chain.thisObject
        val uid = record?.let { readCallbackUid(it) }
        if (displayId == null || uid == null) {
            chain.proceed()
        } else {
            notifyHits.incrementAndGet()
            if (!DisplayPolicyRegistry.isVisible(uid, displayId)) {
                val s = notifySuppressed.incrementAndGet()
                Log.i(TAG, "EVENT notify-suppress uid=$uid displayId=$displayId (suppress#=$s)")
                false
            } else {
                chain.proceed()
            }
        }
    }
    installed["CallbackRecord.notifyDisplayEventAsync"] = true
    log(Log.INFO, TAG, "hooked ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}")
}.onFailure {
    installed["CallbackRecord.notifyDisplayEventAsync"] = false
    log(Log.ERROR, TAG, "hook notifyDisplayEventAsync failed", it)
}

/**
 * 只读诊断：确认事件确实经过 DMS 的 `deliverDisplayEvent` 汇聚点。
 * 只在「displayId 出现在某条策略里」时打印，避免刷屏。
 */
private fun XposedModule.hookDeliverDisplayEventDiag(classLoader: ClassLoader) = runCatching {
    val clz = classLoader.loadClass("com.android.server.display.DisplayManagerService")
    val method = clz.declaredMethods.firstOrNull {
        it.name == "deliverDisplayEvent" && it.parameterCount == 4
    } ?: throw NoSuchMethodException("DisplayManagerService.deliverDisplayEvent(I,ArraySet,I,...)")
    hook(method).intercept { chain ->
        val displayId = chain.getArg(0) as? Int
        if (displayId != null && DisplayPolicyRegistry.policedDisplayIds().contains(displayId)) {
            Log.i(
                TAG,
                "EVENT deliver funnel displayId=$displayId event=${chain.getArg(2)} uids=${chain.getArg(1)}"
            )
        }
        chain.proceed()
    }
    installed["DisplayManagerService.deliverDisplayEvent"] = true
    log(Log.INFO, TAG, "hooked (diag) ${method.declaringClass.name}#${method.name}${method.parameterTypes.contentToString()}")
}.onFailure {
    installed["DisplayManagerService.deliverDisplayEvent"] = false
    log(Log.WARN, TAG, "diag hook deliverDisplayEvent failed", it)
}

private fun readCallbackUid(record: Any): Int? = runCatching {
    val field = callbackUidField ?: record.javaClass.getDeclaredField("mUid").apply {
        isAccessible = true
        callbackUidField = this
    }
    field.getInt(record)
}.getOrElse {
    uidReadFailures.incrementAndGet()
    null
}

/** 供验收/自查：Hook 命中计数快照（区分「已装」与「已生效」）。 */
fun visibilityHookStats(): String =
    "installed=$installedSummary " +
        "list(call=${listHits.get()},trim=${listTrimmed.get()}) " +
        "point(call=${pointHits.get()},block=${pointBlocked.get()}) " +
        "notify(call=${notifyHits.get()},suppress=${notifySuppressed.get()}) " +
        "transmit(call=${transmitHits.get()},suppress=${transmitSuppressed.get()},uidErr=${uidReadFailures.get()})"
