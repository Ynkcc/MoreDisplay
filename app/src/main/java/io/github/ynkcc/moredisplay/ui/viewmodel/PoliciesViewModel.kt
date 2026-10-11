package io.github.ynkcc.moredisplay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.IDisplayRepository
import io.github.ynkcc.moredisplay.core.Visibility

/** 应用选择器条目：label / 包名 / uid 一次取齐，图标在 UI 层按需加载。 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val uid: Int
)

class PoliciesViewModel(
    private val repo: IDisplayRepository,
    private val app: Application
) : ViewModel() {

    val displays = repo.managedDisplays

    private val _policies = MutableStateFlow<List<DisplayPolicy>>(emptyList())
    val policies: StateFlow<List<DisplayPolicy>> = _policies

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 已安装应用列表；null = 尚未加载/加载中。 */
    private val _installedApps = MutableStateFlow<List<AppEntry>?>(null)
    val installedApps: StateFlow<List<AppEntry>?> = _installedApps

    init {
        reload()
    }

    /** 首次调用时在 IO 线程读取已安装应用，结果缓存，重复调用为空操作。 */
    fun loadInstalledApps() {
        if (_installedApps.value != null) return
        viewModelScope.launch(Dispatchers.IO) {
            val pm = app.packageManager
            val list = runCatching {
                pm.getInstalledApplications(0)
                    .map { AppEntry(it.packageName, it.loadLabel(pm).toString(), it.uid) }
                    .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            }.getOrDefault(emptyList())
            _installedApps.value = list
        }
    }

    fun reload() {
        viewModelScope.launch {
            _busy.value = true
            repo.listDisplayPolicies().fold(
                onSuccess = { _policies.value = it },
                onFailure = { _message.value = "加载策略失败: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun resolveUid(packageName: String): Int? = runCatching {
        app.packageManager.getApplicationInfo(packageName, 0).uid
    }.getOrNull()

    fun addPolicy(
        uid: Int,
        packageName: String?,
        visibility: Visibility,
        displayIds: Set<Int>,
        operatedDisplayId: Int?,
        recordDisplayId: Int?
    ) {
        viewModelScope.launch {
            _busy.value = true
            repo.setDisplayPolicy(
                DisplayPolicy(
                    uid = uid,
                    packageName = packageName?.takeIf { it.isNotBlank() },
                    visibility = visibility,
                    displayIds = displayIds,
                    operatedDisplayId = operatedDisplayId,
                    recordDisplayId = recordDisplayId
                )
            ).fold(
                onSuccess = {
                    _message.value = "策略已保存: ${it.describe()}"
                    reload()
                },
                onFailure = { _message.value = "保存失败: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun removePolicy(uid: Int) {
        viewModelScope.launch {
            _busy.value = true
            repo.removeDisplayPolicy(uid).fold(
                onSuccess = {
                    _message.value = "策略已移除 uid=$uid"
                    reload()
                },
                onFailure = { _message.value = "移除失败: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun consumeMessage() { _message.value = null }
}
