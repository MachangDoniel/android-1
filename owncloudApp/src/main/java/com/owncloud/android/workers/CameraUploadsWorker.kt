package com.owncloud.android.workers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.owncloud.android.datamodel.CameraUploadsSyncStorageManager
import com.owncloud.android.domain.UseCaseResult
import com.owncloud.android.domain.camerauploads.model.FolderBackUpConfiguration
import com.owncloud.android.domain.camerauploads.usecases.GetCameraUploadsConfigurationUseCase
import com.owncloud.android.utils.MimetypeIconUtil
import com.owncloud.android.workers.UploadFileFromContentUriWorker.Companion.TRANSFER_TAG_CAMERA_UPLOAD
import org.koin.core.KoinComponent
import org.koin.core.inject
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

class CameraUploadsWorker(
    val appContext: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(
    appContext,
    workerParameters
), KoinComponent {

    private val getCameraUploadsConfigurationUseCase: GetCameraUploadsConfigurationUseCase by inject()

    override suspend fun doWork(): Result {

        when (val useCaseResult = getCameraUploadsConfigurationUseCase.execute(Unit)) {
            is UseCaseResult.Success -> {
                syncCameraPictures(useCaseResult.data?.pictureUploadsConfiguration)
                syncCameraVideos(useCaseResult.data?.videoUploadsConfiguration)
            }
            is UseCaseResult.Error -> {
                Timber.e(useCaseResult.throwable, "Worker ${useCaseResult.throwable}")
            }
        }
        return Result.success()
    }

    private fun syncCameraPictures(
        pictureUploadsConfiguration: FolderBackUpConfiguration.PictureUploadsConfiguration?
    ) {
        if (pictureUploadsConfiguration == null) return

        val cameraPictureSourceUri: Uri = pictureUploadsConfiguration.sourcePath.toUri()
        val localPicturesDocumentFiles: MutableList<DocumentFile> = mutableListOf()
        val documentTree = DocumentFile.fromTreeUri(applicationContext, cameraPictureSourceUri)
        localPicturesDocumentFiles.addAll(documentTree?.listFiles() ?: arrayOf())
        localPicturesDocumentFiles.sortBy { it.lastModified() }
        if (localPicturesDocumentFiles.isNotEmpty()) {
            for (documentFile in localPicturesDocumentFiles) {
                val fileName = documentFile.name
                val mimeType = MimetypeIconUtil.getBestMimeTypeByFilename(fileName)
                val isImage = mimeType.startsWith("image/")

                val cameraUploadSync = CameraUploadsSyncStorageManager(appContext.contentResolver).getCameraUploadSync(
                    null,
                    null,
                    null
                )

                if (isImage) {
                    if (documentFile.lastModified() <= cameraUploadSync.picturesLastSync) {
                        Timber.i("Image ${documentFile.name} created before period to check, ignoring... Last Modified: ${documentFile.lastModified()} Last sync: ${cameraUploadSync.picturesLastSync}")
                    } else {
                        Timber.d("Upload document file ${documentFile.name}")
                        enqueueSingleUpload(
                            contentUri = documentFile.uri,
                            uploadPath = pictureUploadsConfiguration.uploadPath.plus(documentFile.name),
                            lastModified = documentFile.lastModified(),
                            behavior = pictureUploadsConfiguration.behavior.toString(),
                            accountName = pictureUploadsConfiguration.accountName
                        )
                    }
                }
            }
        }
    }

    private fun syncCameraVideos(
        videoUploadsConfiguration: FolderBackUpConfiguration.VideoUploadsConfiguration?
    ) {
        if (videoUploadsConfiguration == null) return

        val cameraVideoSourcePath: String = videoUploadsConfiguration.sourcePath
        val localVideoFiles: MutableList<File> = mutableListOf()
        val cameraVideoFolder = File(cameraVideoSourcePath)
        localVideoFiles.addAll(cameraVideoFolder.listFiles() ?: arrayOf())
        localVideoFiles.sortBy { it.lastModified() }

        if (localVideoFiles.isNotEmpty()) {
            for (localFile in localVideoFiles) {
                val fileName = localFile.name
                val mimeType = MimetypeIconUtil.getBestMimeTypeByFilename(fileName)
                val isVideo = mimeType.startsWith("video/")
                if (isVideo) {
                    // TODO: Upload localFile
                    Timber.d("Upload ${localFile.name}")
                }
            }
        }
    }

    private fun enqueueSingleUpload(
        contentUri: Uri,
        uploadPath: String,
        lastModified: Long,
        behavior: String,
        accountName: String
    ) {
        val lastModifiedInSeconds = (lastModified / 1000L).toString()

        val inputData = workDataOf(
            UploadFileFromContentUriWorker.KEY_PARAM_ACCOUNT_NAME to accountName,
            UploadFileFromContentUriWorker.KEY_PARAM_BEHAVIOR to behavior,
            UploadFileFromContentUriWorker.KEY_PARAM_CONTENT_URI to contentUri.toString(),
            UploadFileFromContentUriWorker.KEY_PARAM_LAST_MODIFIED to lastModifiedInSeconds,
            UploadFileFromContentUriWorker.KEY_PARAM_UPLOAD_PATH to uploadPath,
        )

        val uploadFileFromContentUriWorker = OneTimeWorkRequestBuilder<UploadFileFromContentUriWorker>()
            .setInputData(inputData)
            .addTag(accountName)
            .addTag(TRANSFER_TAG_CAMERA_UPLOAD)
            .build()

        WorkManager.getInstance(appContext).enqueue(uploadFileFromContentUriWorker)
        Timber.i("Upload of ${contentUri.path} has been enqueued.")
    }

    companion object {
        const val CAMERA_UPLOADS_WORKER = "CAMERA_UPLOADS_WORKER"
        const val repeatInterval: Long = 15L
        val repeatIntervalTimeUnit: TimeUnit = TimeUnit.MINUTES
    }
}
