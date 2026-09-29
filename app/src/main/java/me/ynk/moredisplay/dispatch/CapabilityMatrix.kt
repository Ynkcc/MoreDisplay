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
            perUidDisplayVisibility = true
        )
        Privilege.ROOT, Privilege.SHELL_SHIZUKU -> DaemonCapabilities(
            maxDisplayCount = Int.MAX_VALUE,
            supportedFlags = shellFlags(),
            launchOnDisplayBypass = false,
            unlockedDisplay = false,
            privilegedSurface = true,
            perUidDisplayVisibility = false
        )
        Privilege.NONE -> DaemonCapabilities.none()
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
            VirtualDisplayFlags.STEAL_TOP_FOCUS_DISABLED

    // 实测（uid 2000 daemon）：除 TRUSTED 外全部放行；TRUSTED 需特权。
    private fun shellFlags(): Int = fullFlags() and VirtualDisplayFlags.TRUSTED.inv()

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
        VirtualDisplayFlags.STEAL_TOP_FOCUS_DISABLED to 34
    )

    fun flagsForSdk(flags: Int): Int {
        var result = flags
        for ((bit, minSdk) in FLAG_MIN_SDK) {
            if (AndroidVersions.SDK_INT < minSdk) result = result and bit.inv()
        }
        return result
    }
}
