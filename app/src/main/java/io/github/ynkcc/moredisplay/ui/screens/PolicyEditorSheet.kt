package io.github.ynkcc.moredisplay.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ynkcc.moredisplay.core.DisplayInfo
import io.github.ynkcc.moredisplay.core.DisplayPolicy
import io.github.ynkcc.moredisplay.core.Visibility
import io.github.ynkcc.moredisplay.ui.viewmodel.AppEntry

/**
 * 卡片式策略编辑表单（[editing] 为 null 表示新建，非 null 表示编辑既有策略；
 * 保存按 uid upsert，编辑模式额外提供删除入口）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun PolicyEditorSheet(
    editing: DisplayPolicy?,
    busy: Boolean,
    displays: Map<Int, DisplayInfo>,
    apps: List<AppEntry>?,
    onLoadApps: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (DisplayPolicy) -> Unit,
    onDelete: (() -> Unit)?
) {
    var selectedApp by remember {
        mutableStateOf(editing?.packageName?.let { AppEntry(it, it, editing.uid) })
    }
    var uidText by remember { mutableStateOf(editing?.uid?.toString() ?: "") }
    var visibility by remember { mutableStateOf(editing?.visibility ?: Visibility.ALL) }
    var selectedIds by remember { mutableStateOf(editing?.displayIds ?: emptySet()) }
    var record by remember { mutableStateOf(editing?.recordDisplayId ?: -1) }
    var operated by remember { mutableStateOf(editing?.operatedDisplayId ?: -1) }
    var showPicker by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val uid = uidText.toIntOrNull() ?: -1
    val valid = uid >= 0
    val displayIds = displays.keys.sorted()

    LaunchedEffect(Unit) { onLoadApps() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (editing == null) "添加策略" else "编辑策略",
                style = MaterialTheme.typography.titleLarge
            )

            // —— 目标应用 ——
            Text("目标应用", style = MaterialTheme.typography.titleSmall)
            OutlinedCard(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AppIcon(packageName = selectedApp?.packageName, size = 40)
                    val app = selectedApp
                    Column(Modifier.weight(1f)) {
                        if (app == null) {
                            Text("点击选择应用", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "也可在下方手动填写 uid（如系统 uid）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                app.label,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${app.packageName} · uid=${app.uid}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = uidText,
                onValueChange = { uidText = it },
                label = { Text("uid") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = uidText.isNotEmpty() && !valid,
                supportingText = {
                    if (uidText.isNotEmpty() && !valid) Text("uid 必须是非负整数")
                },
                modifier = Modifier.fillMaxWidth()
            )

            // —— 可见性 ——
            Text("可见性", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Visibility.entries.forEach { v ->
                    FilterChip(
                        selected = visibility == v,
                        onClick = {
                            visibility = v
                            if (v == Visibility.ALL) selectedIds = emptySet()
                        },
                        label = { Text(visibilityLabel(v)) }
                    )
                }
            }
            Text(
                when (visibility) {
                    Visibility.ALL -> "不限制：该应用可以看到所有屏幕。"
                    Visibility.ONLY -> "白名单：该应用只能看到勾选的屏幕。"
                    Visibility.HIDE -> "黑名单：该应用看不到勾选的屏幕。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // —— 适用屏幕（ALL 时无意义，隐藏） ——
            if (visibility != Visibility.ALL) {
                Text("适用屏幕", style = MaterialTheme.typography.titleSmall)
                if (displayIds.isEmpty()) {
                    Text(
                        "暂无受管显示，先在「显示器」页创建",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        displayIds.forEach { id ->
                            FilterChip(
                                selected = id in selectedIds,
                                onClick = {
                                    selectedIds =
                                        if (id in selectedIds) selectedIds - id else selectedIds + id
                                },
                                label = { Text(displayLabel(displays, id)) }
                            )
                        }
                    }
                }
            }

            // —— 录屏替换 ——
            Text("录屏替换", style = MaterialTheme.typography.titleSmall)
            Text(
                "录屏来源重定向到勾选的屏幕",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RedirectChips(
                displayIds = displayIds,
                displays = displays,
                selected = record,
                onSelect = { record = it }
            )

            // —— 无障碍替换 ——
            Text("无障碍替换", style = MaterialTheme.typography.titleSmall)
            Text(
                "无障碍手势重定向到勾选的屏幕",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            RedirectChips(
                displayIds = displayIds,
                displays = displays,
                selected = operated,
                onSelect = { operated = it }
            )

            // —— 操作 ——
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = valid && !busy,
                    onClick = {
                        onSave(
                            DisplayPolicy(
                                uid = uid,
                                packageName = selectedApp?.packageName,
                                visibility = visibility,
                                displayIds =
                                    if (visibility == Visibility.ALL) emptySet() else selectedIds,
                                operatedDisplayId = operated.takeIf { it >= 0 },
                                recordDisplayId = record.takeIf { it >= 0 }
                            )
                        )
                    }
                ) { Text("保存") }
                OutlinedButton(onClick = onDismiss) { Text("取消") }
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }

    if (showPicker) {
        AppPickerSheet(
            apps = apps,
            onPick = { app ->
                selectedApp = app
                uidText = app.uid.toString()
                showPicker = false
            },
            onDismiss = { showPicker = false }
        )
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除策略") },
            text = { Text("确认删除 uid=$uid 的策略？删除后该应用恢复默认可见性。") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

/** 替换类单选 chips：「不替换」+ 各受管屏。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RedirectChips(
    displayIds: List<Int>,
    displays: Map<Int, DisplayInfo>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == -1,
            onClick = { onSelect(-1) },
            label = { Text("不替换") }
        )
        displayIds.forEach { id ->
            FilterChip(
                selected = selected == id,
                onClick = { onSelect(id) },
                label = { Text(displayLabel(displays, id)) }
            )
        }
    }
}

/** 应用选择器：搜索（名称 / 包名 / uid）+ 可滚动列表。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppPickerSheet(
    apps: List<AppEntry>?,
    onPick: (AppEntry) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索应用 / 包名 / uid") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
            when {
                apps == null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                apps.isEmpty() -> Text(
                    "未读取到已安装应用",
                    Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                else -> {
                    val filtered = apps.filter { app ->
                        query.isBlank() ||
                            app.label.contains(query, ignoreCase = true) ||
                            app.packageName.contains(query, ignoreCase = true) ||
                            app.uid.toString() == query.trim()
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            "无匹配应用",
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.65f)
                    ) {
                        items(filtered, key = { "${it.packageName}:${it.uid}" }) { app ->
                            AppRow(app = app, onClick = { onPick(app) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppIcon(packageName = app.packageName, size = 40)
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${app.packageName} · uid=${app.uid}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 应用图标：按包名懒加载并栅格化；取不到时显示占位圆。 */
@Composable
internal fun AppIcon(packageName: String?, size: Int) {
    val context = LocalContext.current
    val icon: ImageBitmap? = if (packageName.isNullOrBlank()) null
    else remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            bmp.asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier.size(size.dp)
        )
    } else {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(size.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("?", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

internal fun displayLabel(displays: Map<Int, DisplayInfo>, id: Int): String {
    val name = displays[id]?.spec?.name
    return if (name.isNullOrBlank() || name == "MoreDisplay") "#$id" else "#$id ($name)"
}

internal fun visibilityLabel(v: Visibility): String = when (v) {
    Visibility.ALL -> "全部"
    Visibility.ONLY -> "仅白名单"
    Visibility.HIDE -> "黑名单"
}
