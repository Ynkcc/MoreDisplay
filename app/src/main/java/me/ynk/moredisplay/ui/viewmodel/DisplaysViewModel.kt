package me.ynk.moredisplay.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.DisplaySpec
import me.ynk.moredisplay.core.IDisplayRepository
import me.ynk.moredisplay.core.Privilege

class DisplaysViewModel(
    private val repo: IDisplayRepository
) : ViewModel() {

    /** 按通道分组的托管屏，UI 分通道展示与管理。 */
    val displaysByChannel: StateFlow<Map<Privilege, List<DisplayInfo>>> = repo.displaysByChannel

    /** 当前已连接通道。 */
    val workModes: StateFlow<Map<Privilege, me.ynk.moredisplay.core.WorkModeInfo>> = repo.workModes

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** 在指定通道上创建虚拟屏。 */
    fun createDisplay(
        channel: Privilege,
        width: Int,
        height: Int,
        dpi: Int,
        name: String,
        flags: Int
    ) {
        viewModelScope.launch {
            _busy.value = true
            repo.createDisplay(
                DisplaySpec(
                    width = width, height = height, densityDpi = dpi,
                    name = name.ifBlank { "MoreDisplay" }, flags = flags
                ),
                channel
            ).fold(
                onSuccess = {
                    _message.value = "[${channel.label()}] 已创建 display ${it.displayId}"
                    repo.refreshDisplays()
                },
                onFailure = { _message.value = "创建失败: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun removeDisplay(id: Int) {
        viewModelScope.launch {
            _busy.value = true
            repo.removeDisplay(id).fold(
                onSuccess = {
                    _message.value = "display $id 已移除"
                    repo.refreshDisplays()
                },
                onFailure = { _message.value = "移除失败: ${it.message}" }
            )
            _busy.value = false
        }
    }

    fun refresh() = repo.refreshDisplays()

    fun consumeMessage() { _message.value = null }
}

fun Privilege.label(): String = when (this) {
    Privilege.LSPOSED -> "LSPosed"
    Privilege.ROOT -> "Root"
    Privilege.SHELL_SHIZUKU -> "Shizuku"
    Privilege.NONE -> "未归属"
}
