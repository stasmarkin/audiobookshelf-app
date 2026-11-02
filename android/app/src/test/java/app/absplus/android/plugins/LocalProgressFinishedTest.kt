package app.absplus.android.plugins

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.data.LocalMediaProgress
import app.absplus.android.data.MediaType
import app.absplus.android.data.MediaTypeMetadata
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.DeviceManager
import io.paperdb.Paper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [finishLocalProgress] behind `AbsDatabase.updateLocalMediaProgressFinished`: Paper only, one answer, no request. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalProgressFinishedTest {
  private var previousServer: ServerConnectionConfig? = null

  @Before
  fun setUp() {
    Paper.init(ApplicationProvider.getApplicationContext<Context>())
    for (book in listOf("localLibraryItems", "localMediaProgress")) Paper.book(book).destroy()
    previousServer = DeviceManager.serverConnectionConfig
  }

  @After
  fun tearDown() {
    DeviceManager.serverConnectionConfig = previousServer
  }

  private fun copy(serverConfigId: String?) = LocalLibraryItem(
          "local_li1", "folder", "", "", "", false, "book", MediaType(MediaTypeMetadata("Book", false), null),
          mutableListOf(), null, null, true, serverConfigId, null, null, serverConfigId?.let { "li1" })

  @Test
  fun answersAtOnceForACopyOfTheActiveServer() {
    // The server path used to answer only from the PATCH callback, never without a server answer
    val server = ServerConnectionConfig("srv", 0, "Synthetic", "https://srv.test.invalid", "2.37.0", "user", "u", "token", null)
    DeviceManager.serverConnectionConfig = server
    DeviceManager.dbManager.saveLocalLibraryItem(copy("srv"))

    val result = finishLocalProgress("local_li1", null, true)

    assertTrue(result.getBoolean("local"))
    assertFalse(result.getBoolean("server"))
    assertTrue(result.getJSONObject("localMediaProgress").getBoolean("isFinished"))
    assertTrue(DeviceManager.dbManager.getLocalMediaProgress("local_li1")!!.isFinished)
  }

  @Test
  fun updatesExistingProgressOfACopyWithoutServer() {
    DeviceManager.dbManager.saveLocalLibraryItem(copy(null))
    DeviceManager.dbManager.saveLocalMediaProgress(LocalMediaProgress(
            "local_li1", "local_li1", null, 1000.0, 1.0, 0.0, true, null, null, 5L, 1L, 5L, null, null, null, null, null))

    val result = finishLocalProgress("local_li1", null, false)

    assertFalse(result.getBoolean("server"))
    val saved = DeviceManager.dbManager.getLocalMediaProgress("local_li1")!!
    assertFalse(saved.isFinished)
    assertEquals(0.0, saved.progress, 0.0)
  }

  @Test
  fun unknownItemIsAnError() {
    assertEquals("Library Item not found", finishLocalProgress("local_missing", null, true).getString("error"))
  }
}
