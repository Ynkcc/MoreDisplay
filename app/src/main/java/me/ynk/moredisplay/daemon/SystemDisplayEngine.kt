package me.ynk.moredisplay.daemon

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.VirtualDisplayConfig
import android.util.Log
import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.dispatch.AndroidVersions
import me.ynk.moredisplay.dispatch.CapabilityMatrix

@SuppressLint("PrivateApi")
class SystemDisplayEngine(
    private val systemContext: Context
) : DisplayEngine {

    companion object {
        private const val TAG = "SystemDisplayEngine"
    }

    private val managed = linkedMapOf<Int, DisplayInfo>()

    override val capabilities = CapabilityMatrix.forPrivilege(me.ynk.moredisplay.core.Privilege.LSPOSED)

    override fun createDisplay(spec: DisplaySpec): DisplayInfo {
        val flags = CapabilityMatrix.flagsForSdk(spec.flags)
        if (!capabilities.supportsFlags(spec.flags)) {
            throw SecurityException("unsupported flags 0x${Integer.toHexString(spec.flags)}")
        }
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            .getMethod("getInstance").invoke(null)
        val displayId: Int = runCatching {
            val method = global.javaClass.methods.firstOrNull {
                it.name == "createVirtualDisplay" &&
                        it.parameterTypes.getOrNull(0) == VirtualDisplayConfig::class.java
            } ?: throw NoSuchMethodException("createVirtualDisplay(VirtualDisplayConfig, ...) not found")
            val config = VirtualDisplayConfig.Builder(spec.name, spec.width, spec.height, spec.densityDpi)
                .setFlags(flags)
                .build()
            method.invoke(global, config, null, null, null) as Int
        }.getOrElse {
            throw IllegalStateException("system createDisplay failed for $spec", it)
        }
        val info = DisplayInfo(displayId, spec)
        managed[displayId] = info
        Log.i(TAG, "created displayId=$displayId in system_server flags=0x${Integer.toHexString(flags)}")
        return info
    }

    override fun holdDisplay(displayId: Int): DisplayInfo? = managed[displayId]

    override fun removeDisplay(displayId: Int) {
        managed.remove(displayId) ?: throw IllegalArgumentException("display $displayId not managed")
        val global = Class.forName("android.hardware.display.DisplayManagerGlobal")
            .getMethod("getInstance").invoke(null)
        runCatching {
            global.javaClass.getMethod("releaseVirtualDisplay", Int::class.javaPrimitiveType)
                .invoke(global, displayId)
        }.onFailure {
            Log.e(TAG, "release displayId=$displayId failed", it)
            throw it
        }
    }

    override fun listDisplays(): List<DisplayInfo> = managed.values.toList()
}
