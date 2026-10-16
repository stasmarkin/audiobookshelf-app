package app.absplus.android.server

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.internal.EMPTY_REQUEST
import org.json.JSONObject
import java.io.IOException

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
        private val flight: SingleFlight<String, RefreshResult> = sharedFlight
) {
  companion object {
    /** Shared by every ApiHandler, so one refresh per server connection runs at a time across the app. */
    val sharedFlight = SingleFlight<String, RefreshResult>()
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
    // OkHttp drops Authorization across origins, but retains custom headers such as x-refresh-token.
    // Only the saved connection's original endpoint is approved. Never follow Location, even on
    // the same origin: a proxy's redirect is not authority to send a rotating secret elsewhere.
    // Derive a refresh-only client so normal API/download redirect behavior remains unchanged.
    val refreshClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
    refreshClient.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        log("Token refresh failed (${e.javaClass.simpleName})")
        complete(RefreshResult.Transient)
      }

      override fun onResponse(call: Call, response: Response) {
        try {
          response.use { complete(handleResponse(serverConnectionId, sentRefreshToken, it)) }
        } catch (e: Exception) {
          log("Could not handle the refresh response (${e.javaClass.simpleName})")
          complete(RefreshResult.Transient)
        }
      }
    })
  }

  private fun handleResponse(serverConnectionId: String, sentRefreshToken: String, response: Response): RefreshResult {
    if (response.code in 300..399) {
      log("Token refresh redirect blocked; configure the direct server address and retry (credentials retained)")
      return RefreshResult.Transient
    }
    if (!response.isSuccessful) {
      log("Token refresh returned ${response.code}")
      if (response.code != 401 && response.code != 403) return RefreshResult.Transient
      // Only the token this refresh sent is cleared: a new login during the refresh keeps its session.
      if (!store.removeRefreshTokenIf(serverConnectionId, sentRefreshToken)) return RefreshResult.Transient
      onRejected(serverConnectionId)
      return RefreshResult.Rejected
    }
    val user = JSONObject(response.body!!.string()).optJSONObject("user")
    val accessToken = user?.optString("accessToken").orEmpty()
    if (accessToken.isEmpty()) {
      log("Refresh response had no access token")
      return RefreshResult.Transient
    }
    val rotated = user?.optString("refreshToken").orEmpty().ifEmpty { sentRefreshToken }
    if (addressFor(serverConnectionId) == null ||
            !store.replaceRefreshToken(serverConnectionId, sentRefreshToken, rotated)) {
      // Logged out from the server, removed or signed in again while the refresh ran: newer state wins.
      log("Discarded a refresh result for credentials that changed during the refresh")
      return RefreshResult.Transient
    }
    onRotated(serverConnectionId, accessToken)
    return RefreshResult.Success(accessToken)
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
