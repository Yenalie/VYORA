package com.vyora.music.data.db

import androidx.room.migration.Migration

/**
 * Hand-written migrations for schema changes [androidx.room.AutoMigration] can't express.
 *
 * Checklist for a schema change:
 * 1. Edit the entities and bump [VyoraDatabase.VERSION].
 * 2. Purely additive (new table/column with a default)? Add `AutoMigration(from = N, to = N + 1)`.
 *    Renames/deletes need an `AutoMigrationSpec` (`@RenameColumn`, `@DeleteTable`, ...).
 *    Anything else: add a `Migration(N, N + 1)` object to [ALL].
 * 3. Build once so KSP exports `app/schemas/.../N+1.json`, commit it.
 * 4. Add a case to `MigrationTest`.
 *
 * Never use `fallbackToDestructiveMigration()`: it would silently wipe the user's library.
 */
object Migrations {
    val ALL: Array<Migration> = emptyArray()
}
