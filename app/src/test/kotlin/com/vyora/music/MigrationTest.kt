package com.vyora.music

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.vyora.music.data.db.Migrations
import com.vyora.music.data.db.VyoraDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every schema step must keep the user's library. Add a case here whenever VyoraDatabase.VERSION goes up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MigrationTest {
    private val name = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), VyoraDatabase::class.java)

    @Test fun v1ToV2KeepsLibraryAndAddsPlayEvents() {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO items (id, title, artist, thumbnail, url, duration, type, artist_id, is_video, count) " +
                    "VALUES ('abcdefghijk', 'Hati-Hati di Jalan', 'Tulus', NULL, 'u', 242, 'song', NULL, 0, 0)"
            )
            execSQL("INSERT INTO liked (item_id, liked_at, synced) VALUES ('abcdefghijk', 1, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, *Migrations.ALL)
        db.query("SELECT title FROM items WHERE id = 'abcdefghijk'").use { c ->
            c.moveToFirst()
            assertEquals("Hati-Hati di Jalan", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM liked").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.execSQL("INSERT INTO play_events (item_id, title, artist, thumbnail, played_at, listened_ms) VALUES ('abcdefghijk', 't', 'Tulus', NULL, 5, 30000)")
        db.query("SELECT COUNT(*) FROM play_events").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    @Test fun everyVersionHasACommittedSchema() {
        // KSP exports app/schemas/<db>/<version>.json; a missing file means a schema change wasn't committed.
        (1..VyoraDatabase.VERSION).forEach { v ->
            val file = java.io.File("schemas/com.vyora.music.data.db.VyoraDatabase/$v.json")
            assertEquals("schema $v.json", true, file.isFile)
        }
    }
}
