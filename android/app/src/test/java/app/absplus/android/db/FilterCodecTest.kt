package app.absplus.android.db

import app.absplus.android.db.replica.CatalogFilter
import app.absplus.android.db.replica.FilterCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Plain JVM: the decoder must not need java.util.Base64 (API 26) nor android.util.Base64. */
class FilterCodecTest {
  @Test
  fun decodesLikeTheServer() {
    val table = mapOf(
            "genres.RmFudGFzeQ%3D%3D" to CatalogFilter("genres", "Fantasy"),
            "genres.RmFudGFzeQ==" to CatalogFilter("genres", "Fantasy"),
            "genres.RmFudGFzeQ" to CatalogFilter("genres", "Fantasy"),
            // "~~~" is "fn5+" in base64: the plus is a base64 digit, never a space
            "tags.fn5+" to CatalogFilter("tags", "~~~"),
            "tags.fn5%2B" to CatalogFilter("tags", "~~~"),
            "tags.fn5-" to CatalogFilter("tags", "~~~"),
            "tags.P8O8" to CatalogFilter("tags", "?ü"),
            "series.bm8tc2VyaWVz" to CatalogFilter("series", "no-series"),
            "languages.0KDRg9GB0YHQutC40Lk%3D" to CatalogFilter("languages", "Русский"),
            "issues" to CatalogFilter("issues", null),
            "feed-open" to CatalogFilter("feed-open", null),
            "authors.!!!" to CatalogFilter("authors", null),
            "authors.%E0%A4%A" to CatalogFilter("authors", null),
            "authors.%FF" to CatalogFilter("authors", null))
    for ((filter, expected) in table) assertEquals(filter, expected, FilterCodec.parse(filter))
    assertEquals(null, FilterCodec.parse(null))
    assertEquals(null, FilterCodec.parse("all"))
  }

  @Test
  fun decoderDoesNotUsePlatformBase64() {
    val source = File("src/main/java/app/absplus/android/db/replica/LibraryQuery.kt").readText()
    assertFalse(source.contains("java.util.Base64"))
    assertFalse(source.contains("android.util.Base64"))
  }
}
