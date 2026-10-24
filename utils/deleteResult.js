// The message for a failed delete of a downloaded item or track, from the native reason (AbsFileSystem.deleteItem /
// deleteTrackFromItem, FolderFileDeletion.Detail). Nothing was deleted, except for 'partial'; the item and its progress
// are kept in every case.
const MESSAGES = {
  folderAccess: 'MessageDeleteFolderAccessLost', // the app's access to the selected folder was revoked
  folderMissing: 'MessageDeleteFolderMissing', // access is granted but the folder or its storage cannot be found
  storageUnavailable: 'LabelDownloadStorageUnavailable', // the app's own SD card storage is out
  unsupported: 'MessageDeleteUnsupported', // Android 7: no way to confirm a document is inside the folder
  notAFile: 'MessageDeleteNotAFile',
  unverified: 'MessageDeleteUnverified',
  deleteFailed: 'MessageDeleteFailed',
  partial: 'MessageDeletePartial',
  partialFolderAccess: 'MessageDeletePartialAccessLost', // some files went, then access to the folder was lost
  partialFolderMissing: 'MessageDeletePartialFolderMissing', // some files went, then the folder or its storage vanished
  shared: 'MessageDeleteShared'
}

export function deleteFailureMessage(res, strings) {
  const key = MESSAGES[res && res.reason]
  return (key && strings[key]) || strings.MessageDeleteFailed || 'Failed to delete'
}
