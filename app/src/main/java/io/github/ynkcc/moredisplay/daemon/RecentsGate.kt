package io.github.ynkcc.moredisplay.daemon

import android.util.Log

/**
 * 「最近任务门禁」：让托管虚拟屏上的任务不出现在主屏最近任务里（binder-hook 单方案）。
 *
 * ## 为什么 hook `ActivityTaskManagerService#getRecentTasks`
 *
 * 所有 ROM 的所有最近任务 UI 都必须经 `IActivityTaskManager.getRecentTasks` 取数据：
 * - AOSP / LineageOS Launcher3 直连该 Binder；
 * - ColorOS 的自研 `IRecentTasks`（SystemUI Shell `RecentTasksController`）实测也收敛到
 *   `ActivityTaskManager.getRecentTasks`（反编译核实，两台真机 Android 16）。
 *
 * 该入口是公共 API，签名自 Android R 起为 `(int maxNum, int flags, int userId)`
 * 且未变 —— 比 WM 内部字段/方法名（DWPC、canShowTasksInHostDeviceRecents 等）
 * 稳定得多，因此这里只做 **binder 收敛点过滤**，不做 WM 内部注入。
 *
 * 过滤发生在 `isVisibleRecentTask` 同层、launcher 查询时实时生效：
 * - 不受 launcher 传入 flags 影响（`RECENT_WITH_LIVE_TASKS` 等）；
 * - 只剔除 `displayId ∈ 托管屏集合`（[SystemDaemon.isManagedDisplay]）的任务，
 *   其余原样返回 —— 策略为空时行为与无模块完全一致；
 * - 任务仍在系统内部任务栈里（不杀进程、不 finish），只是不进最近任务查询结果。
 *
 * ## 兼容性设计（硬约束：动态匹配 + 失败只 log 不崩）
 * - 按方法名 + 返回类型 `android.content.pm.ParceledListSlice` 动态匹配全部重载，
 *   不硬编码参数签名（ROM 在此入口加参数也不会漏装）；
 * - 即使某 ROM 子类（如 OplusActivityTaskManagerService）覆写并改写结果，
 *   只要它回调 super，本 hook 仍生效；
 * - 列表剔除优先原地 `remove`（ATMS 传入的是新建 ArrayList）；若不可变则整体重建
 *   ParceledListSlice（反射构造器），再不行则原样放行 —— 任何一步失败都只记日志。
 */
object RecentsGate {

    const val TAG = "MoreDisplay_RecentsGate"
    private const val ATMS_CLASS = "com.android.server.wm.ActivityTaskManagerService"
    private const val SLICE_TYPE = "android.content.pm.ParceledListSlice"

    /** hook 是否已安装（「已装」；「已生效」看 [filteredCount] > 0）。 */
    @Volatile
    var hookInstalled: Boolean = false
        private set

    /** hook 装载的目标方法数。 */
    @Volatile
    var targetCount: Int = 0
        private set

    /** 过滤命中数（每次真的剔除任务才递增）。 */
    @Volatile
    var filteredCount: Int = 0
        private set

    fun stats(): String = "installed=$hookInstalled targets=$targetCount filtered=$filteredCount"

    /**
     * 托管屏判定回调，由模块入口在装 hook 时注入（避免 daemon ↔ xposed 反向依赖）。
     * 返回 true 表示该 displayId 由我们托管，其任务应从最近任务剔除。
     */
    @Volatile
    var isManagedDisplay: ((Int) -> Boolean)? = null

    /**
     * 在 ATMS 上动态匹配 getRecentTasks 目标方法。由 [io.github.ynkcc.moredisplay.xposed.LsposedModule]
     * 在 system_server 启动期调用并执行 hook。
     *
     * @return 命中的方法数（0 = 匹配失败，调用方记 ERROR）。
     */
    fun findTargets(classLoader: ClassLoader): List<java.lang.reflect.Method> {
        val atms = runCatching { classLoader.loadClass(ATMS_CLASS) }
            .getOrElse {
                Log.e(TAG, "load $ATMS_CLASS failed: $it")
                return emptyList()
            }
        val targets = atms.declaredMethods.filter {
            it.name == "getRecentTasks" && it.returnType.name == SLICE_TYPE
        }
        if (targets.isEmpty()) {
            Log.e(TAG, "no getRecentTasks returning $SLICE_TYPE on ${atms.name}")
        }
        return targets
    }

    /** 标记安装结果（供日志与 stats 用）。 */
    fun markInstalled(count: Int) {
        targetCount = count
        hookInstalled = count > 0
    }

    /**
     * hook 的 after 回调：从结果里剔除托管屏任务。绝不抛出，失败原样放行。
     */
    fun filterRecentTasks(result: Any?): Any? {
        if (result == null) return null
        val list = runCatching {
            result.javaClass.getMethod("getList").invoke(result) as? MutableList<Any?>
        }.getOrNull() ?: return result
        if (list.isEmpty()) return result

        var removed = 0
        val iterated = runCatching {
            val it = list.iterator()
            while (it.hasNext()) {
                val t = it.next() ?: continue
                val disp = runCatching { t.javaClass.getField("displayId").getInt(t) }
                    .getOrElse { Int.MIN_VALUE }
                if (disp != Int.MIN_VALUE && isManagedDisplay?.invoke(disp) == true) {
                    it.remove()
                    removed++
                }
            }
            true
        }.getOrDefault(false)

        if (!iterated) {
            // 列表不可变：重建 slice 兜底一次。
            return runCatching {
                val kept = list.filter { t ->
                    val disp: Int = t?.let {
                        runCatching { it.javaClass.getField("displayId").getInt(it) }.getOrDefault(Int.MIN_VALUE)
                    } ?: Int.MIN_VALUE
                    disp == Int.MIN_VALUE || isManagedDisplay?.invoke(disp) != true
                }
                val ctor = result.javaClass.getConstructor(java.util.List::class.java)
                ctor.isAccessible = true
                ctor.newInstance(kept)
            }.getOrElse {
                Log.e(TAG, "rebuild ParceledListSlice failed, pass through: $it")
                result
            }
        }

        if (removed > 0) {
            filteredCount += removed
            Log.i(TAG, "filtered $removed recent task(s) on managed displays (total=$filteredCount)")
        }
        return result
    }
}
