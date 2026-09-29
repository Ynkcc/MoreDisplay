package me.ynk.moredisplay.daemon

import android.os.Binder
import android.os.Bundle
import android.util.Log
import me.ynk.moredisplay.core.RpcCodec
import me.ynk.moredisplay.core.RpcResponse

/**
 * App ↔ system_server 守护进程的 RPC 载体。
 *
 * ## 为什么不是 ServiceManager / LocalSocket
 * Android 14+ 起：
 * - `ServiceManager.addService()` 对未在 `service_contexts` 登记的服务名直接抛
 *   `SecurityException: SELinux denied for service.`；
 * - 应用连接 system_server 创建的 unix socket 同样被策略禁止（只有 crash_dump 被放行）。
 *
 * ## 采用方案
 * 复用系统自带、已导出、且**必然运行在 system_server 进程内**的
 * `content://settings` 提供者（`com.android.providers.settings.SettingsProvider`，
 * `android:process="system"`）：App 用 `ContentResolver.call()` 同步发请求，
 * 模块 Hook `SettingsProvider#call` 就地应答。整条链路不引入新的 SELinux 依赖。
 */
object ProviderRpc {

    /** 自定义 call method，避开 SettingsProvider 自身的 CALL_METHOD_* 命名空间。 */
    const val METHOD = "moredisplay.rpc"
    const val EXTRA_REQUEST = "request"
    const val EXTRA_RESPONSE = "response"

    private const val TAG = "MoreDisplay_ProviderRpc"

    fun handle(engine: DisplayEngine, extras: Bundle?): Bundle {
        val payload = extras?.getByteArray(EXTRA_REQUEST)
            ?: return errorBundle(-1, "missing rpc payload")
        val response = runCatching {
            val request = RpcCodec.unmarshallRequest(payload)
            // 必须清掉调用方身份：否则 DMS 会按发起 App 的 uid 做权限校验而拒绝建屏。
            val identity = Binder.clearCallingIdentity()
            try {
                handleRequest(engine, request)
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }.getOrElse {
            Log.e(TAG, "handle rpc failed", it)
            RpcResponse.Error(0, -1, "daemon rpc failed: ${it.message}")
        }
        return responseBundle(response)
    }

    fun errorBundle(code: Int, message: String): Bundle =
        responseBundle(RpcResponse.Error(0, code, message))

    private fun responseBundle(response: RpcResponse): Bundle = Bundle().apply {
        putByteArray(EXTRA_RESPONSE, RpcCodec.marshallResponse(response))
    }
}
