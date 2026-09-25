package com.wenku8.epubstudio.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.wenku8.epubstudio.file.FontStore
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

class ReaderActivity : ComponentActivity() {
    private val viewModel: ReaderViewModel by viewModels()
    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val path = FontStore(this).import(uri)
        if (path != null) viewModel.updateFontUri(path)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = Uri.parse(intent.getStringExtra(EXTRA_URI).orEmpty())
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID).orEmpty()
        if (uri.scheme.isNullOrBlank()) {
            finish()
            return
        }
        viewModel.load(uri, bookId)
        setContent {
            MiuixTheme(
                controller = remember { ThemeController(ColorSchemeMode.MonetSystem, keyColor = Color(0xFFA34B2F)) },
            ) {
                ReaderScreen(viewModel) { fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")) }
            }
        }
    }

    companion object {
        const val EXTRA_URI = "epub_uri"
        const val EXTRA_BOOK_ID = "book_id"
    }
}
