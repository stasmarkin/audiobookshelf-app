package app.absplus.android.server

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.EMPTY_REQUEST
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

sealed interface RefreshResult {
  data class Success(val accessToken: String) : RefreshResult

  /** The server rejected the refresh token, so the session has already been cleared. */
  data object Rejected : RefreshResult

  /** The refresh could not be completed. Credentials are untouched and the caller may retry. */
  data object Transient : RefreshResult
}

/** Where refresh tokens live (SecureStorage). Writes are compare-and-set so a late result can't replace newer ones. */
interface RefreshTokenStore {
  fun getRefreshToken(serverConnectionId: String): String?

  /** Stores [newToken] only while [expected] is still the stored token. */
  fun replaceRefreshToken(serverConnectionId: String, expected: String, newToken: String): Boolean

  /** Removes the token only while [expected] is still the stored token. */
  fun removeRefreshTokenIf(serverConnectionId: String, expected: String): Boolean
}

/**
 * Refreshes the access token of one saved server connection with POST /auth/refresh.
 *
 * Every caller in the process (API requests, downloads, the web app) shares one refresh per server connection: the
 * server rotates the refresh token on each use, so overlapping refreshes with the same token would race each other.
 * Results are written only while the refresh token that was sent is still the stored one, so a refresh that finishes
 * after a logout, a removed server or a new login can't overwrite or clear the newer credentials.
 *
 * When the answer is lost (no response, a server error, an unreadable body), the server may already have rotated the
 * token. The refresh is repeated with the same token after [retryDelaysMs]: within its grace period the server
 * answers the previous token with the already rotated one, and without a rotation it simply rotates now.
 */
