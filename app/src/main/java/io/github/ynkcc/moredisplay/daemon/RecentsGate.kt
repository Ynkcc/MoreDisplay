package io.github.ynkcc.moredisplay.daemon

import android.content.ComponentName
import android.content.Intent
import android.content.IntentSender
import android.content.pm.ActivityInfo
import android.os.IBinder
import android.util.Log
import android.window.DisplayWindowPolicyController
import java.util.function.Supplier

/**
 * 「最近任务门禁」：让托管虚拟屏上的任务不出现在主屏最近任务里（ROM 无关，框架级）。
 *
 * ## 原理（Android 14~16 实测，含 LineageOS / ColorOS）
 *
 * `RecentTasks#getRecentTasksImpl` → `isVisibleRecentTask(task)` 的最后一关是：
 *
 * ```
 * task.getDisplayContent() == null || task.getDisplayContent().canShowTasksInHostDeviceRecents()
 * ```
 *
 * `canShowTasksInHostDeviceRecents()` → `DisplayContent#mDwpcHelper` →
 * `DisplayWindowPolicyControllerHelper#mDisplayWindowPolicyController`（DWPC）。
 * 普通虚拟屏没有 DWPC（helper 字段为 null），于是恒返回 true，任务进最近任务。
 *
 * 本类在 system_server 内用反射把一个「拒绝显示于宿主最近任务」的 DWPC 实例
 * 写进该屏 `DisplayContent.mDwpcHelper.mDisplayWindowPolicyController` 字段：
 *
 * - 不走 `IVirtualDevice` 建屏路径，不改变任何建屏/删除行为；
 * - 只影响 `canShowTasksInHostDeviceRecents()`（→ false）；
 * - 其余 DWPC 判定全部返回 permissive 默认值（允许启动/容纳），`getCustomHomeComponent`
 *   返回 null，回调类方法为空实现 —— 与「没有 DWPC」时的行为一致（已在 ROM 反编译核对
 *   `mDwpcHelper` 的全部 5 个使用点）；
 * - 拔除（detach）把字段写回 null，helper 回到「无控制器」分支，行为完全还原。
 *
 * ## 时序
 * `createVirtualDisplay` 返回时 WMS 的 `DisplayContent` 可能尚未异步创建
 * （DMS 注册 display → WMS 收到 onDisplayAdded 才建 DisplayContent），因此 attach 由
 * 调用方轮询重试（见 [SystemDisplayEngine]）。
 */
object RecentsGate {

    private const val TAG = "MoreDisplay_RecentsGate"

    /** DWPC 子类：唯一语义差异是 canShowTasksInHostDeviceRecents()=false，其余全放行。 */
    private class HostRecentsGate : DisplayWindowPolicyController() {
        override fun canActivityBeLaunched(
            activityInfo: ActivityInfo,
            intent: Intent?,
            windowingMode: Int,
            activityType: Int,
            canShowAppErrorState: Boolean,
            pendingTransitionToHome: Boolean,
            transitionActivityOptions: Supplier<IntentSender>?,
        ): Boolean = true

        override fun canContainActivity(
            activityInfo: ActivityInfo,
            windowingMode: Int,
            activityType: Int,
            canBeEmbedded: Boolean,
        ): Boolean = true

        override fun canShowTasksInHostDeviceRecents(): Boolean = false

        override fun getCustomHomeComponent(): ComponentName? = null

        override fun keepActivityOnWindowFlagsChanged(
            activityInfo: ActivityInfo,
            windowFlags: Int,
            systemWindowFlags: Int,
        ): Boolean = true
    }

    private val gate: HostRecentsGate? by lazy {
        runCatching { HostRecentsGate() }
            .onFailure { Log.e(TAG, "instantiate HostRecentsGate failed (ROM DWPC abstract surface mismatch?): $it") }
            .getOrNull()
    }

    /**
     * 尝试把门禁 DWPC 注入 displayId 对应的 `DisplayContent`。
     *
     * @return true = 已注入且回读校验通过（「已生效」）；false = 本轮未就绪或失败（调用方决定重试/放弃）。
     */
    fun attach(displayId: Int): Boolean {
        if (gate == null) return false
        val helper = findDwpcHelper(displayId) ?: return false
        return runCatching {
            val field = helper.javaClass.declaredFields.firstOrNull {
                it.type.name == DWPC_CLASS
            } ?: run {
                Log.e(TAG, "attach displayId=$displayId: DWPC field not found on ${helper.javaClass.name}")
                return false
            }
            field.isAccessible = true
            if (field.get(helper) === gate) {
                Log.i(TAG, "attach displayId=$displayId: already attached (idempotent)")
                return true
            }
            field.set(helper, gate)
            val ok = field.get(helper) === gate
            if (ok) {
                Log.i(TAG, "attached displayId=$displayId (controller injected & readback ok)")
            } else {
                Log.e(TAG, "attach displayId=$displayId: injected but readback mismatch")
            }
            ok
        }.getOrElse {
            Log.e(TAG, "attach displayId=$displayId failed: $it")
            false
        }
    }

