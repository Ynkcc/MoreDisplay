package me.ynk.moredisplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.ynk.moredisplay.core.DisplayInfo
import me.ynk.moredisplay.core.Privilege
import me.ynk.moredisplay.core.VirtualDisplayFlags
import me.ynk.moredisplay.ui.viewmodel.DisplaysViewModel
import me.ynk.moredisplay.ui.viewmodel.label

/** 列表实时轮询周期（ms）。列表以守护进程为准，轮询保证外部变化也能反映。 */
private const val REFRESH_INTERVAL_MS = 3000L

@Composable
fun DisplaysScreen(
    vm: DisplaysViewModel,
    snackbarHostState: SnackbarHostState
) {
    val byChannel by vm.displaysByChannel.collectAsStateWithLifecycle()
    val workModes by vm.workModes.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    // null = 关闭；非 null = 在该通道上创建
    var createChannel by rememberSaveable { mutableStateOf<String?>(null) }

    // 进入页面即刷新，并在可见期间轮询，保证列表实时反映守护进程状态。
    LaunchedEffect(Unit) {
        vm.refresh()
        while (isActive) {
            delay(REFRESH_INTERVAL_MS)
            vm.refresh()
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val connectedChannels = workModes.keys.sortedByDescending { it.priority() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (connectedChannels.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("未连接任何通道", style = MaterialTheme.typography.titleMedium)
                Text(
                    "请先在主页连接；Shizuku 与 LSPosed 各自独立管理虚拟屏。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                connectedChannels.forEach { channel ->
                    item(key = "header-${channel.name}") {
                        ChannelSectionHeader(
                            channel = channel,
                            count = byChannel[channel].orEmpty().size,
                            enabled = !busy,
                            onCreate = { createChannel = channel.name }
                        )
                    }
                    val list = byChannel[channel].orEmpty()
                    if (list.isEmpty()) {
                        item(key = "empty-${channel.name}") {
                            Text(
                                "该通道暂无托管屏",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    } else {
                        items(list, key = { it.displayId }) { info ->
                            DisplayCard(
                                info = info,
                                channel = channel,
                                enabled = !busy,
                                onRemove = { vm.removeDisplay(info.displayId) }
                            )
                        }
                    }
                    item(key = "spacer-${channel.name}") { Spacer(Modifier.height(4.dp)) }
                }
            }
        }
    }

    createChannel?.let { channelName ->
        Privilege.entries.firstOrNull { it.name == channelName }?.let { channel ->
            CreateDisplayDialog(
                channel = channel,
                busy = busy,
                onDismiss = { createChannel = null },
                onCreate = { w, h, dpi, name, flags ->
                    createChannel = null
                    vm.createDisplay(channel, w, h, dpi, name, flags)
                }
            )
        }
    }
}

/** 通道分区头：通道名 + 屏数量 + 独立的创建入口。 */
@Composable
private fun ChannelSectionHeader(
    channel: Privilege,
    count: Int,
    enabled: Boolean,
    onCreate: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(channel.label(), style = MaterialTheme.typography.titleMedium)
            Text(
                "　$count 块",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onCreate, enabled = enabled) {
                Icon(Icons.Filled.Add, contentDescription = "在 ${channel.label()} 上创建")
            }
        }
    }
}

@Composable
private fun DisplayCard(
    info: DisplayInfo,
    channel: Privilege,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Display #${info.displayId}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = "移除")
                }
            }
            Text(
                "${info.spec.width}×${info.spec.height} @ ${info.spec.densityDpi}dpi",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                "name=${info.spec.name}  flags=0x${Integer.toHexString(info.spec.flags)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            info.owner?.let { owner ->
                Text(
                    "owner=${owner.packageName ?: "?"}(${owner.uid})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreateDisplayDialog(
    channel: Privilege,
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (Int, Int, Int, String, Int) -> Unit
) {
    var width by rememberSaveable { mutableStateOf("1080") }
    var height by rememberSaveable { mutableStateOf("1920") }
    var dpi by rememberSaveable { mutableStateOf("320") }
    var name by rememberSaveable { mutableStateOf("MoreDisplay") }
    var flags by rememberSaveable { mutableStateOf(0) }

    val w = width.toIntOrNull() ?: 0
    val h = height.toIntOrNull() ?: 0
    val d = dpi.toIntOrNull() ?: 0
    val valid = w in 1..7680 && h in 1..7680 && d in 10..2000

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在 ${channel.label()} 上创建") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1080×1920@320" to Triple(1080, 1920, 320),
                           "1920×1080@240" to Triple(1920, 1080, 240),
                           "800×1280@213" to Triple(800, 1280, 213)).forEach { (label, v) ->
                        AssistChip(onClick = {
                            width = v.first.toString(); height = v.second.toString(); dpi = v.third.toString()
                        }, label = { Text(label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = width, onValueChange = { width = it },
                        label = { Text("宽度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = w !in 1..7680,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = height, onValueChange = { height = it },
                        label = { Text("高度") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = h !in 1..7680,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = dpi, onValueChange = { dpi = it },
                    label = { Text("密度 (dpi)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = d !in 10..2000,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Flags", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FLAG_OPTIONS.forEach { (label, bit) ->
                        val checked = flags and bit != 0
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.width(190.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    flags = if (on) flags or bit else flags and bit.inv()
                                }
                            )
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !busy, onClick = { onCreate(w, h, d, name, flags) }) {
                Text("创建")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 与 DaemonProvider.priority 对齐的展示排序。 */
private fun Privilege.priority(): Int = when (this) {
    Privilege.LSPOSED -> 100
    Privilege.SHELL_SHIZUKU -> 50
    Privilege.ROOT -> 10
    Privilege.NONE -> 0
}

private val FLAG_OPTIONS = listOf(
    "PUBLIC" to VirtualDisplayFlags.PUBLIC,
    "PRESENTATION" to VirtualDisplayFlags.PRESENTATION,
    "SECURE" to VirtualDisplayFlags.SECURE,
    "OWN_CONTENT_ONLY" to VirtualDisplayFlags.OWN_CONTENT_ONLY,
    "AUTO_MIRROR" to VirtualDisplayFlags.AUTO_MIRROR,
    "SUPPORTS_TOUCH" to VirtualDisplayFlags.SUPPORTS_TOUCH,
    "SHOULD_SHOW_SYSTEM_DECORATIONS" to VirtualDisplayFlags.SHOULD_SHOW_SYSTEM_DECORATIONS,
    "OWN_FOCUS" to VirtualDisplayFlags.OWN_FOCUS,
    "ALWAYS_UNLOCKED" to VirtualDisplayFlags.ALWAYS_UNLOCKED,
    "TRUSTED" to VirtualDisplayFlags.TRUSTED
)
