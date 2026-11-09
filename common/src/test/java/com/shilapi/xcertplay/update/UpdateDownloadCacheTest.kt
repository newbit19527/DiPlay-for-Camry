package com.shilapi.xcertplay.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadCacheTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun processStartupRemovesHistoricalDownloadDirectoriesButKeepsCurrentOwner() {
        val root = files.newFolder("update")
        val current = File(root, "current").apply { mkdirs() }
        val old = File(root, "old").apply { mkdirs() }
        val older = File(root, "older").apply { mkdirs() }
        File(current, "current.apk").writeText("current")
        File(old, "old.apk").writeText("old")
        File(older, "older.apk").writeText("older")

        UpdateDownloadCache.removeHistoricalDirectories(root, keep = current)

        assertTrue(current.isDirectory)
        assertFalse(old.exists())
        assertFalse(older.exists())
    }

    @Test fun legacyExportDirectoryRemovesOnlyOlderVersionsRegardlessOfTimestamp() {
        val directory = files.newFolder("legacy")
        val olderButNewerTimestamp = File(directory, "old.apk").apply {
            writeText("old")
            setLastModified(3_000L)
        }
        val current = File(directory, "current.apk").apply {
            writeText("current")
            setLastModified(2_000L)
        }
        val newer = File(directory, "newer.apk").apply { writeText("newer") }
        val unknown = File(directory, "unknown.apk").apply { writeText("unknown") }
        val unrelated = File(directory, "notes.txt").apply { writeText("keep") }
        val versions = mapOf("old.apk" to 99L, "current.apk" to 100L, "newer.apk" to 101L)

        UpdateApkExport.pruneDirectoryHistory(directory, currentVersionCode = 100L) {
            versions[it.name]
        }

        assertFalse(olderButNewerTimestamp.exists())
        assertTrue(current.exists())
        assertTrue(newer.exists())
        assertTrue(unknown.exists())
        assertTrue(unrelated.exists())
    }
}
