package com.melox.player.ui.component.playlist

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.melox.player.R
import com.melox.player.model.LocalPlaylist
import com.melox.player.model.MusicTrack
import com.melox.player.ui.component.library.rememberArtworkBitmap
import top.yukonga.miuix.kmp.basic.BasicComponent
import com.melox.player.ui.component.PageCard as Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType

internal enum class PlaylistArtworkLayout {
    EMPTY,
    SINGLE,
    DOUBLE,
    COLLAGE,
}

internal fun playlistArtworkLayout(entryCount: Int): PlaylistArtworkLayout = when {
    entryCount <= 0 -> PlaylistArtworkLayout.EMPTY
    entryCount == 1 -> PlaylistArtworkLayout.SINGLE
    entryCount == 2 -> PlaylistArtworkLayout.DOUBLE
    else -> PlaylistArtworkLayout.COLLAGE
}

@Composable
fun PlaylistGridItem(
    playlist: LocalPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showEmptyArtworkIcon: Boolean = true,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.surfaceVariant),
        pressFeedbackType = PressFeedbackType.Sink,
        onClick = onClick,
    ) {
        BasicComponent(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                PlaylistArtwork(
                    tracks = playlist.entries.map { entry -> entry.trackSnapshot },
                    showEmptyArtworkIcon = showEmptyArtworkIcon,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = playlist.name,
                    modifier = Modifier.padding(start = 6.dp, top = 12.dp),
                    style = MiuixTheme.textStyles.body2,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_card_song_count,
                        playlist.entries.size,
                        playlist.entries.size,
                    ),
                    modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 6.dp),
                    style = MiuixTheme.textStyles.footnote1.copy(fontSize = 12.sp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun PlaylistArtwork(
    tracks: List<MusicTrack>,
    showEmptyArtworkIcon: Boolean,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(3f / 2f)
            .squircleClip(12.dp),
    ) {
        val coverHeight = maxHeight
        when (playlistArtworkLayout(tracks.size)) {
            PlaylistArtworkLayout.EMPTY -> PlaylistArtworkTile(
                track = null,
                requestSize = coverHeight,
                showPlaceholderIcon = showEmptyArtworkIcon,
            )

            PlaylistArtworkLayout.SINGLE -> PlaylistArtworkTile(
                track = tracks.first(),
                requestSize = coverHeight,
            )

            PlaylistArtworkLayout.DOUBLE -> Row(modifier = Modifier.fillMaxSize()) {
                PlaylistArtworkTile(
                    track = tracks[0],
                    requestSize = coverHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                Spacer(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Color.Black),
                )
                PlaylistArtworkTile(
                    track = tracks[1],
                    requestSize = coverHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }

            PlaylistArtworkLayout.COLLAGE -> Row(modifier = Modifier.fillMaxSize()) {
                PlaylistArtworkTile(
                    track = tracks[0],
                    requestSize = coverHeight,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(coverHeight),
                )
                Spacer(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(Color.Black),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    PlaylistArtworkTile(
                        track = tracks[1],
                        requestSize = coverHeight / 2f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(Color.Black),
                    )
                    PlaylistArtworkTile(
                        track = tracks[2],
                        requestSize = coverHeight / 2f,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistArtworkTile(
    track: MusicTrack?,
    requestSize: Dp,
    modifier: Modifier = Modifier,
    showPlaceholderIcon: Boolean = true,
) {
    val bitmap = rememberArtworkBitmap(
        contentUri = track?.contentUri.orEmpty(),
        dateModifiedEpochSeconds = track?.dateModifiedEpochSeconds ?: 0L,
        fileSizeBytes = track?.fileSizeBytes ?: 0L,
        size = requestSize,
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        } else if (showPlaceholderIcon) {
            Icon(
                imageVector = MiuixIcons.Music,
                contentDescription = null,
                modifier = Modifier.size(requestSize / 2f),
                tint = MiuixTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f),
            )
        }
    }
}
