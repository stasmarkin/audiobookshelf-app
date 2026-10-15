package app.absplus.android.db.replica

import app.absplus.android.db.userdata.MediaProgressEntity
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

/**
 * Filters and sorts of `GET /api/libraries/:id/items` (server 2.37.1, libraryItemsBookFilters and
 * libraryItemsPodcastFilters) over replica rows, so the catalog answers the same offline.
 */
data class CatalogFilter(val group: String, val value: String?)

/** A filter the replica cannot answer (it needs data the minified item does not have). */
class UnsupportedFilterException(message: String) : Exception(message)

object FilterCodec {
  /** The groups the server splits off before decoding; any other filter is a group without a value. */
  private val VALUE_GROUPS = listOf("genres", "tags", "series", "authors", "progress", "narrators", "publishers",
          "publishedDecades", "missing", "languages", "tracks", "ebooks")

  /** Like the server: `decodeURIComponent`, then a lenient base64; a value without any decoded byte is null. */
  fun parse(filter: String?): CatalogFilter? {
    if (filter.isNullOrEmpty() || filter == "all") return null
    val group = VALUE_GROUPS.firstOrNull { filter.startsWith("$it.") } ?: return CatalogFilter(filter, null)
    val raw = filter.substring(group.length + 1)
    val bytes = percentDecode(raw)?.let { base64Decode(it) }
    return CatalogFilter(group, bytes?.takeIf { it.isNotEmpty() }?.toString(Charsets.UTF_8))
  }

  /** The app's `$encode`: `encodeURIComponent` of the standard base64 of the UTF-8 bytes. */
  fun encode(value: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val bytes = value.toByteArray(Charsets.UTF_8)
    val out = StringBuilder()
    for (i in bytes.indices step 3) {
      val n = (bytes[i].toInt() and 0xFF shl 16) or
              ((bytes.getOrNull(i + 1)?.toInt() ?: 0) and 0xFF shl 8) or
              ((bytes.getOrNull(i + 2)?.toInt() ?: 0) and 0xFF)
      val chars = minOf(4, (bytes.size - i) + 1)
      for (k in 0 until 4) out.append(if (k < chars) alphabet[(n shr (18 - 6 * k)) and 0x3F] else '=')
    }
    return out.toString().replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")
  }

  /** `decodeURIComponent`: `+` stays, a malformed escape or invalid UTF-8 is an error (null). */
  internal fun percentDecode(text: String): String? {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
      val c = text[i]
      if (c == '%') {
        val hi = text.getOrNull(i + 1)?.digitToIntOrNull(16) ?: return null
        val lo = text.getOrNull(i + 2)?.digitToIntOrNull(16) ?: return null
        bytes.write(hi * 16 + lo)
        i += 3
      } else {
        val end = if (Character.isHighSurrogate(c) && i + 1 < text.length) i + 2 else i + 1
        bytes.write(text.substring(i, end).toByteArray(Charsets.UTF_8))
        i = end
      }
    }
    return try {
      Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (e: CharacterCodingException) {
      null
    }
  }

  /** As `Buffer.from(text, 'base64')`: standard and url-safe alphabets, anything else (padding included) skipped. */
  internal fun base64Decode(text: String): ByteArray {
    val out = ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (c in text) {
      val v = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> continue
      }
      buffer = (buffer shl 6) or v
      bits += 6
      if (bits >= 8) {
        bits -= 8
        out.write((buffer shr bits) and 0xFF)
      }
    }
    return out.toByteArray()
  }
}

data class ItemsQuery(
        val sort: String?,
        val desc: Boolean,
        val filter: CatalogFilter?,
        val collapseSeries: Boolean,
        val sortingIgnorePrefix: Boolean,
        val mediaType: String,
        /** Library setting; the sub-series collapse of release 4b needs it. */
        val hideSingleBookSeries: Boolean = false
)

class SeriesIndex(
        val series: Map<String, SeriesEntity>,
        val membersByItem: Map<String, List<SeriesItemEntity>>,
        val membersBySeries: Map<String, List<SeriesItemEntity>>
) {
  companion object {
    fun of(series: List<SeriesEntity>, members: List<SeriesItemEntity>) = SeriesIndex(
            series.associateBy { it.id }, members.groupBy { it.libraryItemId }, members.groupBy { it.seriesId })

    val EMPTY = SeriesIndex(emptyMap(), emptyMap(), emptyMap())
  }
}

sealed interface QueryEntry {
  val itemId: String

