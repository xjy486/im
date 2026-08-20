package com.jitong.im.desktop.local

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalDatabaseSyncTest {

    @Test
    fun sync_cursor_survives_a_desktop_database_restart() {
        val root = createTempDirectory("jitong-sync")
        val manager = LocalDatabaseManager(root, InMemoryKeychain())

        manager.open("12345678903").use { database ->
            database.saveLastSyncSeq(17)
        }
        manager.open("12345678903").use { database ->
            assertEquals(17, database.lastSyncSeq())
        }
    }
}
