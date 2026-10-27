package com.melox.player.data.repository

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface TrackSourceDeletionResult {
    data object Deleted : TrackSourceDeletionResult
    data object Failed : TrackSourceDeletionResult
    data object StoragePermissionRequired : TrackSourceDeletionResult
    data class DocumentAccessRequired(val uri: Uri) : TrackSourceDeletionResult
    data class ConsentRequired(
        val sender: IntentSender,
        val deletesOnApproval: Boolean,
    ) : TrackSourceDeletionResult
}

/** A completed file deletion must be reconciled even if its UI owner is cancelled. */
internal suspend fun deleteAndReconcileTrackSource(
    delete: suspend () -> TrackSourceDeletionResult,
    onDeleted: () -> Unit,
): TrackSourceDeletionResult {
    currentCoroutineContext().ensureActive()
    return withContext(NonCancellable) {
        val result = delete()
        if (result == TrackSourceDeletionResult.Deleted) onDeleted()
        result
    }
}

internal suspend fun deleteTrackSource(
    context: Context,
    contentUri: String,
): TrackSourceDeletionResult = withContext(Dispatchers.IO) {
    val uri = Uri.parse(contentUri)
    val isDocument = DocumentsContract.isDocumentUri(context, uri)
    if (!isDocument && Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return@withContext TrackSourceDeletionResult.StoragePermissionRequired
    }
    try {
        val deleted = when {
            isDocument -> DocumentsContract.deleteDocument(context.contentResolver, uri)
            uri.scheme == "file" -> uri.path?.let { File(it).delete() } == true
            uri.scheme == "content" -> context.contentResolver.delete(uri, null, null) > 0
            else -> false
        }
        if (deleted) TrackSourceDeletionResult.Deleted else TrackSourceDeletionResult.Failed
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: SecurityException) {
        when {
            isDocument -> TrackSourceDeletionResult.DocumentAccessRequired(uri)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.authority == MediaStore.AUTHORITY ->
                runCatching {
                    TrackSourceDeletionResult.ConsentRequired(
                        MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender,
                        deletesOnApproval = true,
                    )
                }.getOrDefault(TrackSourceDeletionResult.Failed)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && exception is RecoverableSecurityException ->
                TrackSourceDeletionResult.ConsentRequired(
                    exception.userAction.actionIntent.intentSender,
                    deletesOnApproval = false,
                )
            else -> TrackSourceDeletionResult.Failed
        }
    } catch (_: Exception) {
        TrackSourceDeletionResult.Failed
    }
}
