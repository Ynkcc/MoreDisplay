package io.github.ynkcc.moredisplay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.Visibility
import io.github.ynkcc.moredisplay.ui.viewmodel.PoliciesViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 列表轮询周期（ms），与「显示器」页一致。 */
private const val REFRESH_INTERVAL_MS = 3000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoliciesScreen(
    vm: PoliciesViewModel,
    snackbarHostState: SnackbarHostState
) {
    val policies by vm.policies.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val displays by vm.displays.collectAsStateWithLifecycle()
    val apps by vm.installedApps.collectAsStateWithLifecycle()

    // 编辑器状态：editing = null 新建；非 null 编辑既有策略（按 uid upsert）。
    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<DisplayPolicy?>(null) }
    var pendingDelete by remember { mutableStateOf<DisplayPolicy?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // 进入页面即重新拉取，并在可见期间轮询，保证与守护进程状态一致。
    LaunchedEffect(Unit) {
        vm.reload()
        while (isActive) {
            delay(REFRESH_INTERVAL_MS)
            vm.reload()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "添加策略")
            }
        }
    ) { padding ->
        if (policies.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(policies, key = { it.uid }) { policy ->
                    PolicyCard(
                        policy = policy,
                        enabled = !busy,
                        onEdit = { editing = policy; showEditor = true },
                        onRemove = { pendingDelete = policy }
                    )
                }
            }
        }
    }

    if (showEditor) {
        PolicyEditorSheet(
            editing = editing,
            busy = busy,
            displays = displays,
            apps = apps,
            onLoadApps = vm::loadInstalledApps,
            onDismiss = { showEditor = false },
            onSave = { policy ->
                showEditor = false
                vm.addPolicy(
                    uid = policy.uid,
                    packageName = policy.packageName,
                    visibility = policy.visibility,
                    displayIds = policy.displayIds,
                    operatedDisplayId = policy.operatedDisplayId,
                    recordDisplayId = policy.recordDisplayId
                )
            },
            onDelete = editing?.let { policy ->
                {
                    showEditor = false
                    vm.removePolicy(policy.uid)
                }
            }
        )
    }

    pendingDelete?.let { policy ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除策略") },
            text = {
                Text(
                    "确认删除 ${policy.packageName ?: "uid=${policy.uid}"} 的策略？" +
                        "删除后该应用恢复默认可见性。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { pendingDelete = null; vm.removePolicy(policy.uid) },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/** 策略卡片：应用图标 + 名称 + 可见性徽标 + 屏幕 chips；点卡片进入编辑。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PolicyCard(
    policy: DisplayPolicy,
    enabled: Boolean,
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Card(onClick = onEdit, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppIcon(packageName = policy.packageName, size = 40)
                Column(Modifier.weight(1f)) {
                    Text(
                        policy.packageName ?: "uid=${policy.uid}",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "uid=${policy.uid}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace
                    )
                }
                IconButton(onClick = onRemove, enabled = enabled) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除策略")
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                VisibilityBadge(policy.visibility)
                if (policy.displayIds.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        policy.displayIds.sorted().forEach { id ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    "#$id",
                                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
            val extras = listOfNotNull(
                policy.recordDisplayId?.let { "录屏→#$it" },
                policy.operatedDisplayId?.let { "无障碍→#$it" }
            ).joinToString("  ")
            if (extras.isNotEmpty()) {
                Text(
                    extras,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun VisibilityBadge(v: Visibility) {
    val (label, color) = when (v) {
        Visibility.ALL -> "全部" to MaterialTheme.colorScheme.tertiary
        Visibility.ONLY -> "仅白名单" to MaterialTheme.colorScheme.primary
        Visibility.HIDE -> "黑名单" to MaterialTheme.colorScheme.error
    }
    Surface(shape = MaterialTheme.shapes.small, color = color.copy(alpha = 0.12f)) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            color = color,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}
