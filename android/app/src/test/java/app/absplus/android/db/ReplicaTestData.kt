package app.absplus.android.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.db.replica.ItemRow
import app.absplus.android.db.replica.LibraryEntity
import app.absplus.android.db.replica.LibraryItemEntity
import app.absplus.android.db.replica.ReplicaDatabase
import app.absplus.android.db.replica.SeriesEntity
import app.absplus.android.db.replica.SeriesItemEntity

/** Synthetic replica rows for tests. */
fun inMemoryReplica(): ReplicaDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ReplicaDatabase::class.java).build()

fun libraryRow(sc: String, id: String, position: Int = 0, mediaType: String = "book") =
        LibraryEntity(sc, id, position, mediaType, """{"id":"$id","name":"Library $id","mediaType":"$mediaType"}""", null, 0, 0)

fun itemEntity(
        sc: String,
        id: String,
        libraryId: String = "lib1",
        title: String = "Title $id",
        updatedAt: Long = 1L,
        addedAt: Long = 1L,
        seriesName: String = "",
        mediaType: String = "book"
) = LibraryItemEntity(
        serverConfigId = sc, id = id, libraryId = libraryId, mediaType = mediaType, updatedAt = updatedAt, contentHash = "h$updatedAt",
        title = title, titleIgnorePrefix = title, authorName = "", authorNameLF = "", narratorName = "", seriesName = seriesName,
        publisher = null, language = null, publishedYear = null, explicit = false, abridged = false, genres = "[]", tags = "[]",
        addedAt = addedAt, size = 0, duration = 0.0, birthtimeMs = 0, mtimeMs = 0, numTracks = 0, numEpisodes = 0,
        ebookFormat = null, isMissing = false, isInvalid = false, rssFeedJson = null,
        json = """{"id":"$id","libraryId":"$libraryId","media":{"metadata":{"title":"$title"}}}""")

fun itemRow(
        id: String,
        title: String = "Title $id",
        addedAt: Long = 1L,
        authorName: String = "",
        narratorName: String = "",
        seriesName: String = "",
        publishedYear: String? = null,
        genres: String = "[]",
        tags: String = "[]",
        mediaType: String = "book",
        ebookFormat: String? = null,
        size: Long = 0,
        rssFeedJson: String? = null
) = ItemRow(
        serverConfigId = "srv", id = id, libraryId = "lib1", mediaType = mediaType, updatedAt = 1L, contentHash = "h",
        title = title, titleIgnorePrefix = title, authorName = authorName, authorNameLF = authorName,
        narratorName = narratorName, seriesName = seriesName, publisher = null, language = null,
        publishedYear = publishedYear, explicit = false, abridged = false, genres = genres, tags = tags, addedAt = addedAt,
        size = size, duration = 0.0, birthtimeMs = 0, mtimeMs = 0, numTracks = 0, numEpisodes = 0,
        ebookFormat = ebookFormat, isMissing = false, isInvalid = false, rssFeedJson = rssFeedJson, detailLevel = "minified")

fun seriesRow(sc: String, id: String, libraryId: String = "lib1", name: String = "Series $id") =
        SeriesEntity(sc, id, libraryId, name, name, 1L, 1L, """{"id":"$id","name":"$name"}""")

fun member(sc: String, seriesId: String, itemId: String, sequence: String? = null) = SeriesItemEntity(sc, seriesId, itemId, sequence)
