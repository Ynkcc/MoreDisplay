package me.ynk.moredisplay.daemon

import android.util.Log

/**
 * 在 daemon 进程内直接问平台要「当前真实存在的 displayId 列表」。
 *
 * 用 `DisplayManagerGlobal.getInstance()`（静态、每次重试取服务），**不要**用 `DisplayManager`：
 * 引擎在 AMS 构造期创建时 DMS 还没注册，`DisplayManager` 构造期捕获的 `mGlobal` 会永久为 null。
 *
 * 这里以 daemon 自身身份（LSPosed = uid 1000，Shizuku = uid 2000）调用，
 * 属于系统/特权身份，拿到的是**未经过我们 per-uid 过滤**的完整列表，
 * 正好用来计算「某个目标 uid 实际可见什么」。
 */
object DisplayQuery {

    private const val TAG = "MoreDisplay_DisplayQuery"
    private const val GLOBAL_CLASS = "android.hardware.display.DisplayManagerGlobal"

    fun rawDisplayIds(): List<Int> = runCatching {
        val global = Class.forName(GLOBAL_CLASS).getMethod("getInstance").invoke(null)
            ?: return@runCatching emptyList()
        val method = global.javaClass.methods.firstOrNull {
            it.name == "getDisplayIds" &&
                it.parameterCount == 1 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE &&
                it.returnType == IntArray::class.java
        } ?: throw NoSuchMethodException("DisplayManagerGlobal.getDisplayIds(Z)")
        (method.invoke(global, false) as IntArray).toList()
    }.getOrElse {
        Log.w(TAG, "rawDisplayIds failed: ${it.message}")
        emptyList()
    }

    /** 某个 uid 实际可见的 displayId（已套用策略）。 */
    fun visibleDisplayIds(uid: Int): List<Int> =
        rawDisplayIds().filter { DisplayPolicyRegistry.isVisible(uid, it) }
}
