package me.ynk.moredisplay.data

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
import me.ynk.moredisplay.dispatch.CapabilityMatrix
import me.ynk.moredisplay.dispatch.DaemonProvider
import me.ynk.moredisplay.dispatch.DispatchCenter
import me.ynk.moredisplay.dispatch.flatMapCatching
import java.util.concurrent.Executors

/**
 * 多通道并行的显示仓库：Shizuku 与 LSPosed 守护可**同时**连接，
 * 各自创建/管理各自的虚拟屏。
 *
 * - 每个可用 provider 独立占一个槽位（[Slot]），连接互不影响、单通道失败不拖垮整体；
 * - displayId 由 DMS 全局分配不会撞号，仓库内用 [origin] 记录每块屏归属哪个通道，
 *   create/list 时登记，remove/hold 时优先路由回原通道（未知归属则按优先级逐通道兜底）；
 * - 策略类操作（per-uid 可见性 / displaysForUid）只在 system_server 守护内生效
 *   （hook 与 DisplayPolicyRegistry 都在那里），固定路由到 LSPosed 槽位。
 */
class DisplayRepository : IDisplayRepository {

    companion object {
        private const val TAG = "DisplayRepository"
    }

    /** 一条已连接的守护通道。 */
    private class Slot(
        val provider: DaemonProvider,
        val transport: Transport,
        val workMode: WorkModeInfo
    )

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
    private val modes = MutableStateFlow<Map<Privilege, WorkModeInfo>>(emptyMap())

    /** privilege → 已连接槽位。仅在 [slotDispatcher] 单线程内读写。 */
    private val slots = mutableMapOf<Privilege, Slot>()

    /** displayId → 创建它的通道 privilege。仅在 [slotDispatcher] 单线程内写，读取走 StateFlow。 */
    private val origin = MutableStateFlow<Map<Int, Privilege>>(emptyMap())

    override val displaysByChannel: StateFlow<Map<Privilege, List<DisplayInfo>>> =
        combine(displays, origin) { disp, orig ->
            disp.entries
                .groupBy({ orig[it.key] ?: Privilege.NONE }) { it.value }
                .mapValues { e -> e.value.sortedBy { it.displayId } }
        }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override val connectionStatus: StateFlow<ConnectionStatus> = status
    override val connectionError: StateFlow<String?> = error
    override val managedDisplays: StateFlow<Map<Int, DisplayInfo>> = displays
    override val workMode: StateFlow<WorkModeInfo> = mode
    override val workModes: StateFlow<Map<Privilege, WorkModeInfo>> = modes

    init {
        DispatchCenter.registerDefaults()
    }

    /**
     * 连接所有可用 provider（已连接的跳过）。任意一条成功即返回 success；
     * 全部失败才返回 failure。支持渐进接入：例如先连 LSPosed，之后 Shizuku
     * 就绪后再调 connect() 即可补上第二条通道。
     */
    override suspend fun connect(): Result<Unit> = withContext(slotDispatcher) {
        if (status.value != ConnectionStatus.CONNECTED) status.value = ConnectionStatus.BINDING
        val errors = connectMissingSlots()
        if (slots.isEmpty()) {
            status.value = ConnectionStatus.ERROR
            error.value = errors.joinToString("; ").ifEmpty { "no available daemon provider" }
            Log.e(TAG, "connect failed: ${error.value}")
            Result.failure(IllegalStateException("all providers failed: $errors"))
        } else {
            status.value = ConnectionStatus.CONNECTED
            error.value = null
            refreshDisplays()
            Log.i(TAG, "connected slots=${slots.keys} (skipped errors=$errors)")
            Result.success(Unit)
        }
    }

