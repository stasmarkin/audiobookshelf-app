package app.absplus.android.plugins

import android.app.AlertDialog
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.SimpleStorage
import com.anggrayudi.storage.callback.FolderPickerCallback
import com.anggrayudi.storage.callback.StorageAccessCallback
import com.anggrayudi.storage.file.*
import app.absplus.android.MainActivity
import app.absplus.android.data.LocalFolder
import app.absplus.android.data.LocalLibraryItem
import app.absplus.android.device.DeviceManager
import app.absplus.android.device.FolderAccess
import app.absplus.android.device.FolderFileDeletion
import app.absplus.android.device.FolderScanner
import app.absplus.android.device.LocalItemDeleter
import app.absplus.android.device.RescanError
import app.absplus.android.device.RescanGuard
import app.absplus.android.device.RescanOutcome
import app.absplus.android.device.RescanPhase
import app.absplus.android.device.RescanProgress
import app.absplus.android.device.loadRescanCatalog
import app.absplus.android.server.ApiHandler
import app.absplus.android.services.StorageMoveService
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.getcapacitor.*
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONArray

@CapacitorPlugin(name = "AbsFileSystem")
class AbsFileSystem : Plugin() {
  private val TAG = "AbsFileSystem"
  private val tag = "AbsFileSystem"
  private var jacksonMapper =
          jacksonObjectMapper()
                  .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature())

  lateinit var mainActivity: MainActivity
  lateinit var apiHandler: ApiHandler
  lateinit var folderScanner: FolderScanner

  override fun load() {
    mainActivity = (activity as MainActivity)
    apiHandler = ApiHandler(mainActivity)
    folderScanner = FolderScanner(context)
    StorageMoveService.movedListener = {
      notifyListeners("onLocalLibraryItemMoved", JSObject(jacksonMapper.writeValueAsString(it)))
    }

    mainActivity.storage.storageAccessCallback =
            object : StorageAccessCallback {
              override fun onRootPathNotSelected(
                      requestCode: Int,
                      rootPath: String,
                      uri: Uri,
                      selectedStorageType: StorageType,
                      expectedStorageType: StorageType
              ) {
                Log.d(TAG, "STORAGE ACCESS CALLBACK")
              }

              override fun onCanceledByUser(requestCode: Int) {
                Log.d(TAG, "STORAGE ACCESS CALLBACK")
              }

              override fun onExpectedStorageNotSelected(
                      requestCode: Int,
                      selectedFolder: DocumentFile,
                      selectedStorageType: StorageType,
                      expectedBasePath: String,
                      expectedStorageType: StorageType
              ) {
                Log.d(TAG, "STORAGE ACCESS CALLBACK")
              }

              override fun onStoragePermissionDenied(requestCode: Int) {
                Log.d(TAG, "STORAGE ACCESS CALLBACK")
              }

              override fun onRootPathPermissionGranted(requestCode: Int, root: DocumentFile) {
                Log.d(TAG, "STORAGE ACCESS CALLBACK")
              }
            }
  }

  @PluginMethod
  fun setFolderPickerStrings(call: PluginCall) {
    mainActivity.getSharedPreferences(FOLDER_PICKER_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_WRITE_ACCESS_REQUIRED, call.getString("writeAccessRequired"))
            .putString(KEY_ALLOW, call.getString("allow"))
            .putString(KEY_CANCEL, call.getString("cancel"))
            .putString(KEY_ACCESS_DENIED, call.getString("accessDenied"))
            .putString(KEY_PERMISSION_DENIED, call.getString("permissionDenied"))
            .putString(KEY_MANAGED_FOLDER_WARNING, call.getString("managedFolderWarning"))
            .apply()
    call.resolve()
  }

  @PluginMethod
  fun selectFolder(call: PluginCall) {
    val mediaType = call.data.getString("mediaType", "book").toString()
    val REQUEST_CODE_SELECT_FOLDER = 6
    val REQUEST_CODE_SDCARD_ACCESS = 7

    mainActivity.storage.folderPickerCallback =
            object : FolderPickerCallback {
              override fun onCanceledByUser(requestCode: Int) {
                call.resolve(JSObject().put("error", folderPickerString(KEY_CANCEL, DEFAULT_CANCEL)).put("errorCode", "canceled"))
              }

              override fun onFolderSelected(requestCode: Int, folder: DocumentFile) {
                Log.d(TAG, "ON FOLDER SELECTED ${folder.uri} ${folder.name}")
                val absolutePath = folder.getAbsolutePath(activity)
                val storageType = folder.getStorageType(activity)
                val basePath = folder.getBasePath(activity)
                val folderId =
                        android.util.Base64.encodeToString(
                                folder.id.toByteArray(),
                                android.util.Base64.DEFAULT
                        )

                val localFolder =
                        LocalFolder(
                                folderId,
                                folder.name ?: "",
                                folder.uri.toString(),
                                basePath,
                                absolutePath,
                                storageType.toString(),
                                mediaType
                        )

                DeviceManager.dbManager.saveLocalFolder(localFolder)
                call.resolve(JSObject(jacksonMapper.writeValueAsString(localFolder)))
              }

              override fun onStorageAccessDenied(
                      requestCode: Int,
                      folder: DocumentFile?,
                      storageType: StorageType,
                      storageId: String
              ) {
                Log.e(tag, "Storage Access Denied ${folder?.getAbsolutePath(mainActivity)}")

                val jsobj = JSObject()
                if (requestCode == REQUEST_CODE_SELECT_FOLDER) {

                  val builder: AlertDialog.Builder = AlertDialog.Builder(mainActivity)
                  builder.setMessage(folderPickerString(KEY_WRITE_ACCESS_REQUIRED, DEFAULT_WRITE_ACCESS_REQUIRED))
                  builder.setNegativeButton(folderPickerString(KEY_CANCEL, DEFAULT_CANCEL)) { _, _ ->
                    run {
                      jsobj.put("error", folderPickerString(KEY_ACCESS_DENIED, DEFAULT_ACCESS_DENIED))
                      call.resolve(jsobj)
                    }
                  }
                  builder.setPositiveButton(folderPickerString(KEY_ALLOW, DEFAULT_ALLOW)) { _, _ ->
                    mainActivity.storageHelper.requestStorageAccess(
                            REQUEST_CODE_SDCARD_ACCESS,
                            initialPath = FileFullPath(mainActivity, storageId, "")
                    )
                  }
                  builder.show()
                } else {
                  Log.d(TAG, "STORAGE ACCESS DENIED $requestCode")
                  jsobj.put("error", folderPickerString(KEY_ACCESS_DENIED, DEFAULT_ACCESS_DENIED))
                  call.resolve(jsobj)
                }
              }

              override fun onStoragePermissionDenied(requestCode: Int) {
                Log.d(TAG, "STORAGE PERMISSION DENIED $requestCode")
                val jsobj = JSObject()
                jsobj.put("error", folderPickerString(KEY_PERMISSION_DENIED, DEFAULT_PERMISSION_DENIED))
                call.resolve(jsobj)
              }
            }

    mainActivity.runOnUiThread {
      AlertDialog.Builder(mainActivity)
              .setMessage(folderPickerString(KEY_MANAGED_FOLDER_WARNING, DEFAULT_MANAGED_FOLDER_WARNING))
              .setPositiveButton(folderPickerString(KEY_ALLOW, DEFAULT_ALLOW)) { _, _ ->
                mainActivity.storage.openFolderPicker(REQUEST_CODE_SELECT_FOLDER)
              }
              .setNegativeButton(folderPickerString(KEY_CANCEL, DEFAULT_CANCEL)) { _, _ ->
                call.resolve(JSObject().put("error", folderPickerString(KEY_CANCEL, DEFAULT_CANCEL)).put("errorCode", "canceled"))
              }
              .setOnCancelListener { call.resolve(JSObject().put("error", folderPickerString(KEY_CANCEL, DEFAULT_CANCEL)).put("errorCode", "canceled")) }
              .show()
    }
  }

  @RequiresApi(Build.VERSION_CODES.R)
  @PluginMethod
  fun requestStoragePermission(call: PluginCall) {
    Log.d(TAG, "Request Storage Permissions")
    mainActivity.storageHelper.requestStorageAccess()
    call.resolve()
  }

  @PluginMethod
  fun checkStoragePermission(call: PluginCall) {
    val res: Boolean
    if (Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P) {
      res = SimpleStorage.hasStoragePermission(context)
      Log.d(TAG, "checkStoragePermission: Check Storage Access $res")
    } else {
      Log.d(TAG, "checkStoragePermission: Has permission on Android 10 or up")
      res = true
    }

    val jsobj = JSObject()
    jsobj.put("value", res)
    call.resolve(jsobj)
  }

  @PluginMethod
  fun checkFolderPermissions(call: PluginCall) {
    val folderUrl = call.data.getString("folderUrl", "").toString()
    Log.d(TAG, "Check Folder Permissions for $folderUrl")

    // The saved folder supplies the file path the storage check needs (the URL alone always failed)
    val folder = FolderAccess.folderForUrl(folderUrl, DeviceManager.dbManager.getAllLocalFolders())
    val hasAccess = FolderAccess.canWrite(context, folder)

    val jsobj = JSObject()
    jsobj.put("value", hasAccess)
    call.resolve(jsobj)
  }

  @PluginMethod
  fun getSDKVersion(call: PluginCall) {
    val jsObject = JSObject()
    jsObject.put("version", Build.VERSION.SDK_INT)
    call.resolve(jsObject)
  }

  @PluginMethod
  fun rescanFolder(call: PluginCall) {
    val folderId = call.data.getString("folderId", "").toString()
    // The library selected in the app: the scope for author/title matches without a cover-file id
    val currentLibraryId = call.data.getString("libraryId")?.takeIf { it.isNotEmpty() }
    val localFolder = DeviceManager.dbManager.getLocalFolder(folderId)
    if (localFolder == null) {
      call.resolve(JSObject("{\"error\":\"Folder not found\"}"))
      return
    }

    // Rescan matches books only (podcast episodes in a shared folder are a different problem)
    if (localFolder.mediaType != "book") {
      call.resolve(JSObject().put("error", RescanError.UNSUPPORTED))
      return
    }

    if (!rescanGuard.tryStart(folderId)) {
      call.resolve(JSObject().put("error", RescanError.RUNNING))
      return
    }

    // Sends the final event and resolves the call exactly once, then releases the folder
    var finished = false
    var checked = 0
    var total = -1
    var lastProgressAt = 0L
    fun finish(outcome: RescanOutcome<LocalLibraryItem>) {
      synchronized(this) {
        if (finished) return
        finished = true
      }
      rescanGuard.finish(folderId)
      val phase = if (outcome.error == null) RescanPhase.COMPLETE else RescanPhase.FAILED
      notifyRescanProgress(
              RescanProgress(folderId, phase, checked, total, outcome.matched.size, outcome.unmatched.size, error = outcome.error),
              null
      )
      val jsobj = JSObject()
      jsobj.put("matched", outcome.matched.size)
      jsobj.put("unmatched", JSONArray(outcome.unmatched))
      jsobj.put("ambiguous", JSONArray(outcome.ambiguous))
      jsobj.put("relinked", outcome.relinked.size)
      outcome.error?.let { jsobj.put("error", it) }
      call.resolve(jsobj)
    }
    fun fail(error: String) = finish(RescanOutcome(emptyList(), emptyList(), error))

    try {
      notifyRescanProgress(RescanProgress(folderId, RescanPhase.LOADING), null)
      loadRescanCatalog(apiHandler::getLibrariesOrNull, { it.id }, apiHandler::getAllLibraryItemsOrNull) { catalog ->
        if (catalog == null) {
          fail(RescanError.CATALOG)
          return@loadRescanCatalog
        }
        try {
          folderScanner.rescanFolder(
                  localFolder,
                  catalog,
                  currentLibraryId,
                  { libraryItemId, cb -> apiHandler.getLibraryItem(libraryItemId, cb) },
                  { progress, item ->
                    checked = progress.checked
                    total = progress.total
                    // Unmatched folders resolve in microseconds: send their counts at most every
                    // 100ms; matches, the first event and the last folder are always sent
                    val now = System.currentTimeMillis()
                    if (item != null || progress.checked == 0 || progress.checked == progress.total || now - lastProgressAt >= 100) {
                      lastProgressAt = now
                      notifyRescanProgress(progress, item)
                    }
                  },
                  ::finish
          )
        } catch (e: Exception) {
          Log.e(tag, "rescanFolder failed", e)
          fail(RescanError.UNEXPECTED)
        }
      }
    } catch (e: Exception) {
      Log.e(tag, "rescanFolder failed", e)
      fail(RescanError.UNEXPECTED)
    }
  }

  /** Whether a rescan of the folder is running, so a reopened folder page can show it before its next event. */
  @PluginMethod
  fun isRescanning(call: PluginCall) {
    val folderId = call.data.getString("folderId", "").toString()
    call.resolve(JSObject().put("value", rescanGuard.isRunning(folderId)))
  }

  /** Sends "onRescanProgress"; [item] is the local item just matched, already saved. */
  private fun notifyRescanProgress(progress: RescanProgress, item: LocalLibraryItem?) {
    try {
      val data = JSObject(jacksonMapper.writeValueAsString(progress))
      if (item != null) data.put("localLibraryItem", JSObject(jacksonMapper.writeValueAsString(item)))
      notifyListeners("onRescanProgress", data)
    } catch (e: Exception) {
      // Progress is informational; the call still resolves with the result
      Log.e(tag, "onRescanProgress failed", e)
    }
  }

  @PluginMethod
  fun removeFolder(call: PluginCall) {
    val folderId = call.data.getString("folderId", "").toString()
    DeviceManager.dbManager.removeLocalFolder(folderId)
    call.resolve()
  }

  @PluginMethod
  fun removeLocalLibraryItem(call: PluginCall) {
    val localLibraryItemId = call.data.getString("localLibraryItemId", "").toString()
    DeviceManager.dbManager.removeLocalLibraryItem(localLibraryItemId)
    call.resolve()
  }

  @PluginMethod
  fun deleteItem(call: PluginCall) {
    val localLibraryItemId = call.data.getString("id", "").toString()
    Log.d(tag, "deleteItem $localLibraryItemId")
    // Why it did not happen, for the message (utils/deleteResult.js): folder access lost, folder missing, SD card out, ...
    var detail: FolderFileDeletion.Detail? = null
    when (LocalItemDeleter.deleteItem(mainActivity, localLibraryItemId, auto = false, onDetail = { detail = it })) {
      LocalItemDeleter.Outcome.DELETED -> call.resolve(JSObject("{\"success\":true}"))
      LocalItemDeleter.Outcome.STORAGE_UNAVAILABLE -> call.resolve(deleteFailure(detail ?: FolderFileDeletion.Detail.STORAGE_UNAVAILABLE))
      else -> call.resolve(deleteFailure(detail ?: FolderFileDeletion.Detail.DELETE_FAILED))
    }
  }

  private fun deleteFailure(detail: FolderFileDeletion.Detail) = JSObject().put("success", false).put("reason", detail.reason)

  @PluginMethod
  fun deleteTrackFromItem(call: PluginCall) {
    val localLibraryItemId = call.data.getString("id", "").toString()
    val trackLocalFileId = call.data.getString("trackLocalFileId", "").toString()
    // Optional: which local episode the user is removing (a file shared by two episodes then keeps its audio, R29-N02)
    val localEpisodeId = call.data.getString("localEpisodeId")
    Log.d(tag, "deleteTrackFromItem $localLibraryItemId $trackLocalFileId")

    var detail: FolderFileDeletion.Detail? = null
    when (val result = LocalItemDeleter.deleteTrack(mainActivity, localLibraryItemId, trackLocalFileId, localEpisodeId, onDetail = { detail = it })) {
      is LocalItemDeleter.TrackResult.Deleted -> {
        // The episode's record is gone with its file: so is its local progress (not the server's or the saved sessions')
        if (result.episodeGone) DeviceManager.dbManager.removeLocalMediaProgressFor(localLibraryItemId, localEpisodeId)
        call.resolve(JSObject(jacksonMapper.writeValueAsString(result.item)))
      }
      LocalItemDeleter.TrackResult.RefusedShared -> {
        Log.e(tag, "deleteTrackFromItem: file is shared with another episode; nothing deleted")
        call.resolve(JSObject("{\"success\":false,\"reason\":\"shared\"}"))
      }
      LocalItemDeleter.TrackResult.Failed -> call.resolve(deleteFailure(detail ?: FolderFileDeletion.Detail.DELETE_FAILED))
    }
  }

  private fun folderPickerString(key: String, defaultValue: String): String =
          mainActivity.getSharedPreferences(FOLDER_PICKER_PREFERENCES, Context.MODE_PRIVATE)
                  .getString(key, defaultValue) ?: defaultValue

  private companion object {
    const val FOLDER_PICKER_PREFERENCES = "folder_picker"
    const val KEY_WRITE_ACCESS_REQUIRED = "write_access_required"
    const val KEY_ALLOW = "allow"
    const val KEY_CANCEL = "cancel"
    const val KEY_ACCESS_DENIED = "access_denied"
    const val KEY_PERMISSION_DENIED = "permission_denied"
    const val KEY_MANAGED_FOLDER_WARNING = "managed_folder_warning"
    const val DEFAULT_MANAGED_FOLDER_WARNING =
            "Choose a dedicated download folder. Audiobookshelf+ manages downloaded and temporary files here and may replace or remove them. Do not select a folder containing unrelated or irreplaceable data."
    const val DEFAULT_WRITE_ACCESS_REQUIRED =
            "You do not have write access to this folder. Would you like to grant access?"
    const val DEFAULT_ALLOW = "Allow"
    const val DEFAULT_CANCEL = "Cancel"
    const val DEFAULT_ACCESS_DENIED = "Access denied"
    const val DEFAULT_PERMISSION_DENIED = "Permission denied"

    // Process-wide: a rescan keeps running if the plugin is reloaded with a new bridge
    val rescanGuard = RescanGuard()
  }
}
