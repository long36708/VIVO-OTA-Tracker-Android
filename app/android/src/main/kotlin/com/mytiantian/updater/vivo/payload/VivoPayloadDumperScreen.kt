package com.mytiantian.updater.vivo.payload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.mytiantian.updater.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.window.Dialog

@Composable
fun PayloadDumperScreen(
    viewModel: VivoPayloadViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val zipState by viewModel.zipState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.parseRequestId) {
        if (uiState.parseRequestId > 0 && uiState.pathOrUrl.isNotBlank()) {
            viewModel.parsePayload(uiState.pathOrUrl)
        }
    }

    LaunchedEffect(Unit) {
            viewModel.toastEvent.collect { toast ->
                val msg = when (toast) {
                    is PayloadToast.ExtractSuccess -> context.getString(R.string.payload_extract_success, toast.partitionName)
                    is PayloadToast.ExtractFailed -> context.getString(R.string.payload_extract_failed, toast.partitionName)
                    is PayloadToast.BatchComplete -> context.getString(R.string.payload_batch_complete, toast.success, toast.fail)
                    is PayloadToast.Error -> toast.message
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
    }

    LaunchedEffect(Unit) {
        viewModel.zipMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.payload_dumper),
                actions = {
                    if (uiState.filteredPartitions.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    if (viewModel.isAllSelected()) viewModel.deselectAll() else viewModel.selectAll()
                                }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                state = if (viewModel.isAllSelected()) ToggleableState.On else ToggleableState.Off,
                                onClick = {
                                    if (viewModel.isAllSelected()) viewModel.deselectAll() else viewModel.selectAll()
                                }
                            )
                            Spacer(modifier = Modifier.size(4.dp))
                            Text(
                                text = stringResource(R.string.payload_select_all),
                                fontSize = 14.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    Text(
                        text = "←",
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .clickable { onBack() },
                        fontSize = 20.sp
                    )
                }
            )
        },
        bottomBar = {
            if (uiState.selectedPartitions.isNotEmpty() && !uiState.isExtracting) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.payload_selected_count, uiState.selectedPartitions.size),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Button(
                            onClick = { viewModel.extractSelectedPartitions() },
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(stringResource(R.string.payload_extract_selected))
                        }
                    }
                }
            }
        }
    ) { padding ->
        if (uiState.isParsing) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.payload_loading),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TextField(
                            insideMargin = DpSize(16.dp, 24.dp),
                            value = uiState.inputUrl,
                            onValueChange = { viewModel.updateInputUrl(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.payload_input_hint),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                        )
                        Button(
                            onClick = { viewModel.submitUrl() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColorsPrimary(),
                            enabled = !uiState.isParsing
                        ) {
                            Text(stringResource(R.string.payload_parse))
                        }
                    }
                }

                if (uiState.error != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "⚠",
                                    fontSize = 20.sp
                                )
                                Spacer(modifier = Modifier.size(12.dp))
                                Text(
                                    text = uiState.error!!,
                                    fontSize = 13.sp,
                                    color = Color(0xFFE53935)
                                )
                            }
                        }
                    }
                }

                uiState.archiveInfo?.let { archiveInfo ->
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.rom_info),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                InfoRow(stringResource(R.string.file_name), archiveInfo.fileName)
                                InfoRow(stringResource(R.string.file_size), formatFileSize(archiveInfo.fileSize))
                                if (archiveInfo.securityPatchLevel.isNotEmpty()) {
                                    InfoRow(
                                        stringResource(R.string.payload_security_patch),
                                        archiveInfo.securityPatchLevel
                                    )
                                }
                                if (archiveInfo.buildDate.isNotEmpty()) {
                                    InfoRow(
                                        stringResource(R.string.payload_build_date),
                                        archiveInfo.buildDate
                                    )
                                }
                                InfoRow(
                                    stringResource(R.string.payload_package_type),
                                    if (archiveInfo.minorVersion == 0) {
                                        stringResource(R.string.payload_full)
                                    } else {
                                        stringResource(R.string.payload_incremental)
                                    }
                                )
                                if (archiveInfo.blockSize > 0) {
                                    InfoRow(
                                        stringResource(R.string.payload_block_size),
                                        "${archiveInfo.blockSize} B"
                                    )
                                }
                                if (archiveInfo.partialUpdate) {
                                    InfoRow(
                                        stringResource(R.string.payload_partial_update),
                                        stringResource(R.string.payload_yes)
                                    )
                                }
                            }
                        }
                    }
                }

                if (uiState.pathOrUrl.isNotBlank()) {
                    item {
                        ZipBrowserSection(viewModel = viewModel, zipState = zipState)
                    }
                    if (zipState.expanded) {
                        // 嵌套浏览时显示返回上层的入口
                        if (zipState.breadcrumb.size > 1) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.navigateZipUp(context) }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = "←", fontSize = 18.sp)
                                    Spacer(modifier = Modifier.size(8.dp))
                                    Text(
                                        text = stringResource(R.string.zip_back_up),
                                        fontSize = 13.sp,
                                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                    )
                                }
                            }
                        }
                        item {
                            ZipSearchField(viewModel = viewModel, query = zipState.searchQuery)
                        }
                        // 条目直接挂在外层 LazyColumn 上，避免嵌套滚动
                        items(zipState.visibleEntries) { entry ->
                            ZipEntryItem(
                                entry = entry,
                                isDownloading = zipState.downloadingEntryName == entry.name,
                                onPreview = { viewModel.previewEntry(context, entry) },
                                onOpenZip = { viewModel.openNestedZip(context, entry) },
                                onDownload = { viewModel.downloadEntry(context, entry) }
                            )
                        }
                    }
                }

                if (uiState.filteredPartitions.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.partition_list),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    items(uiState.filteredPartitions) { partitionInfo ->
                        PartitionItem(
                            partitionInfo = partitionInfo,
                            isSelected = uiState.selectedPartitions.contains(partitionInfo.partitionName),
                            isExtracting = uiState.isExtracting,
                            onToggleSelect = { viewModel.toggleSelection(partitionInfo.partitionName) },
                            onItemClick = { viewModel.selectPartition(partitionInfo.partitionName) },
                            onExtract = { viewModel.extractPartition(partitionInfo) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }

    uiState.selectedPartition?.let { partitionInfo ->
        PartitionDetailDialog(
            partitionInfo = partitionInfo,
            onDismiss = { viewModel.clearSelectedPartition() },
            onExtract = { viewModel.extractPartition(partitionInfo) }
        )
    }

    zipState.previewEntry?.let { entry ->
        ZipPreviewDialog(
            entry = entry,
            preview = zipState.preview,
            isLoading = zipState.isPreviewLoading,
            error = zipState.previewError,
            onDismiss = { viewModel.dismissPreview() },
            onLoadFull = { viewModel.previewEntry(context, entry, loadFull = true) },
            onDownload = { viewModel.downloadEntry(context, entry) }
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PartitionItem(
    partitionInfo: PartitionInfo,
    isSelected: Boolean,
    isExtracting: Boolean,
    onToggleSelect: () -> Unit,
    onItemClick: () -> Unit,
    onExtract: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onItemClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                state = if (isSelected) ToggleableState.On else ToggleableState.Off,
                onClick = onToggleSelect
            )
            Spacer(modifier = Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = partitionInfo.partitionName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(
                        R.string.partition_size_ops,
                        formatFileSize(partitionInfo.size),
                        partitionInfo.operationsCount,
                        partitionInfo.mergeOperationsCount
                    ),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
                if (partitionInfo.sha256.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.sha256, partitionInfo.sha256.take(16) + "..."),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("SHA256", partitionInfo.sha256)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            if (partitionInfo.isDownloading) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatFileSize(partitionInfo.progress.toLong()) + "/" + formatFileSize(partitionInfo.size),
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else if (!isExtracting) {
                Button(
                    onClick = onExtract,
                    colors = ButtonDefaults.buttonColorsPrimary()
                ) {
                    Text(stringResource(R.string.extract))
                }
            }
        }
    }
}

@Composable
private fun PartitionDetailDialog(
    partitionInfo: PartitionInfo,
    onDismiss: () -> Unit,
    onExtract: () -> Unit
) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = partitionInfo.partitionName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(4.dp))
                    InfoRow(stringResource(R.string.partition_size), formatFileSize(partitionInfo.size))
                    InfoRow(stringResource(R.string.partition_raw_size), formatFileSize(partitionInfo.rawSize))
                    InfoRow(
                        stringResource(R.string.partition_ops),
                        stringResource(R.string.partition_ops_value, partitionInfo.operationsCount, partitionInfo.mergeOperationsCount)
                    )
                    if (partitionInfo.sha256.isNotEmpty()) {
                        InfoRow(stringResource(R.string.sha256_full), partitionInfo.sha256)
                    }
                    if (partitionInfo.typeStats.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.partition_op_types),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        partitionInfo.typeStats.toList()
                            .sortedByDescending { it.second }
                            .forEach { (type, count) ->
                                InfoRow(type, count.toString())
                            }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            onExtract()
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColorsPrimary()
                    ) {
                        Text(stringResource(R.string.extract))
                    }
                }
            }
        }
    }
}


