package me.ynk.moredisplay.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import me.ynk.moredisplay.core.ConnectionStatus
import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.DisplayPolicy
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.core.DaemonCapabilities
import me.ynk.moredisplay.core.IDisplayRepository
import me.ynk.moredisplay.core.Privilege
import me.ynk.moredisplay.core.RpcRequest
import me.ynk.moredisplay.core.RpcResponse
import me.ynk.moredisplay.core.Transport
import me.ynk.moredisplay.core.WorkModeInfo
import me.ynk.moredisplay.dispatch.DispatchCenter
import java.util.concurrent.Executors

class DisplayRepository : IDisplayRepository {

    companion object {
        private const val TAG = "DisplayRepository"
    }

    private val slotDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "md-slot-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private val scope = CoroutineScope(slotDispatcher + SupervisorJob())

    private val status = MutableStateFlow(ConnectionStatus.IDLE)
    private val error = MutableStateFlow<String?>(null)
    private val displays = MutableStateFlow<Map<Int, DisplayInfo>>(emptyMap())
    private val mode = MutableStateFlow(
        WorkModeInfo(Privilege.NONE, me.ynk.moredisplay.core.DaemonLocation.IN_APP, DaemonCapabilities.none())
    )

    private var transport: Transport? = null

    override val connectionStatus: StateFlow<ConnectionStatus> = status
    override val connectionError: StateFlow<String?> = error
    override val managedDisplays: StateFlow<Map<Int, DisplayInfo>> = displays
    override val workMode: StateFlow<WorkModeInfo> = mode

    init {
        DispatchCenter.registerDefaults()
    }

    override suspend fun connect(): Result<Unit> = withContext(slotDispatcher) {
        if (status.value == ConnectionStatus.CONNECTED) return@withContext Result.success(Unit)
        status.value = ConnectionStatus.BINDING
        DispatchCenter.acquireBest().fold(
            { (provider, t) ->
                transport = t
                t.onDisconnected {
                    scope.launch {
                        status.value = ConnectionStatus.DISCONNECTED
                        transport = null
                        Log.w(TAG, "transport disconnected, mode=${mode.value.privilege}")
                    }
                }
                refreshDisplays()
                mode.value = WorkModeInfo(
                    provider.privilege,
                    locationOf(provider.privilege),
                    CapabilityOf(provider.privilege)
                )
                status.value = ConnectionStatus.CONNECTED
                error.value = null
                Log.i(TAG, "connected via ${provider.privilege} pid")
                Result.success(Unit)
            },
            { e ->
                status.value = ConnectionStatus.ERROR
                error.value = e.message
                Log.e(TAG, "connect failed", e)
                Result.failure(e)
            }
        )
    }

    override suspend fun disconnect(): Result<Unit> = withContext(slotDispatcher) {
        runCatching { transport?.close() }
        transport = null
        status.value = ConnectionStatus.DISCONNECTED
        Result.success(Unit)
    }

    override suspend fun createDisplay(spec: DisplaySpec): Result<DisplayInfo> {
        val response = call { RpcRequest.CreateDisplay(nextId(), spec) }
        return (response as? RpcResponse.DisplayResult)
            ?.let { Result.success(it.info) }
            ?: response.asError("createDisplay")
    }

    override suspend fun holdDisplay(displayId: Int): Result<DisplayInfo> {
        val response = call { RpcRequest.HoldDisplay(nextId(), displayId) }
        return (response as? RpcResponse.DisplayResult)
            ?.let { Result.success(it.info) }
            ?: Result.failure(IllegalArgumentException("display $displayId not held: ${response.message()}"))
    }

    override suspend fun removeDisplay(displayId: Int): Result<Unit> {
        val response = call { RpcRequest.RemoveDisplay(nextId(), displayId) }
        return when (response) {
            is RpcResponse.Ok -> Result.success(Unit)
            else -> Result.failure(IllegalStateException("remove $displayId: ${response.message()}"))
        }
    }

    override suspend fun listDisplays(): Result<List<DisplayInfo>> {
        val response = call { RpcRequest.ListDisplays(nextId()) }
        return (response as? RpcResponse.DisplayList)
            ?.let { Result.success(it.displays) }
            ?: Result.failure(IllegalStateException("listDisplays: ${response.message()}"))
    }

    override suspend fun setDisplayPolicy(policy: DisplayPolicy): Result<DisplayPolicy> {
        val response = call { RpcRequest.SetDisplayPolicy(nextId(), policy) }
        return (response as? RpcResponse.PolicyResult)?.policy
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("setDisplayPolicy: ${response.message()}"))
    }

    override suspend fun listDisplayPolicies(): Result<List<DisplayPolicy>> {
        val response = call { RpcRequest.ListDisplayPolicies(nextId()) }
        return (response as? RpcResponse.PolicyList)
            ?.let { Result.success(it.policies) }
            ?: Result.failure(IllegalStateException("listDisplayPolicies: ${response.message()}"))
    }

    override suspend fun removeDisplayPolicy(uid: Int): Result<Unit> {
        val response = call { RpcRequest.RemoveDisplayPolicy(nextId(), uid) }
        return when (response) {
            is RpcResponse.Ok -> Result.success(Unit)
            else -> Result.failure(IllegalStateException("removeDisplayPolicy $uid: ${response.message()}"))
        }
    }

    override suspend fun displaysForUid(uid: Int): Result<List<Int>> {
        val response = call { RpcRequest.DisplaysForUid(nextId(), uid) }
        return (response as? RpcResponse.IdList)
            ?.let { Result.success(it.ids) }
            ?: Result.failure(IllegalStateException("displaysForUid: ${response.message()}"))
    }

    override fun refreshDisplays() {
        scope.launch {
            listDisplays().onSuccess { list ->
                displays.value = list.associateBy { it.displayId }
            }.onFailure { Log.w(TAG, "refreshDisplays failed: ${it.message}") }
        }
    }

    fun destroy() {
        runBlocking { disconnect() }
        scope.cancel()
        slotDispatcher.close()
    }

    private suspend fun call(build: () -> RpcRequest): RpcResponse = withContext(slotDispatcher) {
        val t = transport ?: return@withContext RpcResponse.Error(0, -1, "not connected")
        runCatching { t.send(build()) }.getOrElse { e ->
            Log.e(TAG, "rpc call failed", e)
            RpcResponse.Error(0, -1, "rpc failed: ${e.message}")
        }
    }

    private var requestId = 0
    private fun nextId(): Int = ++requestId

    private fun <T> RpcResponse.asError(op: String): Result<T> = when (this) {
        is RpcResponse.Error -> Result.failure(IllegalStateException("$op failed [$code]: $message"))
        else -> Result.failure(IllegalStateException("$op bad response: $this"))
    }

    private fun RpcResponse.message(): String = when (this) {
        is RpcResponse.Error -> "[$code]: $message"
        else -> toString()
    }

    private fun locationOf(p: Privilege) = when (p) {
        Privilege.LSPOSED -> me.ynk.moredisplay.core.DaemonLocation.SYSTEM_SERVER
        else -> me.ynk.moredisplay.core.DaemonLocation.STANDALONE_PROCESS
    }

    private fun CapabilityOf(p: Privilege) = me.ynk.moredisplay.dispatch.CapabilityMatrix.forPrivilege(p)
}
