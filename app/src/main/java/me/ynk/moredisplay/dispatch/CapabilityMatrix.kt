package me.ynk.moredisplay.dispatch

import android.util.Log
import me.ynk.moredisplay.core.DaemonCapabilities
import me.ynk.moredisplay.core.Privilege
import me.ynk.moredisplay.core.Transport
import me.ynk.moredisplay.core.VirtualDisplayFlags

object CapabilityMatrix {

    fun forPrivilege(privilege: Privilege): DaemonCapabilities = when (privilege) {
        Privilege.LSPOSED -> DaemonCapabilities(
            maxDisplayCount = Int.MAX_VALUE,
            supportedFlags = fullFlags(),
            launchOnDisplayBypass = true,
            unlockedDisplay = true,
            privilegedSurface = true
        )
        Privilege.ROOT, Privilege.SHELL_SHIZUKU -> DaemonCapabilities(
            maxDisplayCount = Int.MAX_VALUE,
            supportedFlags = shellFlags(),
            launchOnDisplayBypass = false,
            unlockedDisplay = false,
            privilegedSurface = true
        )
        Privilege.NONE -> DaemonCapabilities.none()
    }

    private fun fullFlags(): Int = VirtualDisplayFlags.OWN_CONTENT_ONLY or
            VirtualDisplayFlags.OWN_FOCUS_ONLY or
            VirtualDisplayFlags.TRUSTED or
            VirtualDisplayFlags.ALWAYS_UNLOCKED or
            VirtualDisplayFlags.OWN_FOCUS or
            VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS or
            VirtualDisplayFlags.IME_FALLBACK_DISPLAY

    // 实测（uid 2000 daemon）：除 TRUSTED 外全部放行；TRUSTED 需特权。
    private fun shellFlags(): Int = fullFlags() and VirtualDisplayFlags.TRUSTED.inv()

    fun flagsForSdk(flags: Int): Int {
        var result = flags
        if (AndroidVersions.SDK_INT < AndroidVersions.API_31_ANDROID_12) {
            result = result and VirtualDisplayFlags.TRUSTED.inv()
        }
        if (AndroidVersions.SDK_INT < AndroidVersions.API_33_ANDROID_13) {
            result = result and (VirtualDisplayFlags.OWN_DISPLAY_GROUP or
                    VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS).inv()
        }
        return result
    }
}
