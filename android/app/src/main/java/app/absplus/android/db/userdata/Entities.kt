package app.absplus.android.db.userdata

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Listening progress of one book or podcast episode on one saved server. [pending] means the outbox still has rows for
 * this item, so a server value must not overwrite it.
 */
@Entity(tableName = "media_progress", primaryKeys = ["serverConfigId", "libraryItemId", "episodeId"])
data class MediaProgressEntity(
        val serverConfigId: String,
        val libraryItemId: String,
        /** "" for a book: a primary key column cannot be null. */
        val episodeId: String,
        val serverProgressId: String?,
        val duration: Double,
        val progress: Double,
        val currentTime: Double,
        val isFinished: Boolean,
        val hideFromContinueListening: Boolean,
        val ebookLocation: String?,
        val ebookProgress: Double?,
        val lastUpdate: Long,
        val startedAt: Long,
        val finishedAt: Long?,
        /** "device" or "server": who wrote the current values. */
        val source: String,
        val pending: Boolean
)

/** A bookmark; a row with [deletedAt] is a tombstone that hides the server copy until the removal reaches the server. */
@Entity(tableName = "bookmarks", primaryKeys = ["serverConfigId", "libraryItemId", "time"])
data class BookmarkEntity(
        val serverConfigId: String,
        val libraryItemId: String,
        val time: Double,
        val title: String,
        val createdAt: Long,
        val deletedAt: Long?,
        val pending: Boolean
)

/** A user action waiting to be sent to [serverConfigId]; rows of one server are sent strictly in [id] order. */
@Entity(
        tableName = "outbox",
        indices = [Index(value = ["serverConfigId", "id"]), Index(value = ["serverConfigId", "itemKey"])]
)
data class OutboxEntity(
        @PrimaryKey(autoGenerate = true) val id: Long = 0,
        val serverConfigId: String,
        /** One of `sync.OutboxKind`; stored as text so a newer kind survives a rollback as an unknown row. */
        val kind: String,
        /** `progressItemKey` or `bookmarkItemKey` of the item the action is about. */
        val itemKey: String,
        /** JSON object, see `sync.OutboxPayload`. */
        val payload: String,
        val createdAt: Long,
        val attempts: Int = 0,
        val lastError: String? = null,
        val nextAttemptAt: Long = 0
)

@Entity(tableName = "sync_state")
data class ServerSyncStateEntity(
        @PrimaryKey val serverConfigId: String,
        val lastMeAt: Long?,
        /** The server's user object without mediaProgress and bookmarks (permissions and settings for offline use). */
        val userJson: String?,
        val schemaVersion: Int
)

@Entity(tableName = "library_sync_state", primaryKeys = ["serverConfigId", "libraryId"])
data class LibrarySyncStateEntity(
        val serverConfigId: String,
        val libraryId: String,
        val lastReconcileAt: Long?,
        val lastReconcileOk: Boolean,
        val lastError: String?
)