  /** [series]: the membership in the filtered series, for `filter=series.*`. */
  data class Item(override val itemId: String, val series: SeriesItemEntity?) : QueryEntry

  /** A book standing for its series when series are collapsed. */
  data class Collapsed(
          override val itemId: String,
          val seriesId: String,
          val name: String,
          val nameIgnorePrefix: String,
          val sequence: String?,
          val numBooks: Int,
          val libraryItemIds: List<String>,
          /** Set only on a sub-series card of a series page, which the server sends in its own shape. */
          val seriesSequenceList: String? = null,
          /** On a sub-series card: the book's membership in the filtered series. */
          val filterMember: SeriesItemEntity? = null
  ) : QueryEntry
}

/** An entry with what its sort keys need. */
internal class Keyed(val entry: QueryEntry, val row: ItemRow, val progress: MediaProgressEntity?, val sequence: String?, val displayTitle: String)

internal class Prepared(val entries: List<Keyed>, val primary: Comparator<Keyed>, val random: Boolean)

private const val RECENT_MS = 60L * 24 * 60 * 60 * 1000
private val mapper = jacksonObjectMapper()

/**
 * `COLLATE NOCASE` of SQLite: only ASCII A–Z fold, then code points (the order of UTF-8 bytes); null is the smallest.
 */
fun nocaseCompare(a: String?, b: String?): Int {
  if (a == null) return if (b == null) 0 else -1
  if (b == null) return 1
  var i = 0
  var j = 0
  while (i < a.length && j < b.length) {
    val ca = fold(a.codePointAt(i))
    val cb = fold(b.codePointAt(j))
    if (ca != cb) return ca.compareTo(cb)
    i += Character.charCount(a.codePointAt(i))
    j += Character.charCount(b.codePointAt(j))
  }
  return when {
    i < a.length -> 1
    j < b.length -> -1
    else -> 0
  }
}

private fun fold(cp: Int) = if (cp in 'A'.code..'Z'.code) cp + 32 else cp

/**
 * `Intl.Collator(undefined, { numeric: true, sensitivity: 'base' }).compare` of the server's in-memory sorts: digit runs
 * by value, the rest by a primary-strength collator. Not thread-safe: one per sort.
 */
fun naturalComparator(): Comparator<String> {
  val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.PRIMARY }
  return Comparator { a, b ->
    val ta = naturalTokens(a)
    val tb = naturalTokens(b)
    for (i in 0 until minOf(ta.size, tb.size)) {
      val x = ta[i]
      val y = tb[i]
      val c = if (x[0] in '0'..'9' && y[0] in '0'..'9') compareDigits(x, y) else collator.compare(x, y)
      if (c != 0) return@Comparator c
    }
    ta.size.compareTo(tb.size)
  }
}

private val NATURAL_TOKEN = Regex("[0-9]+|[^0-9]+")

private fun naturalTokens(text: String) = NATURAL_TOKEN.findAll(text).map { it.value }.toList()

private fun compareDigits(x: String, y: String): Int {
  val a = x.trimStart('0')
  val b = y.trimStart('0')
  return if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
}

private val PLAIN_NUMBER = Regex("""^(\d+|\d+\.\d*|\d*\.\d+)$""")

