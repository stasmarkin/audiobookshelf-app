package app.absplus.android.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.diagnostics.DLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Android side of [ReachabilityMachine]: feeds it the default network, request results, socket and auth reports, runs
 * its probe schedule on the main looper and publishes [state]. Every machine input is handled on the main thread.
 */
object ServerReachability : ReachabilitySink {
  private const val TAG = "ServerReachability"

  private val main = Handler(Looper.getMainLooper())
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

  private val _state = MutableStateFlow(ServerState.UNKNOWN)
  val state: StateFlow<ServerState> = _state.asStateFlow()
  private val _serverConfigId = MutableStateFlow<String?>(null)
  /** The server [state] is about; null when none is configured. */
  val serverConfigId: StateFlow<String?> = _serverConfigId.asStateFlow()
  private val _onlineEntries = MutableStateFlow(0L)
  /**
   * Counts the changes into [ServerState.ONLINE]. [state] is conflated: a collector that saw ONLINE misses a quick
   * OFFLINE and back; this count still changes.
   */
  val onlineEntries: StateFlow<Long> = _onlineEntries.asStateFlow()

  @Volatile private var machine: ReachabilityMachine? = null
  private var probe: suspend (ServerConnectionConfig) -> Boolean = ::httpPing
  private var connectivityManager: ConnectivityManager? = null
  private var networkCallback: ConnectivityManager.NetworkCallback? = null
  private var defaultNetwork: Network? = null
  // Bumped whenever a probe is scheduled or cancelled, so a result that arrives late is dropped
  private var probeGeneration = 0L
  private var probeJob: Job? = null
  private val runProbe = Runnable { startProbe() }

