package com.melox.player.ui.component.library

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import com.melox.player.R
import com.melox.player.data.repository.TrackSourceDeletionResult
import com.melox.player.data.repository.deleteAndReconcileTrackSource
import com.melox.player.data.repository.deleteTrackSource
import com.melox.player.model.MusicTrack
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Composable
internal fun rememberTrackSourceDeletionHandler(
    onDeleted: (String) -> Unit,
): (MusicTrack) -> Unit {
    val context = LocalContext.current
    val latestOnDeleted by rememberUpdatedState(onDeleted)
    val failedMessage = stringResource(R.string.music_delete_failed)
    val writeAccessMessage = stringResource(R.string.music_delete_write_access)
    var pendingUri by rememberSaveable { mutableStateOf<String?>(null) }
    var operationUri by rememberSaveable { mutableStateOf<String?>(null) }
    var awaitingApproval by rememberSaveable { mutableStateOf(false) }
    var deletesOnApproval by rememberSaveable { mutableStateOf(false) }
    var documentAccessRequested by rememberSaveable { mutableStateOf(false) }
    var consentRequested by rememberSaveable { mutableStateOf(false) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val clearPending = {
        pendingUri = null
        operationUri = null
        awaitingApproval = false
    }
    val fail = {
        clearPending()
        Toast.makeText(context, failedMessage, Toast.LENGTH_SHORT).show()
    }
    val senderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (deletesOnApproval) {
                pendingUri?.let(latestOnDeleted)
                clearPending()
            } else {
                awaitingApproval = false
                retry += 1
            }
        } else {
            clearPending()
        }
    }
    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            awaitingApproval = false
            retry += 1
        } else {
            clearPending()
        }
    }
    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { tree ->
        val original = pendingUri?.toUri()
        if (tree == null || original == null) {
            clearPending()
        } else {
            try {
                require(tree.authority == original.authority)
                context.contentResolver.takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                operationUri = DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getDocumentId(original),
                ).toString()
                awaitingApproval = false
                retry += 1
            } catch (_: Exception) {
                fail()
            }
        }
    }
    LaunchedEffect(pendingUri, retry) {
        val original = pendingUri ?: return@LaunchedEffect
        if (awaitingApproval) return@LaunchedEffect
        val result = deleteAndReconcileTrackSource(
            delete = { deleteTrackSource(context, operationUri ?: original) },
            onDeleted = {
                latestOnDeleted(original)
                clearPending()
            },
        )
        if (result == TrackSourceDeletionResult.Deleted) return@LaunchedEffect
        currentCoroutineContext().ensureActive()
        when (result) {
            TrackSourceDeletionResult.Deleted -> Unit
            TrackSourceDeletionResult.Failed -> fail()
            TrackSourceDeletionResult.StoragePermissionRequired -> {
                awaitingApproval = true
                try {
                    storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } catch (_: Exception) {
                    fail()
                }
            }
            is TrackSourceDeletionResult.DocumentAccessRequired -> {
                if (documentAccessRequested) {
                    fail()
                } else {
                    documentAccessRequested = true
                    awaitingApproval = true
                    Toast.makeText(context, writeAccessMessage, Toast.LENGTH_LONG).show()
                    try {
                        documentLauncher.launch(result.uri)
                    } catch (_: Exception) {
                        fail()
                    }
                }
            }
            is TrackSourceDeletionResult.ConsentRequired -> {
                if (consentRequested) {
                    fail()
                } else {
                    consentRequested = true
                    deletesOnApproval = result.deletesOnApproval
                    awaitingApproval = true
                    try {
                        senderLauncher.launch(IntentSenderRequest.Builder(result.sender).build())
                    } catch (_: Exception) {
                        fail()
                    }
                }
            }
        }
    }
    return { track ->
        if (pendingUri == null) {
            operationUri = track.contentUri
            documentAccessRequested = false
            consentRequested = false
            pendingUri = track.contentUri
        }
    }
}
