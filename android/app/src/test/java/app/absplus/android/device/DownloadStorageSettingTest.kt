package app.absplus.android.device

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadStorageSettingTest {
  @Test
  fun normalize() {
    listOf(null, "", "sd:", "sd:  ", "foo", "internal").forEach {
      assertEquals("normalize($it)", "internal", DownloadStorageSetting.normalize(it))
    }
    assertEquals("sd:79EC-9B33", DownloadStorageSetting.normalize("sd:79EC-9B33"))
    assertEquals(StorageTarget.Sd("79EC-9B33"), DownloadStorageSetting.parse("sd:79EC-9B33"))
    assertEquals(StorageTarget.Internal, DownloadStorageSetting.parse("internal"))
    listOf(StorageTarget.Internal, StorageTarget.Sd("79EC-9B33")).forEach {
      assertEquals(it, DownloadStorageSetting.parse(DownloadStorageSetting.format(it)))
    }
  }
}
