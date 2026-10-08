package com.thiepn.scan.capture

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.thiepn.scan.data.ScanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Long-running PDF import survives activity recomposition and configuration
 * changes. The ViewModel emits a result instead of retaining an Activity,
 * Compose navigation state, or a SnackbarHostState.
 */
internal class ScanImportViewModel(
    private val importer: suspend (Uri, String?) -> String
) : ViewModel() {
    private val resultChannel = Channel<ScanImportResult>(Channel.BUFFERED)
    val results = resultChannel.receiveAsFlow()

    var isImporting by mutableStateOf(false)
        private set

    fun importPdf(uri: Uri, filename: String?) {
        if (isImporting) return
        isImporting = true

        viewModelScope.launch {
            try {
                resultChannel.send(
                    ScanImportResult.Success(importer(uri, filename))
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                resultChannel.send(
                    ScanImportResult.Failure(
                        error.message ?: "Could not import PDF"
                    )
                )
            } finally {
                isImporting = false
            }
        }
    }

    companion object {
        fun factory(repository: ScanRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    require(modelClass == ScanImportViewModel::class.java)
                    return ScanImportViewModel(repository::importPdf) as T
                }
            }
    }
}

internal sealed interface ScanImportResult {
    data class Success(val documentId: String) : ScanImportResult
    data class Failure(val message: String) : ScanImportResult
}
