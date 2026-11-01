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

    /** 写入中的 `.part` 不参与容量统计，也不能被 trim 当成旧缓存删掉。 */
    @Test
    fun stagingFilesAreNeitherCountedNorDeleted() {
        val directory = Files.createTempDirectory("covers-staging").toFile()
        try {
            (1..3).forEach { index ->
                File(directory, "cover$index").apply {
                    writeBytes(ByteArray(100))
                    setLastModified(BASE_TIME + index * 1_000L)
                }
            }
            val staging = File(directory, "cover4.part").apply {
                writeBytes(ByteArray(1_000))
                setLastModified(BASE_TIME)
            }

            trimCoverDirectory(directory, limitBytes = 250, targetBytes = 150)

            assertEquals(true, staging.isFile)
            val remaining = directory.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") }
            assertEquals(1, remaining.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 重复调用收敛：第二次不该再删任何东西（仓库层靠这一点避免并发 trim 越删越旧）。 */
    @Test
    fun repeatedTrimsAreStable() {
        val directory = Files.createTempDirectory("covers-stable").toFile()
        try {
            (1..4).forEach { index ->
                File(directory, "cover$index").apply {
                    writeBytes(ByteArray(100))
                    setLastModified(BASE_TIME + index * 1_000L)
                }
            }

            trimCoverDirectory(directory, limitBytes = 300, targetBytes = 200)
            val afterFirst = directory.listFiles().orEmpty().map { it.name }.sorted()
            trimCoverDirectory(directory, limitBytes = 300, targetBytes = 200)

            assertEquals(afterFirst, directory.listFiles().orEmpty().map { it.name }.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        const val BASE_TIME = 1_700_000_000_000L
    }
}
