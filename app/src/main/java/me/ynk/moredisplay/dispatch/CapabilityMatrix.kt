package me.ynk.moredisplay.dispatch

import me.ynk.moredisplay.core.DaemonCapabilities
import me.ynk.moredisplay.core.Privilege
import me.ynk.moredisplay.core.VirtualDisplayFlags

object CapabilityMatrix {

    fun forPrivilege(privilege: Privilege): DaemonCapabilities = when (privilege) {
        Privilege.LSPOSED -> DaemonCapabilities(
            maxDisplayCount = Int.MAX_VALUE,
            supportedFlags = fullFlags(),
            launchOnDisplayBypass = true,
            unlockedDisplay = true,
            privilegedSurface = true,
            // 只有 hook 进 system_server 才能按调用方 uid 裁剪 DMS 返回值。
            perUidDisplayVisibility = true,
            // 也只有 hook 进 system_server 才能在 createVirtualDisplayInternal 里改写 mirror 源。
            recordRedirection = true,
            // 同理：hook 进 system_server 才能在无障碍连接处改写手势目标屏。
            accessibilityRedirection = true
        )
        Privilege.ROOT, Privilege.SHELL_SHIZUKU -> shellPrivilegeCapabilities()
        Privilege.NONE -> DaemonCapabilities.none()
    }

    /**
     * shell/uid-0（shizuku）身份下可用的虚拟显示 flag。
     *
     * DMS 对非 SYSTEM_UID 调用方按权限逐项校验：
     * - TRUSTED / OWN_DISPLAY_GROUP → ADD_TRUSTED_DISPLAY
     * - ALWAYS_UNLOCKED → ADD_ALWAYS_UNLOCKED_DISPLAY
     * - SHOULD_SHOW_SYSTEM_DECORATIONS → INTERNAL_SYSTEM_WINDOW（且必须 TRUSTED）
     *
     * 标准 ROM 上 shell 不持有这些权限会直接 SecurityException；部分 ROM
     * 则授予了 shell 全部权限。因此按 [hasPermission] 实测生成，
     * 未提供探测（或探测失败）时回退到保守值。
     */
    fun shellPrivilegeCapabilities(hasPermission: (String) -> Boolean = { false }): DaemonCapabilities {
        // ADD_TRUSTED_DISPLAY / ADD_ALWAYS_UNLOCKED_DISPLAY 为隐藏权限，公开 SDK 无常量。
        var flags = fullFlags() and TRUSTED_GOVERNED_FLAGS.inv()
        if (hasPermission("android.permission.ADD_TRUSTED_DISPLAY")) {
            flags = flags or VirtualDisplayFlags.TRUSTED or VirtualDisplayFlags.OWN_DISPLAY_GROUP
        }
        if (hasPermission("android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY")) {
            flags = flags or VirtualDisplayFlags.ALWAYS_UNLOCKED
        }
        if (hasPermission("android.permission.ADD_TRUSTED_DISPLAY") &&
            hasPermission("android.permission.INTERNAL_SYSTEM_WINDOW")
        ) {
            flags = flags or VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS
        }
        return DaemonCapabilities(
            maxDisplayCount = Int.MAX_VALUE,
            supportedFlags = flags,
            launchOnDisplayBypass = false,
            unlockedDisplay = false,
            privilegedSurface = true,
            perUidDisplayVisibility = false,
            recordRedirection = false
        )
    }

    /** system_server（uid 1000）身份下可用的全部虚拟显示 flag。 */
    private fun fullFlags(): Int = VirtualDisplayFlags.PUBLIC or
            VirtualDisplayFlags.PRESENTATION or
            VirtualDisplayFlags.SECURE or
            VirtualDisplayFlags.OWN_CONTENT_ONLY or
            VirtualDisplayFlags.AUTO_MIRROR or
            VirtualDisplayFlags.CAN_SHOW_WITH_INSECURE_KEYGUARD or
            VirtualDisplayFlags.SUPPORTS_TOUCH or
            VirtualDisplayFlags.ROTATES_WITH_CONTENT or
            VirtualDisplayFlags.DESTROY_CONTENT_ON_REMOVAL or
            VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS or
            VirtualDisplayFlags.TRUSTED or
            VirtualDisplayFlags.OWN_DISPLAY_GROUP or
            VirtualDisplayFlags.ALWAYS_UNLOCKED or
            VirtualDisplayFlags.TOUCH_FEEDBACK_DISABLED or
            VirtualDisplayFlags.OWN_FOCUS or
            VirtualDisplayFlags.DEVICE_DISPLAY_GROUP or
            VirtualDisplayFlags.STEAL_TOP_FOCUS_DISABLED or
            VirtualDisplayFlags.ALLOWS_CONTENT_MODE_SWITCH

