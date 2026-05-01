package com.example.localvoice.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.localvoice.audio.RecordingState
import com.example.localvoice.data.ModelDownloadState
import com.example.localvoice.data.db.Cleanup
import com.example.localvoice.data.db.Recording
import com.example.localvoice.data.db.Transcript
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    micPermissionGranted: Boolean,
    onRequestMicPermission: () -> Unit,
    viewModel: MainViewModel = viewModel(factory = MainViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isRecording = state.recordingState is RecordingState.Recording

    Scaffold(
        topBar = { TopAppBar(title = { Text("localVoice") }) },
        floatingActionButton = {
            RecordFab(
                isRecording = isRecording,
                onClick = {
                    if (!micPermissionGranted) {
                        onRequestMicPermission()
                    } else if (isRecording) {
                        viewModel.stopRecording()
                    } else {
                        viewModel.startRecording()
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            ModelStateBanner(
                label = "Whisper (~190 MB)",
                state = state.whisperState,
                onDownload = viewModel::downloadWhisper,
            )
            ModelStateBanner(
                label = "Qwen3 1.7B (~1 GB)",
                state = state.cleanupState,
                onDownload = viewModel::downloadCleanup,
            )
            if (state.recordings.isEmpty()) {
                EmptyState()
            } else {
                RecordingsList(
                    recordings = state.recordings,
                    transcriptsById = state.transcriptsById,
                    cleanupsById = state.cleanupsById,
                    playingId = state.playingId,
                    onTogglePlay = viewModel::togglePlayback,
                    onDelete = viewModel::delete,
                )
            }
        }
    }
}

@Composable
private fun ModelStateBanner(label: String, state: ModelDownloadState, onDownload: () -> Unit) {
    when (state) {
        is ModelDownloadState.Idle -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$label model not downloaded.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDownload) { Text("Download") }
                }
            }
        }
        is ModelDownloadState.Downloading -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        "Downloading $label… ${(state.progress * 100).toInt()}%" +
                            if (state.totalBytes > 0)
                                "  (${humanSize(state.downloadedBytes)} / ${humanSize(state.totalBytes)})"
                            else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is ModelDownloadState.Failed -> {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$label download failed: ${state.message}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDownload) { Text("Retry") }
                }
            }
        }
        is ModelDownloadState.Ready -> Unit
    }
}

@Composable
private fun RecordFab(isRecording: Boolean, onClick: () -> Unit) {
    val color by animateColorAsState(
        targetValue = if (isRecording) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
        label = "fabColor",
    )
    FloatingActionButton(onClick = onClick, containerColor = color) {
        Icon(
            imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
            contentDescription = if (isRecording) "Stop recording" else "Start recording",
        )
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text("No recordings yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap the mic to record your first voice memo.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordingsList(
    recordings: List<Recording>,
    transcriptsById: Map<String, Transcript>,
    cleanupsById: Map<String, Cleanup>,
    playingId: String?,
    onTogglePlay: (Recording) -> Unit,
    onDelete: (Recording) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 96.dp, start = 16.dp, end = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(recordings, key = { it.id }) { recording ->
            RecordingRow(
                recording = recording,
                transcript = transcriptsById[recording.id],
                cleanup = cleanupsById[recording.id],
                isPlaying = playingId == recording.id,
                onTogglePlay = { onTogglePlay(recording) },
                onDelete = { onDelete(recording) },
            )
        }
    }
}

@Composable
private fun RecordingRow(
    recording: Recording,
    transcript: Transcript?,
    cleanup: Cleanup?,
    isPlaying: Boolean,
    onTogglePlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onTogglePlay) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    formatTimestamp(recording.createdAt),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    formatDuration(recording.durationMs) + " • " + statusLabel(recording.status),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val showCleaned = !cleanup?.cleanedText.isNullOrBlank()
                val showRaw = !showCleaned && !transcript?.rawText.isNullOrBlank()
                if (showCleaned) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        cleanup!!.cleanedText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (!transcript?.rawText.isNullOrBlank() && transcript!!.rawText != cleanup.cleanedText) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Raw: ${transcript.rawText}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (showRaw) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        transcript!!.rawText,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status) {
    Recording.STATUS_RECORDED -> "Queued"
    Recording.STATUS_TRANSCRIBING -> "Transcribing…"
    Recording.STATUS_CLEANING -> "Cleaning up…"
    Recording.STATUS_READY -> "Ready"
    Recording.STATUS_FAILED -> "Failed"
    else -> status
}

private fun formatTimestamp(epochMillis: Long): String {
    val fmt = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
    return fmt.format(Date(epochMillis))
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).toInt()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun humanSize(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
