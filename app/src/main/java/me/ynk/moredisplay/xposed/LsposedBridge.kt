package me.ynk.moredisplay.xposed

object LsposedBridge {
    @Volatile
    private var injected = false

    fun markInjected() {
        injected = true
    }

    val isInjected: Boolean get() = injected
}
