package io.github.ynkcc.moredisplay.xposed

import io.github.ynkcc.moredisplay.App
import io.github.ynkcc.moredisplay.transport.ProviderTransport

/**
 * LSPosed 模块可用性探针。
 *
 * 判定为“可用”的两种情形：
 * 1. 当前进程被模块注入 —— 模块作用域包含本应用时由 `onPackageReady` 打标；
 * 2. system_server 内的守护引擎已就绪 —— 只需把 `android` 勾进作用域，
 *    这是本项目推荐（也是唯一必需）的作用域配置；通过一次 Ping RPC 实测。
 */
object LsposedBridge {

    @Volatile
    private var injected = false

    @Volatile
    private var daemonDetected = false

    fun markInjected() {
        injected = true
    }

    val isInjected: Boolean
        get() = injected || daemonDetected || probeDaemon()

    private fun probeDaemon(): Boolean {
        val context = runCatching { App.context }.getOrNull() ?: return false
        return ProviderTransport.ping(context).also { if (it) daemonDetected = true }
    }
}
