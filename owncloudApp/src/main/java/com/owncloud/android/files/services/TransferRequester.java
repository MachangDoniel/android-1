/**
 * ownCloud Android client application
 *
 * @author David A. Velasco
 * @author David González Verdugo
 * <p>
 * Copyright (C) 2020 ownCloud GmbH.
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2,
 * as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.owncloud.android.files.services;

import android.accounts.Account;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PersistableBundle;

import androidx.documentfile.provider.DocumentFile;
import com.owncloud.android.authentication.AccountUtils;
import com.owncloud.android.datamodel.OCUpload;
import com.owncloud.android.datamodel.UploadsStorageManager;
import com.owncloud.android.db.UploadResult;
import com.owncloud.android.usecases.RetryFailedUploadsUseCase;
import com.owncloud.android.usecases.RetryUploadFromContentUriUseCase;
import com.owncloud.android.domain.files.model.OCFile;
import com.owncloud.android.utils.ConnectivityUtils;
import com.owncloud.android.utils.Extras;
import com.owncloud.android.utils.PowerUtils;
import kotlin.Unit;
import timber.log.Timber;

import java.net.SocketTimeoutException;

import static com.owncloud.android.operations.UploadFileOperation.CREATED_AS_CAMERA_UPLOAD_PICTURE;
import static com.owncloud.android.operations.UploadFileOperation.CREATED_AS_CAMERA_UPLOAD_VIDEO;

/*
 * Facade to start operations in transfer services without the verbosity of Android Intents.
 */

/**
 * Facade class providing methods to ease requesting commands to transfer services {@link FileUploader}.
 * <p>
 * Protects client objects from the verbosity of {@link android.content.Intent}s.
 * <p>
 */

public class TransferRequester {
    /**
     * Call to upload several new files
     */
    public void uploadNewFiles(
            Context context,
            Account account,
            String[] localPaths,
            String[] remotePaths,
            String[] mimeTypes,
            Integer behaviour,
            int createdBy
    ) {
        Intent intent = new Intent(context, FileUploader.class);

        intent.putExtra(FileUploader.KEY_ACCOUNT, account);
        intent.putExtra(FileUploader.KEY_LOCAL_FILE, localPaths);
        intent.putExtra(FileUploader.KEY_REMOTE_FILE, remotePaths);
        intent.putExtra(FileUploader.KEY_MIME_TYPE, mimeTypes);
        intent.putExtra(FileUploader.KEY_LOCAL_BEHAVIOUR, behaviour);
        intent.putExtra(FileUploader.KEY_CREATED_BY, createdBy);

        if ((createdBy == CREATED_AS_CAMERA_UPLOAD_PICTURE || createdBy == CREATED_AS_CAMERA_UPLOAD_VIDEO) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Since in Android O the apps running in background are not allowed to start background services. The
            // camera uploads feature may try to do this. A way to solve this is to run the camera upload feature in
            // the foreground.
            Timber.d("Start to upload some files from foreground/background, startForeground() will be called soon");
            context.startForegroundService(intent);
        } else {
            Timber.d("Start to upload some files from foreground");
            context.startService(intent);
        }
    }

    /**
     * Call to upload a new single file
     */
    public void uploadNewFile(Context context, Account account, String localPath, String remotePath, int
            behaviour, String mimeType, int createdBy) {

        uploadNewFiles(
                context,
                account,
                new String[]{localPath},
                new String[]{remotePath},
                new String[]{mimeType},
                behaviour,
                createdBy
        );
    }

