package com.wenku8.epubstudio

import android.app.Application
import com.wenku8.epubstudio.service.ExportJobManager

class Wenku8Application : Application() {
    val jobManager: ExportJobManager by lazy { ExportJobManager(this) }
}
