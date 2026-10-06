package me.ynk.moredisplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ynk.moredisplay.core.Visibility
import me.ynk.moredisplay.ui.viewmodel.PoliciesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoliciesScreen(
    vm: PoliciesViewModel,
    snackbarHostState: SnackbarHostState
) {
    val policies by vm.policies.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 进入页面即重新拉取策略列表，保证与守护进程状态一致。
    LaunchedEffect(Unit) { vm.reload() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加策略")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (policies.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("暂无显示可见性策略", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "策略按 uid 控制某个应用能看到哪些屏幕。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(policies, key = { it.uid }) { policy ->
                        PolicyCard(policy = policy, enabled = !busy, onRemove = { vm.removePolicy(policy.uid) })
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddPolicyDialog(
            busy = busy,
            availableDisplayIds = vm.displays.value.keys.sorted(),
            onDismiss = { showAdd = false },
            onAdd = { uid, pkg, visibility, ids, operated, record ->
                showAdd = false
                vm.addPolicy(uid, pkg, visibility, ids, operated, record)
            },
            resolveUid = { vm.resolveUid(it) }
        )
    }
}

@Composable
private fun PolicyCard(
    policy: me.ynk.moredisplay.core.DisplayPolicy,
    enabled: Boolean,
    onRemove: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    policy.packageName?.let { "$it(${policy.uid})" } ?: "uid=${policy.uid}",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除策略")
                }
            }
            Text(
                "${policy.visibility.name}[${policy.displayIds.sorted().joinToString(",")}]",
                style = MaterialTheme.typography.bodyMedium
            )
            val extras = listOfNotNull(
                policy.recordDisplayId?.let { "record=$it" },
                policy.operatedDisplayId?.let { "operated=$it" }
            ).joinToString("  ")
            if (extras.isNotEmpty()) {
                Text(extras, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPolicyDialog(
    busy: Boolean,
    availableDisplayIds: List<Int>,
    onDismiss: () -> Unit,
    onAdd: (Int, String?, Visibility, Set<Int>, Int?, Int?) -> Unit,
    resolveUid: (String) -> Int?
) {
    var packageName by rememberSaveable { mutableStateOf("") }
    var uidText by rememberSaveable { mutableStateOf("") }
    var visibility by rememberSaveable { mutableStateOf(Visibility.ALL.name) }
    var selectedIds by rememberSaveable { mutableStateOf(emptySet<Int>()) }
    var operated by rememberSaveable { mutableStateOf(-1) }
    var record by rememberSaveable { mutableStateOf(-1) }

    val uid = uidText.toIntOrNull() ?: -1
    val valid = uid >= 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加可见性策略") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = packageName, onValueChange = { packageName = it },
                    label = { Text("包名（可选，用于自动解析 uid）") },
                    singleLine = true,
                    trailingIcon = {
                        TextButton(
                            enabled = packageName.isNotBlank(),
                            onClick = { resolveUid(packageName)?.let { uidText = it.toString() } }
                        ) { Text("解析") }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = uidText, onValueChange = { uidText = it },
                    label = { Text("uid") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = uidText.isNotEmpty() && !valid,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("可见性", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Visibility.entries.forEach { v ->
                        FilterChip(
                            selected = visibility == v.name,
                            onClick = { visibility = v.name },
                            label = { Text(visibilityLabel(v)) }
                        )
                    }
                }
                if (availableDisplayIds.isEmpty()) {
                    Text(
                        "（暂无受管显示，先在「显示器」页创建）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("适用屏幕（${visibilityLabel(Visibility.valueOf(visibility))}）",
                        style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableDisplayIds.forEach { id ->
                            FilterChip(
                                selected = id in selectedIds,
                                onClick = {
                                    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
                                },
                                label = { Text("#$id") }
                            )
                        }
                    }
                    Text("录屏替换 / 无障碍替换", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = record == -1,
                            onClick = { record = -1 },
                            label = { Text("录屏:无") }
                        )
                        FilterChip(
                            selected = operated == -1,
                            onClick = { operated = -1 },
                            label = { Text("无障碍:无") }
                        )
                        availableDisplayIds.forEach { id ->
                            FilterChip(
                                selected = record == id,
                                onClick = { record = id },
                                label = { Text("录屏:#$id") }
                            )
                            FilterChip(
                                selected = operated == id,
                                onClick = { operated = id },
                                label = { Text("无障碍:#$id") }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && !busy,
                onClick = {
                    onAdd(
                        uid,
                        packageName.takeIf { it.isNotBlank() },
                        Visibility.valueOf(visibility),
                        selectedIds,
                        operated.takeIf { it >= 0 },
                        record.takeIf { it >= 0 }
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun visibilityLabel(v: Visibility): String = when (v) {
    Visibility.ALL -> "全部"
    Visibility.ONLY -> "仅白名单"
    Visibility.HIDE -> "黑名单"
}
