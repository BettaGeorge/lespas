package site.leos.apps.lespas.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.app.Application
import android.content.*
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.preference.PreferenceManager
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import com.thegrizzlylabs.sardineandroid.impl.SardineException
import okhttp3.OkHttpClient
import site.leos.apps.lespas.R
import site.leos.apps.lespas.album.Album
import site.leos.apps.lespas.album.AlbumRepository
import site.leos.apps.lespas.helper.Tools
import site.leos.apps.lespas.photo.Photo
import site.leos.apps.lespas.photo.PhotoRepository
import site.leos.apps.lespas.settings.SettingsFragment
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.LocalDateTime
import java.util.stream.Collectors
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.xml.namespace.QName

class SyncAdapter @JvmOverloads constructor(private val application: Application, autoInitialize: Boolean, allowParallelSyncs: Boolean = false
) : AbstractThreadedSyncAdapter(application.baseContext, autoInitialize, allowParallelSyncs){

    override fun onPerformSync(account: Account, extras: Bundle, authority: String, provider: ContentProviderClient, syncResult: SyncResult) {
        try {
            val order = extras.getInt(ACTION)   // Return 0 when no mapping of ACTION found
            val resourceRoot: String
            var dcimRoot: String
            val localRootFolder: String
            val sp = PreferenceManager.getDefaultSharedPreferences(application)
            val wifionlyKey = application.getString(R.string.wifionly_pref_key)
            val sardine: Sardine

            // Initialize sardine library
            AccountManager.get(application).run {
                val userName = getUserData(account, context.getString(R.string.nc_userdata_username))
                val serverRoot = getUserData(account, context.getString(R.string.nc_userdata_server))
                val selfSigned = getUserData(account, context.getString(R.string.nc_userdata_selfsigned)).toBoolean()
                sardine = if (selfSigned) {
                    val builder = OkHttpClient.Builder().apply {
                        hostnameVerifier { _, _ -> true }
                    }
                    OkHttpSardine(builder.build())
                } else OkHttpSardine()
                sardine.setCredentials(userName, peekAuthToken(account, serverRoot), true)
                application.getString(R.string.lespas_base_folder_name).run {
                    resourceRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}$userName$this"
                    localRootFolder = "${application.filesDir}$this"
                }
                dcimRoot = "$serverRoot${application.getString(R.string.dav_files_endpoint)}${userName}/DCIM"
            }

            // Check network type
            if (sp.getBoolean(wifionlyKey, true)) {
                if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                    syncResult.hasSoftError()
                    return
                }
            }

            // Make sure lespas base directory is there, and it's really a nice moment to test server connectivity
            if (!sardine.exists(resourceRoot)) {
                sardine.createDirectory(resourceRoot)
                return
            }

            val albumRepository = AlbumRepository(application)
            val photoRepository = PhotoRepository(application)
            val actionRepository = ActionRepository(application)

            // Processing pending actions
            //if (order == SYNC_LOCAL_CHANGES) {
                //Log.e("**********", "sync local changes")
                actionRepository.getAllPendingActions().forEach { action ->
                    // Check network type on every loop, so that user is able to stop sync right in the middle
                    if (sp.getBoolean(wifionlyKey, true)) {
                        if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                            syncResult.hasSoftError()
                            return
                        }
                    }

                    // Do not do too many works here, as the local sync should be as simple as making several webdav calls, so that if any thing bad happen, we will be catched by
                    // exceptions handling down below, and start again right here in later sync, e.g. atomic
                    try {
                        when (action.action) {
                            Action.ACTION_DELETE_FILES_ON_SERVER -> {
                                sardine.delete("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}")
                                // TODO need to update album's etag to reduce network usage during next remote sync
                            }
                            Action.ACTION_DELETE_DIRECTORY_ON_SERVER -> {
                                sardine.delete("$resourceRoot/${Uri.encode(action.folderName)}")
                            }
                            Action.ACTION_ADD_FILES_ON_SERVER -> {
                                // Upload to server and verify
                                //Log.e("++++++++", "uploading $resourceRoot/${action.folderName}/${action.fileName}")
                                // MIME type is passed in folderId property
                                val localFile = File(localRootFolder, action.fileName)
                                if (localFile.exists()) sardine.put("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileId)}", localFile, action.folderId)
                                //Log.e("****", "Uploaded ${action.fileName}")
                                // TODO shall we update local database here or leave it to next SYNC_REMOTE_CHANGES round?
                            }
                            Action.ACTION_UPDATE_FILE -> {
                                // MIME type is passed in folderId property
                                val localFile = File(localRootFolder, action.fileName)
                                if (localFile.exists()) sardine.put("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}", localFile, action.folderId)
                            }
                            Action.ACTION_ADD_DIRECTORY_ON_SERVER -> {
                                with("$resourceRoot/${Uri.encode(action.folderName)}") {
                                    try {
                                        sardine.createDirectory(this)
                                    } catch(e: SardineException) {
                                        when(e.statusCode) {
                                            // Should catch status code 405 here to ignore folder already exists on server
                                            405-> {}
                                            else-> {
                                                Log.e("****SardineException: ", e.stackTraceToString())
                                                syncResult.stats.numIoExceptions++
                                                return
                                            }
                                        }
                                    }
                                    sardine.list(this, JUST_FOLDER_DEPTH, NC_PROPFIND_PROP)[0].customProps[OC_UNIQUE_ID]?.let {
                                        // fix album id for new album and photos create on local, put back the cover id in album row so that it will show up in album list
                                        // mind that we purposely leave the eTag column empty
                                        photoRepository.fixNewPhotosAlbumId(action.folderId, it)
                                        albumRepository.fixNewLocalAlbumId(action.folderId, it, action.fileName)
                                    }
                                }
                            }
                            Action.ACTION_MODIFY_ALBUM_ON_SERVER -> {
                                TODO()
                            }
                            Action.ACTION_RENAME_DIRECTORY -> {
                                // Action's folderName property is the old name, fileName property is the new name
                                sardine.move("$resourceRoot/${Uri.encode(action.folderName)}", "$resourceRoot/${Uri.encode(action.fileName)}")
                                //albumRepository.changeName(action.folderId, action.fileName)
                            }
                            Action.ACTION_RENAME_FILE -> {
                                // Action's fileId property is the old name, fileName property is the new name
                                sardine.move("$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileId)}", "$resourceRoot/${Uri.encode(action.folderName)}/${Uri.encode(action.fileName)}")
                            }
                        }
                    } catch (e: SardineException) {
                        Log.e("****SardineException: ", e.stackTraceToString())
                        when(e.statusCode) {
                            400, 404, 405, 406, 410-> {
                                // create file in non-existed folder, target not found, target readonly, target already existed, etc. should be skipped and move onto next action
                            }
                            401, 403, 407-> {
                                syncResult.stats.numAuthExceptions++
                                return
                            }
                            409-> {
                                syncResult.stats.numConflictDetectedExceptions++
                                return
                            }
                            423-> {
                                // Interrupted upload will locked file on server, backoff 90 seconds so that lock gets cleared on server
                                syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 90
                                return
                            }
                            else-> {
                                // Other unhandled error should be retried
                                syncResult.stats.numIoExceptions++
                                return
                            }
                        }
                    }

                    // TODO: Error retry strategy, directory etag update, etc.
                    actionRepository.deleteSync(action)
                }

            //} else {
                //Log.e("**********", "sync remote changes")
                val changedAlbums: MutableList<Album> = mutableListOf()
                val remoteAlbumIds = arrayListOf<String>()
                var remoteAlbumId: String
                // Merge changed and/or new album from server
                var localAlbum: List<Album>
                sardine.list(resourceRoot, FOLDER_CONTENT_DEPTH, NC_PROPFIND_PROP).drop(1).forEach { remoteAlbum ->     // Drop the first one in the list, which is the parent folder itself
                    remoteAlbumId = remoteAlbum.customProps[OC_UNIQUE_ID]!!
                    if (remoteAlbum.isDirectory) {
                        remoteAlbumIds.add(remoteAlbumId)

                        localAlbum = albumRepository.getThisAlbum(remoteAlbumId)
                        if (localAlbum.isNotEmpty()) {
                            // We have hit in local table, which means it's a existing album
                            if (localAlbum[0].eTag != remoteAlbum.etag) {
                                // eTag mismatched, this album changed on server
                                changedAlbums.add(
                                    Album(
                                        remoteAlbumId,    // Either local or remote version is fine
                                        remoteAlbum.name,   // Use remote version, since it might be changed on server
                                        localAlbum[0].startDate,    // Preserve local data
                                        localAlbum[0].endDate,  // Preserve local data
                                        localAlbum[0].cover,    // Preserve local data
                                        localAlbum[0].coverBaseline,    // Preserve local data
                                        localAlbum[0].coverWidth,
                                        localAlbum[0].coverHeight,
                                        Tools.dateToLocalDateTime(remoteAlbum.modified),  // Use remote version
                                        localAlbum[0].sortOrder,    // Preserve local data
                                        remoteAlbum.etag,   // Use remote version
                                        0,       // TODO share
                                        1f
                                    )
                                )
                            } else {
                                // Rename operation on server would not change item's own eTag, have to sync name changes here
                                if (localAlbum[0].name != remoteAlbum.name) albumRepository.changeName(remoteAlbumId, remoteAlbum.name)
                            }
                        } else {
                            // No hit at local, a new album created on server
                            changedAlbums.add(Album(remoteAlbumId, remoteAlbum.name, LocalDateTime.MAX, LocalDateTime.MIN, "", 0, 0, 0,
                                Tools.dateToLocalDateTime(remoteAlbum.modified), Album.BY_DATE_TAKEN_ASC, remoteAlbum.etag, 0, 1f)
                            )
                        }
                    }
                }

                // Delete those albums not exist on server, happens when user delete album on the server. Should skip local added new albums, e.g. those with cover column empty
                val localAlbumIdAndCover = albumRepository.getAllAlbumIds()
                for (local in localAlbumIdAndCover) {
                    if (!remoteAlbumIds.contains(local.id) && local.cover.isNotEmpty()) {
                        albumRepository.deleteByIdSync(local.id)
                        val allPhotoIds = photoRepository.getAllPhotoIdsByAlbum(local.id)
                        photoRepository.deletePhotosByAlbum(local.id)
                        allPhotoIds.forEach {
                            try {
                                File(localRootFolder, it.id).delete()
                            } catch (e: Exception) { e.printStackTrace() }
                            try {
                                File(localRootFolder, it.name).delete()
                            } catch(e: Exception) { e.printStackTrace() }
                        }
                        //Log.e("****", "Deleted album: ${local.id}")
                    }
                }

                if (changedAlbums.isNotEmpty()) {
                    // Sync each changed album
                    val changedPhotos = mutableListOf<Photo>()
                    val remotePhotoIds = mutableListOf<String>()
                    var tempAlbum: Album

                    for (changedAlbum in changedAlbums) {
                        // Check network type on every loop, so that user is able to stop sync right in the middle
                        if (sp.getBoolean(wifionlyKey, true)) {
                            if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                                syncResult.hasSoftError()
                                return
                            }
                        }

                        tempAlbum = changedAlbum.copy(eTag = "")

                        val localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                        val localPhotoNames = photoRepository.getNamesMap(changedAlbum.id)
                        val localPhotoNamesReverse = localPhotoNames.entries.stream().collect(Collectors.toMap({ it.value }) { it.key })
                        var remotePhotoId: String
                        var localImageFileName: String

                        // Create changePhotos list
                        sardine.list("$resourceRoot/${Uri.encode(changedAlbum.name)}", FOLDER_CONTENT_DEPTH, NC_PROPFIND_PROP).drop(1).forEach { remotePhoto ->
                            if (remotePhoto.contentType.startsWith("image/", true) || remotePhoto.contentType.startsWith("video/", true)) {
                                remotePhotoId = remotePhoto.customProps[OC_UNIQUE_ID]!!
                                // Accumulate remote photos list
                                remotePhotoIds.add(remotePhotoId)

                                if (localPhotoETags[remotePhotoId] != remotePhoto.etag) { // Also matches newly created photo id from server, e.g. no such remotePhotoId in local table
                                    //Log.e("=======", "updating photo: ${remotePhoto.name} r_etag:${remotePhoto.etag} l_etag:${localPhotoETags[remotePhotoId]}")

                                    //if (localPhotoETags.containsKey(remotePhoto.name)) {
                                    if (File(localRootFolder, remotePhoto.name).exists()) {
                                        // If there is local file with remote photo's name, that means it's a local added photo which is now coming back from server.
                                        //Log.e("<><><>", "coming back now ${remotePhoto.name}")
                                        // Rename image file name to fileid
                                        try {
                                            // If this file has already being uploaded,
                                            File(localRootFolder, remotePhotoId).delete()
                                        } catch (e: Exception) { Log.e("****Exception: ", e.stackTraceToString()) }
                                        try {
                                            File(localRootFolder, remotePhoto.name).renameTo(File(localRootFolder, remotePhotoId))
                                            //Log.e("****", "rename ${remotePhoto.name} to $remotePhotoId")
                                        } catch (e: Exception) { Log.e("****Exception: ", e.stackTraceToString()) }

                                        localPhotoNamesReverse[remotePhoto.name]?.apply {
                                            // Update it's id to the real fileId and also eTag now
                                            photoRepository.fixPhoto(this, remotePhotoId, remotePhoto.name, remotePhoto.etag, Tools.dateToLocalDateTime(remotePhoto.modified))
                                            // Taking care the cover
                                            // TODO: Condition race here, e.g. user changes this album's cover right at this very moment
                                            if (changedAlbum.cover == this) {
                                                //Log.e("=======", "fixing cover from ${changedAlbum.cover} to $remotePhotoId")
                                                albumRepository.fixCoverId(changedAlbum.id, remotePhotoId)
                                                changedAlbum.cover = remotePhotoId
                                            }
                                        }
                                    } else {
                                        // Either a new photo created on server or an existing photo updated on server
                                        // TODO will share status change create new eTag?
                                        changedPhotos.add(
                                            Photo(remotePhotoId, changedAlbum.id, remotePhoto.name, remotePhoto.etag, LocalDateTime.now(), Tools.dateToLocalDateTime(remotePhoto.modified), 0, 0,
                                                remotePhoto.contentType, 0
                                            )
                                        )
                                    }
                                } else if (localPhotoNames[remotePhotoId] != remotePhoto.name) {
                                    // Rename operation on server would not change item's own eTag, have to sync name changes here. The positive side is avoiding fetching the actual
                                    // file again from server
                                    photoRepository.changeName(remotePhotoId, remotePhoto.name)
                                }
                            }
                        }

                        // Get first JPEG or PNG file, only these two format can be set as coverart because they are supported by BitmapRegionDecoder
                        // If we just can't find one single photo of these two formats in this new album, fall back to the first one in the list, cover will be shown as placeholder
                        var validCover = changedPhotos.indexOfFirst { it.mimeType == "image/jpeg" || it.mimeType == "image/png" }
                        if (validCover == -1) validCover = 0

                        // Fetch changed photo files, extract EXIF info, update Photo table
                        changedPhotos.forEachIndexed {i, changedPhoto->
                            // Check network type on every loop, so that user is able to stop sync right in the middle
                            if (sp.getBoolean(wifionlyKey, true)) {
                                if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                                    syncResult.hasSoftError()
                                    return
                                }
                            }

                            // Prepare the image file
                            localImageFileName = localPhotoNames.getOrDefault(changedPhoto.id, changedPhoto.name)
                            if (File(localRootFolder, localImageFileName).exists()) {
                                // If image file with 'name' exists, replace the old file with this
                                try {
                                    File(localRootFolder, changedPhoto.id).delete()
                                } catch(e: Exception) { Log.e("****Exception: ", e.stackTraceToString())}
                                try {
                                    File(localRootFolder, localImageFileName).renameTo(File(localRootFolder, changedPhoto.id))
                                    Log.e("****", "rename file $localImageFileName to ${changedPhoto.id}")
                                } catch(e: Exception) { Log.e("****Exception: ", e.stackTraceToString())}
                            }
                            else {
                                // Download image file from server
                                sardine.get("$resourceRoot/${Uri.encode(changedAlbum.name)}/${Uri.encode(changedPhoto.name)}").use { input ->
                                    File("$localRootFolder/${changedPhoto.id}").outputStream().use { output ->
                                        input.copyTo(output, 8192)
                                        //Log.e("****", "Downloaded ${changedPhoto.name}")
                                    }
                                }
                            }

                            with(Tools.getPhotoParams("$localRootFolder/${changedPhoto.id}", changedPhoto.mimeType, changedPhoto.name)) {
                                changedPhoto.dateTaken = dateTaken
                                changedPhoto.width = width
                                changedPhoto.height = height
                                changedPhoto.mimeType = mimeType
                            }

                            // Update album's startDate, endDate fields
                            if (changedPhoto.dateTaken > changedAlbum.endDate) changedAlbum.endDate = changedPhoto.dateTaken
                            if (changedPhoto.dateTaken < changedAlbum.startDate) changedAlbum.startDate = changedPhoto.dateTaken

                            // update row when everything's fine. any thing that broke before this point will be captured by exception handler and will be worked on again in next round of sync
                            photoRepository.upsertSync(changedPhoto)

                            // Need to update and show the new album from server in local album list asap, have to do this in the loop
                            if (i == validCover) {
                                if (changedAlbum.cover.isEmpty()) {
                                    // If this is a new album from server, then set it's cover to the first jpeg/png photo in the return list, set cover baseline
                                    // default to show middle part of the photo
                                    changedAlbum.cover = changedPhotos[validCover].id
                                    changedAlbum.coverBaseline = (changedPhotos[validCover].height - (changedPhotos[validCover].width * 9 / 21)) / 2
                                    changedAlbum.coverWidth = changedPhotos[validCover].width
                                    changedAlbum.coverHeight = changedPhotos[validCover].height

                                    // Get cover updated
                                    tempAlbum = changedAlbum.copy(eTag = "", syncProgress = 0f)
                                }
                                // Update UI only if more than one photo changed
                                if (changedPhotos.size > 1) albumRepository.upsertSync(tempAlbum)
                            } else {
                                // Even new album created on server is in local now, update album's sync progress only
                                albumRepository.updateAlbumSyncStatus(changedAlbum.id, (i + 1).toFloat() / changedPhotos.size, changedAlbum.startDate, changedAlbum.endDate)
                            }
                        }

                        // User might change cover already, update it here
                        with(albumRepository.getCover(changedAlbum.id)) {
                            changedAlbum.cover = this.cover
                            changedAlbum.coverBaseline = this.coverBaseline
                            changedAlbum.coverWidth = this.coverWidth
                            changedAlbum.coverHeight = this.coverHeight
                        }
                        // Every changed photos updated, we can commit changes to the Album table now. The most important column is "eTag", dictates the sync status
                        albumRepository.upsertSync(changedAlbum)

                        // Delete those photos not exist on server, happens when user delete photos on the server
                        var deletion = false
                        //localPhotoETags = photoRepository.getETagsMap(changedAlbum.id)
                        for (localPhoto in localPhotoETags) {
                            if (!remotePhotoIds.contains(localPhoto.key)) {
                                deletion = true
                                photoRepository.deleteByIdSync(localPhoto.key)
                                try {
                                    File(localRootFolder, localPhoto.key).delete()
                                    //Log.e("****", "Deleted photo: ${localPhoto.key}")
                                } catch (e: Exception) { e.printStackTrace() }
                            }
                        }

                        if (deletion) {
                            // Maintaining album cover and duration if deletion happened
                            val photosLeft = photoRepository.getAlbumPhotos(changedAlbum.id)
                            if (photosLeft.isNotEmpty()) {
                                val album = albumRepository.getThisAlbum(changedAlbum.id)
                                album[0].startDate = photosLeft[0].dateTaken
                                album[0].endDate = photosLeft.last().dateTaken
                                photosLeft.find { it.id == album[0].cover } ?: run {
                                    album[0].cover = photosLeft[0].id
                                    album[0].coverBaseline = (photosLeft[0].height - (photosLeft[0].width * 9 / 21)) / 2
                                    album[0].coverWidth = photosLeft[0].width
                                    album[0].coverHeight = photosLeft[0].height
                                }
                                albumRepository.updateSync(album[0])
                            } else {
                                // All photos under this album removed, delete album on both local and remote
                                albumRepository.deleteByIdSync(changedAlbum.id)
                                actionRepository.addAction(Action(null, Action.ACTION_DELETE_DIRECTORY_ON_SERVER, changedAlbum.id, changedAlbum.name, "", "", System.currentTimeMillis(), 1))
                            }
                        }

                        // Recycle the list
                        remotePhotoIds.clear()
                        changedPhotos.clear()
                    }
                }
            //}

            // Backup camera roll if setting turn on
            val pref = PreferenceManager.getDefaultSharedPreferences(application)
            if (pref.getBoolean(application.getString(R.string.cameraroll_backup_pref_key), false)) {
                // Make sure DCIM base directory is there
                if (!sardine.exists(dcimRoot)) sardine.createDirectory(dcimRoot)

                // Make sure device subfolder is under DCIM/
                dcimRoot += "/${Tools.getDeviceModel()}"
                if (!sardine.exists(dcimRoot)) sardine.createDirectory(dcimRoot)

                val cacheFolder = "${application.cacheDir}"
                var lastTime = pref.getLong(SettingsFragment.LAST_BACKUP, 0L)
                //Log.e(">>>>>", "backup media later than this time $lastTime")
                val contentUri = MediaStore.Files.getContentUri("external")
                val pathSelection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Files.FileColumns.RELATIVE_PATH else MediaStore.Files.FileColumns.DATA
                val projection = arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    pathSelection,
                    MediaStore.Files.FileColumns.DATE_ADDED,
                    MediaStore.Files.FileColumns.MEDIA_TYPE,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DISPLAY_NAME
                )
                val selection = "(${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE} OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})" + " AND " +
                        "($pathSelection LIKE '%DCIM%')" + " AND " + "(${MediaStore.Files.FileColumns.DATE_ADDED} > ${lastTime})"
                application.contentResolver.query(contentUri, projection, selection, null, "${MediaStore.Files.FileColumns.DATE_ADDED} ASC"
                )?.use { cursor->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                    val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val pathColumn = cursor.getColumnIndexOrThrow(pathSelection)
                    val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                    val typeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)

                    val tempFile = File(cacheFolder, "DCIMTemp")
                    var relativePath: String
                    var fileName: String

                    while(cursor.moveToNext()) {
                        // Check network type on every loop, so that user is able to stop sync right in the middle
                        if (sp.getBoolean(wifionlyKey, true)) {
                            if ((application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered) {
                                syncResult.hasSoftError()
                                return
                            }
                        }

                        //Log.e(">>>>>>>>", "${cursor.getString(nameColumn)} ${cursor.getString(dateColumn)}  ${cursor.getString(pathColumn)} needs uploading")
                        fileName = cursor.getString(nameColumn)
                        relativePath = cursor.getString(pathColumn).substringAfter("DCIM/").substringBefore("/${fileName}")
                        //Log.e(">>>>>", "relative path is $relativePath  server file will be ${dcimRoot}/${relativePath}/${fileName}")
                        try {
                            // Since sardine use File only, need this intermediate temp file
                            application.contentResolver.openInputStream(ContentUris.withAppendedId(contentUri, cursor.getLong(idColumn)))?.use { input ->
                                tempFile.outputStream().use { output ->
                                    input.copyTo(output, 4096)
                                }
                            }
                            //Log.e(">>>>>", "$tempFile created")

                            try {
                                // Upload file
                                sardine.put("${dcimRoot}/${relativePath}/${fileName}", tempFile, cursor.getString(typeColumn))
                                //Log.e(">>>>>", "$tempFile uploaded")
                            } catch (e: SardineException) {
                                Log.e("****SardineException: ", e.stackTraceToString())
                                when(e.statusCode) {
                                    404-> {
                                        // create file in non-existed folder, should create subfolder first
                                        var subFolder = dcimRoot
                                        relativePath.split("/").forEach {
                                            subFolder += "/$it"
                                            try {
                                                if (!sardine.exists(subFolder)) sardine.createDirectory(subFolder)
                                                //Log.e(">>>>", "create subfolder $subFolder")
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                                syncResult.stats.numIoExceptions++
                                                return
                                            }
                                        }
                                        syncResult.stats.numIoExceptions++
                                        return
                                    }
                                    400, 405, 406, 410-> {
                                        // target not found, target readonly, target already existed, etc. should be skipped and move onto next media
                                    }
                                    401, 403, 407-> {
                                        syncResult.stats.numAuthExceptions++
                                        return
                                    }
                                    409-> {
                                        syncResult.stats.numConflictDetectedExceptions++
                                        return
                                    }
                                    423-> {
                                        // Interrupted upload will locked file on server, backoff 90 seconds so that lock gets cleared on server
                                        syncResult.stats.numIoExceptions++
                                        syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 90
                                        return
                                    }
                                    else-> {
                                        // Other unhandled error should be retried
                                        syncResult.stats.numIoExceptions++
                                        return
                                    }
                                }
                            }

                            // New timestamp when success
                            lastTime = cursor.getLong(dateColumn) + 1

                        } catch (e: Exception) {
                            e.printStackTrace()
                            // TODO retry or not
                            //syncResult.hasSoftError()
                        }
                        finally {
                            // Delete temp file
                            try { tempFile.delete() } catch (e: Exception) { e.printStackTrace() }
                            //Log.e(">>>>>>", "$tempFile deleted")
                        }
                    }
                }

                // Save latest timestamp
                pref.edit().apply {
                    putLong(SettingsFragment.LAST_BACKUP, lastTime)
                    apply()
                    //Log.e(">>>>>>>", "new timestamp is $lastTime")
                }
            }

            // Clear status counters
            syncResult.stats.clear()
        } catch (e: IOException) {
            syncResult.stats.numIoExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: SocketTimeoutException) {
            syncResult.stats.numIoExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: InterruptedIOException) {
            syncResult.stats.numIoExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: SSLHandshakeException) {
            syncResult.stats.numIoExceptions++
            syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 10 * 60       // retry 10 minutes later
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: SSLPeerUnverifiedException) {
            syncResult.stats.numIoExceptions++
            syncResult.delayUntil = (System.currentTimeMillis() / 1000) + 10 * 60       // retry 10 minutes later
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: AuthenticatorException) {
            syncResult.stats.numAuthExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e: SardineException) {
            syncResult.stats.numIoExceptions++
            Log.e("****Exception: ", e.stackTraceToString())
        } catch (e:Exception) {
            Log.e("****Exception: ", e.stackTraceToString())
        }
    }

    companion object {
        const val ACTION = "ACTION"
        const val SYNC_LOCAL_CHANGES = 0
        const val SYNC_REMOTE_CHANGES = 1

        // PROPFIND properties namespace
        private const val DAV_NS = "DAV:"
        private const val OC_NS = "http://owncloud.org/ns"
        private const val NC_NS = "http://nextcloud.org/ns"

        // OC and NC defined localpart
        private const val OC_UNIQUE_ID = "fileid"
        private const val OC_SHARETYPE = "share-types"
        private const val OC_CHECKSUMS = "checksums"
        private const val NC_HASPREVIEW = "has-preview"
        private const val OC_SIZE = "size"
        private const val OC_DATA_FINGERPRINT = "data-fingerprint"

        // WebDAV defined localpart
        private const val DAV_GETETAG = "getetag"
        private const val DAV_GETLASTMODIFIED = "getlastmodified"
        private const val DAV_GETCONTENTTYPE = "getcontenttype"
        private const val DAV_RESOURCETYPE = "resourcetype"
        private const val DAV_GETCONTENTLENGTH = "getcontentlength"

        const val JUST_FOLDER_DEPTH = 0
        const val FOLDER_CONTENT_DEPTH = 1

        private val NC_PROPFIND_PROP = setOf(
            QName(DAV_NS, DAV_GETETAG, "D"),
            QName(DAV_NS, DAV_GETLASTMODIFIED, "D"),
            QName(DAV_NS, DAV_GETCONTENTTYPE, "D"),
            QName(DAV_NS, DAV_RESOURCETYPE, "D"),
            QName(OC_NS, OC_UNIQUE_ID, "oc"),
            QName(OC_NS, OC_SHARETYPE, "oc"),
            QName(NC_NS, NC_HASPREVIEW, "nc"),
            QName(OC_NS, OC_CHECKSUMS, "oc"),
            QName(OC_NS, OC_SIZE, "oc"),
            QName(OC_NS, OC_DATA_FINGERPRINT, "oc")
        )
    }
}