    /** 拔除门禁：字段写回 null，helper 回到「无控制器」语义。尽力而为，返回是否成功。 */
    fun detach(displayId: Int): Boolean {
        val helper = findDwpcHelper(displayId) ?: return false
        return runCatching {
            val field = helper.javaClass.declaredFields.firstOrNull { it.type.name == DWPC_CLASS } ?: return false
            field.isAccessible = true
            field.set(helper, null)
            Log.i(TAG, "detached displayId=$displayId (controller reset to null)")
            true
        }.getOrElse {
            Log.e(TAG, "detach displayId=$displayId failed: $it")
            false
        }
    }

    /**
     * WMS("window") → WindowManagerService.mRoot(RootWindowContainer) →
     * getDisplayContent(int) → DisplayContent.mDwpcHelper。
     *
     * 全程按「字段类型名」动态匹配（不依赖具体字段名），任一环未就绪返回 null（可重试）。
     */
    private fun findDwpcHelper(displayId: Int): Any? {
        val binder = runCatching {
            Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
                .invoke(null, "window") as? IBinder
        }.getOrElse { Log.d(TAG, "step1 getService(window) threw: $it"); return null }
        if (binder == null) { Log.d(TAG, "step1 getService(window)=null"); return null }

        val wms = runCatching {
            // IWindowManager 自 Android 12 起从 android.app 挪到 android.view，双包名兼容。
            val stub = (listOf("android.view.IWindowManager\$Stub", "android.app.IWindowManager\$Stub")
                .firstOrNull { runCatching { Class.forName(it) }.isSuccess })
                ?: run { Log.d(TAG, "step2 IWindowManager\$Stub class not found"); return null }
            Class.forName(stub)
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }.getOrElse { Log.d(TAG, "step2 asInterface threw: $it"); return null }
        if (wms == null) { Log.d(TAG, "step2 asInterface=null"); return null }

        val root = runCatching {
            findFieldUpward(wms.javaClass, "RootWindowContainer")
                ?.apply { isAccessible = true }
                ?.get(wms)
        }.getOrElse { Log.d(TAG, "step3 mRoot threw: $it"); return null }
        if (root == null) { Log.d(TAG, "step3 RootWindowContainer field not found on ${wms.javaClass.name}"); return null }

        val dc = runCatching {
            findMethodUpward(root.javaClass, "getDisplayContent", Int::class.javaPrimitiveType)
                ?.apply { isAccessible = true }
                ?.invoke(root, displayId)
        }.getOrElse { Log.d(TAG, "step4 getDisplayContent threw: $it"); return null }
        if (dc == null) { Log.d(TAG, "step4 DisplayContent($displayId)=null (WMS not ready?)"); return null }

        return runCatching {
            findFieldUpward(dc.javaClass, "DisplayWindowPolicyControllerHelper")
                ?.apply { isAccessible = true }
                ?.get(dc)
        }.getOrElse { Log.d(TAG, "step5 mDwpcHelper threw: $it"); return null }
            .also { if (it == null) Log.d(TAG, "step5 mDwpcHelper=null on ${dc.javaClass.name}") }
    }

    /** 在类层次（含父类）里按字段类型名找字段。ROM 子类化（如 OplusWindowManagerService）后字段仍在基类。 */
    private fun findFieldUpward(cls: Class<*>, typeName: String): java.lang.reflect.Field? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredFields.firstOrNull { it.type.simpleName == typeName }?.let { return it }
            c = c.superclass
        }
        return null
    }

    /** 在类层次（含父类）里按方法名+参数类型找方法。 */
    private fun findMethodUpward(cls: Class<*>, name: String, vararg params: Class<*>?): java.lang.reflect.Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredMethods.firstOrNull {
                it.name == name &&
                    it.parameterTypes.size == params.size &&
                    params.indices.all { i -> it.parameterTypes[i] == params[i] }
            }?.let { return it }
            c = c.superclass
        }
        return null
    }

    private const val DWPC_CLASS = "android.window.DisplayWindowPolicyController"
}
