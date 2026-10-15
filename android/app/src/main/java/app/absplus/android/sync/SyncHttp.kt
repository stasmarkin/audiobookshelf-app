package app.absplus.android.sync

import app.absplus.android.data.ServerConnectionConfig
import app.absplus.android.server.ApiHandler
import app.absplus.android.server.RefreshResult
import app.absplus.android.server.RenewalAction
import app.absplus.android.server.renewalAction
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.EMPTY_REQUEST
import okhttp3.internal.http.HttpMethod
import java.io.IOException
import kotlin.coroutines.resume

/** Where request results go to keep the server state up to date; [ServerReachability] in the app. */
interface ReachabilitySink {
  fun reportResponse(address: String, code: Int)
  fun reportUnreachable(address: String)
}

sealed interface HttpOutcome {
  data class Ok(val code: Int, val body: String) : HttpOutcome
  data class HttpError(val code: Int) : HttpOutcome
  /** No usable answer: no connection, a failed refresh, or [STALE]. */
  data class Unreachable(val message: String) : HttpOutcome
  /** The server rejected the session; only signing in again helps. */
  data object Unauthorized : HttpOutcome

  companion object {
    /** The answer to a request sent before a logout or server switch ([ApiHandler.authGeneration]); never applied. */
    val STALE = Unreachable("stale")
  }
}

/**
 * HTTP for the sync engine: returns the status instead of a JSObject and reports every answer to [report].
 *
 * A 401 is renewed like [ApiHandler] does it: retried with a newer token if one appeared meanwhile, otherwise after
 * the shared refresh of that server ([refresh], single-flight per server), and at most once.
 */
class SyncHttp(
        private val client: OkHttpClient,
        private val refresh: (serverConfigId: String, onResult: (RefreshResult) -> Unit) -> Unit,
        private val tokenFor: (serverConfigId: String) -> String?,
        private val report: ReachabilitySink = ServerReachability
) {
  private val json = "application/json; charset=utf-8".toMediaType()

  suspend fun send(config: ServerConnectionConfig, method: String, path: String, jsonBody: String? = null): HttpOutcome {
    val generation = ApiHandler.authGeneration
    val sentToken = tokenFor(config.id) ?: config.token
    val first = execute(config, method, path, jsonBody, sentToken, generation)
    if (first != HttpOutcome.HttpError(401)) return first

    val retryToken = when (val action = renewalAction(sentToken, tokenFor(config.id))) {
      is RenewalAction.RetryWith -> action.accessToken
      RenewalAction.Refresh -> when (val result = awaitRefresh(config.id)) {
        is RefreshResult.Success -> result.accessToken
        RefreshResult.Rejected -> return HttpOutcome.Unauthorized
        RefreshResult.Transient -> return HttpOutcome.Unreachable("token refresh failed")
      }
    }
    if (generation != ApiHandler.authGeneration) return HttpOutcome.STALE
    val second = execute(config, method, path, jsonBody, retryToken, generation)
    return if (second == HttpOutcome.HttpError(401)) HttpOutcome.Unauthorized else second
  }

  private suspend fun awaitRefresh(serverConfigId: String): RefreshResult =
          suspendCancellableCoroutine { cont -> refresh(serverConfigId) { result -> if (cont.isActive) cont.resume(result) } }

  private suspend fun execute(
          config: ServerConnectionConfig,
          method: String,
          path: String,
          jsonBody: String?,
          token: String,
          generation: Long
  ): HttpOutcome {
    val url = (config.address.trimEnd('/') + path).toHttpUrlOrNull()
            ?: return HttpOutcome.Unreachable("invalid server address")
    val body = jsonBody?.toRequestBody(json) ?: if (HttpMethod.requiresRequestBody(method)) EMPTY_REQUEST else null
    val request = Request.Builder().url(url).method(method, body).apply {
      config.customHeaders?.forEach { (name, value) -> header(name, value) }
      header("Authorization", "Bearer $token")
    }.build()

    val answer = await(client.newCall(request))
    // A logout or server switch while the request ran: the answer belongs to a session that no longer exists, and
    // reporting it would move the state of the new one
    if (generation != ApiHandler.authGeneration) return HttpOutcome.STALE
    return when (answer) {
      is Answer.Failed -> {
        report.reportUnreachable(config.address)
        HttpOutcome.Unreachable(answer.error.message ?: answer.error.javaClass.simpleName)
      }
      is Answer.Received -> {
        report.reportResponse(config.address, answer.code)
        if (answer.code in 200..299) HttpOutcome.Ok(answer.code, answer.body) else HttpOutcome.HttpError(answer.code)
      }
    }
  }

  private sealed interface Answer {
    data class Received(val code: Int, val body: String) : Answer
    data class Failed(val error: IOException) : Answer
  }

  private suspend fun await(call: Call): Answer = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        if (cont.isActive) cont.resume(Answer.Failed(e))
      }

      override fun onResponse(call: Call, response: Response) {
        val answer = try {
          response.use { Answer.Received(it.code, it.body?.string().orEmpty()) }
        } catch (e: IOException) {
          Answer.Failed(e)
        }
        if (cont.isActive) cont.resume(answer)
      }
    })
  }
}
