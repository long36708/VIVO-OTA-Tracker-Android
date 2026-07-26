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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.mytiantian.updater.R
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.state.ToggleableState

@Composable
fun PayloadDumperScreen(
    viewModel: VivoPayloadViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.pathOrUrl) {
        if (uiState.pathOrUrl.isNotBlank() && !uiState.isParsing && uiState.partitions.isEmpty()) {
            viewModel.parsePayload(uiState.pathOrUrl)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastEvent.collect { toast ->
            val msg = when (toast) {
                is PayloadToast.ExtractSuccess -> context.getString(R.string.payload_extract_success, toast.partitionName)
                is PayloadToast.ExtractFailed -> context.getString(R.string.payload_extract_failed, toast.partitionName)
                is PayloadToast.BatchComplete -> context.getString(R.string.payload_batch_complete, toast.success, toast.fail)
            }
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
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
    onExtract: () -> Unit
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleSelect() }
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
                    text = stringResource(R.string.partition_size, formatFileSize(partitionInfo.size)),
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

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
