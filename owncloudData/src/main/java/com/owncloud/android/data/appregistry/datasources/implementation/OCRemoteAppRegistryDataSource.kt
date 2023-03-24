/**
 * ownCloud Android client application
 *
 * @author Abel García de Prada
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
package com.owncloud.android.data.appregistry.datasources.implementation

import com.owncloud.android.data.ClientManager
import com.owncloud.android.data.appregistry.datasources.RemoteAppRegistryDataSource
import com.owncloud.android.data.executeRemoteOperation
import com.owncloud.android.domain.appregistry.AppRegistry
import com.owncloud.android.domain.appregistry.AppRegistryMimeType
import com.owncloud.android.domain.appregistry.AppRegistryProvider
import com.owncloud.android.lib.resources.appregistry.responses.AppRegistryResponse

class OCRemoteAppRegistryDataSource(
    private val clientManager: ClientManager
) : RemoteAppRegistryDataSource {
    override fun getAppRegistryForAccount(accountName: String): AppRegistry =
        executeRemoteOperation {
            clientManager.getAppRegistryService(accountName).getAppRegistry()
        }.toModel(accountName)

    private fun AppRegistryResponse.toModel(accountName: String) =
        AppRegistry(
            accountName = accountName,
            mimetypes = value.map { appRegistryMimeTypeResponse ->
                AppRegistryMimeType(
                    mimeType = appRegistryMimeTypeResponse.mimeType,
                    ext = appRegistryMimeTypeResponse.ext,
                    appProviders = appRegistryMimeTypeResponse.appProviders.map { appRegistryProviderResponse ->
                        AppRegistryProvider(
                            name = appRegistryProviderResponse.name,
                            icon = appRegistryProviderResponse.icon
                        )
                    },
                    name = appRegistryMimeTypeResponse.name,
                    icon = appRegistryMimeTypeResponse.icon,
                    description = appRegistryMimeTypeResponse.description,
                    allowCreation = appRegistryMimeTypeResponse.allowCreation,
                    defaultApplication = appRegistryMimeTypeResponse.defaultApplication,
                )
            }
        )
}
