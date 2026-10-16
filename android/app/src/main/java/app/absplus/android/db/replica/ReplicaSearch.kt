package app.absplus.android.db.replica

import app.absplus.android.sync.CatalogJson
import app.absplus.android.sync.SEARCH_KEY_SEPARATOR
import app.absplus.android.sync.SEARCH_KEY_VERSION

const val DEFAULT_SEARCH_LIMIT = 12
const val MAX_SEARCH_LIMIT = 25

/**
 * The `LIKE` pattern for [LibraryItemDao.searchCandidates] and [EpisodeDao.searchByTitle]; null when nothing is left
 * of [q] after normalizing. Anchored after the version prefix, so a query "1" does not match every key, and `%`, `_`
 * and `\` are matched literally (the server treats `%` and `_` as wildcards).
 */
fun searchPattern(q: String): String? {
  val query = CatalogJson.normalizeForSearch(q.trim())
  if (query.isEmpty()) return null
  val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
  return "$SEARCH_KEY_VERSION$SEARCH_KEY_SEPARATOR%$escaped%"
}

data class SearchInput(
        val mediaType: String,
        /** Normalized by [CatalogJson.normalizeForSearch]. */
        val query: String,
        val limit: Int,
        /** Matches of the item SQL search, in its order (addedAt). */
        val matchedItemIds: List<String>,
        val rows: List<ItemRow>,
        val series: SeriesIndex,
        val authors: List<AuthorEntity>,
        /** `filterdata.narrators` of the library. */
        val narratorNames: List<String>,
        /** Matches of [EpisodeDao.searchByTitle]. */
        val matchedEpisodes: List<EpisodeKey>
)

data class SearchResult(
        val books: List<String>,
        val podcasts: List<String>,
        /** podcastId to episodeId. */
        val episodes: List<Pair<String, String>>,
        /** seriesId to bookIds. */
        val series: List<Pair<String, List<String>>>,
        val authors: List<String>,
        /** name to numBooks. */
        val narrators: List<Pair<String, Int>>,
        /** name to numItems. */
        val tags: List<Pair<String, Int>>,
        val genres: List<Pair<String, Int>>
)

/**
 * The groups of the server's library search (2.37.1 `libraryItemsBookFilters.search`, `libraryItemsPodcastFilters.search`)
 * over the replica, each at most [SearchInput.limit] long. Counts cover only the books the replica has (the user may
 * read); the server counts every book.
 */
fun searchReplica(input: SearchInput): SearchResult {
  val q = input.query
  val limit = input.limit
  val podcast = input.mediaType == "podcast"
  val rows = input.rows.associateBy { it.id }
  val items = input.matchedItemIds.filter { it in rows }.take(limit)
  val matches = { text: String -> CatalogJson.normalizeForSearch(text).contains(q) }

  val series = if (podcast) emptyList() else input.series.series.values.filter { matches(it.name) }
          .sortedWith { a, b -> nocaseCompare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
          // As the server: the limit counts series before those without a book the user may read are dropped
          .take(limit)
          .mapNotNull { s ->
            val books = input.series.membersBySeries[s.id].orEmpty().map { it.libraryItemId }.filter { it in rows }.distinct()
            if (books.isEmpty()) null else s.id to books
          }
  val authors = if (podcast) emptyList() else input.authors.filter { matches(it.name) }
          .sortedWith { a, b -> nocaseCompare(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id) }
          .take(limit).map { it.id }

  return SearchResult(
          books = if (podcast) emptyList() else items,
          podcasts = if (podcast) items else emptyList(),
          episodes = if (podcast) input.matchedEpisodes.filter { it.libraryItemId in rows }.take(limit).map { it.libraryItemId to it.id } else emptyList(),
          series = series,
          authors = authors,
          narrators = if (podcast) emptyList() else narrators(input, matches),
          tags = counted(input.rows.map { it.tags }, matches, limit),
          genres = counted(input.rows.map { it.genres }, matches, limit))
}

/**
 * Whole narrator names: the minified `narratorName` joins them with ", ", so "Doe, Jane" would split in two. Names come
 * from filterdata, plus the parts of a row's name that no known name covers (a narrator that came with a socket event
 * after the last reconcile).
 */
private fun narrators(input: SearchInput, matches: (String) -> Boolean): List<Pair<String, Int>> {
  val known = input.narratorNames.filter { it.isNotEmpty() }.toSet()
  val knownParts = known.flatMap { it.split(", ") }.toSet()
  val joined = input.rows.map { it.narratorName }.filter { it.isNotEmpty() }.map { ", $it, " }
  val extra = input.rows.flatMap { it.narratorName.split(", ") }.filter { it.isNotEmpty() && it !in known && it !in knownParts }
  return (known + extra).filter(matches)
          .map { name -> name to joined.count { it.contains(", $name, ") } }
          .filter { it.second > 0 }
          .sortedBy { it.first }
          .take(input.limit)
}

private fun counted(arrays: List<String>, matches: (String) -> Boolean, limit: Int): List<Pair<String, Int>> {
  val counts = HashMap<String, Int>()
  for (json in arrays) {
    if (json == "[]") continue
    val values = try {
      CatalogJson.mapper.readTree(json).mapNotNull { if (it.isTextual) it.asText() else null }.toSet()
    } catch (e: Exception) {
      emptySet()
    }
    values.forEach { counts.merge(it, 1, Int::plus) }
  }
  // Each distinct value is normalized once
  return counts.entries.filter { matches(it.key) }.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
          .take(limit).map { it.key to it.value }
}
