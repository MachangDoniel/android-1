/**
 * ownCloud Android client application
 *
 * @author Abel García de Prada
 * @author Christian Schabesberger
 * @author Juan Carlos Garrote Gascón
 *
 * Copyright (C) 2023 ownCloud GmbH.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.data.files.repository

import com.owncloud.android.data.files.datasources.LocalFileDataSource
import com.owncloud.android.data.files.datasources.RemoteFileDataSource
import com.owncloud.android.data.spaces.datasources.LocalSpacesDataSource
import com.owncloud.android.data.storage.LocalStorageProvider
import com.owncloud.android.domain.availableoffline.model.AvailableOfflineStatus
import com.owncloud.android.domain.availableoffline.model.AvailableOfflineStatus.AVAILABLE_OFFLINE_PARENT
import com.owncloud.android.domain.availableoffline.model.AvailableOfflineStatus.NOT_AVAILABLE_OFFLINE
import com.owncloud.android.domain.exceptions.ConflictException
import com.owncloud.android.domain.exceptions.FileAlreadyExistsException
import com.owncloud.android.domain.exceptions.FileNotFoundException
import com.owncloud.android.domain.files.FileRepository
import com.owncloud.android.domain.files.model.FileListOption
import com.owncloud.android.domain.files.model.MIME_DIR
import com.owncloud.android.domain.files.model.OCFile
import com.owncloud.android.domain.files.model.OCFile.Companion.ROOT_PATH
import com.owncloud.android.domain.files.model.OCFileWithSyncInfo
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import java.io.File
import java.util.UUID

class OCFileRepository(
    private val localFileDataSource: LocalFileDataSource,
    private val remoteFileDataSource: RemoteFileDataSource,
    private val localSpacesDataSource: LocalSpacesDataSource,
    private val localStorageProvider: LocalStorageProvider
) : FileRepository {

    override fun getUrlToOpenInWeb(openWebEndpoint: String, fileId: String): String =
        remoteFileDataSource.getUrlToOpenInWeb(openWebEndpoint = openWebEndpoint, fileId = fileId)

    override fun createFolder(
        remotePath: String,
        parentFolder: OCFile,
    ) {
        val spaceWebDavUrl = localSpacesDataSource.getWebDavUrlForSpace(parentFolder.spaceId, parentFolder.owner)

        remoteFileDataSource.createFolder(
            remotePath = remotePath,
            createFullPath = false,
            isChunksFolder = false,
            accountName = parentFolder.owner,
            spaceWebDavUrl = spaceWebDavUrl,
        ).also {
            localFileDataSource.saveFilesInFolderAndReturnThem(
                folder = parentFolder,
                listOfFiles = listOf(
                    OCFile(
                        remotePath = remotePath,
                        owner = parentFolder.owner,
                        modificationTimestamp = System.currentTimeMillis(),
                        length = 0,
                        mimeType = MIME_DIR,
                        spaceId = parentFolder.spaceId,
                    )
                )
            )
        }
    }

    override fun copyFile(listOfFilesToCopy: List<OCFile>, targetFolder: OCFile) {
        val sourceSpaceWebDavUrl = localSpacesDataSource.getWebDavUrlForSpace(listOfFilesToCopy[0].spaceId, listOfFilesToCopy[0].owner)
        val targetSpaceWebDavUrl = localSpacesDataSource.getWebDavUrlForSpace(targetFolder.spaceId, targetFolder.owner)

        listOfFilesToCopy.forEach { ocFile ->

            // 1. Get the final remote path for this file.
            val expectedRemotePath: String = targetFolder.remotePath + ocFile.fileName
            val finalRemotePath: String = remoteFileDataSource.getAvailableRemotePath(
                expectedRemotePath,
                targetFolder.owner,
                targetSpaceWebDavUrl,
            ).let {
                if (ocFile.isFolder) it.plus(File.separator) else it
            }

            // 2. Try to copy files in server
            val remoteId = try {
                remoteFileDataSource.copyFile(
                    sourceRemotePath = ocFile.remotePath,
                    targetRemotePath = finalRemotePath,
                    accountName = ocFile.owner,
                    sourceSpaceWebDavUrl = sourceSpaceWebDavUrl,
                    targetSpaceWebDavUrl = targetSpaceWebDavUrl,
                )
            } catch (targetNodeDoesNotExist: ConflictException) {
                // Target node does not exist anymore. Remove target folder from database and local storage and return
                deleteLocalFolderRecursively(ocFile = targetFolder, onlyFromLocalStorage = false)
                throw targetNodeDoesNotExist
            } catch (sourceFileDoesNotExist: FileNotFoundException) {
                // Source file does not exist anymore. Remove file from database and local storage and continue
                if (ocFile.isFolder) {
                    deleteLocalFolderRecursively(ocFile = ocFile, onlyFromLocalStorage = false)
                } else {
                    deleteLocalFile(
                        ocFile = ocFile,
                        onlyFromLocalStorage = false
                    )
                }
                if (listOfFilesToCopy.size == 1) {
                    throw sourceFileDoesNotExist
                } else {
                    return@forEach
                }
            }

            // 3. Update database with latest changes
            localFileDataSource.copyFile(
                sourceFile = ocFile,
                targetFolder = targetFolder,
                finalRemotePath = finalRemotePath,
                remoteId = remoteId
            )
        }
    }

    override fun getFileById(fileId: Long): OCFile? =
        localFileDataSource.getFileById(fileId)

    override fun getFileByIdAsFlow(fileId: Long): Flow<OCFile?> =
        localFileDataSource.getFileByIdAsFlow(fileId)

    override fun getFileByRemotePath(remotePath: String, owner: String, spaceId: String?): OCFile? =
        localFileDataSource.getFileByRemotePath(remotePath, owner, spaceId)

    override fun getPersonalRootFolderForAccount(owner: String): OCFile {
        val personalSpace = localSpacesDataSource.getPersonalSpaceForAccount(owner)
        if (personalSpace == null) {
            val legacyRootFolder = localFileDataSource.getFileByRemotePath(remotePath = ROOT_PATH, owner = owner, spaceId = null)
            return legacyRootFolder!!
        }
        // TODO: Retrieving the root folders should return a non nullable. If they don't exist yet, they are created and returned. Remove nullability
        val personalRootFolder = localFileDataSource.getFileByRemotePath(remotePath = ROOT_PATH, owner = owner, spaceId = personalSpace.root.id)
        return personalRootFolder!!
    }

    override fun getSharesRootFolderForAccount(owner: String): OCFile? {
        val sharesSpaces = localSpacesDataSource.getSharesSpaceForAccount(owner) ?: return null

        val personalRootFolder = localFileDataSource.getFileByRemotePath(remotePath = ROOT_PATH, owner = owner, spaceId = sharesSpaces.root.id)
        return personalRootFolder!!
    }

    override fun getSearchFolderContent(fileListOption: FileListOption, folderId: Long, search: String): List<OCFile> =
        when (fileListOption) {
            FileListOption.ALL_FILES -> localFileDataSource.getSearchFolderContent(folderId, search)
            FileListOption.SPACES_LIST -> emptyList()
            FileListOption.AV_OFFLINE -> localFileDataSource.getSearchAvailableOfflineFolderContent(folderId, search)
            FileListOption.SHARED_BY_LINK -> localFileDataSource.getSearchSharedByLinkFolderContent(folderId, search)
        }

    override fun getFolderContent(folderId: Long): List<OCFile> =
        localFileDataSource.getFolderContent(folderId)

    override fun getFolderContentWithSyncInfoAsFlow(folderId: Long): Flow<List<OCFileWithSyncInfo>> =
        localFileDataSource.getFolderContentWithSyncInfoAsFlow(folderId)

    override fun getFolderImages(folderId: Long): List<OCFile> =
        localFileDataSource.getFolderImages(folderId)

    override fun getSharedByLinkWithSyncInfoForAccountAsFlow(owner: String): Flow<List<OCFileWithSyncInfo>> =
        localFileDataSource.getSharedByLinkWithSyncInfoForAccountAsFlow(owner)

    override fun getFilesWithSyncInfoAvailableOfflineFromAccountAsFlow(owner: String): Flow<List<OCFileWithSyncInfo>> =
        localFileDataSource.getFilesWithSyncInfoAvailableOfflineFromAccountAsFlow(owner)

    override fun getFilesAvailableOfflineFromAccount(owner: String): List<OCFile> =
        localFileDataSource.getFilesAvailableOfflineFromAccount(owner)

    override fun getFilesAvailableOfflineFromEveryAccount(): List<OCFile> =
        localFileDataSource.getFilesAvailableOfflineFromEveryAccount()

    override fun moveFile(listOfFilesToMove: List<OCFile>, targetFile: OCFile) {
        val spaceWebDavUrl = localSpacesDataSource.getWebDavUrlForSpace(targetFile.spaceId, targetFile.owner)

        listOfFilesToMove.forEach { ocFile ->

            // 1. Get the final remote path for this file.
            val expectedRemotePath: String = targetFile.remotePath + ocFile.fileName
            val finalRemotePath: String = remoteFileDataSource.getAvailableRemotePath(expectedRemotePath, targetFile.owner, spaceWebDavUrl).let {
                if (ocFile.isFolder) it.plus(File.separator) else it
            }
            val finalStoragePath: String = localStorageProvider.getDefaultSavePathFor(targetFile.owner, finalRemotePath, targetFile.spaceId)

            // 2. Try to move files in server
            try {
                remoteFileDataSource.moveFile(
                    sourceRemotePath = ocFile.remotePath,
                    targetRemotePath = finalRemotePath,
                    accountName = ocFile.owner,
                    spaceWebDavUrl = spaceWebDavUrl,
                )
            } catch (targetNodeDoesNotExist: ConflictException) {
                // Target node does not exist anymore. Remove target folder from database and local storage and return
                deleteLocalFolderRecursively(ocFile = targetFile, onlyFromLocalStorage = false)
                throw targetNodeDoesNotExist
            } catch (sourceFileDoesNotExist: FileNotFoundException) {
                // Source file does not exist anymore. Remove file from database and local storage and continue
                if (ocFile.isFolder) {
                    deleteLocalFolderRecursively(ocFile = ocFile, onlyFromLocalStorage = false)
                } else {
                    deleteLocalFile(
                        ocFile = ocFile,
                        onlyFromLocalStorage = false
                    )
                }
                if (listOfFilesToMove.size == 1) {
                    throw sourceFileDoesNotExist
                } else {
                    return@forEach
                }
            }

            // 3. Clean conflict in old location if there was a conflict
            ocFile.etagInConflict?.let {
                localFileDataSource.cleanConflict(ocFile.id!!)
            }

            // 4. Update database with latest changes
            localFileDataSource.moveFile(
                sourceFile = ocFile,
                targetFolder = targetFile,
                finalRemotePath = finalRemotePath,
                finalStoragePath = finalStoragePath
            )

            // 5. Save conflict in new location if there was conflict
            ocFile.etagInConflict?.let {
                localFileDataSource.saveConflict(ocFile.id!!, it)
            }

            // 6. Update local storage
            localStorageProvider.moveLocalFile(ocFile, finalStoragePath)
        }
    }

    override fun readFile(remotePath: String, accountName: String, spaceId: String?): OCFile {
        val spaceWebDavUrl = localSpacesDataSource.getWebDavUrlForSpace(spaceId, accountName)

        return remoteFileDataSource.readFile(remotePath, accountName, spaceWebDavUrl).copy(spaceId = spaceId)
    }

    override fun refreshFolder(
        remotePath: String,
        accountName: String,
        spaceId: String?,
    ): List<OCFile> {
        val spaceWebDavUrl = localSpacesDataSource.getWebDavUrlForSpace(spaceId, accountName)

        // Retrieve remote folder data
        val fetchFolderResult = remoteFileDataSource.refreshFolder(remotePath, accountName, spaceWebDavUrl).map {
            it.copy(spaceId = spaceId)
        }
        val remoteFolder = fetchFolderResult.first()
        val remoteFolderContent = fetchFolderResult.drop(1)

        // Final content for this folder, we will update the folder content all together
        val folderContentUpdated = mutableListOf<OCFile>()

        // Check if the folder already exists in database.
        val localFolderByRemotePath: OCFile? =
            localFileDataSource.getFileByRemotePath(remotePath = remoteFolder.remotePath, owner = remoteFolder.owner, spaceId = spaceId)

        // If folder doesn't exists in database, insert everything. Easy path
        if (localFolderByRemotePath == null) {
            folderContentUpdated.addAll(remoteFolderContent.map { it.apply { needsToUpdateThumbnail = !it.isFolder } })
        } else {
            // Keep the current local properties or we will miss relevant things.
            remoteFolder.copyLocalPropertiesFrom(localFolderByRemotePath)

            // Folder already exists in database, get database content to update files accordingly
            val localFolderContent = localFileDataSource.getFolderContent(folderId = localFolderByRemotePath.id!!)

            val localFilesMap = localFolderContent.associateBy { localFile -> localFile.remoteId ?: localFile.remotePath }.toMutableMap()

            // Loop to sync every child
            remoteFolderContent.forEach { remoteChild ->
                // Let's try with remote path if the file does not have remote id yet
                val localChildToSync = localFilesMap.remove(remoteChild.remoteId) ?: localFilesMap.remove(remoteChild.remotePath)

                // If local child does not exists, just insert the new one.
                if (localChildToSync == null) {
                    folderContentUpdated.add(
                        remoteChild.apply {
                            parentId = localFolderByRemotePath.id
                            needsToUpdateThumbnail = !remoteChild.isFolder
                            // remote eTag will not be set unless file CONTENTS are synchronized
                            etag = ""
                            availableOfflineStatus =
                                if (remoteFolder.isAvailableOffline) AVAILABLE_OFFLINE_PARENT else NOT_AVAILABLE_OFFLINE

                        })
                } else {
                    // File exists in the database, we need to check several stuff.
                    folderContentUpdated.add(
                        remoteChild.apply {
                            copyLocalPropertiesFrom(localChildToSync)
                            // DO NOT update etag till contents are synced.
                            etag = localChildToSync.etag
                            needsToUpdateThumbnail =
                                (!remoteChild.isFolder && remoteChild.modificationTimestamp != localChildToSync.modificationTimestamp) || localChildToSync.needsToUpdateThumbnail
                            // Probably not needed, if the child was already in the database, the av offline status should be also there
                            if (remoteFolder.isAvailableOffline) {
                                availableOfflineStatus = AVAILABLE_OFFLINE_PARENT
                            }
                            // FIXME: What about renames? Need to fix storage path
                        })
                }
            }

            // Remaining items should be removed from the database and local storage. They do not exists in remote anymore.
            localFilesMap.map { it.value }.forEach { ocFile ->
                ocFile.etagInConflict?.let {
                    localFileDataSource.cleanConflict(ocFile.id!!)
                }
                if (ocFile.isFolder) {
                    deleteLocalFolderRecursively(ocFile = ocFile, onlyFromLocalStorage = false)
                } else {
                    deleteLocalFile(ocFile = ocFile, onlyFromLocalStorage = false)
                }
            }
        }

        val anyConflictInThisFolder = folderContentUpdated.any { it.etagInConflict != null }

        if (!anyConflictInThisFolder) {
            remoteFolder.etagInConflict = null
        }

        return localFileDataSource.saveFilesInFolderAndReturnThem(
            folder = remoteFolder,
            listOfFiles = folderContentUpdated
        )
    }

    override fun deleteFiles(listOfFilesToDelete: List<OCFile>, removeOnlyLocalCopy: Boolean) {
        val spaceWebDavUrl = localSpacesDataSource.getWebDavUrlForSpace(
            spaceId = listOfFilesToDelete.first().spaceId,
            accountName = listOfFilesToDelete.first().owner,
        )

        listOfFilesToDelete.forEach { ocFile ->
            if (!removeOnlyLocalCopy) {
                try {
                    remoteFileDataSource.deleteFile(
                        remotePath = ocFile.remotePath,
                        accountName = ocFile.owner,
                        spaceWebDavUrl = spaceWebDavUrl,
                    )
                } catch (fileNotFoundException: FileNotFoundException) {
                    Timber.i("File ${ocFile.fileName} was not found in server. Let's remove it from local storage")
                }
            }
            ocFile.etagInConflict?.let {
                localFileDataSource.cleanConflict(ocFile.id!!)
            }
            if (ocFile.isFolder) {
                deleteLocalFolderRecursively(ocFile = ocFile, onlyFromLocalStorage = removeOnlyLocalCopy)
            } else {
                deleteLocalFile(ocFile = ocFile, onlyFromLocalStorage = removeOnlyLocalCopy)
            }
        }
    }

    override fun renameFile(ocFile: OCFile, newName: String) {
        // 1. Compose new remote path
        val newRemotePath = localStorageProvider.getExpectedRemotePath(
            remotePath = ocFile.remotePath,
            newName = newName,
            isFolder = ocFile.isFolder
        )

        // 2. Check if file already exists in database
        if (localFileDataSource.getFileByRemotePath(newRemotePath, ocFile.owner, ocFile.spaceId) != null) {
            throw FileAlreadyExistsException()
        }

        // 3. Retrieve the specific web dav url in case there is one.
        val spaceWebDavUrl = localSpacesDataSource.getWebDavUrlForSpace(
            spaceId = ocFile.spaceId,
            accountName = ocFile.owner,
        )

        // 4. Perform remote operation
        remoteFileDataSource.renameFile(
            oldName = ocFile.fileName,
            oldRemotePath = ocFile.remotePath,
            newName = newName,
            isFolder = ocFile.isFolder,
            accountName = ocFile.owner,
            spaceWebDavUrl = spaceWebDavUrl,
        )

        // 5. Save new remote path in the local database
        localFileDataSource.renameFile(
            fileToRename = ocFile,
            finalRemotePath = newRemotePath,
            finalStoragePath = localStorageProvider.getDefaultSavePathFor(ocFile.owner, newRemotePath, ocFile.spaceId)
        )

        // 6. Update local storage
        localStorageProvider.moveLocalFile(
            ocFile = ocFile,
            finalStoragePath = localStorageProvider.getDefaultSavePathFor(ocFile.owner, newRemotePath, ocFile.spaceId)
        )
    }

    override fun saveFile(file: OCFile) {
        localFileDataSource.saveFile(file)
    }

    override fun saveConflict(fileId: Long, eTagInConflict: String) {
        localFileDataSource.saveConflict(fileId, eTagInConflict)
    }

    override fun cleanConflict(fileId: Long) {
        localFileDataSource.cleanConflict(fileId)
    }

    override fun disableThumbnailsForFile(fileId: Long) {
        localFileDataSource.disableThumbnailsForFile(fileId)
    }

    override fun updateFileWithNewAvailableOfflineStatus(ocFile: OCFile, newAvailableOfflineStatus: AvailableOfflineStatus) {
        localFileDataSource.updateAvailableOfflineStatusForFile(ocFile, newAvailableOfflineStatus)
    }

    override fun updateDownloadedFilesStorageDirectoryInStoragePath(oldDirectory: String, newDirectory: String) {
        localFileDataSource.updateDownloadedFilesStorageDirectoryInStoragePath(oldDirectory, newDirectory)
    }

    override fun saveUploadWorkerUuid(fileId: Long, workerUuid: UUID) {
        TODO("Not yet implemented")
    }

    override fun saveDownloadWorkerUuid(fileId: Long, workerUuid: UUID) {
        localFileDataSource.saveDownloadWorkerUuid(fileId, workerUuid)
    }

    override fun cleanWorkersUuid(fileId: Long) {
        localFileDataSource.cleanWorkersUuid(fileId)
    }

    private fun deleteLocalFolderRecursively(ocFile: OCFile, onlyFromLocalStorage: Boolean) {
        val folderContent = localFileDataSource.getFolderContent(ocFile.id!!)

        // 1. Remove folder content recursively
        folderContent.forEach { file ->
            if (file.isFolder) {
                deleteLocalFolderRecursively(ocFile = file, onlyFromLocalStorage = onlyFromLocalStorage)
            } else {
                deleteLocalFile(ocFile = file, onlyFromLocalStorage = onlyFromLocalStorage)
            }
        }

        // 2. Remove the folder itself
        deleteLocalFile(ocFile = ocFile, onlyFromLocalStorage = onlyFromLocalStorage)
    }

    private fun deleteLocalFile(ocFile: OCFile, onlyFromLocalStorage: Boolean) {
        localStorageProvider.deleteLocalFile(ocFile)
        if (onlyFromLocalStorage) {
            localFileDataSource.saveFile(ocFile.copy(storagePath = null, etagInConflict = null))
        } else {
            localFileDataSource.deleteFile(ocFile.id!!)
        }
    }
}
