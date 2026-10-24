package app.absplus.android.managers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.*
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.impl.utils.taskexecutor.WorkManagerTaskExecutor
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real worker doWork entry points; deterministic shared-mutex contention, not OS scheduler timing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkerOutcomeTest {
  @Test fun realWorkersRetryContentionAndFailAtBoundedExhaustion() = runBlocking {
    val ctx = ApplicationProvider.getApplicationContext<Context>()
    DbManager.initialize(ctx)
    val oldData = DeviceManager.deviceData
    val oldConfig = DeviceManager.serverConnectionConfig
    val owner = ServerConnectionConfig("worker-fixture", 0, "Synthetic", "http://127.0.0.1:1", null, "user", "synthetic", "synthetic", null)
    DeviceManager.deviceData = DeviceData(mutableListOf(owner), owner.id, DeviceSettings.default().apply {
      autoDownloadEnabled = true; autoDeleteFinishedBooks = true
    }, null)
    DeviceManager.serverConnectionConfig = owner
    val mutex = AutoDownloadManager::class.java.getDeclaredField("mutex").let { it.isAccessible = true; it.get(null) as Mutex }
    assertTrue(mutex.tryLock())
    try {
      assertEquals(ListenableWorker.Result.retry(), AutoDownloadWorker(ctx, parameters(0)).doWork())
      assertEquals(ListenableWorker.Result.failure(), AutoDownloadWorker(ctx, parameters(PERIODIC_CHECK_MAX_RETRIES)).doWork())
      assertEquals(ListenableWorker.Result.retry(), AutoDeleteWorker(ctx, parameters(0)).doWork())
      assertEquals(ListenableWorker.Result.failure(), AutoDeleteWorker(ctx, parameters(PERIODIC_CHECK_MAX_RETRIES)).doWork())
    } finally {
      mutex.unlock(); DeviceManager.deviceData = oldData; DeviceManager.serverConnectionConfig = oldConfig
    }
  }
  private fun parameters(attempt: Int): WorkerParameters {
    val executor = Executor { it.run() }
    return WorkerParameters(UUID.randomUUID(), Data.EMPTY, emptyList(), WorkerParameters.RuntimeExtras(), attempt, 0,
            executor, WorkManagerTaskExecutor(executor), WorkerFactory.getDefaultWorkerFactory(),
            ProgressUpdater { _, _, _ -> SettableFuture.create<Void>().apply { set(null) } },
            ForegroundUpdater { _, _, _ -> SettableFuture.create<Void>().apply { set(null) } })
  }
}