  private val pingClient by lazy { OkHttpClient.Builder().callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).build() }

  @Synchronized
  fun start(context: Context, probe: suspend (ServerConnectionConfig) -> Boolean = ::httpPing) {
    if (machine != null) return
    this.probe = probe
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    connectivityManager = cm
    val active = cm?.activeNetwork
    val hasInternet = active != null &&
            cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    defaultNetwork = if (hasInternet) active else null
    machine = ReachabilityMachine(networkAvailable = hasInternet)

    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        dispatch { defaultNetwork = network; ReachabilityInput.NetworkAvailable }
      }

      override fun onLost(network: Network) {
        // The default network can switch (Wi-Fi to cellular) before the old one is reported lost
        dispatch { if (network == defaultNetwork) { defaultNetwork = null; ReachabilityInput.NetworkLost } else null }
      }
    }
    networkCallback = callback
    try {
      cm?.registerDefaultNetworkCallback(callback)
    } catch (e: RuntimeException) {
      DLog.e(TAG, "Could not watch the default network: ${e.message}")
    }
    ActiveServer.current()?.let { config -> dispatch { ReachabilityInput.ServerConfigured(config.id) } }
  }

  /** A response from [address] (any status). Requests to other servers are ignored. */
  override fun reportResponse(address: String, code: Int) {
    if (!isActiveServerUrl(address)) return
    dispatch { if (code >= 500) ReachabilityInput.ServerError(code) else ReachabilityInput.RequestSucceeded }
  }

  /** A request to [address] failed without a response (connect error, timeout, TLS). */
  override fun reportUnreachable(address: String) {
    if (!isActiveServerUrl(address)) return
    dispatch { ReachabilityInput.RequestUnreachable }
  }

  fun reportSocketLost() = dispatch { ReachabilityInput.SocketLost }

  fun reportRefreshRejected(serverConfigId: String) {
    if (ActiveServer.current()?.id != serverConfigId) return
    dispatch { ReachabilityInput.RefreshRejected(serverConfigId) }
  }

  fun reportSignedIn(serverConfigId: String) = dispatch { ReachabilityInput.SignedIn(serverConfigId) }

  fun onForeground() = dispatch { ReachabilityInput.Foreground }

  fun retryNow() = dispatch { ReachabilityInput.ManualRetry }

  internal fun onServerConfigured(serverConfigId: String) = dispatch { ReachabilityInput.ServerConfigured(serverConfigId) }

  internal fun onServerCleared() = dispatch { ReachabilityInput.ServerCleared }

  /**
   * Whether [url] belongs to the active server: same scheme, host and port, and a path at or below the server's
   * base path (servers can live under a subpath such as https://host/abs, so https://host/absx is another server).
   */
  internal fun isActiveServerUrl(url: String): Boolean {
    val base = ActiveServer.current()?.address?.toHttpUrlOrNull() ?: return false
    val target = url.toHttpUrlOrNull() ?: return false
    if (target.scheme != base.scheme || target.host != base.host || target.port != base.port) return false
    val basePath = base.encodedPath.trimEnd('/')
    val path = target.encodedPath
    return basePath.isEmpty() || path == basePath || path.startsWith("$basePath/")
  }

  /** Runs [input] on the main thread; a null input only applies its side effects. Ignored before [start]. */
  private fun dispatch(input: () -> ReachabilityInput?) {
    if (Looper.myLooper() == Looper.getMainLooper()) handle(input) else main.post { handle(input) }
  }

  private fun handle(input: () -> ReachabilityInput?) {
    val m = machine ?: return
    val next = input() ?: return
    val effects = m.on(next)
    _serverConfigId.value = m.serverConfigId
    for (effect in effects) {
      when (effect) {
        is ReachabilityEffect.ScheduleProbe -> {
          cancelProbe()
          main.postDelayed(runProbe, effect.delayMs)
        }
        ReachabilityEffect.CancelProbe -> cancelProbe()
        is ReachabilityEffect.Emit -> {
          DLog.i(TAG, "Server ${m.serverConfigId ?: "-"} is ${effect.state} (after $next)")
          val was = _state.value
          _state.value = effect.state
          if (effect.state == ServerState.ONLINE && was != ServerState.ONLINE) _onlineEntries.value++
        }
      }
    }
  }

  private fun cancelProbe() {
    main.removeCallbacks(runProbe)
    probeGeneration++
    probeJob?.cancel()
    probeJob = null
  }

  private fun startProbe() {
    val config = ActiveServer.current()
    if (config == null || config.id != machine?.serverConfigId) {
      // Someone set DeviceManager.serverConnectionConfig without ActiveServer.remember
      DLog.w(TAG, "Probe skipped: active server ${config?.id ?: "-"} is not the tracked ${machine?.serverConfigId ?: "-"}")
      return
    }
    val generation = ++probeGeneration
    probeJob = scope.launch {
      val ok = try {
        probe(config)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        false
      }
      if (generation != probeGeneration) return@launch
      probeJob = null
      handle { if (ok) ReachabilityInput.ProbeSucceeded else ReachabilityInput.ProbeFailed }
    }
  }

  /** GET {address}/ping: the server is up when it answers 2xx with `success: true`. */
  private suspend fun httpPing(config: ServerConnectionConfig): Boolean = withContext(Dispatchers.IO) {
    val base: HttpUrl = config.address.toHttpUrlOrNull() ?: return@withContext false
    val url = base.newBuilder().addPathSegment("ping").build()
    val request = Request.Builder().url(url).apply {
      config.customHeaders?.forEach { (name, value) -> header(name, value) }
    }.build()
    try {
      pingClient.newCall(request).execute().use { response ->
        response.isSuccessful && JSONObject(response.body?.string() ?: "").optBoolean("success")
      }
    } catch (e: Exception) {
      false
    }
  }

  @Synchronized
  internal fun resetForTest() {
    cancelProbe()
    networkCallback?.let { cb -> try { connectivityManager?.unregisterNetworkCallback(cb) } catch (_: Exception) {} }
    networkCallback = null
    connectivityManager = null
    defaultNetwork = null
    machine = null
    probe = ::httpPing
    _state.value = ServerState.UNKNOWN
    _serverConfigId.value = null
  }
}
