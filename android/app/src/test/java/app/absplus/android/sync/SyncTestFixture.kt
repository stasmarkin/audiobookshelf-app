package app.absplus.android.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.device.DeviceManager
import io.paperdb.Paper
import kotlinx.coroutines.CompletableDeferred
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.time.Duration

/** Isolates the sync singletons and DeviceManager state between Robolectric tests. Synthetic servers only. */
class SyncTestFixture {
  val context: Context = ApplicationProvider.getApplicationContext()
  val connectivity: ConnectivityManager = context.getSystemService(ConnectivityManager::class.java)
  private val previousServers: MutableList<ServerConnectionConfig>
  private val previousActive: ServerConnectionConfig?
  private val previousLastId: String?

  /** Every config the fake probe was called with. */
  val probed = mutableListOf<ServerConnectionConfig>()
  /** The answer of the next probes; null makes a probe wait for [pending]. */
  var probeAnswer: Boolean? = true
  val pending = mutableListOf<CompletableDeferred<Boolean>>()
  val probe: suspend (ServerConnectionConfig) -> Boolean = { config ->
    probed.add(config)
    probeAnswer ?: CompletableDeferred<Boolean>().also { pending.add(it) }.await()
  }

  init {
    Paper.init(context)
    previousServers = DeviceManager.deviceData.serverConnectionConfigs
    previousActive = DeviceManager.serverConnectionConfig
    previousLastId = DeviceManager.deviceData.lastServerConnectionConfigId
    resetSingletons()
    DeviceManager.deviceData.serverConnectionConfigs = mutableListOf()
    DeviceManager.serverConnectionConfig = null
    DeviceManager.deviceData.lastServerConnectionConfigId = null
    setNetwork(true)
  }

  fun close() {
    resetSingletons()
    DeviceManager.deviceData.serverConnectionConfigs = previousServers
    DeviceManager.serverConnectionConfig = previousActive
    DeviceManager.deviceData.lastServerConnectionConfigId = previousLastId
  }

  private fun resetSingletons() {
    ServerReachability.resetForTest()
    ActiveServer.resetForTest()
    SyncRuntime.resetForTest()
  }

  fun config(id: String, address: String = "https://$id.test.invalid") =
          ServerConnectionConfig(id, 0, "Synthetic $id", address, "2.30.0", "user-$id", "synthetic", "token-$id", null)

  fun save(vararg configs: ServerConnectionConfig) {
    DeviceManager.deviceData.serverConnectionConfigs = configs.toMutableList()
  }

  /** Whether the default network reports internet when ServerReachability starts. */
  fun setNetwork(available: Boolean) {
    val network = connectivity.activeNetwork
    if (network != null) {
      val caps = ShadowNetworkCapabilities.newInstance()
      if (available) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      shadowOf(connectivity).setNetworkCapabilities(network, caps)
    }
  }

  fun networkCallbacks() = shadowOf(connectivity).networkCallbacks

  fun idle() = shadowOf(Looper.getMainLooper()).idle()

  fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