class TokenRefresher(
        private val store: RefreshTokenStore,
        /** Address of a saved server connection, or null when it no longer exists. */
        private val addressFor: (String) -> String?,
        /** The rotated refresh token is already stored; apply the access token. */
        private val onRotated: (serverConnectionId: String, accessToken: String) -> Unit,
        /** The server rejected the refresh token, which has been removed; clear the session if it is still active. */
        private val onRejected: (serverConnectionId: String) -> Unit,
        private val log: (String) -> Unit = {},
        private val flight: SingleFlight<String, RefreshResult> = sharedFlight,
        private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
        private val schedule: (delayMs: Long, task: () -> Unit) -> Unit = ::scheduleOnSharedExecutor
) {
  companion object {
    /** Shared by every ApiHandler, so one refresh per server connection runs at a time across the app. */
    val sharedFlight = SingleFlight<String, RefreshResult>()

    /** Short enough to stay well inside the server's default 10-minute grace period for the previous token. */
    val DEFAULT_RETRY_DELAYS_MS = listOf(2_000L, 10_000L)
    private const val TIMEOUT_SECONDS = 15L

    private val retryExecutor by lazy {
      Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "token-refresh-retry").apply { isDaemon = true } }
    }

    /** Refreshes never queue behind the callers' ordinary requests, which share their client's dispatcher. */
    private val refreshDispatcher by lazy { Dispatcher() }

    fun scheduleOnSharedExecutor(delayMs: Long, task: () -> Unit) {
      retryExecutor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
    }
  }

  private sealed interface Attempt {
    /** The server may or may not have rotated the token; asking again with the same token is safe. */
    data class Retryable(val reason: String) : Attempt

    data class Final(val result: RefreshResult) : Attempt
  }

  fun refresh(serverConnectionId: String, client: OkHttpClient, onResult: (RefreshResult) -> Unit) {
    flight.run(serverConnectionId, RefreshResult.Transient, { complete -> perform(serverConnectionId, client, complete) }, onResult)
  }

  private fun perform(serverConnectionId: String, client: OkHttpClient, complete: (RefreshResult) -> Unit) {
    val address = addressFor(serverConnectionId)
    val sentRefreshToken = store.getRefreshToken(serverConnectionId)
    if (address == null || sentRefreshToken.isNullOrEmpty()) {
      log("No refresh token or server configuration for the saved server connection (config found=${address != null}, refresh token found=${!sentRefreshToken.isNullOrEmpty()})")
      onRejected(serverConnectionId)
      complete(RefreshResult.Rejected)
      return
    }
    // OkHttp drops Authorization across origins, but retains custom headers such as x-refresh-token.
    // Only the saved connection's original endpoint is approved. Never follow Location, even on
    // the same origin: a proxy's redirect is not authority to send a rotating secret elsewhere.
    // Derive a refresh-only client so normal API/download redirect behavior remains unchanged.
    // Its timeouts don't follow the caller's client: a short ping deadline would cut off the (large) rotation answer.
    // No whole-call deadline either, so a big answer on a slow network is still read to the end.
    val refreshClient = client.newBuilder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .dispatcher(refreshDispatcher)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    attempt(serverConnectionId, address, sentRefreshToken, refreshClient, 1, complete)
  }

  private fun attempt(
          serverConnectionId: String,
          address: String,
          sentRefreshToken: String,
          client: OkHttpClient,
          number: Int,
          complete: (RefreshResult) -> Unit
  ) {
    val request = try {
      Request.Builder()
              .url("$address/auth/refresh")
              .addHeader("x-refresh-token", sentRefreshToken)
              .addHeader("Content-Type", "application/json")
              .post(EMPTY_REQUEST)
              .build()
    } catch (e: Exception) {
      log("Could not create the refresh request (${e.javaClass.simpleName})")
      complete(RefreshResult.Transient)
      return
    }
    val next = { outcome: Attempt -> settle(serverConnectionId, sentRefreshToken, client, number, outcome, complete) }
    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        // No route or no connection: the request never reached the server, so nothing was rotated. OkHttp's own
        // retry after a broken pooled connection keeps that failure in suppressed: then the request may have arrived.
        val neverSent = (listOf(e) + e.suppressed).all { it is UnknownHostException || it is ConnectException }
        if (neverSent) log("Token refresh failed (${e.javaClass.simpleName})")
        next(if (neverSent) Attempt.Final(RefreshResult.Transient) else Attempt.Retryable(e.javaClass.simpleName))
      }

      override fun onResponse(call: Call, response: Response) {
        val outcome = try {
          response.use { handleResponse(serverConnectionId, sentRefreshToken, it) }
        } catch (e: IOException) {
          // The body broke off after the headers: OkHttp reports that here, not in onFailure.
          Attempt.Retryable(e.javaClass.simpleName)
        } catch (e: Exception) {
          log("Could not handle the refresh response (${e.javaClass.simpleName})")
          Attempt.Final(RefreshResult.Transient)
        }
        next(outcome)
      }
    })
  }

  private fun settle(
          serverConnectionId: String,
          sentRefreshToken: String,
          client: OkHttpClient,
          number: Int,
          outcome: Attempt,
          complete: (RefreshResult) -> Unit
  ) {
    if (outcome is Attempt.Final) {
      complete(outcome.result)
      return
    }
    val reason = (outcome as Attempt.Retryable).reason
    val delayMs = retryDelaysMs.getOrNull(number - 1)
    if (delayMs == null) {
      log("Token refresh failed ($reason)")
      complete(RefreshResult.Transient)
      return
    }
    log("Token refresh attempt $number of ${retryDelaysMs.size + 1} failed ($reason), retrying in ${delayMs / 1000}s")
    // SingleFlight only catches a synchronous start, so a failure here must settle the flight itself.
    try {
      schedule(delayMs) {
        try {
          val address = addressFor(serverConnectionId)
          if (address == null || store.getRefreshToken(serverConnectionId) != sentRefreshToken) {
            // A new login or a removed server, not a server answer: nothing to reject.
            log("Stopped retrying the token refresh: the saved credentials changed")
            complete(RefreshResult.Transient)
          } else {
            attempt(serverConnectionId, address, sentRefreshToken, client, number + 1, complete)
          }
        } catch (e: Exception) {
          log("Could not retry the token refresh (${e.javaClass.simpleName})")
          complete(RefreshResult.Transient)
        }
      }
    } catch (e: Exception) {
      log("Could not schedule a token refresh retry (${e.javaClass.simpleName})")
      complete(RefreshResult.Transient)
    }
  }

  private fun handleResponse(serverConnectionId: String, sentRefreshToken: String, response: Response): Attempt {
    if (response.code in 300..399) {
      log("Token refresh redirect blocked; configure the direct server address and retry (credentials retained)")
      return Attempt.Final(RefreshResult.Transient)
    }
    if (!response.isSuccessful) {
      log("Token refresh returned ${response.code}")
      // The server may have rotated the token before failing to build its answer.
      if (response.code >= 500 || response.code == 408) return Attempt.Retryable("HTTP ${response.code}")
      // Anything else, 429 included: its window is minutes long, so a retry in seconds only spends the quota.
      if (response.code != 401 && response.code != 403) return Attempt.Final(RefreshResult.Transient)
      // Only the token this refresh sent is cleared: a new login during the refresh keeps its session.
      if (!store.removeRefreshTokenIf(serverConnectionId, sentRefreshToken)) return Attempt.Final(RefreshResult.Transient)
      onRejected(serverConnectionId)
      return Attempt.Final(RefreshResult.Rejected)
    }
    val user = try {
      JSONObject(response.body!!.string()).optJSONObject("user")
    } catch (e: JSONException) {
      return Attempt.Retryable(e.javaClass.simpleName)
    }
    val accessToken = user?.optString("accessToken").orEmpty()
    if (accessToken.isEmpty()) {
      log("Refresh response had no access token")
      return Attempt.Retryable("no access token")
    }
    val rotated = user?.optString("refreshToken").orEmpty().ifEmpty { sentRefreshToken }
    if (addressFor(serverConnectionId) == null ||
            !store.replaceRefreshToken(serverConnectionId, sentRefreshToken, rotated)) {
      // Logged out from the server, removed or signed in again while the refresh ran: newer state wins.
      log("Discarded a refresh result for credentials that changed during the refresh")
      return Attempt.Final(RefreshResult.Transient)
    }
    onRotated(serverConnectionId, accessToken)
    return Attempt.Final(RefreshResult.Success(accessToken))
  }
}

