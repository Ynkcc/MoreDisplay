package io.github.ynkcc.moredisplay.daemon

import android.os.Process
import android.util.Log
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.Visibility

/**
 * 进程内策略表（LSPosed 模式下即 system_server 进程内）。
 *
 * 与 `SystemDisplayEngine` 一样用 `LinkedHashMap` + `synchronized(lock)`，
 * 线程安全且保持插入顺序（`list` 输出稳定）。
 *
 * ## 默认放行（硬约束）
 * - 没有任何条目 → 行为与未装模块完全一致；
 * - **非 App uid**（uid<10000：root/system/systemui/shell/mediaserver…）一律放行，
 *   绝不因为策略把系统搞挂。
 */
object DisplayPolicyRegistry {

    private const val TAG = "MoreDisplay_Policy"

    const val NO_POLICY_UID = -1

    /** 多用户 uid 布局：uid = userId * 100000 + appId。 */
    private const val PER_USER_RANGE = 100000

    /**
     * 默认屏（`Display.DEFAULT_DISPLAY`）**永不过滤**。
     *
     * 实测：如果把 display 0 从某个 App 的视野里摘掉，framework 的
     * `com.android.internal.policy.DecorContext.<init>` 会拿到 null 的 `Display` 而 NPE
     * （`Attempt to invoke virtual method 'int android.view.Display.getDisplayId()' on a null object reference`），
     * 该 App 直接起不来。它也是调用方自身窗口所在屏，语义上不应该被隐藏。
     * 需要「完全隔离」的场景请配合托管虚拟屏（把 App 启动到托管屏）而不是隐藏 display 0。
     */
    const val DEFAULT_DISPLAY_ID = 0

    /**
     * 由 xposed 层注入的「Hook 命中计数」快照提供者（LSPosed 模式下才非空）。
     * 用于在 `policy-list` / `displays-for-uid` 时打印「已装 vs 已生效」证据。
     */
    @Volatile
    var statsProvider: (() -> String)? = null

    private val lock = Any()
    private val policies = LinkedHashMap<Int, DisplayPolicy>()

    /** [policedDisplayIds] 的缓存，避免在高频事件路径上反复分配集合。 */
    @Volatile
    private var policedIds: Set<Int> = emptySet()

    fun set(policy: DisplayPolicy): DisplayPolicy = synchronized(lock) {
        val sanitized = if (policy.visibility != Visibility.ALL &&
            policy.displayIds.contains(DEFAULT_DISPLAY_ID)
        ) {
            Log.w(TAG, "策略 ${policy.describe()} 含默认屏 $DEFAULT_DISPLAY_ID，已忽略该项（默认屏永不过滤）")
            policy.copy(displayIds = policy.displayIds - DEFAULT_DISPLAY_ID)
        } else {
            policy
        }
        policies[sanitized.uid] = sanitized
        rebuildPolicedIdsLocked()
        sanitized
    }

    fun remove(uid: Int): Boolean = synchronized(lock) {
        val removed = policies.remove(uid) != null
        if (removed) rebuildPolicedIdsLocked()
        removed
    }

    fun get(uid: Int): DisplayPolicy? = synchronized(lock) { policies[uid] }

    fun list(): List<DisplayPolicy> = synchronized(lock) { policies.values.toList() }

    fun clear() = synchronized(lock) {
        policies.clear()
        rebuildPolicedIdsLocked()
    }

    /** 所有策略里出现过的 displayId 并集（仅用于日志筛选，避免刷屏）。 */
    fun policedDisplayIds(): Set<Int> = policedIds

    private fun rebuildPolicedIdsLocked() {
        policedIds = policies.values.flatMapTo(mutableSetOf()) { it.displayIds }
    }

    /**
     * 需求 B：该 uid 的录屏镜像目标屏。
     * 只在「是 App uid 且配置了 recordDisplayId」时返回非空；否则 null（=不干预）。
     */
    fun recordDisplayIdFor(uid: Int): Int? {
        if (!isAppUid(uid)) return null
        return get(uid)?.recordDisplayId
    }

    /**
     * 需求 C：该包名（无障碍服务所在包）的无障碍操作目标屏。
     *
     * 无障碍连接对象上没有 uid，只有服务的 `ComponentName` 和 `mUserId`，
     * 所以按「包名 + 服务所在用户」匹配（`DisplayPolicy.packageName` +
     * 策略 uid 编码的 userId，即 `uid / 100000`）。多用户下同一包名会为每个
     * 用户各安装一份（uid 不同），必须带 userId 消歧。
     *
     * 只在配置了 `operatedDisplayId` 时返回非空；否则 null（=不干预）。
     */
    fun operatedDisplayIdForPackage(packageName: String?, userId: Int): Int? {
        if (packageName.isNullOrEmpty()) return null
        return synchronized(lock) {
            policies.values.firstOrNull {
                it.packageName == packageName && it.uid / PER_USER_RANGE == userId
            }?.operatedDisplayId
        }
    }

    /** 该 uid 是否应当看到该 displayId。 */
    fun isVisible(uid: Int, displayId: Int): Boolean {
        // 默认屏永不过滤（见 DEFAULT_DISPLAY_ID 注释：摘掉会让 DecorContext NPE）。
        if (displayId == DEFAULT_DISPLAY_ID) return true
        if (!isAppUid(uid)) return true
        val policy = get(uid) ?: return true
        return when (policy.visibility) {
            Visibility.ALL -> true
            Visibility.ONLY -> policy.displayIds.contains(displayId)
            Visibility.HIDE -> !policy.displayIds.contains(displayId)
        }
    }

    /** 按策略裁剪一个 displayId 列表；无策略/非 App uid 时原样返回（不分配新数组）。 */
    fun filter(uid: Int, displayIds: IntArray): IntArray {
        if (!isAppUid(uid)) return displayIds
        if (get(uid) == null) return displayIds
        return displayIds.filter { isVisible(uid, it) }.toIntArray()
    }

    /**
     * App uid 判定：多用户下 uid = userId*100000 + appId，appId∈[10000,19999]（含隔离进程 90000+）。
     * 系统/root/shell 一律不是 App uid。
     */
    fun isAppUid(uid: Int): Boolean {
        if (uid < Process.FIRST_APPLICATION_UID) return false
        val appId = uid % 100000
        return appId >= Process.FIRST_APPLICATION_UID
    }
}
