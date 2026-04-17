package io.github.b150005.knitnote.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import kotlin.random.Random

actual fun createTestDriver(): SqlDriver {
    // Use a unique in-memory database per test to avoid data leaking between tests.
    // SQLite treats each distinct ":memory:" name as a separate database.
    val uniqueName = "test_${Random.nextLong()}.db"
    return NativeSqliteDriver(KnitNoteDatabase.Schema, uniqueName)
}
