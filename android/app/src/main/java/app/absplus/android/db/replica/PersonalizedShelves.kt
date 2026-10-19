package app.absplus.android.db.replica

import app.absplus.android.db.userdata.MediaProgressEntity
import kotlin.random.Random

const val RECENT_SERIES_LIMIT = 5
const val DEFAULT_SHELF_LIMIT = 10
const val MAX_SHELF_LIMIT = 25

data class ShelvesInput(
        val mediaType: String,
        val now: Long,
        val limit: Int,
        val rows: List<ItemRow>,
        val progress: List<MediaProgressEntity>,
        val series: SeriesIndex,
        /** `seriesHideFromContinueListening` of the cached user. */
        val hiddenSeriesIds: Set<String>,
        /** Library settings. */
        val onlyLaterBooksInContinueSeries: Boolean,
        val hideSingleBookSeries: Boolean,
        val authors: List<AuthorEntity>,
        val episodes: List<EpisodeKey>,
        val discoverSeed: Long
)

sealed interface ShelfEntity {
  /** A book or podcast; with [episodeId] an episode of the podcast; [seriesMember] on Continue Series. */
  data class Item(val itemId: String, val episodeId: String? = null, val seriesMember: SeriesItemEntity? = null) : ShelfEntity

  data class Series(val seriesId: String, val bookIds: List<String>) : ShelfEntity

  data class Author(val authorId: String) : ShelfEntity
}

data class Shelf(
        val id: String,
        val label: String,
        val labelStringKey: String,
        /** book, podcast, episode, series or authors. */
        val type: String,
        val entities: List<ShelfEntity>,
        val total: Int
)

/**
 * The home shelves of Audiobookshelf 2.37.1 (`LibraryItem.getPersonalizedShelves`) over the replica and the user's
 * progress, in the server's order; empty shelves are left out. Progress of items the replica lacks is ignored. Where the
 * server leaves the order of equal keys open, the id decides.
 */
fun personalizedShelves(input: ShelvesInput): List<Shelf> =
        if (input.mediaType == "podcast") podcastShelves(input) else bookShelves(input)

private val BY_RECENT_PROGRESS = compareByDescending<MediaProgressEntity> { it.lastUpdate }.thenBy { it.libraryItemId }.thenBy { it.episodeId }

/** CAST(sequence AS FLOAT) ascending, nulls last. */
private val BY_SEQUENCE = Comparator<SeriesItemEntity> { a, b ->
  val x = sqliteFloat(a.sequence)
  val y = sqliteFloat(b.sequence)
  when {
    x == null && y == null -> 0
    x == null -> 1
    y == null -> -1
    else -> x.compareTo(y)
  }
}.thenBy { it.libraryItemId }

private fun shelf(id: String, label: String, key: String, type: String, entities: List<ShelfEntity>, total: Int): Shelf? =
        if (entities.isEmpty()) null else Shelf(id, label, key, type, entities, total)

private fun ItemRow.isAudio() = numTracks > 0

private fun ItemRow.isEbookOnly() = !ebookFormat.isNullOrEmpty() && numTracks == 0

