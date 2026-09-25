package com.wenku8.epubstudio

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.wenku8.epubstudio.file.EpubFilePlugin
import com.wenku8.epubstudio.http.Wenku8HttpPlugin
import com.wenku8.epubstudio.task.TaskGuardPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(Wenku8HttpPlugin::class.java)
        registerPlugin(TaskGuardPlugin::class.java)
        registerPlugin(EpubFilePlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
