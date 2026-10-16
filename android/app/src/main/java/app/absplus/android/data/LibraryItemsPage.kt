package app.absplus.android.data

data class LibraryItemsPage(val results: List<LibraryItem>, val total: Int, val page: Int, val limit: Int, val rawCount: Int) {
  val isLastPage get() = rawCount == 0 || limit <= 0 || (page + 1) * limit >= total
}