    /** 由特权权限（而非 SDK 版本）决定的 flag 集合。 */
    internal val TRUSTED_GOVERNED_FLAGS: Int = VirtualDisplayFlags.TRUSTED or
            VirtualDisplayFlags.OWN_DISPLAY_GROUP or
            VirtualDisplayFlags.ALWAYS_UNLOCKED or
            VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS

    /** 每个 flag 生效所需的最低 API，低于该版本时从请求中剔除，避免系统静默忽略或抛错。 */
    private val FLAG_MIN_SDK: List<Pair<Int, Int>> = listOf(
        VirtualDisplayFlags.PUBLIC to 19,
        VirtualDisplayFlags.PRESENTATION to 19,
        VirtualDisplayFlags.SECURE to 19,
        VirtualDisplayFlags.OWN_CONTENT_ONLY to 19,
        VirtualDisplayFlags.AUTO_MIRROR to 21,
        VirtualDisplayFlags.SUPPORTS_TOUCH to 26,
        VirtualDisplayFlags.ROTATES_WITH_CONTENT to 26,
        VirtualDisplayFlags.DESTROY_CONTENT_ON_REMOVAL to 26,
        VirtualDisplayFlags.CAN_SHOW_WITH_INSECURE_KEYGUARD to 29,
        VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS to 29,
        VirtualDisplayFlags.TRUSTED to 33,
        VirtualDisplayFlags.OWN_DISPLAY_GROUP to 33,
        VirtualDisplayFlags.ALWAYS_UNLOCKED to 33,
        VirtualDisplayFlags.TOUCH_FEEDBACK_DISABLED to 33,
        VirtualDisplayFlags.OWN_FOCUS to 34,
        VirtualDisplayFlags.DEVICE_DISPLAY_GROUP to 34,
        VirtualDisplayFlags.STEAL_TOP_FOCUS_DISABLED to 34,
        VirtualDisplayFlags.ALLOWS_CONTENT_MODE_SWITCH to 37
    )

    /**
     * 对齐 DMS `createVirtualDisplayInternal` 的组合归一化，避免非法组合
     * 触发 IllegalArgumentException 导致整次 create 失败：
     * - PUBLIC + CAN_SHOW_WITH_INSECURE_KEYGUARD 为非法组合 → 剔除 KEYGUARD（记日志）
     * - OWN_CONTENT_ONLY 会清 AUTO_MIRROR
     * - AUTO_MIRROR 会清 OWN_DISPLAY_GROUP
     */
    fun normalizeFlagCombination(flags: Int): Int {
        var result = flags
        if (result and VirtualDisplayFlags.PUBLIC != 0 &&
            result and VirtualDisplayFlags.CAN_SHOW_WITH_INSECURE_KEYGUARD != 0
        ) {
            result = result and VirtualDisplayFlags.CAN_SHOW_WITH_INSECURE_KEYGUARD.inv()
        }
        if (result and VirtualDisplayFlags.OWN_CONTENT_ONLY != 0) {
            result = result and VirtualDisplayFlags.AUTO_MIRROR.inv()
        }
        if (result and VirtualDisplayFlags.AUTO_MIRROR != 0) {
            result = result and VirtualDisplayFlags.OWN_DISPLAY_GROUP.inv()
        }
        return result
    }

    fun flagsForSdk(flags: Int): Int {
        var result = flags
        for ((bit, minSdk) in FLAG_MIN_SDK) {
            if (AndroidVersions.SDK_INT < minSdk) result = result and bit.inv()
        }
        return result
    }
}