    /**
     * Call to update multiple files already uploaded
     */
    private void uploadsUpdate(Context context, Account account, OCFile[] existingFiles, Integer behaviour,
                               Boolean forceOverwrite, boolean requestedFromAvOfflineJobService) {
        Intent intent = new Intent(context, FileUploader.class);

        intent.putExtra(FileUploader.KEY_ACCOUNT, account);
        intent.putExtra(FileUploader.KEY_FILE, existingFiles);
        intent.putExtra(FileUploader.KEY_LOCAL_BEHAVIOUR, behaviour);
        intent.putExtra(FileUploader.KEY_FORCE_OVERWRITE, forceOverwrite);

        // Since in Android O the apps running in background are not allowed to start background services. The
        // available offline feature may try to do this. A way to solve this is to run the available offline feature in
        // the foreground.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && requestedFromAvOfflineJobService) {
            intent.putExtra(FileUploader.KEY_IS_AVAILABLE_OFFLINE_FILE, true);
            Timber.d("Start to upload some already uploaded files from foreground/background, startForeground() will be called soon");
            context.startForegroundService(intent);
        } else {
            Timber.d("Start to upload some already uploaded files from foreground");
            context.startService(intent);
        }
    }

    /**
     * Call to update a dingle file already uploaded
     */
    public void uploadUpdate(Context context, Account account, OCFile existingFile, Integer behaviour,
                             Boolean forceOverwrite, boolean requestedFromAvOfflineJobService) {

        uploadsUpdate(context, account, new OCFile[]{existingFile}, behaviour, forceOverwrite,
                requestedFromAvOfflineJobService);
    }

    /**
     * Call to retry upload identified by remotePath
     */
    public void retry(Context context, OCUpload upload, boolean requestedFromWifiBackEvent) {
        if (upload != null && context != null) {
            Account account = AccountUtils.getOwnCloudAccountByName(
                    context,
                    upload.getAccountName()
            );
            retry(context, account, upload, requestedFromWifiBackEvent);

        } else {
            throw new IllegalArgumentException("Null parameter!");
        }
    }

    /**
     * Private implementation of retry.
     *
     * @param context                    Caller {@link Context}
     * @param account                    OC account where the upload will be retried.
     * @param upload                     Persisted upload to retry.
     * @param requestedFromWifiBackEvent true if the retry was requested because wifi connection was back,
     *                                   false otherwise
     */
    private void retry(Context context, Account account, OCUpload upload, boolean requestedFromWifiBackEvent) {
        if (upload == null) {
            return;
        }
        RetryFailedUploadsUseCase retryFailedUploadsUseCase = new RetryFailedUploadsUseCase(context);
        retryFailedUploadsUseCase.execute(Unit.INSTANCE);
    }

    /**
     * Return 'true' when conditions for a scheduled retry are met.
     *
     * @param context Caller {@link Context}
     * @return 'true' when conditions for a scheduled retry are met, 'false' otherwise.
     */
    boolean shouldScheduleRetry(Context context, Exception exception) {
        return (
                !ConnectivityUtils.isNetworkActive(context) ||
                        PowerUtils.isDeviceIdle(context) ||
                        exception instanceof SocketTimeoutException // TODO check if exception is the same in HTTP
                // server
        );
    }

    /**
     * Schedule a future retry of an upload, to be done when a connection via an unmetered network (free Wifi)
     * is available.
     *
     * @param context     Caller {@link Context}.
     * @param jobId       Identifier to set to the retry job.
     * @param accountName Local name of the OC account where the upload will be retried.
     * @param remotePath  Full path of the file to upload, relative to root of the OC account.
     */
    void scheduleUpload(Context context, int jobId, String accountName, String remotePath) {
        boolean scheduled = scheduleTransfer(
                context,
                RetryUploadJobService.class,
                jobId,
                accountName,
                remotePath
        );

        if (scheduled) {
            Timber.d("Scheduled upload retry for %1s in %2s", remotePath, accountName);
        }
    }

    /**
     * Schedule a future transfer of an upload, to be done when a connection via an unmetered network (free Wifi)
     * is available.
     *
     * @param context               Caller {@link Context}.
     * @param scheduledRetryService Class of the appropriate retry service, either to retry downloads
     *                              or to retry uploads.
     * @param jobId                 Identifier to set to the retry job.
     * @param accountName           Local name of the OC account where the upload will be retried.
     * @param remotePath            Full path of the file to upload, relative to root of the OC account.
     */
    private boolean scheduleTransfer(
            Context context,
            Class<?> scheduledRetryService,
            int jobId,
            String accountName,
            String remotePath
    ) {
        ComponentName serviceComponent = new ComponentName(
                context,
                scheduledRetryService
        );

        JobInfo.Builder builder = new JobInfo.Builder(jobId, serviceComponent);

        int networkType = getRequiredNetworkType(context, accountName, remotePath);

        // require network type (Wifi or Wifi and cellular)
        builder.setRequiredNetworkType(networkType);

        // Persist job and prevent it from being deleted after a device restart
        builder.setPersisted(true);

        // Extra data
        PersistableBundle extras = new PersistableBundle();
        extras.putString(Extras.EXTRA_REMOTE_PATH, remotePath);
        extras.putString(Extras.EXTRA_ACCOUNT_NAME, accountName);
        builder.setExtras(extras);

        JobScheduler jobScheduler =
                (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        jobScheduler.schedule(builder.build());

        return true;
    }

    /**
     * Retrieve the type of network connection required to schedule the last upload for an account
     *
     * @param context
     * @param accountName
     * @param remotePath  to upload the file
     * @return 2 if only wifi is required, 1 if any internet connection is required (wifi or cellular)
     */
    private int getRequiredNetworkType(Context context, String accountName, String remotePath) {

        UploadsStorageManager uploadsStorageManager = new UploadsStorageManager(context.getContentResolver());

        // Get last upload to be retried
        // FIXME: 13/10/2020 : New_arch: Upload
        OCUpload ocUpload =  null; //uploadsStorageManager.getLastUploadFor(new OCFile(remotePath), accountName);

        // Wifi by default
        int networkType = JobInfo.NETWORK_TYPE_UNMETERED;

        return networkType;
    }
}
