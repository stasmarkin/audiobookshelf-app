package app.absplus.android.sync

enum class ServerState { UNKNOWN, ONLINE, OFFLINE, UNAUTHORIZED }

sealed interface ReachabilityInput {
  object NetworkAvailable : ReachabilityInput
  object NetworkLost : ReachabilityInput
  data class ServerConfigured(val serverConfigId: String) : ReachabilityInput
  object ServerCleared : ReachabilityInput
  object RequestSucceeded : ReachabilityInput
  object RequestUnreachable : ReachabilityInput
  data class ServerError(val code: Int) : ReachabilityInput
  data class RefreshRejected(val serverConfigId: String) : ReachabilityInput
  data class SignedIn(val serverConfigId: String) : ReachabilityInput
  object ProbeSucceeded : ReachabilityInput
  object ProbeFailed : ReachabilityInput
  object Foreground : ReachabilityInput
  object ManualRetry : ReachabilityInput
  object SocketLost : ReachabilityInput
  /** Something waits on the server to play (on) or stopped waiting (off): probe it at least every [URGENT_PROBE_MS]. */
  data class Urgent(val on: Boolean) : ReachabilityInput
}

sealed interface ReachabilityEffect {
  data class ScheduleProbe(val delayMs: Long) : ReachabilityEffect
  object CancelProbe : ReachabilityEffect
  data class Emit(val state: ServerState) : ReachabilityEffect
}

val PROBE_BACKOFF_MS = listOf(5_000L, 10_000L, 30_000L, 60_000L, 300_000L)
const val PROBE_TIMEOUT_MS = 5_000L
const val URGENT_PROBE_MS = 15_000L

/**
 * Whether the active server answers. Pure state machine: no Android,
 * no clock; delays are effect data and [ServerReachability] runs them. Not thread-safe, call from one thread.
 *
 * UNKNOWN means no server is configured, or a configured server has not answered its first probe yet. UNAUTHORIZED is
 * sticky until the user signs in to that server again (or the server is forgotten or switched).
 */
class ReachabilityMachine(networkAvailable: Boolean) {
  var state: ServerState = ServerState.UNKNOWN
    private set
  var serverConfigId: String? = null
    private set
  var networkAvailable: Boolean = networkAvailable
    private set

  private var backoffIndex = 0
  private var urgent = false

  fun on(input: ReachabilityInput): List<ReachabilityEffect> {
    when (input) {
      ReachabilityInput.NetworkAvailable -> networkAvailable = true
      ReachabilityInput.NetworkLost -> networkAvailable = false
      is ReachabilityInput.Urgent -> {
        val was = urgent
        urgent = input.on
        // A wait that begins while the backoff sleeps minutes probes at once, then keeps the short interval
        val probe = input.on && !was && serverConfigId != null && networkAvailable &&
                state != ServerState.ONLINE && state != ServerState.UNAUTHORIZED
        return if (probe) probeNow() else emptyList()
      }
      is ReachabilityInput.ServerConfigured -> return configure(input.serverConfigId)
      ReachabilityInput.ServerCleared -> {
        if (serverConfigId == null) return emptyList()
        serverConfigId = null
        return listOf(ReachabilityEffect.CancelProbe) + moveTo(ServerState.UNKNOWN)
      }
      else -> Unit
    }
    if (serverConfigId == null) return emptyList()
    if (state == ServerState.UNAUTHORIZED) {
      return if (input is ReachabilityInput.SignedIn && input.serverConfigId == serverConfigId) goOnline() else emptyList()
    }

    return when (input) {
      ReachabilityInput.NetworkAvailable, ReachabilityInput.Foreground, ReachabilityInput.ManualRetry ->
        if (state == ServerState.ONLINE || !networkAvailable) emptyList() else probeNow()
      ReachabilityInput.NetworkLost ->
        listOf(ReachabilityEffect.CancelProbe) + moveTo(ServerState.OFFLINE)
      ReachabilityInput.RequestSucceeded, ReachabilityInput.ProbeSucceeded ->
        if (state == ServerState.ONLINE) emptyList() else goOnline(cancelProbe = input == ReachabilityInput.RequestSucceeded)
      ReachabilityInput.RequestUnreachable ->
        if (state == ServerState.OFFLINE) emptyList() else goOffline()
      ReachabilityInput.ProbeFailed -> goOffline()
      is ReachabilityInput.ServerError -> emptyList()
      is ReachabilityInput.RefreshRejected ->
        if (input.serverConfigId != serverConfigId) emptyList()
        else listOf(ReachabilityEffect.CancelProbe) + moveTo(ServerState.UNAUTHORIZED)
      is ReachabilityInput.SignedIn ->
        if (input.serverConfigId != serverConfigId) emptyList() else goOnline()
      ReachabilityInput.SocketLost ->
        // A dropped socket on a live network is the only signal for a server that went away while nothing was requested
        if (state == ServerState.ONLINE && networkAvailable) listOf(ReachabilityEffect.ScheduleProbe(0)) else emptyList()
      is ReachabilityInput.ServerConfigured, ReachabilityInput.ServerCleared, is ReachabilityInput.Urgent -> emptyList()
    }
  }

  private fun configure(id: String): List<ReachabilityEffect> {
    if (id == serverConfigId) return emptyList()
    serverConfigId = id
    backoffIndex = 0
    // A server we cannot reach without a network is offline, not "not configured"
    if (!networkAvailable) return listOf(ReachabilityEffect.CancelProbe) + moveTo(ServerState.OFFLINE)
    return moveTo(ServerState.UNKNOWN) + ReachabilityEffect.ScheduleProbe(0)
  }

  private fun probeNow(): List<ReachabilityEffect> {
    backoffIndex = 0
    return listOf(ReachabilityEffect.ScheduleProbe(0))
  }

  private fun goOnline(cancelProbe: Boolean = true): List<ReachabilityEffect> {
    backoffIndex = 0
    val cancel = if (cancelProbe) listOf(ReachabilityEffect.CancelProbe) else emptyList()
    return cancel + moveTo(ServerState.ONLINE)
  }

  private fun goOffline(): List<ReachabilityEffect> {
    val emit = moveTo(ServerState.OFFLINE)
    if (!networkAvailable) return emit
    val delay = PROBE_BACKOFF_MS[backoffIndex].let { if (urgent) minOf(it, URGENT_PROBE_MS) else it }
    backoffIndex = minOf(backoffIndex + 1, PROBE_BACKOFF_MS.lastIndex)
    return emit + ReachabilityEffect.ScheduleProbe(delay)
  }

  private fun moveTo(next: ServerState): List<ReachabilityEffect> {
    if (state == next) return emptyList()
    state = next
    return listOf(ReachabilityEffect.Emit(next))
  }
}
