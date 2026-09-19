package me.ynk.moredisplay.daemon

import android.hardware.display.DisplayManager
import android.util.Log
import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.dispatch.CapabilityMatrix
import me.ynk.moredisplay.dispatch.AndroidVersions

class ShellDisplayEngine(
    private val context: android.content.Context
) : DisplayEngine {

    companion object {
        private const val TAG = "ShellDisplayEngine"
    }

    private val manager = context.getSystemService(DisplayManager::class.java)
    private val managed = linkedMapOf<Int, DisplayInfo>()

    override val capabilities = CapabilityMatrix.forPrivilege(me.ynk.moredisplay.core.Privilege.SHELL_SHIZUKU)

    override fun createDisplay(spec: DisplaySpec): DisplayInfo {
        val flags = CapabilityMatrix.flagsForSdk(spec.flags)
        if (!capabilities.supportsFlags(spec.flags)) {
            throw SecurityException("unsupported flags 0x${Integer.toHexString(spec.flags)} under ${me.ynk.moredisplay.core.Privilege.SHELL_SHIZUKU}")
        }
        val vd = checkNotNull(manager) { "DisplayManager unavailable" }.createVirtualDisplay(
            spec.name, spec.width, spec.height, spec.densityDpi, null, flags
        ) ?: throw IllegalStateException("createVirtualDisplay returned null for $spec")
        val info = DisplayInfo(vd.display.displayId, spec)
        managed[info.displayId] = info
        Log.i(TAG, "created displayId=${info.displayId} ${spec.width}x${spec.height}@${spec.densityDpi} flags=0x${Integer.toHexString(flags)}")
        return info
    }

    override fun holdDisplay(displayId: Int): DisplayInfo? {
        managed[displayId]?.let { return it }
        val display = manager?.getDisplay(displayId) ?: return null
        val dpi = context.resources.displayMetrics.densityDpi
        return DisplayInfo(displayId, DisplaySpec(display.width, display.height, dpi)).also { managed[displayId] = it }
    }

    override fun removeDisplay(displayId: Int) {
        val info = managed.remove(displayId) ?: throw IllegalArgumentException("display $displayId not managed")
        val global = globalInstance()
        runCatching {
            global.javaClass.getMethod("releaseVirtualDisplay", Int::class.javaPrimitiveType)
                .invoke(global, displayId)
        }.onFailure {
            Log.e(TAG, "release displayId=$displayId failed", it)
            throw it
        }
        Log.i(TAG, "removed displayId=$displayId")
    }

    override fun listDisplays(): List<DisplayInfo> = managed.values.toList()

    private fun globalInstance(): Any =
        Class.forName("android.hardware.display.DisplayManagerGlobal")
            .getMethod("getInstance").invoke(null)
}
