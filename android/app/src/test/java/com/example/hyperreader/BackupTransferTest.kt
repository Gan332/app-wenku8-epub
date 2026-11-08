package com.example.hyperreader

import com.example.hyperreader.model.BookshelfEntry
import com.example.hyperreader.model.ReadingStats
import com.example.hyperreader.settings.BackupTransfer
import com.example.hyperreader.settings.backupExportFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份文件的纯逻辑：往返、版本拒绝、键白名单。 */
class BackupTransferTest {

    private fun document() = BackupTransfer.documentOf(
        bookshelf = listOf(
            BookshelfEntry(id = "shelf-1", bookId = "2835", title = "测试书"),
        ),
        preferences = mapOf(
            "reader_progress_2835" to """{"bookId":"2835"}""",
            "xy_reader_page_2835" to "12/240",
            "xy_reader_bookmarks" to """[{"bookId":"2835"}]""",
            // 下面这些都不该进入备份
            "theme_mode" to "MONET",
            "online_source" to "LNR",
            "PHPSESSID" to "secret",
        ),
        stats = ReadingStats(totalSeconds = 3600, totalSessions = 3),
        appVersion = "0.19.0-alpha05",
        exportedAt = 1_700_000_000_000L,
    )

    @Test
    fun roundTripKeepsBookshelfPreferencesAndStats() {
        val text = BackupTransfer.encode(document())
        val decoded = BackupTransfer.decode(text)
        assertTrue(decoded is BackupTransfer.DecodeResult.Success)
        val restored = (decoded as BackupTransfer.DecodeResult.Success).document
        assertEquals(1, restored.bookshelf.size)
        assertEquals("2835", restored.bookshelf[0].bookId)
        assertEquals("12/240", restored.preferences["xy_reader_page_2835"])
        assertEquals(3600L, restored.stats?.totalSeconds)
    }

    @Test
    fun nonAllowlistedKeysNeverLeaveTheDevice() {
        val text = BackupTransfer.encode(document())
        assertTrue(!text.contains("PHPSESSID"))
        assertTrue(!text.contains("theme_mode"))
        assertTrue(!text.contains("online_source"))
        assertTrue(text.contains("reader_progress_2835"))
        assertTrue(text.contains("xy_reader_bookmarks"))
    }

    @Test
    fun futureSchemaIsRejected() {
        val text = BackupTransfer.encode(document()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val decoded = BackupTransfer.decode(text)
        assertTrue(decoded is BackupTransfer.DecodeResult.Rejected)
    }

    @Test
    fun garbageIsRejectedInsteadOfCrashing() {
        assertTrue(BackupTransfer.decode("") is BackupTransfer.DecodeResult.Rejected)
        assertTrue(BackupTransfer.decode("{ not json") is BackupTransfer.DecodeResult.Rejected)
    }

    @Test
    fun suggestedNameIsTimestampedJson() {
        val name = backupExportFileName(1_700_000_000_000L, java.time.ZoneId.of("UTC"))
        assertTrue(name.startsWith("wenku8-backup-"))
        assertTrue(name.endsWith(".json"))
    }
}