/** A number as JavaScript prints it ("2", not "2.0"). */
private fun jsNumber(value: Double): String =
        if (value % 1.0 == 0.0 && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

/**
 * `seriesSequenceList` of a sub-series card: the card's sequences in the filtered series, naturally sorted, runs of
 * consecutive numbers joined ("1-3, 5").
 */
fun seriesSequenceList(sequences: List<String?>): String {
  class Run(val start: String, var end: String, var last: Double?)
  val runs = ArrayList<Run>()
  for (sequence in sequences.filterNotNull().filter { it.isNotEmpty() }.sortedWith(naturalComparator())) {
    val number = if (PLAIN_NUMBER.matches(sequence)) sequence.toDouble() else null
    val text = number?.let { jsNumber(it) } ?: sequence
    val run = runs.lastOrNull()
    val previous = run?.last
    if (run != null && number != null && previous != null && previous + 1 == number) {
      run.end = text
      run.last = number
    } else {
      runs += Run(text, text, number)
    }
  }
  return runs.joinToString(", ") { if (it.start == it.end) it.start else "${it.start}-${it.end}" }
}

/** `CAST(x AS INTEGER)` of SQLite on text: the leading integer, 0 without one; null stays null. */
internal fun sqliteInt(text: String?): Long? {
  text ?: return null
  return Regex("""^\s*[+-]?\d+""").find(text)?.value?.trim()?.toLongOrNull() ?: 0L
}

/** `CAST(x AS FLOAT)` of SQLite on text: the leading number, 0.0 without one; null stays null. */
internal fun sqliteFloat(text: String?): Double? {
  text ?: return null
  return Regex("""^\s*[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""").find(text)?.value?.trim()?.toDoubleOrNull() ?: 0.0
}

/** Nulls first ascending, last descending, as SQLite orders them. */
private fun <T : Comparable<T>> sqliteOrder(desc: Boolean, key: (Keyed) -> T?): Comparator<Keyed> {
  val asc = Comparator<Keyed> { a, b -> compareValues(key(a), key(b)) }
  return if (desc) asc.reversed() else asc
}

private fun <T : Comparable<T>> nullsLast(desc: Boolean, key: (Keyed) -> T?): Comparator<Keyed> = Comparator { a, b ->
  val ka = key(a)
  val kb = key(b)
  when {
    ka == null && kb == null -> 0
    ka == null -> 1
    kb == null -> -1
    desc -> kb.compareTo(ka)
    else -> ka.compareTo(kb)
  }
}

private fun nocaseOrder(desc: Boolean, key: (Keyed) -> String?): Comparator<Keyed> {
  val asc = Comparator<Keyed> { a, b -> nocaseCompare(key(a), key(b)) }
  return if (desc) asc.reversed() else asc
}

/** `CAST(sequence AS FLOAT)`, ascending with nulls last or descending with nulls first. */
private fun sequenceOrder(desc: Boolean): Comparator<Keyed> {
  val ascNullsLast = nullsLast(false) { k: Keyed -> sqliteFloat(k.sequence) }
  return if (desc) ascNullsLast.reversed() else ascNullsLast
}

private val TIEBREAK: Comparator<Keyed> = compareBy<Keyed> { it.row.addedAt }.thenBy { it.row.id }

private fun jsonSet(cache: HashMap<String, Set<String>>, json: String): Set<String> = cache.getOrPut(json) {
  try {
    mapper.readTree(json).mapNotNull { if (it.isTextual) it.asText() else null }.toSet()
  } catch (e: Exception) {
    emptySet()
  }
}

fun orderItems(
        rows: List<ItemRow>,
        q: ItemsQuery,
        progress: Map<String, MediaProgressEntity>,
        series: SeriesIndex,
        authorNames: Map<String, String>,
        now: Long,
        random: Random,
        /** The filterdata's narrators, for telling a narrator filter the replica cannot answer. */
        narratorNames: Collection<String> = emptyList()
): List<QueryEntry> {
  val prepared = prepareItems(rows, q, progress, series, authorNames, now, narratorNames)
  val ordered = if (prepared.random) prepared.entries.shuffled(random) else prepared.entries.sortedWith(prepared.primary.then(TIEBREAK))
  return ordered.map { it.entry }
}

internal fun prepareItems(
        rows: List<ItemRow>,
        q: ItemsQuery,
        progress: Map<String, MediaProgressEntity>,
        series: SeriesIndex,
        authorNames: Map<String, String>,
        now: Long,
        narratorNames: Collection<String> = emptyList()
): Prepared {
  val podcast = q.mediaType == "podcast"
  val filter = q.filter
  val seriesFilter = !podcast && filter?.group == "series" && filter.value != "no-series"
  val passes = rowFilter(filter, podcast, progress, series, authorNames, now, narratorNames)
  val filtered = rows.filter(passes)

  val ignorePrefix = q.sortingIgnorePrefix
  val collapse = q.collapseSeries && !podcast && filter?.group != "series"
  val subSeries = if (seriesFilter && q.collapseSeries) {
    filter?.value?.let { collapseSubSeries(filtered, it, series, progress, ignorePrefix, q.hideSingleBookSeries) }
  } else null
  val keyed: List<Keyed> = when {
    subSeries != null -> subSeries
    collapse -> collapse(filtered, series, progress, ignorePrefix)
    seriesFilter -> filtered.map { row ->
      val member = series.membersByItem[row.id]?.firstOrNull { it.seriesId == filter!!.value }
      Keyed(QueryEntry.Item(row.id, member), row, progress[row.id], member?.sequence, titleOf(row, ignorePrefix))
    }
    else -> filtered.map { Keyed(QueryEntry.Item(it.id, null), it, progress[it.id], null, titleOf(it, ignorePrefix)) }
  }

  var sort = q.sort
  if (!seriesFilter && sort == "sequence") sort = "media.metadata.title"
  val desc = q.desc
  val title = nocaseOrder(desc) { it.displayTitle }
  var primary: Comparator<Keyed>? = if (podcast) when (sort) {
    "media.metadata.title" -> title
    "media.metadata.author" -> {
      val ascNullsLast = Comparator<Keyed> { a, b ->
        val ka = a.row.authorName.ifEmpty { null }
        val kb = b.row.authorName.ifEmpty { null }
        when {
          ka == null && kb == null -> 0
          ka == null -> 1
          kb == null -> -1
          else -> nocaseCompare(ka, kb)
        }
      }
      if (desc) ascNullsLast.reversed() else ascNullsLast
    }
    "addedAt" -> sqliteOrder(desc) { it.row.addedAt }
    "size" -> sqliteOrder(desc) { it.row.size }
    "media.numTracks" -> sqliteOrder(desc) { it.row.numEpisodes }
    "birthtimeMs" -> sqliteOrder(desc) { it.row.birthtimeMs }
    "mtimeMs" -> sqliteOrder(desc) { it.row.mtimeMs }
    else -> null
  } else when (sort) {
    "addedAt" -> sqliteOrder(desc) { it.row.addedAt }
    "size" -> sqliteOrder(desc) { it.row.size }
    "birthtimeMs" -> sqliteOrder(desc) { it.row.birthtimeMs }
    "mtimeMs" -> sqliteOrder(desc) { it.row.mtimeMs }
    "media.duration" -> sqliteOrder(desc) { it.row.duration }
    "media.metadata.publishedYear" -> sqliteOrder(desc) { sqliteInt(it.row.publishedYear) }
    "media.metadata.authorNameLF" -> nocaseOrder(desc) { it.row.authorNameLF }.then(nocaseOrder(desc) { titleOf(it.row, ignorePrefix) })
    "media.metadata.authorName" -> nocaseOrder(desc) { it.row.authorName }.then(nocaseOrder(desc) { titleOf(it.row, ignorePrefix) })
    "media.metadata.title" -> title
    "sequence" -> sequenceOrder(desc)
    "progress" -> nullsLast(desc) { it.progress?.lastUpdate }
    "progress.createdAt" -> nullsLast(desc) { it.progress?.startedAt }
    "progress.finishedAt" -> nullsLast(desc) { it.progress?.finishedAt }
    else -> null
  }
  if (seriesFilter && sort != "sequence") primary = primary?.then(sequenceOrder(false)) ?: sequenceOrder(false)
  if (subSeries != null) primary = subSeriesOrder(sort, desc)
  // Without a sort the server's order is undefined; the title keeps it stable
  // The sub-series sort never shuffles: "random" is a key no item has
  return Prepared(keyed, primary ?: nocaseOrder(false) { it.displayTitle }, sort == "random" && subSeries == null)
}

private fun splitsWrongly(name: String, known: Collection<String>) =
        name.contains(", ") || known.any { it.contains(", ") && name in it.split(", ") }

private fun titleOf(row: ItemRow, ignorePrefix: Boolean) = if (ignorePrefix) withoutPrefix(row.title, row.titleIgnorePrefix) else row.title

/**
 * The server sorts on its `titleIgnorePrefix` column, the title without the prefix ("Good Book"), while its JSON has
 * the prefix moved to the end ("Good Book, The"); the column is recovered from the two.
 */
internal fun withoutPrefix(original: String, prefixAtEnd: String): String {
  if (prefixAtEnd == original || !prefixAtEnd.contains(", ")) return prefixAtEnd
  val prefix = prefixAtEnd.substringAfterLast(", ")
  return prefixAtEnd.dropLast(prefix.length + 2)
}

private fun rowFilter(
        filter: CatalogFilter?,
        podcast: Boolean,
        progress: Map<String, MediaProgressEntity>,
        series: SeriesIndex,
        authorNames: Map<String, String>,
        now: Long,
        narratorNames: Collection<String>
): (ItemRow) -> Boolean {
  filter ?: return { true }
  val value = filter.value
  val arrays = HashMap<String, Set<String>>()
  val common: ((ItemRow) -> Boolean)? = when (filter.group) {
    "genres" -> { r -> value != null && value in jsonSet(arrays, r.genres) }
    "tags" -> { r -> value != null && value in jsonSet(arrays, r.tags) }
    "languages" -> { r -> value != null && r.language == value }
    "explicit" -> { r -> r.explicit }
    "issues" -> { r -> r.isMissing || r.isInvalid }
    "feed-open" -> { r -> r.rssFeedJson != null }
    "recent" -> { r -> r.addedAt >= now - RECENT_MS }
    else -> null
  }
  if (common != null) return common
  if (podcast) throw UnsupportedFilterException("podcast filter ${filter.group}")
  return when (filter.group) {
    // Names are joined with ", " in the minified item: a name that contains one, or is a part of a known name that
    // does ("John" of "Doe, John"), cannot be told apart; the server can
    "narrators" -> {
      if (value != null && splitsWrongly(value, narratorNames)) throw UnsupportedFilterException("narrator with a comma")
      val byName: (ItemRow) -> Boolean = { r -> value != null && value in r.narratorName.split(", ") }
      byName
    }
    "authors" -> {
      val name = if (value == null) null else authorNames[value]
      if (name != null && splitsWrongly(name, authorNames.values)) throw UnsupportedFilterException("author with a comma")
      val byName: (ItemRow) -> Boolean = { r -> name != null && name in r.authorName.split(", ") }
      byName
    }
    "series" -> if (value == "no-series") { r -> r.seriesName.isEmpty() } else { r ->
      value != null && series.membersByItem[r.id]?.any { it.seriesId == value } == true
    }
    "publishers" -> { r -> value != null && r.publisher == value }
    "abridged" -> { r -> r.abridged }
    "progress" -> when (value) {
      null -> { _ -> false }
      "finished" -> { r -> progress[r.id]?.isFinished == true }
      "in-progress" -> { r ->
        val p = progress[r.id]
        p != null && (p.currentTime > 0 || (p.ebookProgress ?: 0.0) > 0) && !p.isFinished
      }
      "not-started" -> { r ->
        val p = progress[r.id]
        (p == null || p.currentTime == 0.0) && p?.isFinished != true
      }
      "not-finished" -> { r -> progress[r.id]?.isFinished != true }
      else -> throw UnsupportedFilterException("progress.$value")
    }
    "ebooks" -> when (value) {
      null -> { _ -> false }
      "ebook" -> { r -> r.ebookFormat != null }
      "no-ebook" -> { r -> r.ebookFormat == null }
      else -> throw UnsupportedFilterException("ebooks.$value")
    }
    else -> throw UnsupportedFilterException(filter.group)
  }
}

/**
 * `getCollapseSeriesBooksToExclude`: series in the order of their smallest sequence among the filtered books, each
 * represented by its first filtered book (by sequence) that does not already stand for another series.
 */
private fun collapse(
        filtered: List<ItemRow>,
        series: SeriesIndex,
        progress: Map<String, MediaProgressEntity>,
        ignorePrefix: Boolean
): List<Keyed> {
  val byId = filtered.associateBy { it.id }
  val bySequence = compareBy<Pair<SeriesItemEntity, ItemRow>, Double?>(nullsLast()) { sqliteFloat(it.first.sequence) }
          .thenBy { it.second.addedAt }.thenBy { it.second.id }
  val seriesBooks = series.membersBySeries.mapNotNull { (seriesId, members) ->
    val entity = series.series[seriesId] ?: return@mapNotNull null
    val books = members.mapNotNull { m -> byId[m.libraryItemId]?.let { m to it } }.sortedWith(bySequence)
    if (books.isEmpty()) null else Triple(entity, members.size, books)
  }.sortedWith(compareBy<Triple<SeriesEntity, Int, List<Pair<SeriesItemEntity, ItemRow>>>, Double?>(nullsLast()) {
    sqliteFloat(it.third.first().first.sequence)
  }.thenBy { it.first.addedAt }.thenBy { it.first.id })

  val included = LinkedHashMap<String, QueryEntry.Collapsed>()
  val excluded = HashSet<String>()
  for ((entity, numBooks, books) in seriesBooks) {
    var found = false
    for ((member, row) in books) {
      if (!found && row.id !in included) {
        included[row.id] = QueryEntry.Collapsed(row.id, entity.id, entity.name, withoutPrefix(entity.name, entity.nameIgnorePrefix), member.sequence, numBooks,
                books.map { it.second.id })
        excluded.remove(row.id)
        found = true
      } else if (row.id !in included) {
        excluded.add(row.id)
      }
    }
  }
  return filtered.filter { it.id !in excluded }.map { row ->
    val card = included[row.id]
    if (card != null) {
      Keyed(card, row, progress[row.id], card.sequence, if (ignorePrefix) card.nameIgnorePrefix else card.name)
    } else {
      Keyed(QueryEntry.Item(row.id, null), row, progress[row.id], null, titleOf(row, ignorePrefix))
    }
  }
}

/**
 * `handleCollapseSubseries` of a series page: books of [seriesId] that are also in another series fold into a card of
 * that series, on its first book by that series' sequence; with [hideSingle] only series with more than one book here.
 * When that leaves one card the server lists the books instead, in the same sort.
 */
private fun collapseSubSeries(
        filtered: List<ItemRow>,
        seriesId: String,
        series: SeriesIndex,
        progress: Map<String, MediaProgressEntity>,
        ignorePrefix: Boolean,
        hideSingle: Boolean
): List<Keyed> {
  val natural = naturalComparator()
  val subs = LinkedHashMap<String, MutableList<Pair<SeriesItemEntity, ItemRow>>>()
  for (row in filtered) {
    for (m in series.membersByItem[row.id].orEmpty()) {
      if (m.seriesId != seriesId && m.seriesId in series.series) subs.getOrPut(m.seriesId) { ArrayList() } += m to row
    }
  }
  // The server's collator compares a missing sequence as the text "null"
  val cards = subs.filterValues { !hideSingle || it.size > 1 }
          .mapValues { (_, books) -> books.sortedWith(compareBy(natural) { it.first.sequence ?: "null" }).map { it.second } }
  val inCard = cards.values.flatten().map { it.id }.toSet()
  fun memberOf(row: ItemRow) = series.membersByItem[row.id]?.firstOrNull { it.seriesId == seriesId }

  val keyed = ArrayList<Keyed>()
  for (row in filtered) {
    val member = memberOf(row)
    for ((id, books) in cards) {
      if (books.first().id != row.id) continue
      val entity = series.series.getValue(id)
      val card = QueryEntry.Collapsed(row.id, id, entity.name, entity.nameIgnorePrefix, null, books.size, books.map { it.id },
              seriesSequenceList(books.map { memberOf(it)?.sequence }), member)
      keyed += Keyed(card, row, progress[row.id], member?.sequence, if (ignorePrefix) entity.nameIgnorePrefix else entity.name)
    }
    if (row.id !in inCard) keyed += Keyed(QueryEntry.Item(row.id, member), row, progress[row.id], member?.sequence, titleOf(row, ignorePrefix))
  }
  if (keyed.size == 1 && keyed[0].entry is QueryEntry.Collapsed) {
    return filtered.map { row ->
      val member = memberOf(row)
      Keyed(QueryEntry.Item(row.id, member), row, progress[row.id], member?.sequence, titleOf(row, ignorePrefix))
    }
  }
  return keyed
}

/** `VALID_COLLAPSE_SUBSERIES_SORTS`: any other sort is dropped, which is the sequence order. */
private val SUB_SERIES_SORTS = setOf("addedAt", "size", "birthtimeMs", "mtimeMs", "media.duration", "media.metadata.publishedYear",
        "media.metadata.authorName", "media.metadata.authorNameLF", "media.metadata.title", "sequence", "progress",
        "progress.createdAt", "progress.finishedAt", "random")

/**
 * The in-memory sort of `handleCollapseSubseries`, whose collator compares every key as text: sequence then name by
 * default; by name; else cards last by name (always ascending), then the sort's key read off the server's item model,
 * which lacks most of them ("" for addedAt, birthtimeMs, mtimeMs, publishedYear, progress and random: equal keys).
 */
private fun subSeriesOrder(sort: String?, desc: Boolean): Comparator<Keyed> {
  val natural = naturalComparator()
  fun directed(c: Comparator<Keyed>) = if (desc) c.reversed() else c
  fun by(key: (Keyed) -> String) = Comparator<Keyed> { a, b -> natural.compare(key(a), key(b)) }
  val title = directed(by { it.displayTitle })
  val known = sort?.takeIf { it in SUB_SERIES_SORTS }
  if (known == null || known == "sequence") return directed(by { it.sequence ?: "null" }).then(title)
  if (known == "media.metadata.title") return title
  val key: (Keyed) -> String = when (known) {
    "media.metadata.authorName" -> { k -> k.row.authorName }
    "media.metadata.authorNameLF" -> { k -> k.row.authorNameLF }
    "size" -> { k -> k.row.size.toString() }
    "media.duration" -> { k -> jsNumber(k.row.duration) }
    else -> { _ -> "" }
  }
  return by { if (it.entry is QueryEntry.Collapsed) it.displayTitle else "" }.then(directed(by(key)))
}
