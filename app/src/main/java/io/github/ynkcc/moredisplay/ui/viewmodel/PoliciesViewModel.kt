package io.github.ynkcc.moredisplay.ui.viewmodel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.IDisplayRepository
import io.github.ynkcc.moredisplay.core.Visibility

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

    init {
        reload()
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
