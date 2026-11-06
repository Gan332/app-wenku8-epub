package com.example.hyperreader

import com.example.hyperreader.data.JobRepository
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JobRepositoryTest {
    @Test
    fun deleteRemovesRecordAndStagingFile() {
        runBlocking {
            val root = Files.createTempDirectory("jobs").toFile()
            val jobs = File(root, "jobs").apply { mkdirs() }
            val record = File(jobs, "abc.json").apply { writeText("{}") }
            val staging = File(jobs, "abc.json.tmp").apply { writeText("{}") }
            val other = File(jobs, "keep.json").apply { writeText("{}") }

            JobRepository(root).delete("abc")

            assertFalse(record.exists())
            assertFalse(staging.exists())
            assertTrue("不该误删其它记录", other.exists())
            root.deleteRecursively()
        }
    }
}