    /** 补齐所有「可用但尚未连接」的 provider，单个失败仅记录不中断。 */
    private suspend fun connectMissingSlots(): List<String> {
        val errors = mutableListOf<String>()
        for (provider in DispatchCenter.activeProviders()) {
            if (slots.containsKey(provider.privilege)) continue
            provider.startDaemon().flatMapCatching { provider.connectTransport() }
                .onSuccess { t ->
                    // 能力以 daemon 实测为准（特权 flag 因 ROM 权限而异），失败则回退静态矩阵。
                    val capabilities = queryCapabilities(t)
                        ?: CapabilityMatrix.forPrivilege(provider.privilege)
                    val workMode = WorkModeInfo(
                        provider.privilege,
                        locationOf(provider.privilege),
                        capabilities
                    )
                    slots[provider.privilege] = Slot(provider, t, workMode)
                    t.onDisconnected {
                        scope.launch { dropSlot(provider.privilege, "transport disconnected") }
                    }
                    Log.i(TAG, "slot connected via ${provider.privilege}")
                }
                .onFailure {
                    Log.w(TAG, "provider ${provider.privilege} failed: ${it.message}")
                    errors.add("${provider.privilege}: ${it.message}")
                }
        }
        publishSlots()
        return errors
    }

    /** 移除一条通道（transport 断开或主动断连时）。全部断开则整体回到 DISCONNECTED。 */
    private suspend fun dropSlot(privilege: Privilege, reason: String) = withContext(slotDispatcher) {
        val slot = slots.remove(privilege) ?: return@withContext
        runCatching { slot.transport.close() }
        origin.value = origin.value.filterValues { it != privilege }
        publishSlots()
        Log.w(TAG, "slot $privilege dropped: $reason, remaining=${slots.keys}")
        if (slots.isEmpty()) status.value = ConnectionStatus.DISCONNECTED
    }

    override suspend fun disconnect(): Result<Unit> = withContext(slotDispatcher) {
        slots.values.forEach { runCatching { it.transport.close() } }
        slots.clear()
        origin.value = emptyMap()
        publishSlots()
        status.value = ConnectionStatus.DISCONNECTED
        Result.success(Unit)
    }

    override suspend fun createDisplay(spec: DisplaySpec, privilege: Privilege?): Result<DisplayInfo> =
        withContext(slotDispatcher) {
            val slot = slotFor(privilege)
                ?: return@withContext Result.failure(IllegalStateException(
                    if (privilege == null) "not connected"
                    else "no connected daemon for $privilege (connected=${slots.keys})"
                ))
            val response = sendOn(slot) { RpcRequest.CreateDisplay(nextId(), spec) }
            val info = (response as? RpcResponse.DisplayResult)?.info
            if (info != null) {
                origin.value = origin.value + (info.displayId to slot.provider.privilege)
                Result.success(info)
            } else {
                response.asError("createDisplay")
            }
        }

    override suspend fun holdDisplay(displayId: Int): Result<DisplayInfo> = withContext(slotDispatcher) {
        var last: RpcResponse? = null
        for (slot in orderedSlotsFor(displayId)) {
            val response = sendOn(slot) { RpcRequest.HoldDisplay(nextId(), displayId) }
            (response as? RpcResponse.DisplayResult)?.info?.let { info ->
                origin.value = origin.value + (displayId to slot.provider.privilege)
                return@withContext Result.success(info)
            }
            last = response
        }
        Result.failure(IllegalArgumentException("display $displayId not held: ${last?.message()}"))
    }

    override suspend fun removeDisplay(displayId: Int): Result<Unit> = withContext(slotDispatcher) {
        var last: RpcResponse? = null
        for (slot in orderedSlotsFor(displayId)) {
            val response = sendOn(slot) { RpcRequest.RemoveDisplay(nextId(), displayId) }
            if (response is RpcResponse.Ok) {
                origin.value = origin.value - displayId
                return@withContext Result.success(Unit)
            }
            last = response
        }
        Result.failure(IllegalStateException("remove $displayId: ${last?.message()}"))
    }