sealed interface RenewalAction {
  /** The server connection already has a newer access token than the request was sent with. */
  data class RetryWith(val accessToken: String) : RenewalAction

  data object Refresh : RenewalAction
}

/** A 401 for a request sent with [sentToken] needs a refresh only if nothing renewed the token since it was sent. */
fun renewalAction(sentToken: String, currentToken: String?): RenewalAction =
        if (!currentToken.isNullOrEmpty() && currentToken != sentToken) RenewalAction.RetryWith(currentToken)
        else RenewalAction.Refresh

/**
 * Answers [onResult] once: with the refresh result, or with [RefreshResult.Transient] when [limitMs] passes first
 * (0 = no limit). The refresh keeps running either way and still stores what it gets, so a caller with a short
 * deadline doesn't wait out a whole retry series.
 */
fun refreshWithin(
        limitMs: Long,
        schedule: (delayMs: Long, task: () -> Unit) -> Unit,
        refresh: (onResult: (RefreshResult) -> Unit) -> Unit,
        onResult: (RefreshResult) -> Unit
) {
  val settled = AtomicBoolean(false)
  val once = { result: RefreshResult -> if (settled.compareAndSet(false, true)) onResult(result) }
  if (limitMs > 0) {
    try {
      schedule(limitMs) { once(RefreshResult.Transient) }
    } catch (e: Exception) {
      // Without a timer the caller simply waits for the refresh.
    }
  }
  refresh(once)
}
