package app.absplus.android.server

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import app.absplus.android.data.*
import app.absplus.android.device.DeviceManager
import app.absplus.android.media.MediaEventManager
import app.absplus.android.media.MediaProgressSyncData
import app.absplus.android.media.SyncResult
import app.absplus.android.models.User
import app.absplus.android.BuildConfig
import app.absplus.android.plugins.AbsLogger
import app.absplus.android.managers.SecureStorage
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.internal.EMPTY_REQUEST
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiHandler(var ctx:Context) {
  val tag = "ApiHandler"

  companion object {
    // For sending data back to the Webview frontend
    lateinit var absDatabaseNotifyListeners:(String, JSObject) -> Unit

    fun checkAbsDatabaseNotifyListenersInitted():Boolean {
      return ::absDatabaseNotifyListeners.isInitialized
    }

    /** Bumped on logout and server switches; a request sent before then is not retried with renewed credentials. */
    @Volatile var authGeneration = 0L
      private set

    fun invalidateAuthRequests() {
      authGeneration++
    }
  }

  /** Which saved server and token a request was sent with, so a 401 renews that server and not the current one. */
  private data class AuthTag(val serverConnectionConfigId: String, val token: String, val generation: Long)

  private fun Request.Builder.auth(config: ServerConnectionConfig?): Request.Builder {
    val token = config?.token ?: DeviceManager.token
    tag(AuthTag::class.java, AuthTag(config?.id ?: DeviceManager.serverConnectionConfigId, token, authGeneration))
    return addHeader("Authorization", "Bearer $token")
  }

  private var defaultClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .build()
  private var pingClient = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
  private var jacksonMapper = jacksonObjectMapper().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())
  private var secureStorage = SecureStorage(ctx)
  private val tokenRefresher = TokenRefresher(
          secureStorage,
          addressFor = { id -> DeviceManager.getServerConnectionConfig(id)?.address },
          onRotated = ::updateTokens,
          onRejected = ::handleRefreshRejected,
          log = { message -> AbsLogger.error(tag, message) })

  @JsonIgnoreProperties(ignoreUnknown = true)
  data class LocalSessionSyncResult(val id:String, val success:Boolean, val progressSynced:Boolean?, val error:String?)
  data class LocalSessionsSyncResponsePayload(val results:List<LocalSessionSyncResult>)

  private fun getRequest(endpoint:String, httpClient:OkHttpClient?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val address = config?.address ?: DeviceManager.serverAddress

    try {
      val request = Request.Builder()
        .url("${address}$endpoint").auth(config)
        .build()
      makeRequest(request, httpClient, cb)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun postRequest(endpoint:String, payload: JSObject?, config:ServerConnectionConfig?, cb: (JSObject) -> Unit) {
    val address = config?.address ?: DeviceManager.serverAddress
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload?.toString()?.toRequestBody(mediaType) ?: EMPTY_REQUEST
    val requestUrl = "${address}$endpoint"
    Log.d(tag, "postRequest to $requestUrl")
    try {
      val request = Request.Builder().post(requestBody)
        .url(requestUrl).auth(config)
        .build()
      makeRequest(request, null, cb)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun patchRequest(endpoint:String, payload: JSObject, cb: (JSObject) -> Unit) {
    val mediaType = "application/json; charset=utf-8".toMediaType()
    val requestBody = payload.toString().toRequestBody(mediaType)
    try {
      val request = Request.Builder().patch(requestBody)
        .url("${DeviceManager.serverAddress}$endpoint").auth(null)
        .build()
      makeRequest(request, null, cb)
    } catch(e: Exception) {
      e.printStackTrace()
      val jsobj = JSObject()
      jsobj.put("error", "Request failed: ${e.message}")
      cb(jsobj)
    }
  }

  private fun makeRequest(request:Request, httpClient:OkHttpClient?, cb: (JSObject) -> Unit) {
    val client = httpClient ?: defaultClient

    client.newCall(request).enqueue(object : Callback {
      override fun onFailure(call: Call, e: IOException) {
        Log.d(tag, "FAILURE TO CONNECT")
        e.printStackTrace()

        val jsobj = JSObject()
        jsobj.put("error", "Failed to connect")
        cb(jsobj)
      }

      override fun onResponse(call: Call, response: Response) {
        response.use {
          if (it.code == 401) {
            // Handle 401 Unauthorized by attempting token refresh
            AbsLogger.info(tag, "makeRequest: 401 Unauthorized for request to \"${request.url.encodedPath}\" - attempt token refresh")
            handleTokenRefresh(request, httpClient, cb)
            return
          }

          if (!it.isSuccessful) {
            val jsobj = JSObject()
            jsobj.put("error", "Unexpected code $response")
            cb(jsobj)
            return
          }

          val bodyString = it.body!!.string()
          if (bodyString == "OK") {
            cb(JSObject())
          } else {
            try {
              var jsonObj = JSObject()
              if (bodyString.startsWith("[")) {
                val array = JSArray(bodyString)
                jsonObj.put("value", array)
              } else {
                jsonObj = JSObject(bodyString)
              }
              cb(jsonObj)
            } catch(je:JSONException) {
              Log.e(tag, "Invalid JSON response ${je.localizedMessage} from body $bodyString")
              val jsobj = JSObject()
              jsobj.put("error", "Invalid response body")
              cb(jsobj)
            }
          }
        }
      }
    })
  }

  /**
   * Handles a 401 for a request sent with a saved server's access token:
   * - after a logout or server switch since the request was sent, it fails without renewing anything;
   * - if that server already has a newer access token (another request renewed it), it retries with that one;
   * - otherwise it waits for the shared refresh of that server ([TokenRefresher]) and retries once.
   * Each request is retried at most once, and a failed refresh fails the request ([TokenRefresher] owns clearing the
   * session when the server rejects the refresh token).
   */
  private fun handleTokenRefresh(originalRequest: Request, httpClient: OkHttpClient?, callback: (JSObject) -> Unit) {
    val auth = originalRequest.tag(AuthTag::class.java)
    val failed = { callback(JSObject().put("error", "Authentication failed - login again")) }
    if (auth == null || auth.serverConnectionConfigId.isEmpty() || auth.generation != authGeneration) {
      failed()
      return
    }
    when (val action = renewalAction(auth.token, DeviceManager.getServerConnectionConfig(auth.serverConnectionConfigId)?.token)) {
      is RenewalAction.RetryWith -> retryOriginalRequest(originalRequest, action.accessToken, httpClient, callback)
      RenewalAction.Refresh ->
        refreshAuthTokens(auth.serverConnectionConfigId, httpClient) { result ->
          if (result is RefreshResult.Success && auth.generation == authGeneration) {
            retryOriginalRequest(originalRequest, result.accessToken, httpClient, callback)
          } else {
            failed()
          }
        }
    }
  }

  /** Refreshes tokens for a specific saved server, including downloads queued while another server is active. */
  fun refreshAuthTokens(
          serverConnectionConfigId: String,
          httpClient: OkHttpClient? = null,
          onResult: (RefreshResult) -> Unit
  ) {
    tokenRefresher.refresh(serverConnectionConfigId, httpClient ?: defaultClient, onResult)
  }

  /**
   * Clears only the server that rejected the refresh token; queued downloads can target a non-active server.
   *
   * Only call this when the server explicitly rejected the refresh token. Transient failures should not log the user out
   */
  private fun handleRefreshRejected(serverConnectionConfigId: String) {
    // Must not throw: callers still have to report the refresh result to an in-flight request or download.
    try {
      if (DeviceManager.serverConnectionConfigId != serverConnectionConfigId) return
      DeviceManager.serverConnectionConfig = null
      DeviceManager.deviceData.lastServerConnectionConfigId = null
      DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
      invalidateAuthRequests()
      if (checkAbsDatabaseNotifyListenersInitted()) {
        absDatabaseNotifyListeners(
                "onTokenRefreshFailure",
                JSObject().put("error", "Token refresh failed").put("serverConnectionConfigId", serverConnectionConfigId))
      }
    } catch (e: Exception) {
      AbsLogger.error(tag, "Could not clear the rejected session: ${e.message}")
    }
  }

  /**
   * Applies a renewed access token; [TokenRefresher] has already stored the rotated refresh token.
   *
   * @param serverConnectionConfigId The saved server the token belongs to
   * @param newAccessToken The new access token
   */
  private fun updateTokens(serverConnectionConfigId: String, newAccessToken: String) {
    try {
      // The refreshed connection may be queued in the downloader rather than currently active.
      DeviceManager.getServerConnectionConfig(serverConnectionConfigId)?.let { config ->
        config.token = newAccessToken
        DeviceManager.dbManager.saveDeviceData(DeviceManager.deviceData)
        Log.d(tag, "updateTokens: Updated access token in server connection config")
      }

      // Send access token to Webview frontend, only for the server it is using
      if (DeviceManager.serverConnectionConfigId == serverConnectionConfigId && checkAbsDatabaseNotifyListenersInitted()) {
        val tokenJsObject = JSObject()
        tokenJsObject.put("accessToken", newAccessToken)
        tokenJsObject.put("serverConnectionConfigId", serverConnectionConfigId)
        absDatabaseNotifyListeners("onTokenRefresh", tokenJsObject)
      } else {
        // Can happen if Webview is never run, or the refresh was for a server that is not active
        Log.i(tag, "Not sending the new access token to the Webview")
      }
      AbsLogger.info(tag, "updateTokens: Successfully refreshed auth tokens")
    } catch (e: Exception) {
      Log.e(tag, "updateTokens: Failed to update tokens", e)
      AbsLogger.error(tag, "updateTokens: Failed to apply refreshed auth tokens (error: ${e.message})")
    }
  }

  /**
   * Retries the original request with the new access token
   *
   * @param originalRequest The original request to retry
   * @param newAccessToken The new access token to use
   * @param httpClient The HTTP client to use
   * @param callback The callback to return the response
   */
  private fun retryOriginalRequest(originalRequest: Request, newAccessToken: String, httpClient: OkHttpClient?, callback: (JSObject) -> Unit) {
    try {
      // Create a new request with the updated authorization header
      val newRequest = originalRequest.newBuilder()
        .removeHeader("Authorization")
        .addHeader("Authorization", "Bearer $newAccessToken")
        .build()

      Log.d(tag, "retryOriginalRequest: Retrying request to ${newRequest.url}")

      // Make the retry request
      val client = httpClient ?: defaultClient
      client.newCall(newRequest).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
          Log.e(tag, "retryOriginalRequest: Failed to retry request", e)
          AbsLogger.error(tag, "retryOriginalRequest: Failed to retry request after token refresh for server ${DeviceManager.serverConnectionConfigString} (error: ${e.message})")
          val errorObj = JSObject()
          errorObj.put("error", "Failed to retry request after token refresh")
          callback(errorObj)
        }

        override fun onResponse(call: Call, response: Response) {
          response.use {
            if (!it.isSuccessful) {
              Log.e(tag, "retryOriginalRequest: Retry request failed with status ${it.code}")
              AbsLogger.error(tag, "retryOriginalRequest: Retry request failed with status ${it.code} for server ${DeviceManager.serverConnectionConfigString}")
              val errorObj = JSObject()
              errorObj.put("error", "Retry request failed with status ${it.code}")
              callback(errorObj)
              return
            }

            val bodyString = it.body!!.string()
            if (bodyString == "OK") {
              callback(JSObject())
            } else {
              try {
                var jsonObj = JSObject()
                if (bodyString.startsWith("[")) {
                  val array = JSArray(bodyString)
                  jsonObj.put("value", array)
                } else {
                  jsonObj = JSObject(bodyString)
                }
                callback(jsonObj)
              } catch(je:JSONException) {
                Log.e(tag, "retryOriginalRequest: Invalid JSON response ${je.localizedMessage} from body $bodyString")
                val errorObj = JSObject()
                errorObj.put("error", "Invalid response body")
                callback(errorObj)
              }
            }
          }
        }
      })

    } catch (e: Exception) {
      Log.e(tag, "retryOriginalRequest: Unexpected error during retry", e)
      AbsLogger.error(tag, "retryOriginalRequest: Unexpected error during retry for server ${DeviceManager.serverConnectionConfigString}")
      val errorObj = JSObject()
      errorObj.put("error", "Failed to retry request")
      callback(errorObj)
    }
  }

  fun getCurrentUser(cb: (User?) -> Unit) {
    getRequest("/api/me", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getCurrentUser Failed")
        cb(null)
      } else {
        val user = try {
          jacksonMapper.readValue<User>(it.toString())
        } catch (e: Exception) {
          Log.e(tag, "getCurrentUser: invalid response", e)
          null
        }
        cb(user)
      }
    }
  }

  fun getLibraries(cb: (List<Library>) -> Unit) {
    val mapper = jacksonMapper
    getRequest("/api/libraries?include=stats", null,null) {
      val libraries = mutableListOf<Library>()

      var array = JSONArray()
      if (it.has("libraries")) { // TODO: Server 2.2.9 changed to this
        array = it.getJSONArray("libraries")
      } else if (it.has("value")) {
        array = it.getJSONArray("value")
      }

      for (i in 0 until array.length()) {
        try {
          libraries.add(mapper.readValue(array.get(i).toString()))
        } catch (e: Exception) {
          Log.e(tag, "getLibraries: skipping unparsable library at index $i", e)
        }
      }
      cb(libraries)
    }
  }

  fun getLibraryPersonalized(libraryItemId:String, cb: (List<LibraryShelfType>?) -> Unit) {
    getRequest("/api/libraries/$libraryItemId/personalized", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryStats Failed")
        cb(null)
      } else {
        val items = mutableListOf<LibraryShelfType>()
        val array = it.getJSONArray("value")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryShelfType>(array.get(i).toString())
          items.add(item)
        }
        cb(items)
      }
    }
  }

  fun getLibraryItem(libraryItemId:String, cb: (LibraryItem?) -> Unit) {
    getRequest("/api/items/$libraryItemId?expanded=1", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryItem Failed")
        cb(null)
      } else {
        val libraryItem = try {
          jacksonMapper.readValue<LibraryItem>(it.toString())
        } catch (e: Exception) {
          Log.e(tag, "getLibraryItem: invalid response for $libraryItemId", e)
          null
        }
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItemWithProgress(libraryItemId:String, episodeId:String?, cb: (LibraryItem?) -> Unit) {
    var requestUrl = "/api/items/$libraryItemId?expanded=1&include=progress"
    if (!episodeId.isNullOrEmpty()) requestUrl += "&episode=$episodeId"
    getRequest(requestUrl, null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getLibraryItemWithProgress Failed")
        cb(null)
      } else {
        val libraryItem = try {
          jacksonMapper.readValue<LibraryItem>(it.toString())
        } catch (e: Exception) {
          Log.e(tag, "getLibraryItemWithProgress: invalid response for $libraryItemId", e)
          null
        }
        cb(libraryItem)
      }
    }
  }

  fun getLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    getRequest("/api/libraries/$libraryId/items?limit=100&minified=1", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  // Unlike getLibraryItems() (capped at 100 for Android Auto browsing), this pages through the
  // entire library - used by Rescan Folder, which needs to find a match regardless of how many
  // items are in the library, not just the first page.
  fun getAllLibraryItems(libraryId:String, cb: (List<LibraryItem>) -> Unit) {
    val allItems = mutableListOf<LibraryItem>()
    val limit = 100

    fun fetchPage(page: Int) {
      getRequest("/api/libraries/$libraryId/items?limit=$limit&page=$page&minified=1", null, null) {
        if (!it.has("results")) {
          cb(allItems)
          return@getRequest
        }
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          allItems.add(jacksonMapper.readValue<LibraryItem>(array.get(i).toString()))
        }
        val total = it.optInt("total", allItems.size)
        if (array.length() < limit || allItems.size >= total) {
          cb(allItems)
        } else {
          fetchPage(page + 1)
        }
      }
    }
    fetchPage(0)
  }

  // Rescan Folder variants of getLibraries()/getAllLibraryItems() that report a failure (null)
  // instead of an empty or partial list, so an unreachable server is never a "0 matched" success.
  fun getLibrariesOrNull(cb: (List<Library>?) -> Unit) {
    getRequest("/api/libraries", null, null) {
      val libraries = try {
        when {
          it.has("libraries") -> it.getJSONArray("libraries")
          it.has("value") -> it.getJSONArray("value")
          else -> null
        }?.let { array -> (0 until array.length()).map { i -> jacksonMapper.readValue<Library>(array.get(i).toString()) } }
      } catch (e: Exception) {
        Log.e(tag, "getLibrariesOrNull: invalid response", e)
        null
      }
      if (libraries == null) Log.e(tag, "getLibrariesOrNull failed: ${it.optString("error")}")
      cb(libraries)
    }
  }

  fun getAllLibraryItemsOrNull(libraryId:String, cb: (List<LibraryItem>?) -> Unit) {
    val allItems = mutableListOf<LibraryItem>()
    val limit = 100

    fun fetchPage(page: Int) {
      getRequest("/api/libraries/$libraryId/items?limit=$limit&page=$page&minified=1", null, null) {
        val next = try {
          val array = it.getJSONArray("results")
          for (i in 0 until array.length()) {
            allItems.add(jacksonMapper.readValue<LibraryItem>(array.get(i).toString()))
          }
          val total = it.optInt("total", allItems.size)
          if (array.length() < limit || allItems.size >= total) null else page + 1
        } catch (e: Exception) {
          Log.e(tag, "getAllLibraryItemsOrNull: page $page of $libraryId failed: ${it.optString("error")}", e)
          cb(null)
          return@getRequest
        }
        if (next == null) cb(allItems) else fetchPage(next)
      }
    }
    fetchPage(0)
  }

  fun getLibraryItemsPage(libraryId: String, page: Int, limit: Int, cb: (LibraryItemsPage?) -> Unit) {
    getRequest("/api/libraries/$libraryId/items?limit=$limit&page=$page&minified=1", null, null) {
      if (it.has("error")) {
        AbsLogger.error(tag, "getLibraryItemsPage: ${it.getString("error") ?: "request failed"}")
        cb(null)
        return@getRequest
      }
      val result = try {
        val items = mutableListOf<LibraryItem>()
        var rawCount = 0
        if (it.has("results")) {
          val array = it.getJSONArray("results")
          rawCount = array.length()
          for (i in 0 until array.length()) {
            try {
              items.add(jacksonMapper.readValue<LibraryItem>(array.get(i).toString()))
            } catch (e: Exception) {
              AbsLogger.error(tag, "getLibraryItemsPage: skipping unparsable item at index $i: ${e.message}")
            }
          }
        }
        LibraryItemsPage(items, it.optInt("total", rawCount), it.optInt("page", page), it.optInt("limit", limit), rawCount)
      } catch (e: Exception) {
        AbsLogger.error(tag, "getLibraryItemsPage: failed to parse response: ${e.message}")
        null
      }
      cb(result)
    }
  }

  fun getLibrarySeries(libraryId:String, cb: (List<LibrarySeriesItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/series?minified=1&sort=name&limit=10000", null, null) {
      val items = mutableListOf<LibrarySeriesItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibrarySeriesItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibrarySeriesItems(libraryId:String, seriesId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting items for series")
    val seriesIdBase64 = Base64.encodeToString(seriesId.toByteArray(), Base64.DEFAULT)
    getRequest("/api/libraries/$libraryId/items?minified=1&sort=media.metadata.title&filter=series.${seriesIdBase64}&limit=1000", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getLibraryAuthors(libraryId:String, cb: (List<LibraryAuthorItem>) -> Unit) {
    Log.d(tag, "Getting series")
    getRequest("/api/libraries/$libraryId/authors", null, null) {
      val items = mutableListOf<LibraryAuthorItem>()
      if (it.has("authors")) {
        val array = it.getJSONArray("authors")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryAuthorItem>(array.get(i).toString())
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryItemsFromAuthor(libraryId:String, authorId:String, cb: (List<LibraryItem>) -> Unit) {
    Log.d(tag, "Getting author items")
    val authorIdBase64 = Base64.encodeToString(authorId.toByteArray(), Base64.DEFAULT)
    getRequest("/api/libraries/$libraryId/items?limit=1000&minified=1&filter=authors.${authorIdBase64}&sort=media.metadata.title&collapseseries=1", null, null) {
      val items = mutableListOf<LibraryItem>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryItem>(array.get(i).toString())
          if (item.collapsedSeries != null) {
            item.collapsedSeries?.libraryId = libraryId
          }
          items.add(item)
        }
      }else{
        Log.e(tag, "No results")
      }
      cb(items)
    }
  }

  fun getLibraryCollections(libraryId:String, cb: (List<LibraryCollection>) -> Unit) {
    Log.d(tag, "Getting collections")
    getRequest("/api/libraries/$libraryId/collections?minified=1&sort=name&limit=1000", null, null) {
      val items = mutableListOf<LibraryCollection>()
      if (it.has("results")) {
        val array = it.getJSONArray("results")
        for (i in 0 until array.length()) {
          val item = jacksonMapper.readValue<LibraryCollection>(array.get(i).toString())
          items.add(item)
        }
      }
      cb(items)
    }
  }

  fun getSearchResults(libraryId:String, queryString:String, cb: (LibraryItemSearchResultType?) -> Unit) {
    Log.d(tag, "Doing search for library $libraryId")
    getRequest("/api/libraries/$libraryId/search?q=$queryString", null, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "getSearchResults Failed")
        cb(null)
      } else {
        val librarySearchResults = jacksonMapper.readValue<LibraryItemSearchResultType>(it.toString())
        cb(librarySearchResults)
      }
    }
  }

  fun getAllItemsInProgress(cb: (List<ItemInProgress>) -> Unit) {
    getRequest("/api/me/items-in-progress", null, null) {
      val items = mutableListOf<ItemInProgress>()
      if (it.has("libraryItems")) {
        val array = it.getJSONArray("libraryItems")
        for (i in 0 until array.length()) {
          val jsobj = array.get(i) as JSONObject
          val itemInProgress = ItemInProgress.makeFromServerObject(jsobj, jacksonMapper)
          items.add(itemInProgress)
        }
      }
      cb(items)
    }
  }

  fun playLibraryItem(libraryItemId:String, episodeId:String?, playItemRequestPayload:PlayItemRequestPayload, cb: (PlaybackSession?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(playItemRequestPayload))

    val endpoint = if (episodeId.isNullOrEmpty()) "/api/items/$libraryItemId/play" else "/api/items/$libraryItemId/play/$episodeId"
    postRequest(endpoint, payload, null) {
      if (it.has("error")) {
        Log.e(tag, it.getString("error") ?: "Play Library Item Failed")
        cb(null)
      } else {
        it.put("serverConnectionConfigId", DeviceManager.serverConnectionConfig?.id)
        it.put("serverAddress", DeviceManager.serverAddress)
        val playbackSession = jacksonMapper.readValue<PlaybackSession>(it.toString())
        cb(playbackSession)
      }
    }
  }

  fun sendProgressSync(sessionId:String, syncData: MediaProgressSyncData, cb: (Boolean, String?) -> Unit) {
    val payload = JSObject(jacksonMapper.writeValueAsString(syncData))

    postRequest("/api/session/$sessionId/sync", payload, null) {
      if (!it.getString("error").isNullOrEmpty()) {
        cb(false, it.getString("error"))
      } else {
        cb(true, null)
      }
    }
  }

  private fun createPartialPlaybackSession(playbackSession: PlaybackSession): ObjectNode {
    val json = jacksonMapper.createObjectNode()
    json.put("id", playbackSession.id)
    json.put("userId", playbackSession.userId)
    json.put("libraryItemId", playbackSession.libraryItemId)
    json.put("episodeId", playbackSession.episodeId)
    json.put("mediaType", playbackSession.mediaType)
    json.put("displayTitle", playbackSession.displayTitle)
    json.put("displayAuthor", playbackSession.displayAuthor)
    json.put("duration", playbackSession.duration)
    json.put("playMethod", playbackSession.playMethod)
    json.put("startedAt", playbackSession.startedAt)
    json.put("updatedAt", playbackSession.updatedAt)
    json.put("timeListening", playbackSession.timeListening)
    json.put("currentTime", playbackSession.currentTime)
    json.put("mediaPlayer", playbackSession.mediaPlayer)
    return json
  }

  fun sendLocalProgressSync(playbackSession:PlaybackSession, cb: (Boolean, String?) -> Unit) {
    val partialSession = createPartialPlaybackSession(playbackSession)
    partialSession.set<ObjectNode>("deviceInfo", jacksonMapper.valueToTree(playbackSession.deviceInfo))
    postRequest("/api/session/local", JSObject(partialSession.toString()), null) {
      if (!it.getString("error").isNullOrEmpty()) {
        cb(false, it.getString("error"))
      } else {
        cb(true, null)
      }
    }
  }

  fun updateMediaProgress(libraryItemId:String,episodeId:String?,updatePayload:JSObject, cb: () -> Unit) {
    Log.d(tag, "updateMediaProgress $libraryItemId $episodeId $updatePayload")
    val endpoint = if(episodeId.isNullOrEmpty()) "/api/me/progress/$libraryItemId" else "/api/me/progress/$libraryItemId/$episodeId"
    patchRequest(endpoint,updatePayload) {
      Log.d(tag, "updateMediaProgress patched progress")
      cb()
    }
  }

  fun getMediaProgress(libraryItemId:String, episodeId:String?, serverConnectionConfig:ServerConnectionConfig?, cb: (MediaProgress?) -> Unit) {
    val endpoint = if(episodeId.isNullOrEmpty()) "/api/me/progress/$libraryItemId" else "/api/me/progress/$libraryItemId/$episodeId"

    // TODO: Using ping client here allows for shorter timeout (3 seconds), maybe rename or make diff client for requests requiring quicker response
    getRequest(endpoint, pingClient, serverConnectionConfig) {
      if (it.has("error")) {
        Log.e(tag, "getMediaProgress: Failed to get progress")
        cb(null)
      } else {
        val progress = jacksonMapper.readValue<MediaProgress>(it.toString())
        cb(progress)
      }
    }
  }

  fun getPlaybackSession(playbackSessionId:String, cb: (PlaybackSession?) -> Unit) {
    Log.d(tag, "getPlaybackSession for $playbackSessionId for server ${DeviceManager.serverAddress}")
    val endpoint = "/api/session/$playbackSessionId"
    getRequest(endpoint, null, null) {
      val err = it.getString("error")
      if (!err.isNullOrEmpty()) {
        cb(null)
      } else {
        cb(jacksonMapper.readValue<PlaybackSession>(it.toString()))
      }
    }
  }

  fun pingServer(config:ServerConnectionConfig, cb: (Boolean) -> Unit) {
    Log.d(tag, "pingServer: Pinging ${config.address}")
    getRequest("/ping", pingClient, config) {
      val success = it.getString("success")
      if (success.isNullOrEmpty()) {
        Log.d(tag, "pingServer: Ping ${config.address} Failed")
        cb(false)
      } else {
        Log.d(tag, "pingServer: Ping ${config.address} Successful")
        cb(true)
      }
    }
  }

  fun closePlaybackSession(playbackSessionId:String, config:ServerConnectionConfig?, cb: (Boolean) -> Unit) {
    Log.d(tag, "closePlaybackSession: playbackSessionId=$playbackSessionId")
    postRequest("/api/session/$playbackSessionId/close", null, config) {
      cb(true)
    }
  }

  fun authorize(config:ServerConnectionConfig, cb: (MutableList<MediaProgress>?) -> Unit) {
    Log.d(tag, "authorize: Authorizing ${config.address}")
    postRequest("/api/authorize", JSObject(), config) {
      val error = it.getString("error")
      if (!error.isNullOrEmpty()) {
        Log.d(tag, "authorize: Authorize ${config.address} Failed: $error")
        cb(null)
      } else {
        val mediaProgressList:MutableList<MediaProgress> = mutableListOf()
        val user = it.getJSObject("user")
        val mediaProgress = user?.getJSONArray("mediaProgress") ?: JSONArray()
        for (i in 0 until mediaProgress.length()) {
          val mediaProg = jacksonMapper.readValue<MediaProgress>(mediaProgress.getJSONObject(i).toString())
          mediaProgressList.add(mediaProg)
        }
        Log.d(tag, "authorize: Authorize ${config.address} Successful")
        cb(mediaProgressList)
      }
    }
  }

  fun sendSyncLocalSessions(playbackSessions:List<PlaybackSession>, cb: (Boolean, String?) -> Unit) {
    @SuppressLint("HardwareIds")
    val deviceId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
    val deviceInfo = DeviceInfo(deviceId, Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME)

    val json = jacksonMapper.createObjectNode();
    json.putArray("sessions").addAll(playbackSessions.map(::createPartialPlaybackSession))
    json.set<ObjectNode>("deviceInfo", jacksonMapper.valueToTree(deviceInfo))
    AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Sending ${playbackSessions.size} saved local playback sessions to server (${DeviceManager.serverConnectionConfigName})")

    postRequest("/api/session/local-all", JSObject(json.toString()), null) {
      if (!it.getString("error").isNullOrEmpty()) {
        AbsLogger.error("ApiHandler", "sendSyncLocalSessions: Failed to sync local sessions. (${it.getString("error")})")
        cb(false, it.getString("error"))
      } else {
        val response = jacksonMapper.readValue<LocalSessionsSyncResponsePayload>(it.toString())
        response.results.forEach { localSessionSyncResult ->
          Log.d(tag, "Synced session result ${localSessionSyncResult.id}|${localSessionSyncResult.progressSynced}|${localSessionSyncResult.success}")

          playbackSessions.find { ps -> ps.id == localSessionSyncResult.id }?.let { session ->
            if (localSessionSyncResult.progressSynced == true) {
              val syncResult = SyncResult(true, true, "Progress synced on server")
              MediaEventManager.saveEvent(session, syncResult)

              AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Synced session \"${session.displayTitle}\" with server, server progress was updated for item ${session.mediaItemId}")
            } else if (!localSessionSyncResult.success) {
              AbsLogger.error("ApiHandler", "sendSyncLocalSessions: Failed to sync session \"${session.displayTitle}\" with server. Error: ${localSessionSyncResult.error}")
            } else {
              AbsLogger.info("ApiHandler", "sendSyncLocalSessions: Synced session \"${session.displayTitle}\" with server. Server progress was up-to-date for item ${session.mediaItemId}")
            }
          }
        }
        cb(true, null)
      }
    }
  }

  fun syncLocalMediaProgressForUser(cb: () -> Unit) {
    AbsLogger.info("ApiHandler", "[ApiHandler] syncLocalMediaProgressForUser: Server connection ${DeviceManager.serverConnectionConfigName}")

    // Get all local media progress for this server
    val allLocalMediaProgress = DeviceManager.dbManager.getAllLocalMediaProgress().filter { it.serverConnectionConfigId == DeviceManager.serverConnectionConfigId }
    if (allLocalMediaProgress.isEmpty()) {
      AbsLogger.info("ApiHandler", "[ApiHandler] syncLocalMediaProgressForUser: No local media progress to sync")
      return cb()
    }

    AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Found ${allLocalMediaProgress.size} local media progress")

    getCurrentUser { user ->
      if (user == null) {
        AbsLogger.error("ApiHandler", "syncLocalMediaProgressForUser: Failed to load user from server (${DeviceManager.serverConnectionConfigName})")
      } else {
        var numLocalMediaProgressUptToDate = 0
        var numLocalMediaProgressUpdated = 0

        // Compare server user progress with local progress
        //
        // REVERT POINT (advplyr/audiobookshelf-app#2024 fix): the `mediaProgress.lastUpdate >
        // localMediaProgress.lastUpdate` branch below used to unconditionally overwrite local with
        // server whenever server's timestamp was newer. That silently discarded real offline
        // listening progress whenever the server's timestamp was newer without its progress
        // actually being further along (clock skew, a stale-but-recently-touched server record,
        // etc). It's now gated on the server's progress actually being >= local's (or finished);
        // otherwise local's more-advanced progress is pushed up to the server instead. To restore
        // the old pure-timestamp behavior, delete the `if (mediaProgress.currentTime >= ...)` gate
        // below and always take the `pullServerProgressToLocal` path.
        user.mediaProgress.forEach { mediaProgress ->
          // Get matching local media progress
          allLocalMediaProgress.find { it.isMatch(mediaProgress) }?.let { localMediaProgress ->
            if (mediaProgress.lastUpdate > localMediaProgress.lastUpdate) {
              if (mediaProgress.currentTime >= localMediaProgress.currentTime || mediaProgress.isFinished) {
                pullServerProgressToLocal(mediaProgress, localMediaProgress)
              } else {
                AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Server lastUpdate is newer for item \"${mediaProgress.mediaItemId}\" but its progress (currentTime=${mediaProgress.currentTime}) is behind local (currentTime=${localMediaProgress.currentTime}) - pushing local progress to server instead of overwriting it")
                pushLocalProgressToServer(localMediaProgress)
              }
              numLocalMediaProgressUpdated++
            } else if (localMediaProgress.lastUpdate > mediaProgress.lastUpdate) {
              var pushedSomething = false
              if (localMediaProgress.ebookLocation != null && localMediaProgress.ebookLocation != mediaProgress.ebookLocation) {
                // Patch ebook progress to server
                AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Local progress for ebook item \"${mediaProgress.mediaItemId}\" is more recent than server progress. Local progress last updated ${localMediaProgress.lastUpdate}, server progress last updated ${mediaProgress.lastUpdate}. Sending server request to update ebook progress from ${mediaProgress.ebookProgress} to ${localMediaProgress.ebookProgress}")
                val endpoint = "/api/me/progress/${localMediaProgress.libraryItemId}"
                val updatePayload = JSObject()
                updatePayload.put("ebookLocation", localMediaProgress.ebookLocation)
                updatePayload.put("ebookProgress", localMediaProgress.ebookProgress)
                updatePayload.put("lastUpdate", localMediaProgress.lastUpdate)
                patchRequest(endpoint,updatePayload) {
                  AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Successfully updated server ebook progress for item item \"${mediaProgress.mediaItemId}\"")
                }
                pushedSomething = true
              }
              if (localMediaProgress.currentTime != mediaProgress.currentTime || localMediaProgress.isFinished != mediaProgress.isFinished) {
                AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Local progress for item \"${mediaProgress.mediaItemId}\" is more recent than server progress. Sending server request to update currentTime from ${mediaProgress.currentTime} to ${localMediaProgress.currentTime}")
                pushLocalProgressToServer(localMediaProgress)
                pushedSomething = true
              }
              if (!pushedSomething) numLocalMediaProgressUptToDate++ else numLocalMediaProgressUpdated++
            } else {
              numLocalMediaProgressUptToDate++
            }
          }
        }

        AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Finishing syncing local media progress with server. $numLocalMediaProgressUptToDate up-to-date, $numLocalMediaProgressUpdated updated")
      }
      cb()
    }
  }

  private fun pullServerProgressToLocal(mediaProgress: MediaProgress, localMediaProgress: LocalMediaProgress) {
    val updateLogs = mutableListOf<String>()
    if (mediaProgress.progress != localMediaProgress.progress) {
      updateLogs.add("Updated progress from ${localMediaProgress.progress} to ${mediaProgress.progress}")
    }
    if (mediaProgress.currentTime != localMediaProgress.currentTime) {
      updateLogs.add("Updated currentTime from ${localMediaProgress.currentTime} to ${mediaProgress.currentTime}")
    }
    if (mediaProgress.isFinished != localMediaProgress.isFinished) {
      updateLogs.add("Updated isFinished from ${localMediaProgress.isFinished} to ${mediaProgress.isFinished}")
    }
    if (mediaProgress.ebookProgress != localMediaProgress.ebookProgress) {
      updateLogs.add("Updated ebookProgress from ${localMediaProgress.isFinished} to ${mediaProgress.isFinished}")
    }
    if (updateLogs.isNotEmpty()) {
      AbsLogger.info("ApiHandler", "syncLocalMediaProgressForUser: Server progress for item \"${mediaProgress.mediaItemId}\" is more recent than local (server lastUpdate=${mediaProgress.lastUpdate}, local lastUpdate=${localMediaProgress.lastUpdate}). ${updateLogs.joinToString()}")
    }

    localMediaProgress.updateFromServerMediaProgress(mediaProgress)

    // Only report sync if progress changed
    if (updateLogs.isNotEmpty()) {
      MediaEventManager.syncEvent(mediaProgress, "Sync on server connection")
    }
    DeviceManager.dbManager.saveLocalMediaProgressLocked(localMediaProgress)
  }

  // Pushes local audio progress (currentTime/progress/isFinished/duration) to the server -
  // the audio-progress counterpart to the ebook-location patch already done inline above.
  private fun pushLocalProgressToServer(localMediaProgress: LocalMediaProgress) {
    val libraryItemId = localMediaProgress.libraryItemId
    if (libraryItemId == null) {
      AbsLogger.error("ApiHandler", "pushLocalProgressToServer: Local media progress \"${localMediaProgress.id}\" has no server libraryItemId, cannot push")
      return
    }
    val updatePayload = JSObject()
    updatePayload.put("currentTime", localMediaProgress.currentTime)
    updatePayload.put("progress", localMediaProgress.progress)
    updatePayload.put("isFinished", localMediaProgress.isFinished)
    updatePayload.put("duration", localMediaProgress.duration)
    updatePayload.put("lastUpdate", localMediaProgress.lastUpdate)
    updateMediaProgress(libraryItemId, localMediaProgress.episodeId, updatePayload) {
      AbsLogger.info("ApiHandler", "pushLocalProgressToServer: Successfully pushed local progress to server for item \"$libraryItemId\"")
    }
  }
}