    /** 合并所有通道的托管屏列表，并刷新 origin 归属表。 */
    override suspend fun listDisplays(): Result<List<DisplayInfo>> = withContext(slotDispatcher) {
        if (slots.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("not connected"))
        }
        val merged = LinkedHashMap<Int, DisplayInfo>()
        val origins = LinkedHashMap<Int, Privilege>()
        val errors = mutableListOf<String>()
        for (slot in slots.values.sortedByDescending { it.provider.priority }) {
            when (val response = sendOn(slot) { RpcRequest.ListDisplays(nextId()) }) {
                is RpcResponse.DisplayList -> response.displays.forEach {
                    merged[it.displayId] = it
                    origins[it.displayId] = slot.provider.privilege
                }
                is RpcResponse.Error -> errors.add("${slot.provider.privilege}: [${response.code}] ${response.message}")
                else -> errors.add("${slot.provider.privilege}: bad response $response")
            }
        }
        if (origins.isNotEmpty()) origin.value = origins
        if (merged.isEmpty() && errors.isNotEmpty()) {
            Result.failure(IllegalStateException("listDisplays failed on all slots: $errors"))
        } else {
            Result.success(merged.values.toList())
        }
    }

    override fun displayOrigin(displayId: Int): Privilege? = origin.value[displayId]

    // ---- 策略类操作：只在 system_server 守护（LSPosed 槽位）内生效 ----

    override suspend fun setDisplayPolicy(policy: DisplayPolicy): Result<DisplayPolicy> {
        val response = callSystemServer { RpcRequest.SetDisplayPolicy(nextId(), policy) }
        return (response as? RpcResponse.PolicyResult)?.policy
            ?.let { Result.success(it) }
            ?: Result.failure(IllegalStateException("setDisplayPolicy: ${response.message()}"))
    }

    override suspend fun listDisplayPolicies(): Result<List<DisplayPolicy>> {
        val response = callSystemServer { RpcRequest.ListDisplayPolicies(nextId()) }
        return (response as? RpcResponse.PolicyList)
            ?.let { Result.success(it.policies) }
            ?: Result.failure(IllegalStateException("listDisplayPolicies: ${response.message()}"))
    }

    override suspend fun removeDisplayPolicy(uid: Int): Result<Unit> {
        val response = callSystemServer { RpcRequest.RemoveDisplayPolicy(nextId(), uid) }
        return when (response) {
            is RpcResponse.Ok -> Result.success(Unit)
            else -> Result.failure(IllegalStateException("removeDisplayPolicy $uid: ${response.message()}"))
        }
    }

    override suspend fun displaysForUid(uid: Int): Result<List<Int>> {
        val response = callSystemServer { RpcRequest.DisplaysForUid(nextId(), uid) }
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

    // ---- 内部工具 ----

    private suspend fun sendOn(slot: Slot, build: () -> RpcRequest): RpcResponse =
        runCatching { slot.transport.send(build()) }.getOrElse { e ->
            Log.e(TAG, "rpc call failed via ${slot.provider.privilege}", e)
            RpcResponse.Error(0, -1, "rpc failed: ${e.message}")
        }

    private suspend fun queryCapabilities(transport: Transport): DaemonCapabilities? {
        val response = runCatching { transport.send(RpcRequest.GetCapabilities(nextId())) }
            .getOrElse { return null }
        return (response as? RpcResponse.Capabilities)?.capabilities
    }

    /** 指定 privilege 的槽位；null 则取最高优先级的已连接槽位。指定了但未连接时返回 null（不回退）。 */
    private fun slotFor(privilege: Privilege?): Slot? = when (privilege) {
        null -> primarySlot()
        else -> slots[privilege]
    }

    private fun primarySlot(): Slot? =
        slots.values.maxByOrNull { it.provider.priority }

    /** remove/hold 的路由顺序：已知归属的槽位优先，其余按优先级兜底。 */
    private fun orderedSlotsFor(displayId: Int): List<Slot> {
        val known = origin.value[displayId]?.let { slots[it] }
        val rest = slots.values.filter { it !== known }.sortedByDescending { it.provider.priority }
        return if (known != null) listOf(known) + rest else rest
    }

    /** 策略/可见性查询固定走 system_server 守护；未连接 LSPosed 时明确报错。 */
    private suspend fun callSystemServer(build: () -> RpcRequest): RpcResponse = withContext(slotDispatcher) {
        val slot = slots[Privilege.LSPOSED]
            ?: return@withContext RpcResponse.Error(
                0, -1,
                "LSPosed daemon not connected (policy ops require system_server daemon, connected=${slots.keys})"
            )
        sendOn(slot, build)
    }

    private fun publishSlots() {
        modes.value = slots.mapValues { it.value.workMode }
        mode.value = primarySlot()?.workMode
            ?: WorkModeInfo(Privilege.NONE, me.ynk.moredisplay.core.DaemonLocation.IN_APP, DaemonCapabilities.none())
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
}
