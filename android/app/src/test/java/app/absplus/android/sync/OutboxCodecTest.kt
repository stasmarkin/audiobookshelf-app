package app.absplus.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Outbox keys and payloads: pure functions. */
class OutboxCodecTest {
  @Test
  fun canonicalTimeDropsTheZeroFractionOnly() {
    assertEquals("754", canonicalTime(754.0))
    assertEquals("0", canonicalTime(0.0))
    assertEquals("754.5", canonicalTime(754.5))
    assertEquals("li1@754", bookmarkItemKey("li1", 754.0))
    assertEquals("li1@754.25", bookmarkItemKey("li1", 754.25))
  }

  @Test(expected = IllegalArgumentException::class)
  fun canonicalTimeRejectsNaN() {
    canonicalTime(Double.NaN)
  }

  @Test
  fun progressKeyUsesEmptyEpisodeForBooks() {
    assertEquals("li1|", progressItemKey("li1", null))
    assertEquals("li1|", progressItemKey("li1", ""))
    assertEquals("li1|ep1", progressItemKey("li1", "ep1"))
  }

  @Test
  fun progressPatchFieldsKeepKnownNamesInServerOrder() {
    val payload = OutboxPayload.progressPatch(listOf("isFinished", "bogus", "currentTime", "isFinished"))

    assertEquals("""{"fields":["currentTime","isFinished"]}""", payload)
    assertEquals(listOf("currentTime", "isFinished"), OutboxPayload.progressPatchFields(payload))
    assertNull(OutboxPayload.progressPatchFields("""{"time":1}"""))
    assertNull(OutboxPayload.progressPatchFields("not json"))
  }

  @Test
  fun progressDeleteCarriesTheServerId() {
    assertEquals("mp1", OutboxPayload.serverProgressId(OutboxPayload.progressDelete("mp1")))
    assertNull(OutboxPayload.serverProgressId("{}"))
    assertNull(OutboxPayload.serverProgressId("""{"serverProgressId":""}"""))
  }

  @Test
  fun bookmarkPayloadRoundTrips() {
    val add = OutboxPayload.bookmark(754.0, "Chapter \"2\"")
    assertEquals("""{"time":754.0,"title":"Chapter \"2\""}""", add)
    assertEquals(754.0, OutboxPayload.bookmarkTime(add)!!, 0.0)
    assertEquals("Chapter \"2\"", OutboxPayload.bookmarkTitle(add))

    val remove = OutboxPayload.bookmark(754.5)
    assertEquals("""{"time":754.5}""", remove)
    assertNull(OutboxPayload.bookmarkTitle(remove))
    assertNull(OutboxPayload.bookmarkTime("""{"time":"754"}"""))
  }
}