private fun bookShelves(input: ShelvesInput): List<Shelf> {
  val rows = input.rows.associateBy { it.id }
  val progress = input.progress.filter { it.episodeId.isEmpty() && it.libraryItemId in rows }
  val progressOf = progress.associateBy { it.libraryItemId }
  val limit = input.limit
  val recentFrom = input.now - RECENT_WINDOW_MS
  val shelves = ArrayList<Shelf?>()

  // Continue Listening / Reading share one LIMIT, then split by media
  val inProgress = progress.filter {
    !it.isFinished && !it.hideFromContinueListening && (it.currentTime > 0 || (it.ebookProgress ?: 0.0) > 0)
  }.sortedWith(BY_RECENT_PROGRESS)
  val firstInProgress = inProgress.take(limit).map { rows.getValue(it.libraryItemId) }
  shelves += shelf("continue-listening", "Continue Listening", "LabelContinueListening", "book",
          firstInProgress.filter { it.isAudio() }.map { ShelfEntity.Item(it.id) }, inProgress.size)
  shelves += shelf("continue-reading", "Continue Reading", "LabelContinueReading", "book",
          firstInProgress.filter { it.isEbookOnly() }.map { ShelfEntity.Item(it.id) }, inProgress.size)

  shelves += continueSeries(input, rows, progressOf)

  val recent = input.rows.filter { it.addedAt >= recentFrom }.sortedWith(compareByDescending<ItemRow> { it.addedAt }.thenBy { it.id })
  shelves += shelf("recently-added", "Recently Added", "LabelRecentlyAdded", input.mediaType,
          recent.take(limit).map { ShelfEntity.Item(it.id) }, recent.size)

  val natural = naturalComparator()
  val recentSeries = input.series.series.values.filter { it.addedAt >= recentFrom }.mapNotNull { s ->
    val members = input.series.membersBySeries[s.id].orEmpty()
    if (input.hideSingleBookSeries && members.size <= 1) return@mapNotNull null
    // As the server: members without a sequence last, the rest naturally ("2" before "10")
    val books = members.filter { it.libraryItemId in rows }.sortedWith { a, b ->
      val x = a.sequence?.takeIf { it.isNotEmpty() }
      val y = b.sequence?.takeIf { it.isNotEmpty() }
      when {
        x == null && y == null -> 0
        x == null -> 1
        y == null -> -1
        else -> natural.compare(x, y)
      }
    }.map { it.libraryItemId }
    if (books.isEmpty()) null else s to books
  }.sortedWith(compareByDescending<Pair<SeriesEntity, List<String>>> { it.first.addedAt }.thenBy { it.first.id })
  shelves += shelf("recent-series", "Recent Series", "LabelRecentSeries", "series",
          recentSeries.take(RECENT_SERIES_LIMIT).map { (s, books) -> ShelfEntity.Series(s.id, books) }, recentSeries.size)

  shelves += discover(input, rows, progressOf)

  val finished = progress.filter { it.isFinished }.sortedWith(BY_RECENT_PROGRESS)
  val firstFinished = finished.take(limit).map { rows.getValue(it.libraryItemId) }
  shelves += shelf("listen-again", "Listen Again", "LabelListenAgain", "book",
          firstFinished.filter { it.isAudio() }.map { ShelfEntity.Item(it.id) }, finished.size)
  shelves += shelf("read-again", "Read Again", "LabelReadAgain", "book",
          firstFinished.filter { it.isEbookOnly() }.map { ShelfEntity.Item(it.id) }, finished.size)

  val authors = input.authors.filter { it.addedAt >= recentFrom && it.numBooks > 0 }
          .sortedWith(compareByDescending<AuthorEntity> { it.addedAt }.thenBy { it.id })
  shelves += shelf("newest-authors", "Newest Authors", "LabelNewestAuthors", "authors",
          authors.take(limit).map { ShelfEntity.Author(it.id) }, authors.size)
  return shelves.filterNotNull()
}

/**
 * Series with a finished book, none in progress and an unfinished one (with `onlyLater`: past the highest finished
 * sequence), most recently listened first; each gives its lowest unfinished book.
 */
private fun continueSeries(input: ShelvesInput, rows: Map<String, ItemRow>, progressOf: Map<String, MediaProgressEntity>): Shelf? {
  class Candidate(val seriesId: String, val recent: Long, val unfinished: List<SeriesItemEntity>, val maxFinished: Double?)

  val candidates = input.series.series.values.filter { it.id !in input.hiddenSeriesIds }.mapNotNull { s ->
    val members = input.series.membersBySeries[s.id].orEmpty().filter { it.libraryItemId in rows }
    val finished = members.filter { progressOf[it.libraryItemId]?.isFinished == true }
    if (finished.isEmpty()) return@mapNotNull null
    if (members.any { m -> progressOf[m.libraryItemId]?.let { !it.isFinished && it.currentTime > 0 } == true }) return@mapNotNull null
    val unfinished = members.filter { progressOf[it.libraryItemId]?.isFinished != true }
    var maxFinished: Double? = null
    val qualifying = if (input.onlyLaterBooksInContinueSeries) {
      // The server takes MAX of the sequence text, then casts: "9" beats "10"; no finished sequence drops the series
      maxFinished = sqliteFloat(finished.mapNotNull { it.sequence }.maxOrNull()) ?: return@mapNotNull null
      unfinished.filter { m -> sqliteFloat(m.sequence)?.let { it > maxFinished } == true }
    } else unfinished
    if (qualifying.isEmpty()) return@mapNotNull null
    val recent = members.mapNotNull { progressOf[it.libraryItemId]?.lastUpdate }.maxOrNull() ?: 0L
    Candidate(s.id, recent, unfinished, maxFinished)
  }.sortedWith(compareByDescending<Candidate> { it.recent }.thenBy { it.seriesId })
  val entities = candidates.take(input.limit).mapNotNull { c ->
    val ordered = c.unfinished.sortedWith(BY_SEQUENCE)
    // Here the server compares with JavaScript's parseFloat, not CAST: "4a" counts as 4
    val pick = if (input.onlyLaterBooksInContinueSeries) {
      val max = c.maxFinished ?: return@mapNotNull null
      ordered.firstOrNull { m -> jsParseFloat(m.sequence)?.let { it > max } == true }
    } else ordered.firstOrNull()
    pick?.let { ShelfEntity.Item(it.libraryItemId, seriesMember = it) }
  }
  return shelf("continue-series", "Continue Series", "LabelContinueSeries", "book", entities, candidates.size)
}

