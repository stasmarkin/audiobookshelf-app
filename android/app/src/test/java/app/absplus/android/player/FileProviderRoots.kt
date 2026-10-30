package app.absplus.android.player

import androidx.core.content.FileProvider

/** FileProvider caches its roots statically, but every Robolectric test gets a new files directory. */
internal fun resetFileProviderRoots() {
  val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.get(null) as MutableMap<*, *>
  synchronized(cache) { cache.clear() }
}
