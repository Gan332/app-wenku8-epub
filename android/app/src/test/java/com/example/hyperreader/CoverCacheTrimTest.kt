package com.example.hyperreader

import com.example.hyperreader.ui.cover.trimCoverDirectory
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class CoverCacheTrimTest {

    @Test
    fun evictsOldestFilesUntilTheCacheFitsAgain() {
        val directory = Files.createTempDirectory("covers-trim").toFile()
        try {
            (1..4).forEach { index ->
                File(directory, "cover$index").apply {
                    writeBytes(ByteArray(100))
                    setLastModified(BASE_TIME + index * 1_000L)
                }
            }

            trimCoverDirectory(directory, limitBytes = 300, targetBytes = 200)

            val remaining = directory.listFiles().orEmpty().map { it.name }.sorted()
            assertEquals(listOf("cover3", "cover4"), remaining)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun keepsCacheUntouchedWhenItIsUnderTheLimit() {
        val directory = Files.createTempDirectory("covers-keep").toFile()
        try {
            (1..2).forEach { index -> File(directory, "cover$index").writeBytes(ByteArray(100)) }

            trimCoverDirectory(directory, limitBytes = 300, targetBytes = 200)

            assertEquals(2, directory.listFiles().orEmpty().size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryIsNotAnError() {
        val directory = File(Files.createTempDirectory("covers-missing").toFile(), "absent")

        trimCoverDirectory(directory, limitBytes = 1, targetBytes = 1)

        assertEquals(false, directory.exists())
    }

    private companion object {
        const val BASE_TIME = 1_700_000_000_000L
    }
}
