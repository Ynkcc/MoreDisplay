package io.github.ynkcc.moredisplay.core

/**
 * 与 `android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_*` 逐位对齐。
 *
 * 这些值会被原样交给 `DisplayManager.createVirtualDisplay(..., flags)` /
 * `VirtualDisplayConfig#setFlags`，所以必须使用平台真实位定义，
 * 不能自建一套“紧凑位序”。
 */
object VirtualDisplayFlags {
    const val PUBLIC = 1 shl 0
    const val PRESENTATION = 1 shl 1
    const val SECURE = 1 shl 2
    const val OWN_CONTENT_ONLY = 1 shl 3
    const val AUTO_MIRROR = 1 shl 4
    const val CAN_SHOW_WITH_INSECURE_KEYGUARD = 1 shl 5
    const val SUPPORTS_TOUCH = 1 shl 6
    const val ROTATES_WITH_CONTENT = 1 shl 7
    const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 9
    const val TRUSTED = 1 shl 10
    const val OWN_DISPLAY_GROUP = 1 shl 11
    const val ALWAYS_UNLOCKED = 1 shl 12
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val OWN_FOCUS = 1 shl 14
    const val DEVICE_DISPLAY_GROUP = 1 shl 15
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16

    /** API 37+：允许在内容模式间切换（需与 PUBLIC + TRUSTED 搭配，勿与 AUTO_MIRROR 等同时用）。 */
    const val ALLOWS_CONTENT_MODE_SWITCH = 1 shl 17
}

data class DaemonCapabilities(
    val maxDisplayCount: Int,
    val supportedFlags: Int,
    val launchOnDisplayBypass: Boolean,
    val unlockedDisplay: Boolean,
    val privilegedSurface: Boolean,
    /** 需求 A：能否按调用方 uid 过滤屏幕可见性（黑/白名单）。 */
    val perUidDisplayVisibility: Boolean = false,
    /** 需求 B（未实现）：录屏屏替换。 */
    val recordRedirection: Boolean = false,
    /** 需求 C：无障碍屏替换（LSPosed 模式下已实现，见 AccessibilityRedirectHooks）。 */
    val accessibilityRedirection: Boolean = false,
    /** 需求 D：托管屏任务从最近任务剔除（LSPosed 模式下已实现，见 RecentsGate）。 */
    val recentsGate: Boolean = false
) {
    fun supportsFlags(flags: Int): Boolean = flags and supportedFlags.inv() == 0

    companion object {
        fun none() = DaemonCapabilities(
            maxDisplayCount = 0,
            supportedFlags = 0,
            launchOnDisplayBypass = false,
            unlockedDisplay = false,
            privilegedSurface = false
        )
    }
}
