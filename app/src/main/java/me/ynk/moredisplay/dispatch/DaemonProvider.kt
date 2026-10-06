package me.ynk.moredisplay.dispatch

import android.util.Log
import me.ynk.moredisplay.core.Privilege
import me.ynk.moredisplay.core.Transport

interface DaemonProvider {
    val privilege: Privilege
    val priority: Int

    fun isAvailable(): Boolean
    suspend fun startDaemon(): Result<Unit>
    suspend fun stopDaemon(): Result<Unit>
    suspend fun connectTransport(): Result<Transport>
}

object DispatchCenter {
    private const val TAG = "DispatchCenter"

    private val providers = mutableListOf<DaemonProvider>()

    fun register(provider: DaemonProvider) {
        providers.add(provider)
    }

    fun registerDefaults() {
        providers.clear()
        register(me.ynk.moredisplay.provider.LsposedDaemonProvider())
        register(me.ynk.moredisplay.provider.ShizukuDaemonProvider())
        register(me.ynk.moredisplay.provider.RootDaemonProvider())
    }

    fun activeProviders(): List<DaemonProvider> =
        providers.filter { it.isAvailable() }.sortedByDescending { it.priority }

    suspend fun acquireBest(): Result<Pair<DaemonProvider, Transport>> {
        val candidates = activeProviders()
        if (candidates.isEmpty()) {
            return Result.failure(IllegalStateException(
                "no available daemon provider, tried=[${providers.joinToString { "${it.privilege}(available=${it.isAvailable()})" }}]"
            ))
        }
        val errors = mutableListOf<String>()
        for (provider in candidates) {
            val transport = provider.startDaemon().flatMapCatching { provider.connectTransport() }
                .onFailure {
                    Log.w(TAG, "provider ${provider.privilege} failed: ${it.message}")
                    errors.add("${provider.privilege}: ${it.message}")
                }
                .getOrNull()
            if (transport != null) return Result.success(provider to transport)
        }
        return Result.failure(IllegalStateException("all providers failed: $errors"))
    }
}

internal inline fun <T, R> Result<T>.flatMapCatching(transform: (T) -> Result<R>): Result<R> =
    fold({ transform(it) }, { Result.failure(it) })
