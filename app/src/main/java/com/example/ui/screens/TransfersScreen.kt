package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.ChannelMode
import com.example.data.local.FileTransferEntity
import com.example.data.local.TransferDirection
import com.example.data.local.TransferStatus
import com.example.data.repository.MeshChatRepository
import com.example.ui.components.ChannelModeBadge
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.SignalEmerald
import com.example.ui.theme.WarmAmber

@Composable
fun TransfersScreen(
    transfers: List<FileTransferEntity>,
    onPauseTransfer: (String) -> Unit,
    onResumeTransfer: (String) -> Unit,
    onResumeAllTransfers: () -> Unit,
    onCancelTransfer: (String) -> Unit,
    onOpenFile: (pathOrUri: String, mimeType: String?) -> Unit,
    onSaveToDownloads: (localPath: String, fileName: String, mimeType: String) -> Boolean
) {
    val context = LocalContext.current

    if (transfers.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = null,
                    tint = ElectricCyan,
                    modifier = Modifier.size(52.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.no_transfers_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.no_transfers_tip),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        return
    }

    val resumableCount = remember(transfers) {
        transfers.count { it.status == TransferStatus.PAUSED || it.status == TransferStatus.FAILED }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (resumableCount > 0) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = WarmAmber.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, WarmAmber.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.resumable_transfers_banner, resumableCount),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(R.string.resumable_transfers_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = onResumeAllTransfers,
                            modifier = Modifier.testTag("resume_all_transfers_button")
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.resume_all))
                        }
                    }
                }
            }
        }

        items(transfers, key = { it.transferId }) { transfer ->
            val progress = if (transfer.totalBytes > 0) {
                (transfer.transferredBytes.toFloat() / transfer.totalBytes).coerceIn(0f, 1f)
            } else 0f
            val pct = (progress * 100).toInt()

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("transfer_card_${transfer.transferId}")
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (transfer.direction == TransferDirection.INCOMING) {
                                Icons.Rounded.Download
                            } else {
                                Icons.Rounded.Upload
                            },
                            contentDescription = transfer.fileName,
                            tint = if (transfer.direction == TransferDirection.INCOMING) ElectricCyan else SignalEmerald
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = transfer.fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                ChannelModeBadge(mode = transfer.channelMode)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "${transfer.peerName} • ${MeshChatRepository.formatBytes(transfer.transferredBytes)} / ${MeshChatRepository.formatBytes(transfer.totalBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        StatusChip(transfer.status)
                    }

                    when (transfer.status) {
                        TransferStatus.TRANSFERRING, TransferStatus.QUEUED -> {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.transfer_in_progress,
                                        pct,
                                        MeshChatRepository.formatBytesPerSec(transfer.bytesPerSecond)
                                    ),
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        onClick = { onPauseTransfer(transfer.transferId) },
                                        modifier = Modifier.testTag("pause_transfer_${transfer.transferId}")
                                    ) {
                                        Text(stringResource(R.string.pause_transfer))
                                    }
                                    TextButton(onClick = { onCancelTransfer(transfer.transferId) }) {
                                        Text(stringResource(R.string.cancel_transfer), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        TransferStatus.PAUSED, TransferStatus.FAILED -> {
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress = { progress },
                                color = WarmAmber,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (transfer.channelMode == ChannelMode.ONLINE && transfer.totalChunks > 0) {
                                        stringResource(R.string.transfer_paused_chunks, pct, transfer.completedChunks, transfer.totalChunks)
                                    } else {
                                        stringResource(R.string.transfer_paused_progress, pct)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WarmAmber
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilledTonalButton(
                                        onClick = { onResumeTransfer(transfer.transferId) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("resume_transfer_${transfer.transferId}")
                                    ) {
                                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.resume_transfer))
                                    }
                                    TextButton(onClick = { onCancelTransfer(transfer.transferId) }) {
                                        Text(stringResource(R.string.cancel_transfer), color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }

                        TransferStatus.COMPLETED -> {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (transfer.checksumVerified) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Rounded.Verified,
                                            contentDescription = null,
                                            tint = SignalEmerald,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = stringResource(R.string.checksum_verified),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = SignalEmerald
                                        )
                                    }
                                }
                                Row {
                                    val path = transfer.localPath
                                    if (!path.isNullOrBlank()) {
                                        TextButton(onClick = { onOpenFile(path, transfer.mimeType) }) {
                                            Text(stringResource(R.string.open_file))
                                        }
                                        if (transfer.direction == TransferDirection.INCOMING) {
                                            TextButton(onClick = {
                                                if (onSaveToDownloads(path, transfer.fileName, transfer.mimeType)) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.saved_to_downloads),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }) {
                                                Text(stringResource(R.string.save_to_downloads))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        TransferStatus.CANCELLED -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: TransferStatus) {
    val label = when (status) {
        TransferStatus.QUEUED, TransferStatus.TRANSFERRING -> stringResource(R.string.outgoing_file)
        TransferStatus.PAUSED -> stringResource(R.string.transfer_paused)
        TransferStatus.COMPLETED -> stringResource(R.string.transfer_completed)
        TransferStatus.CANCELLED -> stringResource(R.string.transfer_cancelled)
        TransferStatus.FAILED -> stringResource(R.string.transfer_failed)
    }
    val color = when (status) {
        TransferStatus.COMPLETED -> SignalEmerald
        TransferStatus.PAUSED -> WarmAmber
        TransferStatus.FAILED, TransferStatus.CANCELLED -> MaterialTheme.colorScheme.error
        else -> ElectricCyan
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.16f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