/**
 * Not-started books: random ones that are in no series or open a series nobody has started. Candidates are ordered by
 * id before shuffling, so the same seed gives the same shelf.
 */
private fun discover(input: ShelvesInput, rows: Map<String, ItemRow>, progressOf: Map<String, MediaProgressEntity>): Shelf? {
  val random = Random(input.discoverSeed)
  val notStarted = input.series.series.keys.sorted().filter { id ->
    input.series.membersBySeries[id].orEmpty().none { m -> progressOf[m.libraryItemId]?.let { it.isFinished || it.currentTime > 0 } == true }
  }.shuffled(random).take(input.limit)
  val firstBooks = notStarted.mapNotNull { id ->
    input.series.membersBySeries[id].orEmpty().filter { it.libraryItemId in rows }.sortedWith(BY_SEQUENCE).firstOrNull()?.libraryItemId
  }.toSet()
  val candidates = input.rows.filter { row ->
    val p = progressOf[row.id]
    (p == null || (!p.isFinished && p.currentTime == 0.0)) &&
            (input.series.membersByItem[row.id].isNullOrEmpty() || row.id in firstBooks)
  }.sortedBy { it.id }
  return shelf("discover", "Discover", "LabelDiscover", input.mediaType,
          candidates.shuffled(random).take(input.limit).map { ShelfEntity.Item(it.id) }, candidates.size)
}

private fun podcastShelves(input: ShelvesInput): List<Shelf> {
  val rows = input.rows.associateBy { it.id }
  val episodes = input.episodes.filter { it.libraryItemId in rows }.associateBy { it.id }
  // An episode whose list has not been read yet is left out until it is
  val progress = input.progress.filter { p -> p.episodeId.isNotEmpty() && episodes[p.episodeId]?.libraryItemId == p.libraryItemId }
  val limit = input.limit
  val recentFrom = input.now - RECENT_WINDOW_MS
  val shelves = ArrayList<Shelf?>()

  val inProgress = progress.filter { !it.isFinished && !it.hideFromContinueListening && it.currentTime > 0 }.sortedWith(BY_RECENT_PROGRESS)
  shelves += shelf("continue-listening", "Continue Listening", "LabelContinueListening", "episode",
          inProgress.take(limit).map { ShelfEntity.Item(it.libraryItemId, it.episodeId) }, inProgress.size)

  val newest = episodes.values.filter { it.addedAt >= recentFrom }.sortedWith(compareByDescending<EpisodeKey> { it.addedAt }.thenBy { it.id })
  shelves += shelf("newest-episodes", "Newest Episodes", "LabelNewestEpisodes", "episode",
          newest.take(limit).map { ShelfEntity.Item(it.libraryItemId, it.id) }, newest.size)

  val recent = input.rows.filter { it.addedAt >= recentFrom }.sortedWith(compareByDescending<ItemRow> { it.addedAt }.thenBy { it.id })
  shelves += shelf("recently-added", "Recently Added", "LabelRecentlyAdded", "podcast",
          recent.take(limit).map { ShelfEntity.Item(it.id) }, recent.size)

  val finished = progress.filter { it.isFinished }.sortedWith(BY_RECENT_PROGRESS)
  shelves += shelf("listen-again", "Listen Again", "LabelListenAgain", "episode",
          finished.take(limit).map { ShelfEntity.Item(it.libraryItemId, it.episodeId) }, finished.size)
  return shelves.filterNotNull()
}