private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}

// ===== 包内文件浏览（ADR-001 / L0 层）=====

/** 可折叠的「包内文件」区块，默认折叠（ADR-001 D10）。 */
@Composable
private fun ZipBrowserSection(
    viewModel: VivoPayloadViewModel,
    zipState: ZipBrowserState
) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleZipExpanded(context) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.zip_package_files) +
                            if (zipState.entries.isNotEmpty()) " (${zipState.entries.size})" else "",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // 嵌套浏览时显示路径，便于确认当前所在层级
                    if (zipState.breadcrumb.size > 1) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = zipState.breadcrumb.joinToString(" / "),
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                Text(
                    text = if (zipState.expanded) "▲" else "▼",
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
            if (zipState.isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = stringResource(R.string.zip_loading),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
            zipState.error?.let { err ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = err, fontSize = 12.sp, color = Color(0xFFE53935))
            }
        }
    }
}

@Composable
private fun ZipSearchField(viewModel: VivoPayloadViewModel, query: String) {
    TextField(
        insideMargin = DpSize(16.dp, 20.dp),
        modifier = Modifier.fillMaxWidth(),
        value = query,
        onValueChange = { viewModel.updateZipSearch(it) },
        label = stringResource(R.string.zip_search_hint),
        singleLine = true
    )
}

