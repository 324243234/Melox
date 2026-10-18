package com.melox.player.ui.screen.home

import com.melox.player.model.MusicTrack
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.random.Random

internal data class HomeRecommendationSelection(
    val tracks: List<MusicTrack>,
    val artworkTrackIds: Set<Long>,
)

internal data class HomeRecommendationSource(
    val id: Long,
    val contentUri: String,
    val dateModifiedEpochSeconds: Long,
    val fileSizeBytes: Long,
)

internal fun MusicTrack.homeRecommendationSource(): HomeRecommendationSource =
    HomeRecommendationSource(id, contentUri, dateModifiedEpochSeconds, fileSizeBytes)

internal fun homeRecommendationSources(tracks: List<MusicTrack>): Set<HomeRecommendationSource> =
    tracks.mapTo(HashSet(), MusicTrack::homeRecommendationSource)

/** One root session reuses its candidate order and confirmed file-version probes. */
internal class HomeRecommendationLoader(private val seed: Int) {
    private val lock = Any()
    private var sources: Set<HomeRecommendationSource> = emptySet()
    private var candidates: List<MusicTrack> = emptyList()
    private val artworkChecks = ConcurrentHashMap<HomeRecommendationSource, Boolean>()

    suspend fun select(
        tracks: List<MusicTrack>,
        count: Int,
        probeBatchSize: Int,
        probeArtwork: suspend (MusicTrack) -> Boolean?,
    ): HomeRecommendationSelection {
        val caller = currentCoroutineContext()
        val currentSources = homeRecommendationSources(tracks)
        val orderedCandidates = synchronized(lock) {
            caller.ensureActive()
            if (currentSources != sources) {
                artworkChecks.keys.retainAll(currentSources)
                candidates = tracks.sortedBy(MusicTrack::id).shuffled(Random(seed))
                sources = currentSources
            }
            candidates
        }
        val tracksById = tracks.associateBy(MusicTrack::id)
        val currentCandidates = orderedCandidates.mapNotNull { tracksById[it.id] }
        return selectHomeRecommendationCandidates(currentCandidates, count, probeBatchSize) { track ->
            val key = track.homeRecommendationSource()
            artworkChecks[key] ?: run {
                val available = probeArtwork(track)
                synchronized(lock) {
                    caller.ensureActive()
                    if (available != null && key in sources) artworkChecks[key] = available
                }
                available == true
            }
        }
    }
}

internal suspend fun selectHomeRecommendations(
    tracks: List<MusicTrack>,
    seed: Int,
    count: Int,
    knownArtworkTrackIds: Set<Long> = emptySet(),
    probeBatchSize: Int = 8,
    hasArtwork: suspend (MusicTrack) -> Boolean,
): HomeRecommendationSelection = selectHomeRecommendationCandidates(
    candidates = tracks.shuffled(Random(seed)),
    count = count,
    probeBatchSize = probeBatchSize,
) { track -> track.id in knownArtworkTrackIds || hasArtwork(track) }

private suspend fun selectHomeRecommendationCandidates(
    candidates: List<MusicTrack>,
    count: Int,
    probeBatchSize: Int,
    hasArtwork: suspend (MusicTrack) -> Boolean,
): HomeRecommendationSelection {
    if (count <= 0 || probeBatchSize <= 0) {
        return HomeRecommendationSelection(emptyList(), emptySet())
    }
    val recommendations = ArrayList<MusicTrack>(count.coerceAtMost(candidates.size))
    val withoutArtwork = ArrayList<MusicTrack>()
    val artworkTrackIds = HashSet<Long>()
    for (batchStart in candidates.indices step probeBatchSize) {
        val batch = candidates.subList(
            batchStart,
            minOf(batchStart + probeBatchSize, candidates.size),
        )
        val artworkMatches = coroutineScope {
            batch.map { track -> async { hasArtwork(track) } }.awaitAll()
        }
        batch.forEachIndexed { index, track ->
            if (artworkMatches[index]) {
                recommendations += track
                artworkTrackIds += track.id
                if (recommendations.size == count) {
                    return HomeRecommendationSelection(recommendations, artworkTrackIds)
                }
            } else if (withoutArtwork.size < count) {
                withoutArtwork += track
            }
        }
    }
    recommendations += withoutArtwork.take(count - recommendations.size)
    return HomeRecommendationSelection(recommendations, artworkTrackIds)
}
