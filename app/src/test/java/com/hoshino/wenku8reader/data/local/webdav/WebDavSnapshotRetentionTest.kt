package com.hoshino.wenku8reader.data.local.webdav

import org.junit.Assert.assertEquals
import org.junit.Test

class WebDavSnapshotRetentionTest {

    @Test
    fun `retains newest three snapshots and only deletes managed older snapshots`() {
        val snapshots = (1L..5L).map { timestamp ->
            WebDavSnapshot(WebDavClient.snapshotFileName(timestamp), timestamp, timestamp * 100L)
        }

        val deleted = WebDavSnapshotRetention.toDelete(snapshots)

        assertEquals(listOf(2L, 1L), deleted.map { it.timestampMillis })
    }

    @Test
    fun `snapshot file name round trips its timestamp`() {
        val timestamp = 1_797_123_456_789L

        val fileName = WebDavClient.snapshotFileName(timestamp)

        assertEquals(timestamp, WebDavClient.parseSnapshotTimestamp(fileName))
        assertEquals(null, WebDavClient.parseSnapshotTimestamp("other-file.json"))
        assertEquals(null, WebDavClient.parseSnapshotTimestamp("${fileName}.tmp"))
    }
}