@Composable
private fun ZipEntryItem(
    entry: ZipEntryInfo,
    isDownloading: Boolean,
    onPreview: () -> Unit,
    onOpenZip: () -> Unit,
    onDownload: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name.substringAfterLast('/').ifBlank { entry.name },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                entry.name.substringBeforeLast('/').let { dir ->
                    if (dir.isNotEmpty()) {
                        Text(
                            text = dir,
                            fontSize = 10.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                // ADR-001 D3：压缩后与解压后尺寸都展示，便于理解为何被 10MB 拦下
                Text(
                    text = stringResource(
                        R.string.zip_size_format,
                        formatFileSize(entry.compressedSize),
                        formatFileSize(entry.uncompressedSize)
                    ),
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }

            Spacer(modifier = Modifier.size(8.dp))
            if (isDownloading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else if (entry.isNestedZip) {
                // 嵌套 zip：进入内部继续浏览（仍需 ≤10MB，因 DEFLATE 无法部分解压）
                if (entry.canDownload) {
                    Button(onClick = onOpenZip) {
                        Text(stringResource(R.string.zip_open_zip), fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.zip_download), fontSize = 12.sp)
                }
            } else if (entry.canDownload) {
                if (entry.canPreview) {
                    Button(onClick = onPreview) {
                        Text(stringResource(R.string.zip_preview), fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                }
                Button(onClick = onDownload) {
                    Text(stringResource(R.string.zip_download), fontSize = 12.sp)
                }
            } else if (!entry.isDirectory) {
                Text(
                    text = stringResource(
                        if (entry.isSupported) R.string.zip_too_large else R.string.zip_unsupported
                    ),
                    fontSize = 10.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

@Composable
private fun ZipPreviewDialog(
    entry: ZipEntryInfo,
    preview: TextPreview?,
    isLoading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onLoadFull: () -> Unit,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .heightIn(max = 460.dp)
                ) {
                    Text(
                        text = entry.name.substringAfterLast('/').ifBlank { entry.name },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    when {
                        isLoading -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.size(8.dp))
                                Text(
                                    text = stringResource(R.string.zip_loading),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                        }

                        error != null -> {
                            Text(text = error, fontSize = 13.sp, color = Color(0xFFE53935))
                        }

                        preview != null -> {
                            InfoRow(stringResource(R.string.zip_encoding), preview.encoding)
                            if (preview.truncated) {
                                Text(
                                    text = stringResource(
                                        R.string.zip_truncated_hint,
                                        formatFileSize(preview.bytesLoaded),
                                        formatFileSize(preview.totalSize)
                                    ),
                                    fontSize = 11.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            // ADR-001 D8：Compose 的 Text 默认不可选中，必须包 SelectionContainer
                            // 才能长按拖选；这里用原生 BasicText 而非 miuix 的 Text，
                            // 以确保选择功能一定生效（后者内部实现不受我们控制）。
                            SelectionContainer(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f, fill = false)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                BasicText(
                                    text = preview.text,
                                    style = TextStyle(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { copyPreviewText(context, preview.text) }) {
                                Text(
                                    text = stringResource(
                                        if (preview.canCopyAll) R.string.zip_copy_all
                                        else R.string.zip_too_large_to_copy
                                    ),
                                    fontSize = 12.sp
                                )
                            }
                            if (preview.truncated && entry.canDownload) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Button(onClick = onLoadFull) {
                                    Text(stringResource(R.string.zip_load_full), fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    if (entry.canDownload) {
                        Button(
                            onClick = {
                                onDownload()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColorsPrimary()
                        ) {
                            Text(stringResource(R.string.zip_download))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Binder 事务上限约 1MB，复制超大文本会抛 TransactionTooLargeException（ADR-001 D8）。
 * 这里兜住异常，避免用户预览 9MB 文本并点「复制」时直接崩溃。
 */
private fun copyPreviewText(context: Context, text: String) {
    runCatching {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("preview", text))
        Toast.makeText(context, context.getString(R.string.copied), Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.zip_too_large_to_copy), Toast.LENGTH_LONG).show()
    }
}
