package com.wenku8.epubstudio

import android.app.Application
import com.wenku8.epubstudio.core.Wenku8SessionStore
import com.wenku8.epubstudio.core.Wenku8SearchProvider
import com.wenku8.epubstudio.service.ExportJobManager
import com.wenku8.epubstudio.settings.SettingsRepository

class Wenku8Application : Application() {
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
    val sessionStore: Wenku8SessionStore by lazy { Wenku8SessionStore(this) }
    val jobManager: ExportJobManager by lazy { ExportJobManager(this, sessionStore) }
    val searchProvider: Wenku8SearchProvider by lazy { Wenku8SearchProvider(jobManager.httpClient(), sessionStore) }
}
