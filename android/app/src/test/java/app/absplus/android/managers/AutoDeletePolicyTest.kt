package app.absplus.android.managers

import app.absplus.android.data.DeviceSettings
import app.absplus.android.managers.AutoDeletePolicy.Candidate
import app.absplus.android.managers.AutoDeletePolicy.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoDeletePolicyTest {
  private val day = 24L * 60 * 60 * 1000
  private val now = 100 * day

  private fun done(lastUpdate: Long, finishedAt: Long? = lastUpdate) = Progress(true, finishedAt, lastUpdate)
  private fun open(lastUpdate: Long) = Progress(false, null, lastUpdate)

  private fun settings(books: Boolean = true, delay: Int? = 1, limitGb: Int = 0) =
          DeviceSettings.default().apply {
            autoDeleteFinishedBooks = books
            autoDeleteDelayDays = delay
            downloadsLimitGb = limitGb
          }

  private val sameIdentity = AutoDeletePolicy.Identity("cfg", "u", "srv")

  // Defaults: an online candidate whose device progress is on the server
  private fun candidate(
          key: String = "c",
          size: Long = 10,
          local: Progress? = done(10),
          server: Progress? = done(10),
          serverKnown: Boolean = true,
          confirmed: Long? = null,
          pending: Boolean = false,
          isProtected: Boolean = false,
          identity: AutoDeletePolicy.Identity = sameIdentity,
          confirmedFinished: Boolean = true,
          downloadedAt: Long? = null
  ) = Candidate(
          key, size, local, server, serverKnown,
          confirmed?.let { AutoDeletePolicy.Confirmation(identity, it, confirmedFinished) }, pending, isProtected, identity, downloadedAt
  )

  private fun due(c: Candidate, s: DeviceSettings = settings(), at: Long = now) = AutoDeletePolicy.selectDue(listOf(c), s, at).isNotEmpty()

  // finishedState

  @Test
  fun `newer server record that is not finished beats a finished device record`() {
    val state = AutoDeletePolicy.finishedState(done(5), open(6), serverKnown = true)
    assertFalse(state.finished)
  }

  @Test
  fun `newer device record that is not finished beats a finished server record`() {
    val state = AutoDeletePolicy.finishedState(open(8), done(5), serverKnown = true)
    assertFalse(state.finished)
  }

  @Test
  fun `newer finished record wins and its finishedAt is used`() {
    val state = AutoDeletePolicy.finishedState(open(3), done(5, finishedAt = 4), serverKnown = true)
    assertEquals(AutoDeletePolicy.FinishedState(true, 4), state)
  }

  @Test
  fun `a tie goes to the server`() {
    assertTrue(AutoDeletePolicy.finishedState(open(5), done(5), serverKnown = true).finished)
    assertFalse(AutoDeletePolicy.finishedState(done(5), open(5), serverKnown = true).finished)
  }

  @Test
  fun `a missing finishedAt falls back to the last update`() {
    assertEquals(7L, AutoDeletePolicy.finishedState(null, done(7, finishedAt = null), serverKnown = true).finishedAt)
  }

  @Test
  fun `a single record decides on its own`() {
    assertTrue(AutoDeletePolicy.finishedState(done(1), null, serverKnown = true).finished)
    assertTrue(AutoDeletePolicy.finishedState(null, done(1), serverKnown = true).finished)
    assertFalse(AutoDeletePolicy.finishedState(null, null, serverKnown = true).finished)
  }

  @Test
  fun `without the server only the device record counts`() {
    assertTrue(AutoDeletePolicy.finishedState(done(5), open(9), serverKnown = false).finished)
    assertFalse(AutoDeletePolicy.finishedState(open(5), done(9), serverKnown = false).finished)
    assertNull(AutoDeletePolicy.finishedState(null, done(9), serverKnown = false).finishedAt)
  }

  // isSyncSafe

  @Test
  fun `a pending session is never safe`() {
    assertFalse(AutoDeletePolicy.isSyncSafe(candidate(pending = true, local = null)))
  }

  @Test
  fun `no device progress is safe`() {
    assertTrue(AutoDeletePolicy.isSyncSafe(candidate(local = null, server = null, serverKnown = false)))
  }

  @Test
  fun `online needs the server record`() {
    assertFalse(AutoDeletePolicy.isSyncSafe(candidate(local = open(5), server = null)))
  }

  @Test
  fun `online server at least as new as the device is safe`() {
    assertTrue(AutoDeletePolicy.isSyncSafe(candidate(local = open(5), server = open(5))))
    assertFalse(AutoDeletePolicy.isSyncSafe(candidate(local = open(6), server = open(5))))
  }

  @Test
  fun `online both finished is safe even when the device is newer`() {
    assertTrue(AutoDeletePolicy.isSyncSafe(candidate(local = done(9), server = done(5))))
    assertFalse(AutoDeletePolicy.isSyncSafe(candidate(local = done(9), server = open(5))))
  }

  @Test
  fun `both finished is not safe while the device holds an ebook position the server lacks`() {
    val server = Progress(true, 5, 5, ebookLocation = "cfi-old")
    assertFalse(AutoDeletePolicy.isSyncSafe(candidate(local = Progress(true, 9, 9, ebookLocation = "cfi-new"), server = server)))
    assertTrue(AutoDeletePolicy.isSyncSafe(candidate(local = Progress(true, 9, 9, ebookLocation = "cfi-old"), server = server)))
    assertTrue(AutoDeletePolicy.isSyncSafe(candidate(local = Progress(true, 9, 9, ebookLocation = null), server = server)))
  }

  @Test
  fun `offline needs an earlier confirmation that is not older than the device record`() {
    assertFalse(AutoDeletePolicy.isSyncSafe(candidate(serverKnown = false, server = null, local = done(5), confirmed = null)))
    assertFalse(AutoDeletePolicy.isSyncSafe(candidate(serverKnown = false, server = null, local = done(5), confirmed = 4)))
    assertTrue(AutoDeletePolicy.isSyncSafe(candidate(serverKnown = false, server = null, local = done(5), confirmed = 5)))
  }

  @Test
  fun `confirm only online and only when safe`() {
    assertTrue(AutoDeletePolicy.shouldConfirm(candidate(local = open(5), server = open(5))))
    assertFalse(AutoDeletePolicy.shouldConfirm(candidate(local = open(6), server = open(5))))
    assertFalse(AutoDeletePolicy.shouldConfirm(candidate(serverKnown = false, server = null, confirmed = 99)))
    assertFalse(AutoDeletePolicy.shouldConfirm(candidate(local = null)))
    assertFalse(AutoDeletePolicy.shouldConfirm(candidate(pending = true)))
  }

  // delay

  @Test
  fun `delay counts whole days from finishing and zero means right away`() {
    assertTrue(AutoDeletePolicy.isDue(now, 0, now))
    assertFalse(AutoDeletePolicy.isDue(now - day + 1, 1, now))
    assertTrue(AutoDeletePolicy.isDue(now - day, 1, now))
    assertTrue(AutoDeletePolicy.isDue(now - 7 * day, 7, now))
    assertFalse(AutoDeletePolicy.isDue(now - 7 * day + 1, 7, now))
  }

  @Test
  fun `an unknown delay reads as one day`() {
    assertEquals(1, AutoDeletePolicy.delayDays(settings(delay = null)))
    assertEquals(1, AutoDeletePolicy.delayDays(settings(delay = 5)))
    assertEquals(0, AutoDeletePolicy.delayDays(settings(delay = 0)))
    assertEquals(7, AutoDeletePolicy.delayDays(settings(delay = 7)))
  }

  // limit

  @Test
  fun `limit is off for zero and unknown values, otherwise gigabytes`() {
    assertNull(AutoDeletePolicy.limitBytes(settings(limitGb = 0)))
    assertNull(AutoDeletePolicy.limitBytes(settings(limitGb = 3)))
    assertEquals(5L * 1024 * 1024 * 1024, AutoDeletePolicy.limitBytes(settings(limitGb = 5)))
  }

  // selectDue

  @Test
  fun `due selects a finished synced unprotected download after the delay`() {
    val c = candidate(local = done(now - 2 * day), server = done(now - 2 * day))
    assertTrue(due(c))
    assertFalse("not due yet", due(c, at = now - 2 * day + day - 1))
  }

  @Test
  fun `due respects the books toggle`() {
    val book = candidate(local = done(1), server = done(1))
    assertFalse(due(book, settings(books = false)))
    assertTrue(due(book, settings(books = true)))
  }

  @Test
  fun `due skips protected downloads`() {
    assertFalse(due(candidate(local = done(1), server = done(1), isProtected = true)))
  }

  @Test
  fun `due skips a download with a session not yet sent`() {
    assertFalse(due(candidate(local = done(1), server = done(1), pending = true)))
  }

  @Test
  fun `due skips a download whose device progress is newer than the server's`() {
    assertFalse(due(candidate(local = done(9), server = open(1))))
  }

  @Test
  fun `due skips an unfinished download even when it is old`() {
    assertFalse(due(candidate(local = open(1), server = open(1))))
  }

  @Test
  fun `due offline deletes only a confirmed device record`() {
    val offline = candidate(serverKnown = false, server = null, local = done(1))
    assertFalse(due(offline))
    assertTrue(due(offline.copy(confirmed = AutoDeletePolicy.Confirmation(sameIdentity, 1, true))))
  }

  // selectForSpace

  private fun spaceCandidate(key: String, finishedAt: Long, size: Long) =
          candidate(key = key, size = size, local = done(finishedAt), server = done(finishedAt))

  @Test
  fun `space takes the longest finished first and stops once enough is freed`() {
    val list = listOf(spaceCandidate("new", 30, 10), spaceCandidate("old", 10, 10), spaceCandidate("mid", 20, 10))
    val result = AutoDeletePolicy.selectForSpace(list, settings(), 15)
    assertEquals(listOf("old", "mid"), result.selected.map { it.key })
    assertTrue(result.enough)
    assertEquals(listOf("old"), AutoDeletePolicy.selectForSpace(list, settings(), 10).selected.map { it.key })
  }

  @Test
  fun `space ignores the delay`() {
    val fresh = spaceCandidate("fresh", now, 10)
    assertFalse(due(fresh, settings(delay = 7)))
    assertEquals(listOf("fresh"), AutoDeletePolicy.selectForSpace(listOf(fresh), settings(delay = 7), 5).selected.map { it.key })
  }

  @Test
  fun `space never takes unfinished, protected, unsynced or disabled downloads`() {
    val list = listOf(
      candidate(key = "unfinished", local = open(1), server = open(1)),
      candidate(key = "protected", local = done(1), server = done(1), isProtected = true),
      candidate(key = "unsynced", local = done(9), server = open(1)),
      candidate(key = "pending", local = done(1), server = done(1), pending = true)
    )
    val result = AutoDeletePolicy.selectForSpace(list, settings(), 5)
    assertTrue(result.selected.isEmpty())
    assertFalse(result.enough)
  }

  @Test
  fun `space deletes nothing while the books toggle is off`() {
    val list = listOf(spaceCandidate("a", 1, 10), spaceCandidate("b", 2, 10))
    val result = AutoDeletePolicy.selectForSpace(list, settings(books = false, limitGb = 1), 5)
    assertTrue(result.selected.isEmpty())
    assertFalse(result.enough)
    assertTrue(AutoDeletePolicy.orderedForSpace(list, settings(books = false)).isEmpty())
  }

  @Test
  fun `space reports when finished downloads are not enough`() {
    val result = AutoDeletePolicy.selectForSpace(listOf(spaceCandidate("a", 1, 10), spaceCandidate("b", 2, 10)), settings(), 25)
    assertEquals(2, result.selected.size)
    assertFalse(result.enough)
  }

  @Test
  fun `space needs nothing when nothing is to be freed`() {
    val result = AutoDeletePolicy.selectForSpace(listOf(spaceCandidate("a", 1, 10)), settings(), 0)
    assertTrue(result.selected.isEmpty())
    assertTrue(result.enough)
  }

  @Test
  fun `a download finished listening before it was downloaded again waits for a new finish`() {
    val again = candidate(local = done(10), server = done(10), downloadedAt = 50)
    assertFalse(due(again))
    assertTrue(AutoDeletePolicy.selectForSpace(listOf(again), settings(), 1).selected.isEmpty())
    assertTrue("finished after the download", due(candidate(local = done(60), server = done(60), downloadedAt = 50)))
    assertTrue("no record of the download time", due(candidate(local = done(10), server = done(10), downloadedAt = null)))
  }

  @Test
  fun `an offline confirmation of another server item or account does not count`() {
    val offline = candidate(serverKnown = false, server = null, local = done(1), confirmed = 1)
    assertTrue(due(offline))
    assertFalse(due(offline.copy(identity = sameIdentity.copy(libraryItemId = "other"))))
    assertFalse(due(offline.copy(identity = sameIdentity.copy(serverUserId = "someone"))))
    assertFalse(due(offline.copy(identity = sameIdentity.copy(serverConnectionConfigId = "cfg2"))))
  }

  @Test
  fun `an online pass that found the server newer and not finished revokes the offline deletion`() {
    val online = candidate(local = done(5), server = open(9))
    assertTrue(AutoDeletePolicy.shouldConfirm(online))
    val confirmation = AutoDeletePolicy.confirmationFor(online)
    assertFalse(confirmation.isFinished)
    val offline = candidate(serverKnown = false, server = null, local = done(5), confirmed = confirmation.lastUpdate, confirmedFinished = confirmation.isFinished)
    assertFalse(due(offline))
  }

  @Test
  fun `an online pass that saw the finished state on the server allows the offline deletion`() {
    val confirmation = AutoDeletePolicy.confirmationFor(candidate(local = done(5), server = done(5)))
    assertTrue(confirmation.isFinished)
    assertEquals(5L, confirmation.lastUpdate)
  }

  @Test
  fun `the ordered list for space has every eligible candidate and the selection is its prefix`() {
    val list = listOf(spaceCandidate("new", 30, 10), spaceCandidate("old", 10, 10), spaceCandidate("mid", 20, 10))
    assertEquals(listOf("old", "mid", "new"), AutoDeletePolicy.orderedForSpace(list, settings()).map { it.key })
    assertEquals(listOf("old"), AutoDeletePolicy.selectForSpace(list, settings(), 10).selected.map { it.key })
  }
}
