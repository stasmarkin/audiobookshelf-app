package app.absplus.android.managers

/** Best-effort logical bytes, never signed overflow or negative reservations. */
object DownloadBudget {
  fun size(value: Long): Long = value.coerceAtLeast(0L)
  fun add(a: Long, b: Long): Long {
    val x = size(a); val y = size(b)
    return if (Long.MAX_VALUE - x < y) Long.MAX_VALUE else x + y
  }
  fun sum(values: Iterable<Long>): Long = values.fold(0L, ::add)
}
