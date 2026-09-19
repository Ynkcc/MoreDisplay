package me.ynk.moredisplay.core

object VirtualDisplayFlags {
    const val OWN_CONTENT_ONLY = 1 shl 0
    const val OWN_FOCUS_ONLY = 1 shl 1
    const val TRUSTED = 1 shl 2
    const val OWN_DISPLAY_GROUP = 1 shl 3
    const val ALWAYS_UNLOCKED = 1 shl 4
    const val TOUCH_FEEDBACK_DISABLED = 1 shl 5
    const val OWN_FOCUS = 1 shl 6
    const val STEAL_TOP_FOCUS_DISABLED = 1 shl 7
    const val SHOULD_SHOW_SYSTEM_DECORATIONS = 1 shl 8
    const val IME_FALLBACK_DISPLAY = 1 shl 9
}

data class DaemonCapabilities(
    val maxDisplayCount: Int,
    val supportedFlags: Int,
    val launchOnDisplayBypass: Boolean,
    val unlockedDisplay: Boolean,
    val privilegedSurface: Boolean
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
