package app.absplus.android.managers

import androidx.work.ListenableWorker
import app.absplus.android.managers.AutoDownloadManager.Status
import org.junit.Assert.assertEquals
import org.junit.Test

/** A periodic check that could not run is retried, not reported as a successful run (R32-M02). */
class PeriodicCheckResultTest {
  @Test fun aBusyCheckIsRetriedBecauseItDidNothing() {
    assertEquals(ListenableWorker.Result.retry(), periodicCheckResult(Status.BUSY, 0))
  }

  @Test fun aFailedCheckIsRetried() {
    assertEquals(ListenableWorker.Result.retry(), periodicCheckResult(null, 1))
  }

  @Test fun retriesAreBoundedSoThePeriodicWorkGoesOn() {
    assertEquals(ListenableWorker.Result.success(), periodicCheckResult(Status.BUSY, PERIODIC_CHECK_MAX_RETRIES))
    assertEquals(ListenableWorker.Result.success(), periodicCheckResult(null, PERIODIC_CHECK_MAX_RETRIES))
  }

  @Test fun everyRunOrDeliberateSkipIsSuccess() {
    Status.values().filter { it != Status.BUSY }.forEach { assertEquals(it.name, ListenableWorker.Result.success(), periodicCheckResult(it, 0)) }
  }
}
