package com.example.localvoice.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.localvoice.LocalVoiceApp
import com.example.localvoice.audio.RecordingService
import com.example.localvoice.audio.RecordingState
import com.example.localvoice.data.ModelDownloadState
import com.example.localvoice.data.ModelManager
import com.example.localvoice.data.RecordingRepository
import com.example.localvoice.data.db.Cleanup
import com.example.localvoice.data.db.CleanupDao
import com.example.localvoice.data.db.Recording
import com.example.localvoice.data.db.Transcript
import com.example.localvoice.data.db.TranscriptDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val recordings: List<Recording> = emptyList(),
    val transcriptsById: Map<String, Transcript> = emptyMap(),
    val cleanupsById: Map<String, Cleanup> = emptyMap(),
    val recordingState: RecordingState = RecordingState.Idle,
    val playingId: String? = null,
    val whisperState: ModelDownloadState = ModelDownloadState.Idle,
    val cleanupState: ModelDownloadState = ModelDownloadState.Idle,
)

class MainViewModel(
    application: Application,
    private val repository: RecordingRepository,
    private val transcriptDao: TranscriptDao,
    private val cleanupDao: CleanupDao,
    private val modelManager: ModelManager,
) : AndroidViewModel(application) {

    private val player = Player()
    private val playingIdFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<UiState> =
        combineAll(
            repository.observeAll(),
            transcriptDao.observeAll(),
            cleanupDao.observeAll(),
            RecordingService.state,
            playingIdFlow,
            modelManager.whisperState,
            modelManager.cleanupState,
        ).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UiState(),
        )

    private fun combineAll(
        recordings: kotlinx.coroutines.flow.Flow<List<Recording>>,
        transcripts: kotlinx.coroutines.flow.Flow<List<Transcript>>,
        cleanups: kotlinx.coroutines.flow.Flow<List<Cleanup>>,
        recordingState: kotlinx.coroutines.flow.Flow<RecordingState>,
        playingId: kotlinx.coroutines.flow.Flow<String?>,
        whisperState: kotlinx.coroutines.flow.Flow<ModelDownloadState>,
        cleanupState: kotlinx.coroutines.flow.Flow<ModelDownloadState>,
    ): kotlinx.coroutines.flow.Flow<UiState> = combine(
        recordings, transcripts, cleanups, recordingState, playingId, whisperState, cleanupState,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        UiState(
            recordings = args[0] as List<Recording>,
            transcriptsById = (args[1] as List<Transcript>).associateBy { it.recordingId },
            cleanupsById = (args[2] as List<Cleanup>).associateBy { it.recordingId },
            recordingState = args[3] as RecordingState,
            playingId = args[4] as String?,
            whisperState = args[5] as ModelDownloadState,
            cleanupState = args[6] as ModelDownloadState,
        )
    }

    fun startRecording() = RecordingService.start(getApplication())
    fun stopRecording() = RecordingService.stop(getApplication())

    fun togglePlayback(recording: Recording) {
        if (playingIdFlow.value == recording.id) {
            player.stop()
            playingIdFlow.value = null
        } else {
            playingIdFlow.value = recording.id
            player.play(recording.id, recording.filePath) {
                playingIdFlow.value = null
            }
        }
    }

    fun delete(recording: Recording) {
        if (playingIdFlow.value == recording.id) {
            player.stop()
            playingIdFlow.value = null
        }
        viewModelScope.launch {
            cleanupDao.deleteByRecordingId(recording.id)
            transcriptDao.deleteByRecordingId(recording.id)
            repository.delete(recording.id)
        }
    }

    fun downloadWhisper() {
        viewModelScope.launch { modelManager.ensureWhisperModel() }
    }

    fun downloadCleanup() {
        viewModelScope.launch { modelManager.ensureCleanupModel() }
    }

    override fun onCleared() {
        player.stop()
        super.onCleared()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as LocalVoiceApp
                MainViewModel(
                    application = app,
                    repository = app.container.recordingRepository,
                    transcriptDao = app.container.transcriptDao,
                    cleanupDao = app.container.cleanupDao,
                    modelManager = app.container.modelManager,
                )
            }
        }
    }
}
