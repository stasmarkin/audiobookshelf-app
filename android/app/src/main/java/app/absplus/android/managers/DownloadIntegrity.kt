package app.absplus.android.managers

/**
 * Whether a finished transfer may count as a complete file. A transfer is never complete with zero bytes (no media,
 * ebook or cover is empty). A size the server declared for this response (Content-Length, or the total of a
 * Content-Range) is authoritative. Without one (chunked, or a Content-Range with an unknown total), the expected size from the item's metadata
 * must match when it is known, so an empty or cut-short body is not accepted as the file.
 */
object DownloadIntegrity {
  /** @return null when the received file is complete, otherwise a short reason for the log and the retry */
  fun incompleteReason(receivedBytes: Long, serverSize: Long?, expectedSize: Long): String? {
    if (receivedBytes <= 0L) return "empty response (0 bytes)"
    if (serverSize != null) {
      return if (receivedBytes == serverSize) null else "received $receivedBytes of $serverSize bytes"
    }
    if (expectedSize > 0L && receivedBytes != expectedSize) {
      return "received $receivedBytes of $expectedSize expected bytes (server sent no size)"
    }
    return null
  }
}
