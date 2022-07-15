/**
 * ownCloud Android client application
 *
 * @author Abel García de Prada
 * @author Juan Carlos Garrote Gascón
 *
 * Copyright (C) 2021 ownCloud GmbH.
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

package com.owncloud.android.usecases.transfers.uploads

import com.owncloud.android.domain.BaseUseCase
import com.owncloud.android.domain.files.model.OCFile.Companion.PATH_SEPARATOR
import com.owncloud.android.domain.transfers.TransferRepository

class RetryUploadFromSystemUseCase(
    private val uploadFilesFromSystemUseCase: UploadFilesFromSystemUseCase,
    private val transferRepository: TransferRepository,
) : BaseUseCase<Unit, RetryUploadFromSystemUseCase.Params>() {

    override fun run(params: Params) {
        val uploadToRetry = transferRepository.getTransferById(params.uploadIdInStorageManager)

        uploadToRetry ?: return

        uploadFilesFromSystemUseCase.execute(
            UploadFilesFromSystemUseCase.Params(
                accountName = uploadToRetry.accountName,
                listOfLocalPaths = listOf(uploadToRetry.localPath),
                uploadFolderPath = uploadToRetry.remotePath.trimEnd(PATH_SEPARATOR),
            )
        )
    }

    data class Params(
        val uploadIdInStorageManager: Long,
    )
}